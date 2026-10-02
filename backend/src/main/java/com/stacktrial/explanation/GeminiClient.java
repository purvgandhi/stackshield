package com.stacktrial.explanation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stacktrial.common.StackTrialProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;

/**
 * Calls the Gemini REST API (generateContent + embedContent) with Spring's RestClient.
 * Only created when GEMINI_API_KEY is set (see {@link ExplanationConfiguration}).
 */
public class GeminiClient implements LlmClient, EmbeddingClient {

    private final RestClient http;
    private final ObjectMapper json;
    private final StackTrialProperties.Gemini config;
    private final int dimensions;

    public GeminiClient(StackTrialProperties properties, ObjectMapper json) {
        this.config = properties.gemini();
        this.dimensions = properties.rag().embeddingDimensions();
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.timeout()).build());
        factory.setReadTimeout(config.timeout());
        this.http = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(factory)
                .defaultHeader("x-goog-api-key", config.apiKey())
                .build();
    }

    @Override
    public String modelName() {
        return config.chatModel();
    }

    @Override
    public String generate(String systemInstruction, String prompt) {
        ObjectNode body = json.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", systemInstruction);
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        content.putArray("parts").addObject().put("text", prompt);
        body.putObject("generationConfig").put("temperature", 0.1);

        JsonNode response = post("/models/" + config.chatModel() + ":generateContent", body);
        JsonNode parts = response.path("candidates").path(0).path("content").path("parts");
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            text.append(part.path("text").asText(""));
        }
        if (text.isEmpty()) {
            String reason = response.path("candidates").path(0).path("finishReason").asText("no candidates");
            throw new LlmException("Gemini returned no text (" + reason + ")", null);
        }
        return text.toString().strip();
    }

    @Override
    public float[] embed(String text, Purpose purpose) {
        ObjectNode body = json.createObjectNode();
        body.put("model", "models/" + config.embeddingModel());
        body.putObject("content").putArray("parts").addObject().put("text", text);
        body.put("taskType", purpose == Purpose.QUERY ? "RETRIEVAL_QUERY" : "RETRIEVAL_DOCUMENT");
        body.put("outputDimensionality", dimensions);

        JsonNode values = post("/models/" + config.embeddingModel() + ":embedContent", body).path("embedding").path("values");
        if (!(values instanceof ArrayNode array) || array.isEmpty()) {
            throw new LlmException("Gemini returned no embedding", null);
        }
        float[] vector = new float[array.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) array.get(i).asDouble();
        }
        return vector;
    }

    private JsonNode post(String path, ObjectNode body) {
        try {
            return http.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // Never include the request (it carries the API key header) in the message.
            throw new LlmException("Gemini request failed: " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()), e);
        }
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}
