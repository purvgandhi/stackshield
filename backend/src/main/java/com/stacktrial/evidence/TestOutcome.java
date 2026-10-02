package com.stacktrial.evidence;

public enum TestOutcome {
    PASSED,
    FAILED,
    ERROR,
    SKIPPED;

    public boolean isFailure() {
        return this == FAILED || this == ERROR;
    }
}
