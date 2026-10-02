package com.stacktrial.explanation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "explanation")
public class Explanation {

    @Id
    private UUID id;
    private UUID trialId;

    @Enumerated(EnumType.STRING)
    private ExplanationStatus status;

    private String model;

    @Enumerated(EnumType.STRING)
    private RetrievalMethod retrievalMethod;

    private String content;
    private String statusReason;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> retrievedChunks;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> citedEvidence;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> citedKnowledge;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> unknownCitations;

    @Column(nullable = false)
    private Instant createdAt;

    protected Explanation() {
    }

    static Explanation of(UUID trialId, ExplanationStatus status, String model, RetrievalMethod method, String content,
                          String reason, List<String> retrieved, CitationValidator.Citations citations) {
        Explanation e = new Explanation();
        e.id = UUID.randomUUID();
        e.trialId = trialId;
        e.status = status;
        e.model = model;
        e.retrievalMethod = method;
        e.content = content;
        e.statusReason = reason;
        e.retrievedChunks = List.copyOf(retrieved);
        e.citedEvidence = citations.evidence();
        e.citedKnowledge = citations.knowledge();
        e.unknownCitations = citations.unknown();
        e.createdAt = Instant.now();
        return e;
    }

    public UUID getTrialId() { return trialId; }
    public ExplanationStatus getStatus() { return status; }
    public String getModel() { return model; }
    public RetrievalMethod getRetrievalMethod() { return retrievalMethod; }
    public String getContent() { return content; }
    public String getStatusReason() { return statusReason; }
    public List<String> getRetrievedChunks() { return retrievedChunks; }
    public List<String> getCitedEvidence() { return citedEvidence; }
    public List<String> getCitedKnowledge() { return citedKnowledge; }
    public List<String> getUnknownCitations() { return unknownCitations; }
    public Instant getCreatedAt() { return createdAt; }
}
