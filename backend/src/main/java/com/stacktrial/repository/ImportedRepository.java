package com.stacktrial.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "imported_repository")
public class ImportedRepository {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "repo_url", nullable = false)
    private String repoUrl;

    @Column(name = "default_ref", nullable = false)
    private String defaultRef;

    @Column(name = "local_path", nullable = false)
    private String localPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RepositoryStatus status;

    @Column(name = "status_message")
    private String statusMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scan_result")
    private String scanResultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "architecture_result")
    private String architectureResultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "doctor_result")
    private String doctorResultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "security_result")
    private String securityResultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "readiness_result")
    private String readinessResultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "impact_result")
    private String impactResultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "interview_result")
    private String interviewResultJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_result")
    private String analysisResultJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ImportedRepository() {
    }

    public ImportedRepository(UUID id, String name, String repoUrl, String defaultRef, String localPath,
                              RepositoryStatus status, String statusMessage, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.repoUrl = repoUrl;
        this.defaultRef = defaultRef;
        this.localPath = localPath;
        this.status = status;
        this.statusMessage = statusMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRepoUrl() {
        return repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    public String getDefaultRef() {
        return defaultRef;
    }

    public void setDefaultRef(String defaultRef) {
        this.defaultRef = defaultRef;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public RepositoryStatus getStatus() {
        return status;
    }

    public void setStatus(RepositoryStatus status) {
        this.status = status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public String getScanResultJson() {
        return scanResultJson;
    }

    public void setScanResultJson(String scanResultJson) {
        this.scanResultJson = scanResultJson;
    }

    public String getArchitectureResultJson() {
        return architectureResultJson;
    }

    public void setArchitectureResultJson(String architectureResultJson) {
        this.architectureResultJson = architectureResultJson;
    }

    public String getDoctorResultJson() {
        return doctorResultJson;
    }

    public void setDoctorResultJson(String doctorResultJson) {
        this.doctorResultJson = doctorResultJson;
    }

    public String getSecurityResultJson() {
        return securityResultJson;
    }

    public void setSecurityResultJson(String securityResultJson) {
        this.securityResultJson = securityResultJson;
    }

    public String getReadinessResultJson() {
        return readinessResultJson;
    }

    public void setReadinessResultJson(String readinessResultJson) {
        this.readinessResultJson = readinessResultJson;
    }

    public String getImpactResultJson() {
        return impactResultJson;
    }

    public void setImpactResultJson(String impactResultJson) {
        this.impactResultJson = impactResultJson;
    }

    public String getInterviewResultJson() {
        return interviewResultJson;
    }

    public void setInterviewResultJson(String interviewResultJson) {
        this.interviewResultJson = interviewResultJson;
    }

    public String getAnalysisResultJson() {
        return analysisResultJson;
    }

    public void setAnalysisResultJson(String analysisResultJson) {
        this.analysisResultJson = analysisResultJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
