package com.stacktrial.execution;

/**
 * @param exitCode   process exit code, or -1 when the container was killed on timeout
 * @param consoleLog stdout/stderr of the container (capped)
 */
public record ContainerRunResult(int exitCode, boolean timedOut, long durationMs, String consoleLog) {
}
