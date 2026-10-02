package com.stacktrial.comparison;

/**
 * A single fact with a stable, human-readable id ({@code EVIDENCE-001}).
 * Findings point at these ids, and the AI explanation must cite them.
 *
 * @param side null when the fact spans both sides (e.g. a dependency diff)
 */
public record EvidenceItem(String code, EvidenceKind kind, Side side, String title, String detail) {
}
