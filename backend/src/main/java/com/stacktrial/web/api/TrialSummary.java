package com.stacktrial.web.api;

import com.stacktrial.trial.Trial;

import java.time.Instant;
import java.util.UUID;

public record TrialSummary(UUID id, String repoUrl, String baselineRef, String changeType, String changeSummary,
                           String status, String currentStep, String verdict, Instant createdAt) {

    public static TrialSummary of(Trial t) {
        return new TrialSummary(t.getId(), t.getRepoUrl(), t.getBaselineRef(), t.getChangeType().name(),
                t.getChangeSummary(), t.getStatus().name(), t.getCurrentStep(),
                t.getVerdict() == null ? null : t.getVerdict().name(), t.getCreatedAt());
    }
}
