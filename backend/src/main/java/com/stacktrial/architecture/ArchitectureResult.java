package com.stacktrial.architecture;

import java.util.List;
import java.util.Map;

public record ArchitectureResult(
        String style, // Layered Monolith, Clean/Hexagonal, Microservice Component, Generic
        int totalComponents,
        int detectedControllers,
        int detectedServices,
        int detectedRepositories,
        int detectedEntities,
        int detectedConfigurations,
        int detectedEndpoints,
        List<ArchitectureNode> nodes,
        List<ApiEndpointInfo> endpoints,
        List<String> externalIntegrations,
        Map<String, List<String>> packageHierarchy,
        String summary
) {
}
