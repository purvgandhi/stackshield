package com.stacktrial.analyzer;

/**
 * Represents a single rule-based finding discovered during static codebase analysis.
 */
public record CodebaseFinding(
        String id,
        String category, // "Security Risks", "Code Quality", "Dependencies", "Configuration", "Improvement Suggestions"
        String severity, // "High", "Medium", "Low"
        String title,
        String affectedItem, // e.g. "src/main/resources/application.properties:14"
        String whyItMatters,
        String recommendation
) {
}
