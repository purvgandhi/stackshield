package com.stacktrial.scanner;

public record HealthObservation(
        String type, // INFO, WARNING, SUCCESS, CRITICAL
        String title,
        String description,
        String recommendation
) {
}
