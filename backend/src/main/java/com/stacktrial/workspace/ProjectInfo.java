package com.stacktrial.workspace;

/**
 * What StackTrial detected in a workspace's pom.xml.
 *
 * @param javaVersion       major Java version (17, 21, ...) or null when the pom does not declare one
 * @param springBootVersion Spring Boot version from the parent or {@code spring-boot.version}, or null
 */
public record ProjectInfo(String groupId, String artifactId, Integer javaVersion, String springBootVersion) {
}
