package com.stacktrial.execution.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_run")
public class ProjectRun {

    @Id
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(nullable = false)
    private String command;

    @Column(nullable = false)
    private String image;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectRunOutcome outcome;

    @Column(name = "exit_code", nullable = false)
    private int exitCode;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "console_log", columnDefinition = "TEXT")
    private String consoleLog;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ProjectRun() {
    }

    public ProjectRun(UUID id, UUID repositoryId, String command, String image, ProjectRunOutcome outcome,
                      int exitCode, long durationMs, String consoleLog, Instant createdAt) {
        this.id = id;
        this.repositoryId = repositoryId;
        this.command = command;
        this.image = image;
        this.outcome = outcome;
        this.exitCode = exitCode;
        this.durationMs = durationMs;
        this.consoleLog = consoleLog;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(UUID repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public ProjectRunOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(ProjectRunOutcome outcome) {
        this.outcome = outcome;
    }

    public int getExitCode() {
        return exitCode;
    }

    public void setExitCode(int exitCode) {
        this.exitCode = exitCode;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getConsoleLog() {
        return consoleLog;
    }

    public void setConsoleLog(String consoleLog) {
        this.consoleLog = consoleLog;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
