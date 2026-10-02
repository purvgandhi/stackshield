package com.stacktrial.pr;

public record ImpactedComponent(
        String name,
        String type, // CONTROLLER, SERVICE, REPOSITORY, ENTITY, CONFIG, DATABASE, API
        String impactReason,
        RiskLevel risk
) {
}
