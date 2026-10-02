package com.stacktrial.comparison;

/** The deterministic result of comparing baseline and trial. Never set by AI. */
public enum Verdict {
    /** Trial is at least as healthy as baseline and nothing regressed. */
    PASS,
    /** Something that worked in baseline is broken in trial. */
    REGRESSION,
    /** Something broken in baseline works in trial, and nothing regressed. */
    IMPROVEMENT,
    /** Baseline and trial builds both failed, so the change cannot be judged. */
    BOTH_FAILED,
    /** The evidence is not sufficient to decide (timeout, tests disappeared). */
    INCONCLUSIVE
}
