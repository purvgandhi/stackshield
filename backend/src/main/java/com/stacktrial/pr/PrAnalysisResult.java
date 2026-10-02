package com.stacktrial.pr;

import java.util.List;

public record PrAnalysisResult(
        RiskLevel overallRisk,
        int totalFilesChanged,
        int totalAdditions,
        int totalDeletions,
        List<ChangedFileImpact> changedFiles,
        List<ImpactedComponent> impactedComponents,
        List<String> breakingChangeWarnings,
        List<String> suggestedTestSuites,
        String summary
) {
}
