package com.stacktrial.explanation;

import com.stacktrial.comparison.EvidenceItem;
import com.stacktrial.comparison.Finding;
import com.stacktrial.comparison.Verdict;

import java.util.List;
import java.util.UUID;

/** Everything the explanation step may use: the change and the deterministic comparison output. Nothing else. */
public record ExplanationInput(UUID trialId, String changeSummary, Verdict verdict, String verdictReason,
                               List<Finding> findings, List<EvidenceItem> evidence) {
}
