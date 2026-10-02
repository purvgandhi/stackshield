package com.stacktrial.explanation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stacktrial.common.StackTrialProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExplanationConfiguration {

    /** Exists only when a key is configured; otherwise explanations are reported as unavailable, never faked. */
    @Bean
    @ConditionalOnExpression("!'${stacktrial.gemini.api-key:}'.isBlank()")
    public GeminiClient geminiClient(StackTrialProperties properties, ObjectMapper objectMapper) {
        return new GeminiClient(properties, objectMapper);
    }
}
