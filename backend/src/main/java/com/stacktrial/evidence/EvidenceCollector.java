package com.stacktrial.evidence;

import org.springframework.stereotype.Component;

import java.util.List;

/** Turns the raw artifacts of one container run into structured {@link RunEvidence}. */
@Component
public class EvidenceCollector {

    private final SurefireReportParser surefireParser;
    private final DependencyTreeParser dependencyTreeParser;
    private final MavenBuildLogParser buildLogParser;

    public EvidenceCollector(SurefireReportParser surefireParser,
                             DependencyTreeParser dependencyTreeParser,
                             MavenBuildLogParser buildLogParser) {
        this.surefireParser = surefireParser;
        this.dependencyTreeParser = dependencyTreeParser;
        this.buildLogParser = buildLogParser;
    }

    public RunEvidence collect(RawRunArtifacts raw) {
        BuildOutcome outcome = raw.timedOut()
                ? BuildOutcome.TIMEOUT
                : raw.exitCode() == 0 ? BuildOutcome.SUCCESS : BuildOutcome.FAILURE;

        MavenBuildLogParser.ParsedBuildLog log = buildLogParser.parse(raw.buildLog(), outcome == BuildOutcome.SUCCESS);
        List<TestCaseResult> tests = surefireParser.parseDirectory(raw.surefireReportsDir());
        List<Dependency> dependencies = dependencyTreeParser.parse(raw.dependencyTree());

        String failureSummary = switch (outcome) {
            case SUCCESS -> null;
            case TIMEOUT -> "Run exceeded the hard timeout and was killed after " + raw.durationMs() + " ms";
            case FAILURE -> log.failureSummary() != null
                    ? log.failureSummary()
                    : "Maven exited with code " + raw.exitCode();
        };

        return new RunEvidence(outcome, raw.exitCode(), log.phaseReached(), raw.durationMs(),
                tests, log.compileErrors(), dependencies, failureSummary);
    }
}
