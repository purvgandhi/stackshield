package com.stacktrial.comparison;

import java.util.List;

/**
 * One deterministic observation about baseline vs trial.
 *
 * @param baselineEvidence evidence codes describing the baseline side
 * @param trialEvidence    evidence codes describing the trial side
 */
public record Finding(
        FindingType type,
        Severity severity,
        String title,
        String description,
        List<String> baselineEvidence,
        List<String> trialEvidence) {

    public Finding {
        baselineEvidence = List.copyOf(baselineEvidence);
        trialEvidence = List.copyOf(trialEvidence);
    }
}
