package com.stacktrial.scanner;

import java.util.List;
import java.util.Map;

public record ScanResult(
        String projectName,
        String buildSystem, // Maven, Gradle, npm, Python, Unknown
        String buildSystemVersion,
        Integer javaVersion,
        String springBootVersion,
        List<String> detectedFrameworks,
        List<String> detectedDatabases,
        List<String> testFrameworks,
        boolean hasDocker,
        boolean hasDockerCompose,
        boolean hasGit,
        int totalFiles,
        long totalLinesOfCode,
        List<LanguageStat> languages,
        List<DependencyInfo> dependencies,
        List<String> importantFiles,
        List<String> sourceDirectories,
        List<String> testDirectories,
        int testFileCount,
        List<HealthObservation> healthObservations,
        Map<String, String> configurationProperties
) {
}
