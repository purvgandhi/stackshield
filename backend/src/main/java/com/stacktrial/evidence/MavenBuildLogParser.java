package com.stacktrial.evidence;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured facts from a Maven build log: the goal that failed, the phase it belongs to,
 * and javac errors. Only lines Maven actually printed are used.
 */
@Component
public class MavenBuildLogParser {

    private static final int MAX_COMPILE_ERRORS = 50;

    /** [ERROR] /workspace/src/main/java/com/acme/A.java:[12,5] cannot find symbol */
    private static final Pattern COMPILE_ERROR =
            Pattern.compile("^\\[ERROR] (/\\S+\\.java):\\[(\\d+),(\\d+)] (.+)$");

    /** [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project x: ... */
    private static final Pattern FAILED_GOAL =
            Pattern.compile("^\\[ERROR] Failed to execute goal ([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-]+):([\\w\\-]+).*$");

    /** javac detail lines under an error: "  symbol:   method foo()", "[ERROR]   location: class Bar" */
    private static final Pattern COMPILE_DETAIL =
            Pattern.compile("^(?:\\[ERROR])?\\s{2,}((?:symbol|location|required|found|reason):.*)$");

    private static final String WORKSPACE_PREFIX = "/workspace/";

    public ParsedBuildLog parse(String log, boolean succeeded) {
        if (log == null) {
            log = "";
        }
        List<CompileError> compileErrors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String failureSummary = null;
        BuildPhase failedPhase = BuildPhase.UNKNOWN;

        String[] lines = log.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher c = COMPILE_ERROR.matcher(line);
            if (c.matches()) {
                StringBuilder message = new StringBuilder(c.group(4).strip());
                while (i + 1 < lines.length) {
                    Matcher detail = COMPILE_DETAIL.matcher(lines[i + 1]);
                    if (!detail.matches()) {
                        break;
                    }
                    message.append(" | ").append(detail.group(1).replaceAll("\\s+", " ").strip());
                    i++;
                }
                CompileError error = new CompileError(relativize(c.group(1)), Integer.parseInt(c.group(2)),
                        Integer.parseInt(c.group(3)), message.toString());
                if (compileErrors.size() < MAX_COMPILE_ERRORS && seen.add(error.location() + error.message())) {
                    compileErrors.add(error);
                }
                continue;
            }
            Matcher g = FAILED_GOAL.matcher(line);
            if (g.matches() && failureSummary == null) {
                failureSummary = line.substring("[ERROR] ".length()).strip();
                failedPhase = phaseOf(g.group(2), g.group(4));
            } else if (failureSummary == null && line.startsWith("[ERROR] Failed to execute goal")) {
                failureSummary = line.substring("[ERROR] ".length()).strip();
            }
            if (failedPhase == BuildPhase.UNKNOWN
                    && (line.contains("Could not resolve dependencies") || line.contains("Non-resolvable parent POM"))) {
                failedPhase = BuildPhase.DEPENDENCY_RESOLUTION;
            }
        }

        BuildPhase phase = succeeded ? BuildPhase.VERIFY : failedPhase;
        if (!succeeded && phase == BuildPhase.UNKNOWN && !compileErrors.isEmpty()) {
            phase = BuildPhase.COMPILE;
        }
        return new ParsedBuildLog(phase, compileErrors, truncate(failureSummary, 600));
    }

    static BuildPhase phaseOf(String pluginArtifactId, String goal) {
        if (pluginArtifactId.contains("compiler")) {
            return "testCompile".equals(goal) ? BuildPhase.TEST_COMPILE : BuildPhase.COMPILE;
        }
        if (pluginArtifactId.contains("surefire") || pluginArtifactId.contains("failsafe")) {
            return BuildPhase.TEST;
        }
        if (pluginArtifactId.contains("resources")) {
            return "testResources".equals(goal) ? BuildPhase.TEST_COMPILE : BuildPhase.COMPILE;
        }
        if (pluginArtifactId.contains("jar") || pluginArtifactId.contains("spring-boot-maven-plugin")) {
            return BuildPhase.PACKAGE;
        }
        return BuildPhase.UNKNOWN;
    }

    private static String relativize(String path) {
        return path.startsWith(WORKSPACE_PREFIX) ? path.substring(WORKSPACE_PREFIX.length()) : path;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public record ParsedBuildLog(BuildPhase phaseReached, List<CompileError> compileErrors, String failureSummary) {
    }
}
