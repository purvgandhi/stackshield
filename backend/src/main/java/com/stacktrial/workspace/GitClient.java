package com.stacktrial.workspace;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper over the {@code git} CLI. Git only reads and writes files here; it never runs code from the
 * target repository (hooks are not cloned, and the {@code ext::} transport is disabled).
 */
@Component
public class GitClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    public void cloneRepository(String url, Path target, Duration timeout) {
        run(null, timeout, "-c", "protocol.ext.allow=never", "clone", "--quiet", "--no-checkout",
                "--", url, target.toString());
    }

    /** Checks out a branch, tag or commit as a detached HEAD and returns the resolved commit SHA. */
    public String checkout(Path repo, String ref) {
        String sha = resolveCommit(repo, ref);
        run(repo, DEFAULT_TIMEOUT, "-c", "advice.detachedHead=false", "checkout", "--quiet", "--detach", sha);
        return sha;
    }

    private String resolveCommit(Path repo, String ref) {
        for (String candidate : List.of(ref, "origin/" + ref)) {
            try {
                return run(repo, DEFAULT_TIMEOUT, "rev-parse", "--verify", "--quiet", candidate + "^{commit}").strip();
            } catch (GitCommandException ignored) {
                // try the next candidate
            }
        }
        throw new GitCommandException("Baseline ref '" + ref + "' was not found in the repository");
    }

    public String headCommit(Path repo) {
        return run(repo, DEFAULT_TIMEOUT, "rev-parse", "HEAD").strip();
    }

    /** Validates then applies a unified diff to the working tree. */
    public void applyPatch(Path repo, Path patchFile) {
        run(repo, DEFAULT_TIMEOUT, "apply", "--check", "--whitespace=nowarn", patchFile.toString());
        run(repo, DEFAULT_TIMEOUT, "apply", "--whitespace=nowarn", patchFile.toString());
    }

    /** The full change in the working tree versus HEAD, including new files. */
    public String diffAgainstHead(Path repo) {
        run(repo, DEFAULT_TIMEOUT, "add", "--all");
        return run(repo, DEFAULT_TIMEOUT, "-c", "core.quotepath=off", "diff", "--cached", "--no-color", "HEAD");
    }

    private String run(Path workDir, Duration timeout, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        try {
            Process process = pb.start();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GitCommandException("git " + args[args.length > 2 ? 2 : 0] + " timed out after " + timeout.toSeconds() + "s");
            }
            String out = output.join();
            if (process.exitValue() != 0) {
                throw new GitCommandException("git " + String.join(" ", safeArgs(args)) + " failed: " + out.strip());
            }
            return out;
        } catch (IOException e) {
            throw new GitCommandException("Could not run git: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandException("Interrupted while running git");
        }
    }

    private static List<String> safeArgs(String[] args) {
        return List.of(args).stream().filter(a -> !a.startsWith("protocol.")).toList();
    }

    private static String readAll(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
