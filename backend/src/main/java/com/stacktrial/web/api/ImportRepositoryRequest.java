package com.stacktrial.web.api;

import jakarta.validation.constraints.NotBlank;

public record ImportRepositoryRequest(
        @NotBlank(message = "repoUrl is required")
        String repoUrl,
        String ref
) {
}
