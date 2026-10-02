package com.stacktrial.common;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Masks secrets in build logs before they are stored or sent to Gemini.
 * Covers common token formats and key=value / key: value pairs whose key looks secret.
 */
@Component
public class LogRedactor {

    static final String MASK = "[REDACTED]";

    private static final List<Pattern> TOKEN_PATTERNS = List.of(
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),                 // GitHub tokens
            Pattern.compile("github_pat_[A-Za-z0-9_]{20,}"),               // GitHub fine-grained tokens
            Pattern.compile("AIza[0-9A-Za-z_\\-]{35}"),                     // Google API keys (Gemini)
            Pattern.compile("AKIA[0-9A-Z]{16}"),                            // AWS access key ids
            Pattern.compile("xox[baprs]-[A-Za-z0-9\\-]{10,}"),              // Slack tokens
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"));

    /** Bearer tokens and credentials embedded in URLs. */
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@");

    /** password=..., API_KEY: ..., "secret": "..." */
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?[\\w.\\-]*(?:password|passwd|secret|token|api[_\\-]?key|credentials)[\\w.\\-]*[\"']?\\s*[=:]\\s*[\"']?)([^\\s\"',;]{3,})");

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Pattern p : TOKEN_PATTERNS) {
            out = p.matcher(out).replaceAll(MASK);
        }
        out = BEARER.matcher(out).replaceAll("$1" + MASK);
        out = URL_CREDENTIALS.matcher(out).replaceAll("$1" + MASK + "@");
        out = SECRET_ASSIGNMENT.matcher(out).replaceAll("$1" + MASK);
        return out;
    }
}
