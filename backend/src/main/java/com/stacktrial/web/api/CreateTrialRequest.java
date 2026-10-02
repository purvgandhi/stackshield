package com.stacktrial.web.api;

import com.stacktrial.workspace.change.ChangeRequest;
import com.stacktrial.workspace.change.ChangeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Example:
 * <pre>
 * { "repoUrl": "https://github.com/acme/shop", "baselineRef": "main",
 *   "changeType": "SPRING_BOOT_VERSION", "targetVersion": "3.0.13" }
 * </pre>
 */
public record CreateTrialRequest(
        @NotBlank @Size(max = 500) String repoUrl,
        @NotBlank @Size(max = 200) String baselineRef,
        @NotNull ChangeType changeType,
        @Size(max = 200) String groupId,
        @Size(max = 200) String artifactId,
        @Size(max = 64) String targetVersion,
        @Size(max = 1_000_000) String diff) {

    public ChangeRequest toChange() {
        return new ChangeRequest(changeType, trim(groupId), trim(artifactId), trim(targetVersion), diff);
    }

    private static String trim(String s) {
        return s == null ? null : s.strip();
    }
}
