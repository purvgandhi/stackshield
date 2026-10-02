package com.stacktrial.trial;

import com.stacktrial.comparison.EvidenceItem;
import com.stacktrial.comparison.EvidenceKind;
import com.stacktrial.comparison.Side;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "evidence")
public class EvidenceRecord {

    @Id
    private UUID id;
    private UUID trialId;
    private String code;

    @Enumerated(EnumType.STRING)
    private EvidenceKind kind;

    @Enumerated(EnumType.STRING)
    private Side side;

    private String title;
    private String detail;

    protected EvidenceRecord() {
    }

    public static EvidenceRecord of(UUID trialId, EvidenceItem item) {
        EvidenceRecord r = new EvidenceRecord();
        r.id = UUID.randomUUID();
        r.trialId = trialId;
        r.code = item.code();
        r.kind = item.kind();
        r.side = item.side();
        r.title = item.title();
        r.detail = item.detail();
        return r;
    }

    public EvidenceItem toItem() {
        return new EvidenceItem(code, kind, side, title, detail);
    }

    public String getCode() { return code; }
}
