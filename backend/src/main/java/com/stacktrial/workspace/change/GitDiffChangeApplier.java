package com.stacktrial.workspace.change;

import com.stacktrial.workspace.GitClient;
import com.stacktrial.workspace.GitCommandException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class GitDiffChangeApplier implements ChangeApplier {

    private static final int MAX_DIFF_BYTES = 1_000_000;

    private final GitClient git;

    public GitDiffChangeApplier(GitClient git) {
        this.git = git;
    }

    @Override
    public ChangeType type() {
        return ChangeType.GIT_DIFF;
    }

    @Override
    public String apply(Path workspace, ChangeRequest change) {
        String diff = change.diff();
        if (diff == null || diff.isBlank()) {
            throw new ChangeApplicationException("Diff is empty");
        }
        if (diff.length() > MAX_DIFF_BYTES) {
            throw new ChangeApplicationException("Diff is larger than 1 MB");
        }
        if (!diff.endsWith("\n")) {
            diff = diff + "\n";
        }
        Path patch = null;
        try {
            patch = Files.createTempFile("stacktrial-", ".patch");
            Files.writeString(patch, diff, StandardCharsets.UTF_8);
            git.applyPatch(workspace, patch);
        } catch (GitCommandException e) {
            throw new ChangeApplicationException("Diff does not apply cleanly: " + e.getMessage());
        } catch (IOException e) {
            throw new ChangeApplicationException("Could not write diff: " + e.getMessage());
        } finally {
            if (patch != null) {
                try {
                    Files.deleteIfExists(patch);
                } catch (IOException ignored) {
                    // temp file, best effort
                }
            }
        }
        long files = diff.lines().filter(l -> l.startsWith("diff --git ")).count();
        return "Applied git diff touching " + files + " file(s)";
    }
}
