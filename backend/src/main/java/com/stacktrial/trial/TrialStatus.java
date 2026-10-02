package com.stacktrial.trial;

public enum TrialStatus {
    QUEUED,
    PREPARING,
    RUNNING,
    ANALYZING,
    EXPLAINING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
