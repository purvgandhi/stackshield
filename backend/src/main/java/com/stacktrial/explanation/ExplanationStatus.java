package com.stacktrial.explanation;

public enum ExplanationStatus {
    /** Gemini produced an explanation. */
    GENERATED,
    /** Verdict is PASS: there is no regression to explain. */
    NOT_NEEDED,
    /** No Gemini API key configured. */
    UNAVAILABLE,
    /** Gemini was called and failed. */
    FAILED
}
