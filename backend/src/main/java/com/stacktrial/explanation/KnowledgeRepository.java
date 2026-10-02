package com.stacktrial.explanation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Plain JDBC: pgvector columns and tsvector queries are clearer in SQL than through JPA. */
@Repository
public class KnowledgeRepository {

    private static final RowMapper<KnowledgeChunk> CHUNK = (rs, i) -> new KnowledgeChunk(
            rs.getString("id"), rs.getString("source_title"), rs.getString("source_url"),
            rs.getString("title"), rs.getString("content"));

    private final JdbcTemplate jdbc;

    public KnowledgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts or updates a chunk. If the content changed, its embedding is cleared so it gets re-embedded. */
    public void upsert(KnowledgeChunk c, String contentHash) {
        jdbc.update("""
                INSERT INTO knowledge_chunk (id, source_title, source_url, title, content, content_hash, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    source_title = EXCLUDED.source_title,
                    source_url = EXCLUDED.source_url,
                    title = EXCLUDED.title,
                    content = EXCLUDED.content,
                    embedding = CASE WHEN knowledge_chunk.content_hash = EXCLUDED.content_hash
                                     THEN knowledge_chunk.embedding ELSE NULL END,
                    content_hash = EXCLUDED.content_hash,
                    updated_at = EXCLUDED.updated_at
                """, c.id(), c.sourceTitle(), c.sourceUrl(), c.title(), c.content(), contentHash,
                Timestamp.from(Instant.now()));
    }

    public void deleteAllExcept(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM knowledge_chunk WHERE NOT (id = ANY (?))",
                (Object) ids.toArray(new String[0]));
    }

    public List<KnowledgeChunk> findByIds(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT * FROM knowledge_chunk WHERE id = ANY (?) ORDER BY id", CHUNK,
                (Object) ids.toArray(new String[0]));
    }

    public List<KnowledgeChunk> findWithoutEmbedding() {
        return jdbc.query("SELECT * FROM knowledge_chunk WHERE embedding IS NULL ORDER BY id", CHUNK);
    }

    public void updateEmbedding(String id, float[] embedding) {
        jdbc.update("UPDATE knowledge_chunk SET embedding = ?::vector WHERE id = ?", toVectorLiteral(embedding), id);
    }

    public boolean allEmbedded() {
        Integer missing = jdbc.queryForObject("SELECT count(*) FROM knowledge_chunk WHERE embedding IS NULL", Integer.class);
        Integer total = jdbc.queryForObject("SELECT count(*) FROM knowledge_chunk", Integer.class);
        return total != null && total > 0 && missing != null && missing == 0;
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM knowledge_chunk", Long.class);
        return n == null ? 0 : n;
    }

    /** Nearest neighbours by cosine distance ({@code <=>}), keeping only hits within maxDistance. */
    public List<RetrievedChunk> searchByVector(float[] query, int topK, double maxDistance) {
        return jdbc.query("""
                SELECT *, embedding <=> ?::vector AS distance
                FROM knowledge_chunk
                WHERE embedding IS NOT NULL
                ORDER BY distance
                LIMIT ?
                """, (rs, i) -> new RetrievedChunk(CHUNK.mapRow(rs, i), 1 - rs.getDouble("distance")),
                toVectorLiteral(query), topK)
                .stream()
                .filter(r -> 1 - r.score() <= maxDistance)
                .toList();
    }

    /** Full-text search with an OR query built from the given terms. */
    public List<RetrievedChunk> searchByText(List<String> terms, int topK) {
        if (terms.isEmpty()) {
            return List.of();
        }
        String tsQuery = String.join(" | ", terms);
        return jdbc.query("""
                SELECT *, ts_rank(search_vector, to_tsquery('english', ?)) AS rank
                FROM knowledge_chunk
                WHERE search_vector @@ to_tsquery('english', ?)
                ORDER BY rank DESC, id
                LIMIT ?
                """, (rs, i) -> new RetrievedChunk(CHUNK.mapRow(rs, i), rs.getDouble("rank")),
                tsQuery, tsQuery, topK);
    }

    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
