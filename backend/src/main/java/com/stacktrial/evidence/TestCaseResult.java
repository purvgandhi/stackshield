package com.stacktrial.evidence;

/** One test case parsed from a Surefire XML report. */
public record TestCaseResult(
        String className,
        String name,
        TestOutcome outcome,
        String failureMessage,
        String failureType,
        String stackTrace,
        long durationMs) {

    /** Stable identity used to match the same test across baseline and trial. */
    public String key() {
        return className + "#" + name;
    }
}
