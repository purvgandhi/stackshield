package com.stacktrial.trial;

import com.stacktrial.comparison.Side;
import com.stacktrial.evidence.BuildOutcome;
import com.stacktrial.evidence.BuildPhase;
import com.stacktrial.evidence.RunEvidence;
import com.stacktrial.evidence.TestSummary;
import com.stacktrial.execution.MavenRunResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Persisted summary of one side's execution. */
@Entity
@Table(name = "trial_run")
public class TrialRun {

    @Id
    private UUID id;
    private UUID trialId;

    @Enumerated(EnumType.STRING)
    private Side side;

    private String image;
    private String command;

    @Enumerated(EnumType.STRING)
    private BuildOutcome outcome;

    private int exitCode;
    private boolean timedOut;

    @Enumerated(EnumType.STRING)
    private BuildPhase phaseReached;

    private long durationMs;
    private int testsTotal;
    private int testsPassed;
    private int testsFailed;
    private int testsErrored;
    private int testsSkipped;
    private int compileErrorCount;
    private int dependencyCount;
    private String failureSummary;
    private String buildLogKey;
    private String dependencyTreeKey;

    @Column(nullable = false)
    private Instant createdAt;

    protected TrialRun() {
    }

    public static TrialRun of(UUID trialId, Side side, MavenRunResult run, RunEvidence evidence) {
        TrialRun r = new TrialRun();
        r.id = UUID.randomUUID();
        r.trialId = trialId;
        r.side = side;
        r.image = run.image();
        r.command = run.command();
        r.outcome = evidence.outcome();
        r.exitCode = evidence.exitCode();
        r.timedOut = evidence.outcome() == BuildOutcome.TIMEOUT;
        r.phaseReached = evidence.phaseReached();
        r.durationMs = evidence.durationMs();
        TestSummary s = evidence.testSummary();
        r.testsTotal = s.total();
        r.testsPassed = s.passed();
        r.testsFailed = s.failed();
        r.testsErrored = s.errors();
        r.testsSkipped = s.skipped();
        r.compileErrorCount = evidence.compileErrors().size();
        r.dependencyCount = evidence.dependencies().size();
        r.failureSummary = evidence.failureSummary();
        r.buildLogKey = run.buildLogKey();
        r.dependencyTreeKey = run.dependencyTreeKey();
        r.createdAt = Instant.now();
        return r;
    }

    public TestSummary testSummary() {
        return new TestSummary(testsTotal, testsPassed, testsFailed, testsErrored, testsSkipped);
    }

    public UUID getId() { return id; }
    public UUID getTrialId() { return trialId; }
    public Side getSide() { return side; }
    public String getImage() { return image; }
    public String getCommand() { return command; }
    public BuildOutcome getOutcome() { return outcome; }
    public int getExitCode() { return exitCode; }
    public boolean isTimedOut() { return timedOut; }
    public BuildPhase getPhaseReached() { return phaseReached; }
    public long getDurationMs() { return durationMs; }
    public int getCompileErrorCount() { return compileErrorCount; }
    public int getDependencyCount() { return dependencyCount; }
    public String getFailureSummary() { return failureSummary; }
    public String getBuildLogKey() { return buildLogKey; }
    public String getDependencyTreeKey() { return dependencyTreeKey; }
}
