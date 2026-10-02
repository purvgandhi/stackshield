package com.stacktrial.interview;

import java.util.List;

public record InterviewQuestion(
        String id,
        String category, // SYSTEM_DESIGN, ARCHITECTURE, CONCURRENCY, DATABASE, ERROR_HANDLING, PERFORMANCE
        String difficulty, // BEGINNER, INTERMEDIATE, ADVANCED
        String question,
        String answerGuide,
        List<String> relatedFiles,
        List<String> keyTalkingPoints
) {
}
