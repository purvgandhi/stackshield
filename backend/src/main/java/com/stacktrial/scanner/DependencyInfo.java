package com.stacktrial.scanner;

public record DependencyInfo(
        String groupId,
        String artifactId,
        String version,
        String scope,
        String category,
        boolean isDirect
) {
    public String coordinate() {
        return (groupId != null ? groupId + ":" : "") + artifactId + (version != null ? ":" + version : "");
    }
}
