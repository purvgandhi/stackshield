package com.stacktrial.readiness;

import java.util.List;

public record ReadinessResult(
        int scorePercentage, // 0 - 100
        String readinessStatus, // PRODUCTION_READY, ACTION_REQUIRED, NOT_READY
        int passedCount,
        int warningCount,
        int failedCount,
        List<ReadinessCheck> checks,
        String summary
) {
}
