package com.stacktrial.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** All StackTrial settings, bound from {@code stacktrial.*} in application.yml / environment variables. */
@ConfigurationProperties("stacktrial")
public record StackTrialProperties(
        @DefaultValue("./data") Path dataDir,
        @DefaultValue Workspace workspace,
        @DefaultValue Execution execution,
        @DefaultValue Runner runner,
        @DefaultValue Gemini gemini,
        @DefaultValue Rag rag) {

    public record Workspace(
            @DefaultValue("true") boolean allowLocalRepositories,
            @DefaultValue("3m") Duration cloneTimeout,
            @DefaultValue("false") boolean keepWorkspaces) {
    }

    public record Execution(
            @DefaultValue("1") int maxConcurrentTrials,
            @DefaultValue("20") int queueCapacity) {
    }

    /**
     * Container settings. They are global on purpose: baseline and trial cannot be given different limits.
     *
     * @param images      Java major version -> runner image
     * @param networkMode Docker network for runner containers (bridge by default)
     * @param extraEnv    extra environment variables, e.g. an HTTPS proxy for Maven
     * @param extraBinds  extra read-only binds "hostPath:containerPath", e.g. a proxy CA truststore
     */
    public record Runner(
            @DefaultValue Map<Integer, String> images,
            @DefaultValue("21") int defaultJavaVersion,
            @DefaultValue("2048") long memoryMb,
            @DefaultValue("2.0") double cpus,
            @DefaultValue("512") long pidsLimit,
            @DefaultValue("15m") Duration timeout,
            @DefaultValue("1000:1000") String user,
            @DefaultValue("bridge") String networkMode,
            @DefaultValue("stacktrial-m2-cache") String dependencyCacheVolume,
            @DefaultValue Map<String, String> extraEnv,
            @DefaultValue List<String> extraBinds) {
    }

    public record Gemini(
            @DefaultValue("") String apiKey,
            @DefaultValue("https://generativelanguage.googleapis.com/v1beta") String baseUrl,
            @DefaultValue("gemini-2.5-flash") String chatModel,
            @DefaultValue("gemini-embedding-001") String embeddingModel,
            @DefaultValue("60s") Duration timeout) {

        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * @param embeddingDimensions must match the vector(768) column in the Flyway migration
     * @param maxCosineDistance   vector hits further away than this are treated as "not relevant"
     */
    public record Rag(
            @DefaultValue("5") int topK,
            @DefaultValue("768") int embeddingDimensions,
            @DefaultValue("0.45") double maxCosineDistance) {
    }
}
