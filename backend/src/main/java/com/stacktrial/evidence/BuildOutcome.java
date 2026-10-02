package com.stacktrial.evidence;

/** How a Maven run ended, as observed from the container. */
public enum BuildOutcome {
    /** Maven exited with code 0. */
    SUCCESS,
    /** Maven exited with a non-zero code. */
    FAILURE,
    /** The container was killed because it exceeded the hard timeout. */
    TIMEOUT
}
