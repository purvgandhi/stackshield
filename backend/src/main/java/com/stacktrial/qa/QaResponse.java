package com.stacktrial.qa;

import java.util.List;

public record QaResponse(
        String question,
        String answer,
        List<QaCitation> citations,
        boolean aiGenerated
) {
}
