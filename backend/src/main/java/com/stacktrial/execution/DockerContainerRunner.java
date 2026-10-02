package com.stacktrial.execution;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.stacktrial.common.StackTrialProperties;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs commands in disposable containers through the Docker Engine API (docker-java).
 *
 * <p>Isolation applied to every container:
 * <ul>
 *   <li>CPU, memory (no extra swap) and process-count limits</li>
 *   <li>runs as a non-root user, all Linux capabilities dropped, no-new-privileges, never privileged</li>
 *   <li>no Docker socket and no host directories mounted: the workspace is <em>copied in</em> as a tar archive and
 *       results are <em>copied out</em>, so the container cannot touch the host filesystem</li>
 *   <li>hard timeout: the container is killed when it runs too long</li>
 *   <li>always force-removed afterwards</li>
 * </ul>
 * The only shared state is a named volume used as the Maven dependency cache.
 */
@Component
public class DockerContainerRunner implements ContainerRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerContainerRunner.class);
    private static final String WORKSPACE = "/workspace";
    private static final String CACHE_MOUNT = "/m2";
    private static final int MAX_CONSOLE_CHARS = 200_000;

    private final DockerClient docker;
    private final StackTrialProperties.Runner config;
    private volatile boolean cacheVolumeReady;

    public DockerContainerRunner(DockerClient docker, StackTrialProperties properties) {
        this.docker = docker;
        this.config = properties.runner();
    }

    @Override
    public ContainerRunResult run(ContainerRunSpec spec) {
        String containerId = null;
        try {
            ensureImage(spec.image());
            ensureCacheVolumeOwnership(spec.image());

            CreateContainerResponse created = docker.createContainerCmd(spec.image())
                    .withName(spec.name())
                    .withUser(config.user())
                    .withWorkingDir(WORKSPACE)
                    .withEnv(env(spec.env()))
                    .withLabels(spec.labels())
                    .withCmd(spec.command())
                    .withHostConfig(hostConfig())
                    .exec();
            containerId = created.getId();

            copyWorkspaceIn(containerId, spec.workspace());

            long start = System.nanoTime();
            docker.startContainerCmd(containerId).exec();
            Integer exitCode = null;
            boolean timedOut = false;
            try {
                exitCode = docker.waitContainerCmd(containerId).start()
                        .awaitStatusCode(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (DockerClientException e) {
                // awaitStatusCode throws when the timeout elapses
                timedOut = true;
                log.warn("Container {} exceeded timeout {}, killing it", spec.name(), config.timeout());
                killQuietly(containerId);
            }
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            String console = consoleLog(containerId);
            copyResultsOut(containerId, spec.collectPath(), spec.collectInto());
            return new ContainerRunResult(timedOut ? -1 : exitCode, timedOut, durationMs, console);
        } catch (ContainerExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ContainerExecutionException("Docker execution failed: " + e.getMessage(), e);
        } finally {
            if (containerId != null) {
                removeQuietly(containerId);
            }
        }
    }

    private HostConfig hostConfig() {
        long memoryBytes = config.memoryMb() * 1024 * 1024;
        List<Bind> binds = new ArrayList<>();
        binds.add(new Bind(config.dependencyCacheVolume(), new Volume(CACHE_MOUNT)));
        for (String extra : config.extraBinds()) {
            binds.add(Bind.parse(extra.endsWith(":ro") ? extra : extra + ":ro"));
        }
        return HostConfig.newHostConfig()
                .withMemory(memoryBytes)
                .withMemorySwap(memoryBytes)
                .withNanoCPUs((long) (config.cpus() * 1_000_000_000L))
                .withPidsLimit(config.pidsLimit())
                .withPrivileged(false)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withNetworkMode(config.networkMode())
                .withBinds(binds);
    }

    private List<String> env(Map<String, String> specEnv) {
        List<String> env = new ArrayList<>();
        env.add("HOME=/tmp");
        env.add("MAVEN_CONFIG=/tmp/.m2");
        specEnv.forEach((k, v) -> env.add(k + "=" + v));
        config.extraEnv().forEach((k, v) -> env.add(k + "=" + v));
        return env;
    }

    // ---- image + cache -------------------------------------------------------------------------

    private void ensureImage(String image) {
        try {
            docker.inspectImageCmd(image).exec();
        } catch (NotFoundException missing) {
            log.info("Pulling runner image {}", image);
            try {
                docker.pullImageCmd(image).start().awaitCompletion(10, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerExecutionException("Interrupted while pulling " + image, e);
            } catch (RuntimeException e) {
                throw new ContainerExecutionException("Could not pull runner image " + image + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * A new named volume is owned by root. A short-lived root container (only CHOWN capability) hands it to the
     * non-root runner user once.
     */
    private void ensureCacheVolumeOwnership(String image) {
        if (cacheVolumeReady) {
            return;
        }
        synchronized (this) {
            if (cacheVolumeReady) {
                return;
            }
            String id = docker.createContainerCmd(image)
                    .withUser("0:0")
                    .withEntrypoint("chown", "-R", config.user(), CACHE_MOUNT)
                    .withLabels(Map.of("stacktrial", "cache-init"))
                    .withHostConfig(HostConfig.newHostConfig()
                            .withBinds(new Bind(config.dependencyCacheVolume(), new Volume(CACHE_MOUNT)))
                            .withCapDrop(Capability.ALL)
                            .withCapAdd(Capability.CHOWN)
                            .withNetworkMode("none"))
                    .exec().getId();
            try {
                docker.startContainerCmd(id).exec();
                docker.waitContainerCmd(id).start().awaitStatusCode(5, TimeUnit.MINUTES);
            } finally {
                removeQuietly(id);
            }
            cacheVolumeReady = true;
        }
    }

    // ---- copy in / out -------------------------------------------------------------------------

    private void copyWorkspaceIn(String containerId, Path workspace) {
        Path tar = null;
        try {
            tar = Files.createTempFile("stacktrial-workspace-", ".tar");
            writeWorkspaceTar(workspace, tar);
            try (InputStream in = Files.newInputStream(tar)) {
                docker.copyArchiveToContainerCmd(containerId)
                        .withTarInputStream(in)
                        .withRemotePath("/")
                        .exec();
            }
        } catch (IOException e) {
            throw new ContainerExecutionException("Could not copy workspace into container: " + e.getMessage(), e);
        } finally {
            deleteQuietly(tar);
        }
    }

    /** Tar of the workspace rooted at "workspace/", owned by the runner user. Skips .git and symlinks. */
    private void writeWorkspaceTar(Path workspace, Path tarFile) throws IOException {
        String[] ids = config.user().split(":");
        int uid = Integer.parseInt(ids[0]);
        int gid = ids.length > 1 ? Integer.parseInt(ids[1]) : uid;

        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tarFile));
             TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.getFileName().toString().equals(".git") && !dir.equals(workspace)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String name = "workspace/" + workspace.relativize(dir).toString().replace('\\', '/');
                    TarArchiveEntry entry = new TarArchiveEntry(name.endsWith("/") ? name : name + "/");
                    entry.setMode(040755);
                    entry.setIds(uid, gid);
                    tar.putArchiveEntry(entry);
                    tar.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        return FileVisitResult.CONTINUE;
                    }
                    TarArchiveEntry entry = new TarArchiveEntry(file.toFile(),
                            "workspace/" + workspace.relativize(file).toString().replace('\\', '/'));
                    entry.setMode(Files.isExecutable(file) ? 0100755 : 0100644);
                    entry.setIds(uid, gid);
                    entry.setUserName("");
                    entry.setGroupName("");
                    tar.putArchiveEntry(entry);
                    Files.copy(file, tar);
                    tar.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
            tar.finish();
        }
    }

    private void copyResultsOut(String containerId, String containerPath, Path hostDir) {
        if (containerPath == null || hostDir == null) {
            return;
        }
        try (InputStream in = docker.copyArchiveFromContainerCmd(containerId, containerPath).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            Files.createDirectories(hostDir);
            Path root = hostDir.toAbsolutePath().normalize();
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    continue; // never let an archive write outside the target directory
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else if (entry.isFile()) {
                    Files.createDirectories(target.getParent());
                    Files.copy(tar, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (NotFoundException e) {
            log.warn("Nothing to collect at {} in container {}", containerPath, containerId);
        } catch (IOException e) {
            throw new ContainerExecutionException("Could not copy results out of container: " + e.getMessage(), e);
        }
    }

    private String consoleLog(String containerId) {
        StringBuilder sb = new StringBuilder();
        try {
            docker.logContainerCmd(containerId).withStdOut(true).withStdErr(true).withFollowStream(false)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (sb.length() < MAX_CONSOLE_CHARS) {
                                sb.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                            }
                        }
                    }).awaitCompletion(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Could not read container logs: {}", e.getMessage());
        }
        return sb.toString();
    }

    // ---- cleanup -------------------------------------------------------------------------------

    private void killQuietly(String containerId) {
        try {
            docker.killContainerCmd(containerId).exec();
        } catch (RuntimeException e) {
            log.debug("Kill failed for {}: {}", containerId, e.getMessage());
        }
    }

    private void removeQuietly(String containerId) {
        try {
            docker.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
        } catch (RuntimeException e) {
            log.warn("Could not remove container {}: {}", containerId, e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // temp file, best effort
            }
        }
    }
}
