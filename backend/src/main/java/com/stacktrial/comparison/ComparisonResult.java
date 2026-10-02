package com.stacktrial.comparison;

import java.util.List;

public record ComparisonResult(Verdict verdict, String verdictReason, List<Finding> findings, List<EvidenceItem> evidence) {

    public ComparisonResult {
        findings = List.copyOf(findings);
        evidence = List.copyOf(evidence);
    }
}
