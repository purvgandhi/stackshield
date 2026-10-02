package com.stacktrial.comparison;

import com.stacktrial.evidence.BuildOutcome;
import com.stacktrial.evidence.BuildPhase;
import com.stacktrial.evidence.CompileError;
import com.stacktrial.evidence.Dependency;
import com.stacktrial.evidence.RunEvidence;
import com.stacktrial.evidence.TestCaseResult;
import com.stacktrial.evidence.TestOutcome;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic baseline-vs-trial comparison. Pure Java: no Spring, no I/O, no clock, no AI.
 * The same two {@link RunEvidence} inputs always produce the same findings, evidence codes and verdict.
 *
 * <p>Two steps:
 * <ol>
 *   <li>Derive findings from the raw facts (build outcome, per-test outcomes, dependency diff).</li>
 *   <li>Pick the verdict from the first matching row of {@link #VERDICT_RULES}.</li>
 * </ol>
 */
public final class ComparisonEngine {

    private static final int MAX_COMPILE_ERRORS_AS_EVIDENCE = 10;
    private static final int MAX_DEPENDENCY_CHANGES_AS_EVIDENCE = 40;
    private static final int MAX_DETAIL_CHARS = 1500;

    /** Phases at or after which surefire has run, so a missing test really is missing. */
    private static final Set<BuildPhase> TESTS_RAN = EnumSet.of(BuildPhase.TEST, BuildPhase.PACKAGE, BuildPhase.VERIFY);

    /** The verdict decision table. Order matters: first match wins. */
    static final List<VerdictRule> VERDICT_RULES = List.of(
            new VerdictRule("timeout", ComparisonContext::anyTimeout, Verdict.INCONCLUSIVE,
                    c -> "A run hit the hard timeout, so its results are incomplete."),
            new VerdictRule("both-builds-failed", c -> c.baselineFailed() && c.trialFailed(), Verdict.BOTH_FAILED,
                    c -> "Baseline and trial builds both failed, so the change cannot be judged against a working baseline."),
            new VerdictRule("trial-build-broke", c -> !c.baselineFailed() && c.trialFailed(), Verdict.REGRESSION,
                    c -> "Baseline build succeeded but the trial build failed in phase "
                            + c.trial().phaseReached() + testRegressionSuffix(c) + "."),
            new VerdictRule("trial-build-fixed", c -> c.baselineFailed() && !c.trialFailed(), Verdict.IMPROVEMENT,
                    c -> "Baseline build failed but the trial build succeeded."),
            new VerdictRule("test-regression", c -> c.has(FindingType.TEST_REGRESSION), Verdict.REGRESSION,
                    c -> c.count(FindingType.TEST_REGRESSION) + " test(s) passed in baseline but failed in trial."),
            new VerdictRule("tests-missing", c -> c.has(FindingType.TEST_MISSING), Verdict.INCONCLUSIVE,
                    c -> c.count(FindingType.TEST_MISSING) + " baseline test(s) did not run in trial, so their behaviour is unknown."),
            new VerdictRule("tests-fixed", c -> c.has(FindingType.TEST_FIXED), Verdict.IMPROVEMENT,
                    c -> c.count(FindingType.TEST_FIXED) + " test(s) failed in baseline and pass in trial; nothing regressed."),
            new VerdictRule("no-regression", c -> true, Verdict.PASS,
                    c -> "Both builds succeeded and no test that passed in baseline fails in trial.")
    );

    public ComparisonResult compare(RunEvidence baseline, RunEvidence trial) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(trial, "trial");

        EvidenceLedger ledger = new EvidenceLedger();
        String baselineRun = ledger.record(EvidenceKind.RUN_RESULT, Side.BASELINE,
                "Baseline run: " + baseline.outcome(), describeRun(baseline));
        String trialRun = ledger.record(EvidenceKind.RUN_RESULT, Side.TRIAL,
                "Trial run: " + trial.outcome(), describeRun(trial));

        List<Finding> findings = new ArrayList<>();
        findings.addAll(buildFindings(baseline, trial, baselineRun, trialRun, ledger));
        findings.addAll(testFindings(baseline, trial, baselineRun, trialRun, ledger));
        findings.addAll(dependencyFindings(baseline, trial, ledger));

        ComparisonContext context = new ComparisonContext(baseline, trial, findings);
        VerdictRule rule = VERDICT_RULES.stream()
                .filter(r -> r.when().test(context))
                .findFirst()
                .orElseThrow();

        return new ComparisonResult(rule.verdict(), rule.reason().apply(context), findings, ledger.items());
    }

    // ---- build ---------------------------------------------------------------------------------

    private List<Finding> buildFindings(RunEvidence baseline, RunEvidence trial,
                                        String baselineRun, String trialRun, EvidenceLedger ledger) {
        List<Finding> findings = new ArrayList<>();

        if (baseline.outcome() == BuildOutcome.TIMEOUT) {
            findings.add(new Finding(FindingType.RUN_TIMEOUT, Severity.HIGH, "Baseline run timed out",
                    baseline.failureSummary(), List.of(baselineRun), List.of()));
        }
        if (trial.outcome() == BuildOutcome.TIMEOUT) {
            findings.add(new Finding(FindingType.RUN_TIMEOUT, Severity.HIGH, "Trial run timed out",
                    trial.failureSummary(), List.of(), List.of(trialRun)));
        }

        boolean baselineOk = baseline.outcome() == BuildOutcome.SUCCESS;
        boolean trialOk = trial.outcome() == BuildOutcome.SUCCESS;
        boolean baselineFailed = baseline.outcome() == BuildOutcome.FAILURE;
        boolean trialFailed = trial.outcome() == BuildOutcome.FAILURE;

        if (baselineOk && trialFailed) {
            List<String> trialCodes = new ArrayList<>(List.of(trialRun));
            trialCodes.addAll(recordCompileErrors(trial.compileErrors(), Side.TRIAL, ledger));
            String description = "The build succeeded on baseline and failed on trial in phase " + trial.phaseReached() + "."
                    + (trial.compileErrors().isEmpty() ? "" : " " + trial.compileErrors().size() + " compile error(s).")
                    + (trial.failureSummary() == null ? "" : " Maven: " + trial.failureSummary());
            findings.add(new Finding(FindingType.BUILD_REGRESSION, Severity.CRITICAL,
                    "Trial build fails (" + trial.phaseReached() + ")", description, List.of(baselineRun), trialCodes));
        } else if (baselineFailed && trialOk) {
            List<String> baselineCodes = new ArrayList<>(List.of(baselineRun));
            baselineCodes.addAll(recordCompileErrors(baseline.compileErrors(), Side.BASELINE, ledger));
            findings.add(new Finding(FindingType.BUILD_FIXED, Severity.INFO,
                    "Trial build succeeds where baseline failed",
                    "Baseline failed in phase " + baseline.phaseReached() + "; trial build succeeded.",
                    baselineCodes, List.of(trialRun)));
        } else if (baselineFailed && trialFailed) {
            List<String> baselineCodes = new ArrayList<>(List.of(baselineRun));
            baselineCodes.addAll(recordCompileErrors(baseline.compileErrors(), Side.BASELINE, ledger));
            List<String> trialCodes = new ArrayList<>(List.of(trialRun));
            trialCodes.addAll(recordCompileErrors(trial.compileErrors(), Side.TRIAL, ledger));
            findings.add(new Finding(FindingType.BUILD_FAILED_BOTH, Severity.HIGH,
                    "Baseline and trial builds both fail",
                    "Baseline failed in phase " + baseline.phaseReached() + ", trial failed in phase " + trial.phaseReached() + ".",
                    baselineCodes, trialCodes));
        }
        return findings;
    }

    private List<String> recordCompileErrors(List<CompileError> errors, Side side, EvidenceLedger ledger) {
        return errors.stream()
                .limit(MAX_COMPILE_ERRORS_AS_EVIDENCE)
                .map(e -> ledger.record(EvidenceKind.COMPILE_ERROR, side,
                        side + " compile error at " + e.location(), e.message()))
                .toList();
    }

    // ---- tests ---------------------------------------------------------------------------------

    private List<Finding> testFindings(RunEvidence baseline, RunEvidence trial,
                                       String baselineRun, String trialRun, EvidenceLedger ledger) {
        Map<String, TestCaseResult> baselineTests = byKey(baseline.tests());
        Map<String, TestCaseResult> trialTests = byKey(trial.tests());
        boolean trialRanTests = TESTS_RAN.contains(trial.phaseReached()) || !trial.tests().isEmpty();

        List<Finding> findings = new ArrayList<>();
        List<String> stillFailing = new ArrayList<>();

        for (Map.Entry<String, TestCaseResult> entry : baselineTests.entrySet()) {
            String key = entry.getKey();
            TestCaseResult before = entry.getValue();
            TestCaseResult after = trialTests.get(key);

            if (before.outcome() == TestOutcome.PASSED) {
                if (after != null && after.outcome().isFailure()) {
                    String baselineCode = ledger.record(EvidenceKind.TEST_PASS, Side.BASELINE,
                            "Baseline test passed: " + key, "Outcome PASSED.");
                    String trialCode = ledger.record(EvidenceKind.TEST_FAILURE, Side.TRIAL,
                            "Trial test " + after.outcome() + ": " + key, failureDetail(after));
                    findings.add(new Finding(FindingType.TEST_REGRESSION, Severity.HIGH,
                            "Test regressed: " + key,
                            "Passed in baseline, " + after.outcome() + " in trial"
                                    + (after.failureMessage() == null ? "." : ": " + oneLine(after.failureMessage())),
                            List.of(baselineCode), List.of(trialCode)));
                } else if (after == null && trialRanTests) {
                    findings.add(new Finding(FindingType.TEST_MISSING, Severity.MEDIUM,
                            "Test missing in trial: " + key,
                            "Passed in baseline but was not executed in trial.",
                            List.of(baselineRun), List.of(trialRun)));
                }
            } else if (before.outcome().isFailure() && after != null) {
                if (after.outcome() == TestOutcome.PASSED) {
                    String baselineCode = ledger.record(EvidenceKind.TEST_FAILURE, Side.BASELINE,
                            "Baseline test " + before.outcome() + ": " + key, failureDetail(before));
                    findings.add(new Finding(FindingType.TEST_FIXED, Severity.INFO,
                            "Test fixed: " + key, "Failed in baseline, passes in trial.",
                            List.of(baselineCode), List.of(trialRun)));
                } else if (after.outcome().isFailure()) {
                    stillFailing.add(key);
                }
            }
        }

        if (!stillFailing.isEmpty()) {
            findings.add(new Finding(FindingType.TEST_STILL_FAILING, Severity.LOW,
                    stillFailing.size() + " test(s) fail on both sides",
                    "Already failing in baseline, so not caused by the change: " + String.join(", ", stillFailing),
                    List.of(baselineRun), List.of(trialRun)));
        }

        if (baseline.succeeded() && trial.succeeded() && baseline.tests().isEmpty() && trial.tests().isEmpty()) {
            findings.add(new Finding(FindingType.NO_TESTS, Severity.LOW,
                    "No tests ran on either side",
                    "Verification coverage is limited to compilation and packaging.",
                    List.of(baselineRun), List.of(trialRun)));
        }
        return findings;
    }

    private static Map<String, TestCaseResult> byKey(List<TestCaseResult> tests) {
        Map<String, TestCaseResult> map = new TreeMap<>(); // sorted => deterministic evidence order
        for (TestCaseResult t : tests) {
            map.putIfAbsent(t.key(), t);
        }
        return map;
    }

    // ---- dependencies --------------------------------------------------------------------------

    private List<Finding> dependencyFindings(RunEvidence baseline, RunEvidence trial, EvidenceLedger ledger) {
        if (baseline.dependencies().isEmpty() || trial.dependencies().isEmpty()) {
            return List.of(); // one side has no tree; a diff would be misleading
        }
        Map<String, Dependency> before = new LinkedHashMap<>();
        baseline.dependencies().forEach(d -> before.put(d.key(), d));
        Map<String, Dependency> after = new LinkedHashMap<>();
        trial.dependencies().forEach(d -> after.put(d.key(), d));

        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());

        List<String> changes = new ArrayList<>();
        for (String key : keys) {
            Dependency b = before.get(key);
            Dependency a = after.get(key);
            if (b != null && a != null && !Objects.equals(b.version(), a.version())) {
                changes.add(key + " " + b.version() + " -> " + a.version());
            } else if (b == null) {
                changes.add(key + " added (" + a.version() + ")");
            } else if (a == null) {
                changes.add(key + " removed (was " + b.version() + ")");
            }
        }
        if (changes.isEmpty()) {
            return List.of();
        }
        List<String> codes = changes.stream()
                .limit(MAX_DEPENDENCY_CHANGES_AS_EVIDENCE)
                .map(c -> ledger.record(EvidenceKind.DEPENDENCY_CHANGE, null, "Dependency change: " + c, c))
                .toList();
        return List.of(new Finding(FindingType.DEPENDENCY_CHANGED, Severity.INFO,
                changes.size() + " resolved dependency change(s)",
                "Resolved dependency tree differs between baseline and trial"
                        + (changes.size() > codes.size() ? " (first " + codes.size() + " shown as evidence)." : "."),
                List.of(), codes));
    }

    // ---- text helpers --------------------------------------------------------------------------

    private static String describeRun(RunEvidence run) {
        StringBuilder sb = new StringBuilder()
                .append("outcome=").append(run.outcome())
                .append(", exitCode=").append(run.exitCode())
                .append(", phaseReached=").append(run.phaseReached())
                .append(", tests: ").append(run.testSummary().describe())
                .append(", compileErrors=").append(run.compileErrors().size());
        if (run.failureSummary() != null) {
            sb.append(". Failure: ").append(run.failureSummary());
        }
        return truncate(sb.toString());
    }

    private static String failureDetail(TestCaseResult test) {
        StringBuilder sb = new StringBuilder();
        if (test.failureType() != null) sb.append(test.failureType()).append(": ");
        if (test.failureMessage() != null) sb.append(test.failureMessage());
        if (test.stackTrace() != null) sb.append("\n").append(test.stackTrace());
        return truncate(sb.isEmpty() ? "No failure message recorded." : sb.toString());
    }

    private static String testRegressionSuffix(ComparisonContext c) {
        long n = c.count(FindingType.TEST_REGRESSION);
        return n == 0 ? "" : "; " + n + " test(s) that passed in baseline fail in trial";
    }

    private static String oneLine(String s) {
        String line = s.strip().lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }

    private static String truncate(String s) {
        return s.length() <= MAX_DETAIL_CHARS ? s : s.substring(0, MAX_DETAIL_CHARS) + "\n... (truncated)";
    }
}
