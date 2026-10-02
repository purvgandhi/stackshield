package com.stacktrial.workspace;

import com.stacktrial.common.StackTrialProperties;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Only plain HTTPS repositories (and, when enabled for local demos, file:// repositories) are accepted.
 * This blocks ssh, git://, ext:: and option-injection tricks such as a URL starting with "--upload-pack".
 */
@Component
public class RepositoryUrlValidator {

    private static final Pattern HTTPS = Pattern.compile("^https://[A-Za-z0-9.\\-]+(:\\d+)?/[A-Za-z0-9._~\\-/]+$");
    private static final Pattern FILE = Pattern.compile("^file:///[A-Za-z0-9._~\\-/: ]+$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/\\-]{0,199}$");

    private final boolean allowLocal;

    public RepositoryUrlValidator(StackTrialProperties properties) {
        this.allowLocal = properties.workspace().allowLocalRepositories();
    }

    public void validate(String repoUrl, String ref) {
        if (repoUrl == null || !(HTTPS.matcher(repoUrl).matches() || (allowLocal && FILE.matcher(repoUrl).matches()))) {
            throw new IllegalArgumentException(allowLocal
                    ? "Repository URL must be https://host/path or file:///path"
                    : "Repository URL must be https://host/path");
        }
        if (repoUrl.contains("..")) {
            throw new IllegalArgumentException("Repository URL must not contain '..'");
        }
        if (ref == null || !REF.matcher(ref).matches() || ref.contains("..")) {
            throw new IllegalArgumentException("Baseline ref must be a branch, tag or commit SHA");
        }
    }
}
