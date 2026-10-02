package com.stacktrial.comparison;

import com.stacktrial.evidence.BuildOutcome;
import com.stacktrial.evidence.BuildPhase;
import com.stacktrial.evidence.CompileError;
import com.stacktrial.evidence.Dependency;
import com.stacktrial.evidence.RunEvidence;
import com.stacktrial.evidence.TestCaseResult;
import com.stacktrial.evidence.TestOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonEngineTest {

    private final ComparisonEngine engine = new ComparisonEngine();

    // ---- builders ------------------------------------------------------------------------------

    static TestCaseResult pass(String name) {
        return new TestCaseResult("com.acme.ShopTest", name, TestOutcome.PASSED, null, null, null, 5);
    }

    static TestCaseResult fail(String name) {
        return new TestCaseResult("com.acme.ShopTest", name, TestOutcome.FAILED,
                "Status expected:<200> but was:<404>", "java.lang.AssertionError", "at com.acme.ShopTest", 5);
    }

    static RunEvidence success(TestCaseResult... tests) {
        return new RunEvidence(BuildOutcome.SUCCESS, 0, BuildPhase.VERIFY, 1000, List.of(tests), List.of(), List.of(), null);
    }

    static RunEvidence testFailure(TestCaseResult... tests) {
        return new RunEvidence(BuildOutcome.FAILURE, 1, BuildPhase.TEST, 1000, List.of(tests), List.of(), List.of(),
                "Failed to execute goal maven-surefire-plugin: There are test failures.");
    }

    static RunEvidence compileFailure() {
        return new RunEvidence(BuildOutcome.FAILURE, 1, BuildPhase.COMPILE, 1000, List.of(),
                List.of(new CompileError("src/main/java/A.java", 3, 8, "package javax.annotation does not exist")),
                List.of(), "Failed to execute goal maven-compiler-plugin:compile");
    }

    static RunEvidence timeout() {
        return new RunEvidence(BuildOutcome.TIMEOUT, -1, BuildPhase.UNKNOWN, 900_000, List.of(), List.of(), List.of(),
                "Run exceeded the hard timeout");
    }

    // ---- the verdict decision table ------------------------------------------------------------

    static Stream<Arguments> verdictTable() {
        return Stream.of(
                Arguments.of("identical green runs", success(pass("a"), pass("b")), success(pass("a"), pass("b")), Verdict.PASS),
                Arguments.of("tests passed in baseline fail in trial", success(pass("a"), pass("b")), testFailure(pass("a"), fail("b")), Verdict.REGRESSION),
                Arguments.of("trial no longer compiles", success(pass("a")), compileFailure(), Verdict.REGRESSION),
                Arguments.of("trial fixes a broken build", compileFailure(), success(pass("a")), Verdict.IMPROVEMENT),
                Arguments.of("trial fixes a failing test", testFailure(fail("a"), pass("b")), success(pass("a"), pass("b")), Verdict.IMPROVEMENT),
                Arguments.of("both builds fail", compileFailure(), compileFailure(), Verdict.BOTH_FAILED),
                Arguments.of("baseline timeout", timeout(), success(pass("a")), Verdict.INCONCLUSIVE),
                Arguments.of("trial timeout", success(pass("a")), timeout(), Verdict.INCONCLUSIVE),
                Arguments.of("a baseline test vanished in trial", success(pass("a"), pass("b")), success(pass("a")), Verdict.INCONCLUSIVE),
                Arguments.of("no tests on either side", success(), success(), Verdict.PASS),
                Arguments.of("same test fails on both sides", testFailure(fail("a")), testFailure(fail("a")), Verdict.BOTH_FAILED));
    }

    @ParameterizedTest(name = "{0} -> {3}")
    @MethodSource("verdictTable")
    void verdictFollowsTheDecisionTable(String scenario, RunEvidence baseline, RunEvidence trial, Verdict expected) {
        assertThat(engine.compare(baseline, trial).verdict()).isEqualTo(expected);
    }

    // ---- findings and evidence -----------------------------------------------------------------

    @Test
    void newTestFailureRequiresBaselinePass() {
        ComparisonResult result = engine.compare(success(pass("a"), pass("b")), testFailure(pass("a"), fail("b")));

        List<Finding> regressions = result.findings().stream().filter(f -> f.type() == FindingType.TEST_REGRESSION).toList();
        assertThat(regressions).hasSize(1);
        Finding f = regressions.get(0);
        assertThat(f.title()).contains("com.acme.ShopTest#b");
        assertThat(f.severity()).isEqualTo(Severity.HIGH);
        assertThat(f.baselineEvidence()).hasSize(1);
        assertThat(f.trialEvidence()).hasSize(1);

        EvidenceItem trialEvidence = result.evidence().stream()
                .filter(e -> e.code().equals(f.trialEvidence().get(0))).findFirst().orElseThrow();
        assertThat(trialEvidence.kind()).isEqualTo(EvidenceKind.TEST_FAILURE);
        assertThat(trialEvidence.detail()).contains("expected:<200> but was:<404>");
    }

    @Test
    void testFailingOnBothSidesIsNotARegression() {
        ComparisonResult result = engine.compare(testFailure(fail("a"), pass("b")), testFailure(fail("a"), pass("b")));

        assertThat(result.findings()).extracting(Finding::type)
                .contains(FindingType.TEST_STILL_FAILING)
                .doesNotContain(FindingType.TEST_REGRESSION);
    }

    @Test
    void buildRegressionCarriesCompileErrorsAsEvidence() {
        ComparisonResult result = engine.compare(success(pass("a")), compileFailure());

        Finding build = result.findings().get(0);
        assertThat(build.type()).isEqualTo(FindingType.BUILD_REGRESSION);
        assertThat(build.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(result.evidence()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo(EvidenceKind.COMPILE_ERROR);
            assertThat(e.detail()).contains("javax.annotation");
        });
        // Missing tests are explained by the compile failure, so no TEST_MISSING noise.
        assertThat(result.findings()).extracting(Finding::type).doesNotContain(FindingType.TEST_MISSING);
    }

    @Test
    void dependencyDiffIsInformationalOnly() {
        RunEvidence baseline = new RunEvidence(BuildOutcome.SUCCESS, 0, BuildPhase.VERIFY, 1, List.of(pass("a")), List.of(),
                List.of(new Dependency("org.acme", "lib", "1.0", "compile"), new Dependency("org.acme", "old", "1.0", "compile")), null);
        RunEvidence trial = new RunEvidence(BuildOutcome.SUCCESS, 0, BuildPhase.VERIFY, 1, List.of(pass("a")), List.of(),
                List.of(new Dependency("org.acme", "lib", "2.0", "compile"), new Dependency("org.acme", "new", "1.0", "compile")), null);

        ComparisonResult result = engine.compare(baseline, trial);

        assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        Finding deps = result.findings().stream().filter(f -> f.type() == FindingType.DEPENDENCY_CHANGED).findFirst().orElseThrow();
        assertThat(deps.severity()).isEqualTo(Severity.INFO);
        assertThat(result.evidence()).extracting(EvidenceItem::detail)
                .contains("org.acme:lib 1.0 -> 2.0", "org.acme:new added (1.0)", "org.acme:old removed (was 1.0)");
    }

    @Test
    void durationNeverChangesTheVerdict() {
        RunEvidence fast = success(pass("a"));
        RunEvidence slow = new RunEvidence(BuildOutcome.SUCCESS, 0, BuildPhase.VERIFY, 999_999, List.of(pass("a")), List.of(), List.of(), null);
        assertThat(engine.compare(fast, slow).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void sameInputsProduceIdenticalOutput() {
        RunEvidence baseline = success(pass("b"), pass("a"), pass("c"));
        RunEvidence trial = testFailure(fail("c"), pass("a"), fail("b"));

        assertThat(engine.compare(baseline, trial)).isEqualTo(engine.compare(baseline, trial));
        assertThat(engine.compare(baseline, trial).evidence().get(0).code()).isEqualTo("EVIDENCE-001");
    }

    @Test
    void decisionTableEndsWithACatchAllPassRule() {
        VerdictRule last = ComparisonEngine.VERDICT_RULES.get(ComparisonEngine.VERDICT_RULES.size() - 1);
        assertThat(last.verdict()).isEqualTo(Verdict.PASS);
    }
}
