package com.stacktrial.security;

import java.util.List;

public record SecurityResult(
        String securityGrade, // A, B, C, D, F
        int totalIssues,
        int criticalCount,
        int highCount,
        int mediumCount,
        int lowCount,
        List<SecurityFinding> findings,
        String summary
) {
}
