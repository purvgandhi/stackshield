package com.stacktrial.evidence;

/** One resolved artifact from {@code mvn dependency:tree}. */
public record Dependency(String groupId, String artifactId, String version, String scope) {

    public String key() {
        return groupId + ":" + artifactId;
    }
}
