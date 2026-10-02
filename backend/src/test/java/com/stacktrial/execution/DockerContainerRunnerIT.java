package com.stacktrial.execution;

import com.github.dockerjava.api.DockerClient;
import com.stacktrial.common.StackTrialProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Talks to the real Docker Engine (no mocks): checks the isolation settings from inside a running container,
 * the copy-in / copy-out of files, the hard timeout, and that every container is removed afterwards.
 * Needs only the Java 17 runner image and no network access.
 */
class DockerContainerRunnerIT {

    static final String IMAGE = "maven:3.9-eclipse-temurin-17";
    static DockerClient docker;

    @TempDir
    Path tmp;

    @BeforeAll
    static void connect() {
        docker = new DockerConfiguration().dockerClient();
    }

    @AfterAll
    static void close() throws Exception {
        docker.close();
    }

    @Test
    void runsAsNonRootWithLimitsAndCopiesFilesInAndOut() throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(workspace.resolve("input.txt"), "hello from the host");
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve(".git/config"), "must not be copied");
        String label = UUID.randomUUID().toString();

        String script = """
                mkdir -p .stacktrial
                {
                  echo "uid=$(id -u)"
                  echo "input=$(cat input.txt)"
                  echo "git=$(ls -a | grep -c '^.git$')"
                  grep CapEff /proc/self/status
                  grep NoNewPrivs /proc/self/status
                  echo "mem=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || cat /sys/fs/cgroup/memory/memory.limit_in_bytes)"
                  echo "pids=$(cat /sys/fs/cgroup/pids.max 2>/dev/null || cat /sys/fs/cgroup/pids/pids.max)"
                } > .stacktrial/facts.txt
                cat .stacktrial/facts.txt
                exit 3
                """;
        ContainerRunResult result = runner(Duration.ofMinutes(2)).run(spec(label, script, workspace, tmp.resolve("out")));

        String facts = Files.readString(tmp.resolve("out/.stacktrial/facts.txt"));
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.consoleLog()).contains("uid=1000");
        assertThat(facts)
                .contains("uid=1000")
                .contains("input=hello from the host")
                .contains("git=0")
                .containsPattern("CapEff:\\s+0000000000000000")
                .containsPattern("NoNewPrivs:\\s+1")
                .contains("mem=" + 512L * 1024 * 1024)
                .contains("pids=64");
        assertNoContainersLeft(label);
    }

    @Test
    void killsContainerOnTimeoutAndStillRemovesIt() {
        String label = UUID.randomUUID().toString();
        ContainerRunResult result = runner(Duration.ofSeconds(3))
                .run(spec(label, "echo started; sleep 60", tmp, null));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.consoleLog()).contains("started");
        assertNoContainersLeft(label);
    }

    private static DockerContainerRunner runner(Duration timeout) {
        StackTrialProperties.Runner runner = new StackTrialProperties.Runner(
                Map.of(17, IMAGE), 17, 512, 1.0, 64, timeout, "1000:1000", "none",
                "stacktrial-m2-cache", Map.of(), List.of());
        StackTrialProperties properties = new StackTrialProperties(Path.of("."), null, null, runner, null, null);
        return new DockerContainerRunner(docker, properties);
    }

    private static ContainerRunSpec spec(String label, String script, Path workspace, Path collectInto) {
        return new ContainerRunSpec("stacktrial-it-" + label, IMAGE, List.of("sh", "-c", script), workspace,
                Map.of(), Map.of("stacktrial-it", label), collectInto == null ? null : "/workspace/.stacktrial", collectInto);
    }

    private static void assertNoContainersLeft(String label) {
        assertThat(docker.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("stacktrial-it", label)).exec()).isEmpty();
    }
}
