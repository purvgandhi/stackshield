package com.stacktrial.trial;

import com.stacktrial.comparison.EvidenceItem;
import com.stacktrial.comparison.Finding;
import com.stacktrial.comparison.Verdict;
import com.stacktrial.evidence.TestSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model for the API and the report page. Built from stored rows; contains no JPA entities. */
public record TrialReport(
        UUID id,
        String repoUrl,
        String baselineRef,
        String baselineCommit,
        String changeType,
        String changeSummary,
        String appliedDiff,
        String status,
        String currentStep,
        boolean finished,
        String failureReason,
        Verdict verdict,
        String verdictReason,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Environment environment,
        RunView baseline,
        RunView trial,
        List<ComparisonRow> comparison,
        List<Finding> findings,
        List<EvidenceItem> evidence,
        List<FailedTest> failedTests,
        ExplanationView explanation) {

    public record Environment(Integer baselineJava, Integer trialJava, String baselineSpringBoot, String trialSpringBoot) {
    }

    public record RunView(String side, String image, String outcome, int exitCode, boolean timedOut, String phaseReached,
                          long durationMs, TestSummary tests, int compileErrors, int dependencies,
                          String failureSummary, String buildLogUrl, String dependencyTreeUrl) {
    }

    /** One line of the BASELINE vs TRIAL table, e.g. TESTS: "6 passed" -> "4 passed / 2 failed". */
    public record ComparisonRow(String label, String baseline, String trial, boolean changed) {
    }

    public record FailedTest(String side, String test, String outcome, String type, String message, String stackTrace) {
    }

    public record ExplanationView(String status, String model, String retrievalMethod, String content, String note,
                                  List<String> citedEvidence, List<String> citedKnowledge, List<String> unknownCitations,
                                  List<KnowledgeRef> documentation) {
    }

    public record KnowledgeRef(String id, String source, String sourceUrl, String title, String content, boolean cited) {
    }
}
