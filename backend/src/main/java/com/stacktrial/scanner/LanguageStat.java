package com.stacktrial.scanner;

public record LanguageStat(
        String language,
        int fileCount,
        long estimatedLines,
        double percentage,
        String color
) {
}
