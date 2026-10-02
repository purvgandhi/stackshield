package com.stacktrial.comparison;

import com.stacktrial.evidence.BuildOutcome;
import com.stacktrial.evidence.RunEvidence;

import java.util.List;

/** What the verdict rules can look at: both runs and the findings already derived from them. */
record ComparisonContext(RunEvidence baseline, RunEvidence trial, List<Finding> findings) {

    long count(FindingType type) {
        return findings.stream().filter(f -> f.type() == type).count();
    }

    boolean has(FindingType type) {
        return count(type) > 0;
    }

    boolean anyTimeout() {
        return baseline.outcome() == BuildOutcome.TIMEOUT || trial.outcome() == BuildOutcome.TIMEOUT;
    }

    boolean baselineFailed() {
        return baseline.outcome() == BuildOutcome.FAILURE;
    }

    boolean trialFailed() {
        return trial.outcome() == BuildOutcome.FAILURE;
    }
}
