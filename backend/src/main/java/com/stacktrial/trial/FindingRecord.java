package com.stacktrial.trial;

import com.stacktrial.comparison.Finding;
import com.stacktrial.comparison.FindingType;
import com.stacktrial.comparison.Severity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "finding")
public class FindingRecord {

    @Id
    private UUID id;
    private UUID trialId;
    private int position;

    @Enumerated(EnumType.STRING)
    private FindingType type;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    private String title;
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> baselineEvidence;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> trialEvidence;

    protected FindingRecord() {
    }

    public static FindingRecord of(UUID trialId, int position, Finding f) {
        FindingRecord r = new FindingRecord();
        r.id = UUID.randomUUID();
        r.trialId = trialId;
        r.position = position;
        r.type = f.type();
        r.severity = f.severity();
        r.title = f.title();
        r.description = f.description();
        r.baselineEvidence = f.baselineEvidence();
        r.trialEvidence = f.trialEvidence();
        return r;
    }

    public Finding toFinding() {
        return new Finding(type, severity, title, description, baselineEvidence, trialEvidence);
    }
}
