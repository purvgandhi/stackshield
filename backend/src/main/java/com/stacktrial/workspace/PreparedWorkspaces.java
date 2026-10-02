package com.stacktrial.workspace;

import java.nio.file.Path;

/** Two independent copies of the repository at the same commit. */
public record PreparedWorkspaces(Path baselineDir, Path trialDir, String commitSha) {
}
