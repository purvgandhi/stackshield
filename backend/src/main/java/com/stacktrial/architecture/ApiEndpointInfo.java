package com.stacktrial.architecture;

public record ApiEndpointInfo(
        String httpMethod, // GET, POST, PUT, DELETE, PATCH
        String path,
        String controllerName,
        String methodName,
        String description
) {
}
