package com.stacktrial.interview;

import java.util.List;

public record InterviewResult(
        int totalQuestions,
        List<InterviewQuestion> questions,
        String summary
) {
}
