package com.stacktrial.workspace;

import com.stacktrial.common.StackTrialProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.UUID;

/**
 * Creates the isolated host-side workspaces for a trial:
 * <pre>
 * {dataDir}/workspaces/{trialId}/source    fresh clone at the baseline ref
 *                                /baseline  untouched copy
 *                                /trial     copy that receives the proposed change
 * </pre>
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final GitClient git;
    private final Path root;
    private final Duration cloneTimeout;

    public WorkspaceService(GitClient git, StackTrialProperties properties) {
        this.git = git;
        this.root = properties.dataDir().resolve("workspaces").toAbsolutePath();
        this.cloneTimeout = properties.workspace().cloneTimeout();
    }

    public PreparedWorkspaces prepare(UUID trialId, String repoUrl, String ref) {
        Path trialRoot = trialRoot(trialId);
        Path source = trialRoot.resolve("source");
        try {
            Files.createDirectories(trialRoot);
            git.cloneRepository(repoUrl, source, cloneTimeout);
            String sha = git.checkout(source, ref);

            Path baseline = trialRoot.resolve("baseline");
            Path trial = trialRoot.resolve("trial");
            copyTree(source, baseline);
            copyTree(source, trial);
            return new PreparedWorkspaces(baseline, trial, sha);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create workspace: " + e.getMessage(), e);
        }
    }

    public void cleanup(UUID trialId) {
        Path dir = trialRoot(trialId);
        try {
            FileSystemUtils.deleteRecursively(dir);
        } catch (IOException e) {
            log.warn("Could not delete workspace {}: {}", dir, e.getMessage());
        }
    }

    private Path trialRoot(UUID trialId) {
        return root.resolve(trialId.toString());
    }

    /** Copies regular files and directories only; symlinks are skipped so a repo cannot point outside its workspace. */
    static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(file, to.resolve(from.relativize(file)),
                            StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
