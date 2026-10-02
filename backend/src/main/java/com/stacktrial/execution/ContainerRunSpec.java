package com.stacktrial.execution;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What to run. Resource limits, user, network and timeout are deliberately NOT part of the spec: they come from
 * configuration and are identical for every run, which keeps baseline and trial comparable.
 *
 * @param workspace    host directory copied into the container at {@code /workspace} (.git excluded)
 * @param collectPath  container path copied back out after the run
 * @param collectInto  host directory receiving the collected files
 */
public record ContainerRunSpec(
        String name,
        String image,
        List<String> command,
        Path workspace,
        Map<String, String> env,
        Map<String, String> labels,
        String collectPath,
        Path collectInto) {
}
