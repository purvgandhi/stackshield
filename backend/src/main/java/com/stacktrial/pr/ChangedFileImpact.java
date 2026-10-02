package com.stacktrial.pr;

import java.util.List;

public record ChangedFileImpact(
        String filePath,
        String changeType, // MODIFIED, ADDED, DELETED
        int additions,
        int deletions,
        RiskLevel fileRisk,
        List<String> affectedSymbols,
        String explanation
) {
}
