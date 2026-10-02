package com.stacktrial.explanation;

import com.stacktrial.common.StackTrialProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the knowledge chunks relevant to a trial's findings. Uses pgvector similarity when every chunk has an
 * embedding and Gemini is configured; otherwise PostgreSQL full-text search. Both are real retrieval.
 */
@Service
public class KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetriever.class);
    private static final int MAX_TERMS = 40;
    static final double MIN_RELATIVE_RANK = 0.35;
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "was", "were", "are", "not", "trial",
            "baseline", "test", "tests", "failed", "passed", "build", "phase", "outcome", "evidence", "exit", "code",
            "java", "org", "com", "run", "verify", "maven", "error", "errors", "expected", "actual", "spring",
            "boot", "upgrade", "version", "dependency", "change", "changes", "resolved", "jar", "compile", "null",
            "class", "lang", "location", "method", "file", "files", "main", "src", "example", "line", "apply", "applied",
            "diff", "git", "touching", "uploaded");

    private final KnowledgeRepository repository;
    private final ObjectProvider<EmbeddingClient> embeddings;
    private final StackTrialProperties.Rag config;

    public KnowledgeRetriever(KnowledgeRepository repository, ObjectProvider<EmbeddingClient> embeddings,
                              StackTrialProperties properties) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.config = properties.rag();
    }

    public Retrieval retrieve(String query) {
        EmbeddingClient client = embeddings.getIfAvailable();
        if (client != null && repository.allEmbedded()) {
            try {
                float[] vector = client.embed(query, EmbeddingClient.Purpose.QUERY);
                return new Retrieval(RetrievalMethod.VECTOR,
                        repository.searchByVector(vector, config.topK(), config.maxCosineDistance()));
            } catch (RuntimeException e) {
                log.warn("Vector retrieval failed ({}); falling back to full-text search", e.getMessage());
            }
        }
        return new Retrieval(RetrievalMethod.FULL_TEXT, keepStrongHits(repository.searchByText(terms(query), config.topK())));
    }

    /**
     * Full-text ranks have no absolute meaning, so weak matches are dropped relative to the best one: a chunk must
     * score at least {@value #MIN_RELATIVE_RANK} of the top hit to be shown or sent to Gemini.
     */
    static List<RetrievedChunk> keepStrongHits(List<RetrievedChunk> hits) {
        if (hits.isEmpty()) {
            return hits;
        }
        double best = hits.get(0).score();
        return hits.stream().filter(h -> h.score() >= best * MIN_RELATIVE_RANK).toList();
    }

    /**
     * Lower-cased alphanumeric words, de-duplicated, stop words removed; safe to splice into to_tsquery.
     * camelCase identifiers (test names) are split, so "listsOrdersWithTrailingSlash" yields "trailing" and "slash".
     * Numbers are kept only when they have exactly three digits (HTTP status codes such as 404).
     */
    static List<String> terms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        String split = query.replaceAll("([a-z])([A-Z])", "$1 $2");
        for (String raw : split.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            boolean number = !raw.isEmpty() && raw.chars().allMatch(Character::isDigit);
            if (raw.length() >= 3 && !STOP_WORDS.contains(raw) && (!number || raw.length() == 3)) {
                terms.add(raw);
            }
            if (terms.size() >= MAX_TERMS) {
                break;
            }
        }
        return List.copyOf(terms);
    }

    public record Retrieval(RetrievalMethod method, List<RetrievedChunk> chunks) {
    }
}
