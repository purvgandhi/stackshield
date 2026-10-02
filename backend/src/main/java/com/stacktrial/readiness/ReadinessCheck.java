package com.stacktrial.readiness;

public record ReadinessCheck(
        String name,
        String category, // CONTAINERIZATION, HEALTH_CHECKS, CONFIGURATION, OBSERVABILITY, GRACEFUL_SHUTDOWN, BUILD_CI
        String status,   // PASS, WARN, FAIL
        String description,
        String recommendation
) {
}
