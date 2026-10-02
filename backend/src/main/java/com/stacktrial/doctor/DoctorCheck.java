package com.stacktrial.doctor;

public record DoctorCheck(
        String name,
        String category, // RUNTIME, BUILD_TOOL, CONTAINER, DATABASE, NETWORK, CONFIG
        String status,   // PASS, WARN, FAIL
        String problem,
        String evidence,
        String whyItMatters,
        String suggestedFix
) {
}
