package com.stacktrial.evidence;

import java.util.List;

/**
 * Everything StackTrial knows about one execution (baseline or trial), parsed from real artifacts
 * copied out of the container. This is the only input the comparison engine sees.
 */
public record RunEvidence(
        BuildOutcome outcome,
        int exitCode,
        BuildPhase phaseReached,
        long durationMs,
        List<TestCaseResult> tests,
        List<CompileError> compileErrors,
        List<Dependency> dependencies,
        String failureSummary) {

    public RunEvidence {
        tests = List.copyOf(tests);
        compileErrors = List.copyOf(compileErrors);
        dependencies = List.copyOf(dependencies);
    }

    public TestSummary testSummary() {
        return TestSummary.of(tests);
    }

    public boolean succeeded() {
        return outcome == BuildOutcome.SUCCESS;
    }
}
