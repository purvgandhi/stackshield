package com.stacktrial.analyzer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Clean read model for the Codebase Analysis Report.
 */
public record CodebaseAnalysisReport(
        UUID id,
        String repoUrl,
        String repoName,
        String defaultRef,
        String primaryLanguage,
        int totalFiles,
        int javaFilesCount,
        long linesOfCode,
        String projectType,
        String status,
        String healthGrade,
        int securityCount,
        int codeQualityCount,
        int dependencyCount,
        int configurationCount,
        int improvementCount,
        List<CodebaseFinding> findings,
        List<String> topPriorities,
        Instant analyzedAt
) {
    public int highSeverityCount() {
        if (findings == null) return 0;
        return (int) findings.stream().filter(f -> "HIGH".equalsIgnoreCase(f.severity())).count();
    }

    public int mediumSeverityCount() {
        if (findings == null) return 0;
        return (int) findings.stream().filter(f -> "MEDIUM".equalsIgnoreCase(f.severity())).count();
    }

    public int lowSeverityCount() {
        if (findings == null) return 0;
        return (int) findings.stream().filter(f -> "LOW".equalsIgnoreCase(f.severity())).count();
    }
}
