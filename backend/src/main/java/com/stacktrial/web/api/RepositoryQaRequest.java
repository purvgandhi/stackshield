package com.stacktrial.web.api;

import jakarta.validation.constraints.NotBlank;

public record RepositoryQaRequest(
        @NotBlank(message = "question is required")
        String question
) {
}
