package com.stacktrial.execution;

import com.stacktrial.common.LogRedactor;
import com.stacktrial.common.StackTrialProperties;
import com.stacktrial.comparison.Side;
import com.stacktrial.evidence.RawRunArtifacts;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs {@code mvn verify} plus {@code mvn dependency:tree} for one side of a trial inside a container, then copies
 * the build log, Surefire XML reports and dependency tree back out. Baseline and trial use exactly this method.
 */
@Service
public class MavenRunExecutor {

    static final String MAVEN = "mvn -B -ntp -Dstyle.color=never -Dmaven.repo.local=/m2/repository";

    /**
     * The script that runs inside the container. Exit code is the exit code of {@code mvn verify}.
     * Surefire reports from every module are gathered into .stacktrial/surefire-reports.
     */
    static final String SCRIPT = String.join("\n",
            "mkdir -p /workspace/.stacktrial/surefire-reports",
            "cd /workspace",
            MAVEN + " verify > .stacktrial/build.log 2>&1",
            "rc=$?",
            "find . -path ./.stacktrial -prune -o -path '*/target/surefire-reports/TEST-*.xml' -exec cp -t .stacktrial/surefire-reports/ {} +",
            MAVEN + " -q dependency:tree -DoutputFile=/workspace/.stacktrial/dependency-tree.txt -DappendOutput=true"
                    + " > .stacktrial/dependency-tree.log 2>&1",
            "echo \"stacktrial: mvn verify exited with $rc\"",
            "exit $rc");

    private final ContainerRunner runner;
    private final StorageService storage;
    private final LogRedactor redactor;
    private final Path scratchRoot;

    public MavenRunExecutor(ContainerRunner runner, StorageService storage, LogRedactor redactor,
                            StackTrialProperties properties) {
        this.runner = runner;
        this.storage = storage;
        this.redactor = redactor;
        this.scratchRoot = properties.dataDir().resolve("collected").toAbsolutePath();
    }

    public MavenRunResult run(UUID trialId, Side side, Path workspace, String image) {
        Path collectInto = scratchRoot.resolve(trialId + "-" + side.name().toLowerCase());
        try {
            FileSystemUtils.deleteRecursively(collectInto);
            ContainerRunSpec spec = new ContainerRunSpec(
                    "stacktrial-" + trialId.toString().substring(0, 8) + "-" + side.name().toLowerCase() + "-" + System.currentTimeMillis(),
                    image,
                    List.of("sh", "-c", SCRIPT),
                    workspace,
                    Map.of(),
                    Map.of("stacktrial", "run", "stacktrial.trial", trialId.toString(), "stacktrial.side", side.name()),
                    "/workspace/.stacktrial",
                    collectInto);

            ContainerRunResult result = runner.run(spec);

            Path collected = collectInto.resolve(".stacktrial");
            String buildLog = readOrEmpty(collected.resolve("build.log"));
            if (buildLog.isEmpty()) {
                buildLog = result.consoleLog();
            }
            buildLog = redactor.redact(buildLog);
            String dependencyTree = readOrEmpty(collected.resolve("dependency-tree.txt"));

            String prefix = "trials/" + trialId + "/" + side.name();
            String logKey = storage.storeText(prefix + "/build.log", buildLog);
            String treeKey = dependencyTree.isBlank() ? null : storage.storeText(prefix + "/dependency-tree.txt", dependencyTree);

            // Surefire XML is parsed from the collected directory; parsing happens before cleanup by the caller.
            RawRunArtifacts raw = new RawRunArtifacts(result.exitCode(), result.timedOut(), result.durationMs(),
                    buildLog, dependencyTree, collected.resolve("surefire-reports"));
            return new MavenRunResult(image, "mvn verify", raw, logKey, treeKey);
        } catch (IOException e) {
            throw new ContainerExecutionException("Could not prepare result directory: " + e.getMessage(), e);
        }
    }

    public void discardCollected(UUID trialId) {
        for (Side side : Side.values()) {
            try {
                FileSystemUtils.deleteRecursively(scratchRoot.resolve(trialId + "-" + side.name().toLowerCase()));
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static String readOrEmpty(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }
}
