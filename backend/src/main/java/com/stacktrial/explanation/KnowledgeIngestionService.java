package com.stacktrial.explanation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Loads the curated knowledge base into PostgreSQL at startup and embeds it when Gemini is configured. */
@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final KnowledgeLoader loader;
    private final KnowledgeRepository repository;
    private final ObjectProvider<EmbeddingClient> embeddings;

    public KnowledgeIngestionService(KnowledgeLoader loader, KnowledgeRepository repository,
                                     ObjectProvider<EmbeddingClient> embeddings) {
        this.loader = loader;
        this.repository = repository;
        this.embeddings = embeddings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ingest() {
        List<KnowledgeChunk> chunks = loader.load();
        chunks.forEach(c -> repository.upsert(c, sha256(c.title() + "\n" + c.content())));
        repository.deleteAllExcept(chunks.stream().map(KnowledgeChunk::id).toList());
        log.info("Knowledge base loaded: {} chunks", chunks.size());

        EmbeddingClient client = embeddings.getIfAvailable();
        if (client == null) {
            log.info("GEMINI_API_KEY not set: knowledge retrieval will use PostgreSQL full-text search");
            return;
        }
        int embedded = 0;
        for (KnowledgeChunk c : repository.findWithoutEmbedding()) {
            try {
                repository.updateEmbedding(c.id(), client.embed(c.title() + "\n" + c.content(), EmbeddingClient.Purpose.DOCUMENT));
                embedded++;
            } catch (RuntimeException e) {
                log.warn("Embedding failed for {}: {}. Full-text retrieval will be used until all chunks are embedded.",
                        c.id(), e.getMessage());
                break;
            }
        }
        log.info("Embedded {} knowledge chunks", embedded);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
