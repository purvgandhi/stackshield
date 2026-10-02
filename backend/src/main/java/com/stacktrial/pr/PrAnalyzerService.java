package com.stacktrial.pr;

import com.stacktrial.architecture.ArchitectureNode;
import com.stacktrial.architecture.ArchitectureResult;
import com.stacktrial.workspace.GitClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PrAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(PrAnalyzerService.class);

    private static final Pattern DIFF_FILE_HEADER = Pattern.compile("^diff --git a/(.*) b/(.*)$", Pattern.MULTILINE);
    private static final Pattern ADDED_LINES = Pattern.compile("^\\+[^+]", Pattern.MULTILINE);
    private static final Pattern DELETED_LINES = Pattern.compile("^-[^-]", Pattern.MULTILINE);

    private final GitClient git;

    public PrAnalyzerService(GitClient git) {
        this.git = git;
    }

    public PrAnalysisResult analyzeImpact(Path repoPath, ArchitectureResult architecture, String customDiff) {
        String diff = customDiff;
        if (diff == null || diff.isBlank()) {
            try {
                diff = git.diffAgainstHead(repoPath);
            } catch (Exception e) {
                log.debug("No uncommitted diff against HEAD in {}: {}", repoPath, e.getMessage());
                diff = "";
            }
        }

        List<ChangedFileImpact> changedFiles = new ArrayList<>();
        List<ImpactedComponent> impactedComponents = new ArrayList<>();
        List<String> breakingChanges = new ArrayList<>();
        List<String> suggestedTests = new ArrayList<>();

        int totalAdditions = 0;
        int totalDeletions = 0;

        if (!diff.isBlank()) {
            String[] fileSections = diff.split("diff --git a/");
            for (String section : fileSections) {
                if (section.isBlank()) continue;

                String firstLine = section.lines().findFirst().orElse("");
                String filePath = firstLine.split(" ")[0].trim();

                int adds = countMatches(ADDED_LINES, section);
                int dels = countMatches(DELETED_LINES, section);
                totalAdditions += adds;
                totalDeletions += dels;

                RiskLevel fileRisk = RiskLevel.LOW;
                List<String> affectedSymbols = new ArrayList<>();
                String explanation = "Standard source modification";

                if (filePath.endsWith("pom.xml") || filePath.endsWith("build.gradle")) {
                    fileRisk = RiskLevel.HIGH;
                    explanation = "Build definition / dependency tree modified";
                    breakingChanges.add("Build configuration modified in " + filePath + " - dependency version changes or plugin changes may alter runtime behavior.");
                    suggestedTests.add("Full regression test suite (mvn verify)");
                } else if (filePath.contains("schema") || filePath.contains("migration") || filePath.contains("Entity") || filePath.endsWith(".sql")) {
                    fileRisk = RiskLevel.HIGH;
                    explanation = "Database schema / persistence entity modified";
                    breakingChanges.add("Database schema or entity modified in " + filePath + " - verify schema backward compatibility.");
                    suggestedTests.add("JPA / Database integration test suite");
                } else if (filePath.contains("Controller") || filePath.contains("Resource") || filePath.contains("Endpoint")) {
                    fileRisk = RiskLevel.MEDIUM;
                    explanation = "API Controller / route handler modified";
                    suggestedTests.add("Web / API contract test suite");
                } else if (filePath.contains("Security") || filePath.contains("Auth")) {
                    fileRisk = RiskLevel.CRITICAL;
                    explanation = "Security / Authentication filter chain modified";
                    breakingChanges.add("Security configuration altered in " + filePath + " - verify endpoint authorization rules.");
                    suggestedTests.add("Security & Auth integration tests");
                }

                // Map to architecture nodes if available
                if (architecture != null && architecture.nodes() != null) {
                    for (ArchitectureNode node : architecture.nodes()) {
                        if (node.filePath() != null && (node.filePath().contains(filePath) || filePath.contains(node.filePath()))) {
                            affectedSymbols.add(node.name() + " (" + node.role() + ")");
                            impactedComponents.add(new ImpactedComponent(
                                    node.name(),
                                    node.role(),
                                    "Direct modification in " + filePath,
                                    fileRisk
                            ));
                        }
                    }
                }

                changedFiles.add(new ChangedFileImpact(
                        filePath,
                        section.contains("new file mode") ? "ADDED" : section.contains("deleted file mode") ? "DELETED" : "MODIFIED",
                        adds,
                        dels,
                        fileRisk,
                        affectedSymbols,
                        explanation
                ));
            }
        } else {
            // When no diff is active, generate a baseline impact map from the architecture
            if (architecture != null && architecture.nodes() != null) {
                for (ArchitectureNode node : architecture.nodes()) {
                    if ("CONTROLLER".equals(node.role()) || "SECURITY".equals(node.role())) {
                        impactedComponents.add(new ImpactedComponent(
                                node.name(),
                                node.role(),
                                "Key entrypoint component mapped in repository",
                                "SECURITY".equals(node.role()) ? RiskLevel.HIGH : RiskLevel.LOW
                        ));
                    }
                }
            }
            suggestedTests.add("mvn test (Standard Unit Tests)");
            suggestedTests.add("mvn verify (Integration Tests)");
        }

        RiskLevel overallRisk = RiskLevel.LOW;
        if (changedFiles.stream().anyMatch(f -> f.fileRisk() == RiskLevel.CRITICAL) || impactedComponents.stream().anyMatch(c -> c.risk() == RiskLevel.CRITICAL)) {
            overallRisk = RiskLevel.CRITICAL;
        } else if (changedFiles.stream().anyMatch(f -> f.fileRisk() == RiskLevel.HIGH) || impactedComponents.stream().anyMatch(c -> c.risk() == RiskLevel.HIGH)) {
            overallRisk = RiskLevel.HIGH;
        } else if (changedFiles.stream().anyMatch(f -> f.fileRisk() == RiskLevel.MEDIUM)) {
            overallRisk = RiskLevel.MEDIUM;
        }

        String summary = String.format("PR Impact Analysis: %d files modified (+%d/-%d lines). Overall Risk: %s. %d impacted components identified.",
                changedFiles.size(), totalAdditions, totalDeletions, overallRisk, impactedComponents.size());

        return new PrAnalysisResult(
                overallRisk,
                changedFiles.size(),
                totalAdditions,
                totalDeletions,
                changedFiles,
                impactedComponents,
                breakingChanges,
                suggestedTests,
                summary
        );
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }
}
