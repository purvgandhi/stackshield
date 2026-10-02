package com.stacktrial.workspace.change;

/**
 * The proposed change. Which fields are required depends on {@link #type()}:
 * <ul>
 *   <li>DEPENDENCY_VERSION: groupId, artifactId, targetVersion</li>
 *   <li>SPRING_BOOT_VERSION: targetVersion</li>
 *   <li>JAVA_VERSION: targetVersion (e.g. "21")</li>
 *   <li>GIT_DIFF: diff (unified diff text, as produced by {@code git diff})</li>
 * </ul>
 */
public record ChangeRequest(ChangeType type, String groupId, String artifactId, String targetVersion, String diff) {

    public String describe() {
        return switch (type) {
            case DEPENDENCY_VERSION -> "Upgrade " + groupId + ":" + artifactId + " to " + targetVersion;
            case SPRING_BOOT_VERSION -> "Upgrade Spring Boot to " + targetVersion;
            case JAVA_VERSION -> "Upgrade Java to " + targetVersion;
            case GIT_DIFF -> "Apply uploaded git diff";
        };
    }
}
