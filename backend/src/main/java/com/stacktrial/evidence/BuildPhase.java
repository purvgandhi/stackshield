package com.stacktrial.evidence;

/**
 * The furthest Maven lifecycle stage a run reached. Derived from the build log,
 * never guessed: a successful run is {@link #VERIFY}; a failed run is the phase of the goal that failed.
 */
public enum BuildPhase {
    DEPENDENCY_RESOLUTION,
    COMPILE,
    TEST_COMPILE,
    TEST,
    PACKAGE,
    VERIFY,
    UNKNOWN
}
