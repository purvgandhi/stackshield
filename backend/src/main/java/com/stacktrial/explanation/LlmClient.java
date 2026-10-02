package com.stacktrial.explanation;

/** Text generation. Implemented by {@link GeminiClient}; replaced by a stub in tests. */
public interface LlmClient {

    String modelName();

    String generate(String systemInstruction, String prompt);
}
