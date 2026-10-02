package com.stacktrial.trial;

import com.stacktrial.evidence.TestCaseResult;
import com.stacktrial.evidence.TestOutcome;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "test_result")
public class TestResult {

    @Id
    private UUID id;
    private UUID runId;
    private String className;
    private String testName;

    @Enumerated(EnumType.STRING)
    private TestOutcome outcome;

    private String failureType;
    private String failureMessage;
    private String stackTrace;
    private long durationMs;

    protected TestResult() {
    }

    public static TestResult of(UUID runId, TestCaseResult t) {
        TestResult r = new TestResult();
        r.id = UUID.randomUUID();
        r.runId = runId;
        r.className = t.className();
        r.testName = t.name();
        r.outcome = t.outcome();
        r.failureType = t.failureType();
        r.failureMessage = t.failureMessage();
        r.stackTrace = t.stackTrace();
        r.durationMs = t.durationMs();
        return r;
    }

    public UUID getRunId() { return runId; }
    public String getClassName() { return className; }
    public String getTestName() { return testName; }
    public TestOutcome getOutcome() { return outcome; }
    public String getFailureType() { return failureType; }
    public String getFailureMessage() { return failureMessage; }
    public String getStackTrace() { return stackTrace; }
    public long getDurationMs() { return durationMs; }
}
