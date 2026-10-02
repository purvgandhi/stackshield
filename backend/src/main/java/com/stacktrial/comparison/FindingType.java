package com.stacktrial.comparison;

public enum FindingType {
    BUILD_REGRESSION,
    BUILD_FIXED,
    BUILD_FAILED_BOTH,
    RUN_TIMEOUT,
    TEST_REGRESSION,
    TEST_FIXED,
    TEST_STILL_FAILING,
    TEST_MISSING,
    NO_TESTS,
    DEPENDENCY_CHANGED
}
