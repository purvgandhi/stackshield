package com.stacktrial.security;

public record SecurityFinding(
        String id,
        SecurityCategory category,
        SecuritySeverity severity,
        String title,
        String description,
        String filePath,
        Integer lineNumber,
        String matchedSnippet,
        String remediation
) {
}
