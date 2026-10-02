package com.stacktrial.web.api;

public record ProjectRunApiRequest(
        String command,
        String image,
        Integer javaVersion
) {
}
