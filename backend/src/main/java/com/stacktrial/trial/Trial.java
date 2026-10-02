package com.stacktrial.trial;

import com.stacktrial.comparison.Verdict;
import com.stacktrial.workspace.change.ChangeRequest;
import com.stacktrial.workspace.change.ChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "trial")
public class Trial {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String repoUrl;

    @Column(nullable = false)
    private String baselineRef;

    private String baselineCommit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChangeType changeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> changeSpec;

    private String changeSummary;
    private String appliedDiff;
    private Integer baselineJava;
    private Integer trialJava;
    private String baselineBoot;
    private String trialBoot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrialStatus status;

    private String currentStep;

    @Enumerated(EnumType.STRING)
    private Verdict verdict;

    private String verdictReason;
    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Trial() {
    }

    public static Trial queued(String repoUrl, String baselineRef, ChangeRequest change) {
        Trial t = new Trial();
        t.id = UUID.randomUUID();
        t.repoUrl = repoUrl;
        t.baselineRef = baselineRef;
        t.changeType = change.type();
        t.changeSpec = new LinkedHashMap<>();
        putIfPresent(t.changeSpec, "groupId", change.groupId());
        putIfPresent(t.changeSpec, "artifactId", change.artifactId());
        putIfPresent(t.changeSpec, "targetVersion", change.targetVersion());
        putIfPresent(t.changeSpec, "diff", change.diff());
        t.changeSummary = change.describe();
        t.status = TrialStatus.QUEUED;
        t.currentStep = "Waiting for a worker";
        t.createdAt = Instant.now();
        t.updatedAt = t.createdAt;
        return t;
    }

    public ChangeRequest changeRequest() {
        return new ChangeRequest(changeType, changeSpec.get("groupId"), changeSpec.get("artifactId"),
                changeSpec.get("targetVersion"), changeSpec.get("diff"));
    }

    public void moveTo(TrialStatus next, String step) {
        if (status.isTerminal()) {
            throw new IllegalStateException("Trial " + id + " is already " + status);
        }
        if (startedAt == null && next != TrialStatus.QUEUED) {
            startedAt = Instant.now();
        }
        status = next;
        currentStep = step;
        updatedAt = Instant.now();
        if (next.isTerminal()) {
            finishedAt = updatedAt;
        }
    }

    public void fail(String reason) {
        failureReason = reason;
        moveTo(TrialStatus.FAILED, "Failed");
    }

    public void recordPreparation(String commit, String appliedChange, String diff,
                                  Integer baselineJava, Integer trialJava, String baselineBoot, String trialBoot) {
        this.baselineCommit = commit;
        this.changeSummary = appliedChange;
        this.appliedDiff = diff;
        this.baselineJava = baselineJava;
        this.trialJava = trialJava;
        this.baselineBoot = baselineBoot;
        this.trialBoot = trialBoot;
        this.updatedAt = Instant.now();
    }

    public void recordVerdict(Verdict verdict, String reason) {
        this.verdict = verdict;
        this.verdictReason = reason;
        this.updatedAt = Instant.now();
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    public UUID getId() { return id; }
    public String getRepoUrl() { return repoUrl; }
    public String getBaselineRef() { return baselineRef; }
    public String getBaselineCommit() { return baselineCommit; }
    public ChangeType getChangeType() { return changeType; }
    public Map<String, String> getChangeSpec() { return changeSpec; }
    public String getChangeSummary() { return changeSummary; }
    public String getAppliedDiff() { return appliedDiff; }
    public Integer getBaselineJava() { return baselineJava; }
    public Integer getTrialJava() { return trialJava; }
    public String getBaselineBoot() { return baselineBoot; }
    public String getTrialBoot() { return trialBoot; }
    public TrialStatus getStatus() { return status; }
    public String getCurrentStep() { return currentStep; }
    public Verdict getVerdict() { return verdict; }
    public String getVerdictReason() { return verdictReason; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
