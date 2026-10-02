package com.stacktrial.web.api;

import java.util.UUID;

public record TrialCreatedResponse(UUID trialId, String status, String reportUrl, String pageUrl) {
}
