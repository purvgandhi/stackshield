package com.stacktrial.doctor;

import java.util.List;

public record DoctorResult(
        String overallStatus, // READY, WARNINGS, BLOCKED
        int passedCount,
        int warningCount,
        int failedCount,
        List<DoctorCheck> checks,
        String summary
) {
}
