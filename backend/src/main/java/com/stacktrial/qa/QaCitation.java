package com.stacktrial.qa;

public record QaCitation(
        String filePath,
        String symbolName,
        String snippet,
        double score
) {
}
