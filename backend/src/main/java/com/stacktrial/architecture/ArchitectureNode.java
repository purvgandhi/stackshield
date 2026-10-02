package com.stacktrial.architecture;

import java.util.List;

public record ArchitectureNode(
        String id,
        String name,
        String layer, // API, SERVICE, PERSISTENCE, DOMAIN, CONFIG, EXTERNAL
        String role,  // CONTROLLER, SERVICE, REPOSITORY, ENTITY, CONFIGURATION, CLIENT
        String packageName,
        String filePath,
        String detectionType, // DETECTED, INFERRED
        List<String> annotations,
        List<String> targets // IDs of nodes this component calls/depends on
) {
}
