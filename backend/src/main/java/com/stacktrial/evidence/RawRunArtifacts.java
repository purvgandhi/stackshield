package com.stacktrial.evidence;

import java.nio.file.Path;

/** Raw files and numbers produced by one container run, before parsing. */
public record RawRunArtifacts(
        int exitCode,
        boolean timedOut,
        long durationMs,
        String buildLog,
        String dependencyTree,
        Path surefireReportsDir) {
}
