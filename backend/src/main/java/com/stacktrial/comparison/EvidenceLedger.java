package com.stacktrial.comparison;

import java.util.ArrayList;
import java.util.List;

/** Hands out sequential evidence codes (EVIDENCE-001, EVIDENCE-002, ...) in the order facts are recorded. */
final class EvidenceLedger {

    private final List<EvidenceItem> items = new ArrayList<>();

    String record(EvidenceKind kind, Side side, String title, String detail) {
        String code = "EVIDENCE-%03d".formatted(items.size() + 1);
        items.add(new EvidenceItem(code, kind, side, title, detail));
        return code;
    }

    List<EvidenceItem> items() {
        return items;
    }
}
