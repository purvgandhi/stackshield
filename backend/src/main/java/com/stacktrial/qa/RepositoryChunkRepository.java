package com.stacktrial.qa;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class RepositoryChunkRepository {

    private static final RowMapper<RepositoryChunk> MAPPER = (rs, i) -> new RepositoryChunk(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("repository_id"),
            rs.getString("file_path"),
            rs.getInt("chunk_index"),
            rs.getString("symbol_name"),
            rs.getString("content"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public RepositoryChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertChunk(RepositoryChunk chunk, float[] embedding) {
        if (embedding != null && embedding.length > 0) {
            jdbc.update("""
                    INSERT INTO repository_chunk (id, repository_id, file_path, chunk_index, symbol_name, content, embedding, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?)
                    ON CONFLICT (id) DO NOTHING
                    """,
                    chunk.getId(), chunk.getRepositoryId(), chunk.getFilePath(),
                    chunk.getChunkIndex(), chunk.getSymbolName(), chunk.getContent(),
                    toVectorLiteral(embedding), Timestamp.from(chunk.getCreatedAt()));
        } else {
            jdbc.update("""
                    INSERT INTO repository_chunk (id, repository_id, file_path, chunk_index, symbol_name, content, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """,
                    chunk.getId(), chunk.getRepositoryId(), chunk.getFilePath(),
                    chunk.getChunkIndex(), chunk.getSymbolName(), chunk.getContent(),
                    Timestamp.from(chunk.getCreatedAt()));
        }
    }

    public void deleteByRepositoryId(UUID repositoryId) {
        jdbc.update("DELETE FROM repository_chunk WHERE repository_id = ?", repositoryId);
    }

    public long countByRepositoryId(UUID repositoryId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM repository_chunk WHERE repository_id = ?", Long.class, repositoryId);
        return count == null ? 0 : count;
    }

    public List<RepositoryChunk> findTopByRepositoryId(UUID repositoryId, int limit) {
        return jdbc.query("SELECT * FROM repository_chunk WHERE repository_id = ? ORDER BY chunk_index ASC LIMIT ?", MAPPER, repositoryId, limit);
    }

    public List<QaCitation> searchByVector(UUID repositoryId, float[] queryVector, int topK) {
        return jdbc.query("""
                SELECT *, 1 - (embedding <=> ?::vector) AS score
                FROM repository_chunk
                WHERE repository_id = ? AND embedding IS NOT NULL
                ORDER BY score DESC
                LIMIT ?
                """, (rs, i) -> new QaCitation(
                rs.getString("file_path"),
                rs.getString("symbol_name"),
                rs.getString("content"),
                rs.getDouble("score")
        ), toVectorLiteral(queryVector), repositoryId, topK);
    }

    public List<QaCitation> searchByText(UUID repositoryId, List<String> keywords, int topK) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        List<String> sanitizedKeywords = keywords.stream()
                .map(k -> k.replaceAll("[^A-Za-z0-9_]", ""))
                .filter(k -> !k.isBlank())
                .toList();

        if (sanitizedKeywords.isEmpty()) {
            return List.of();
        }

        String tsQuery = String.join(" | ", sanitizedKeywords);
        try {
            return jdbc.query("""
                    SELECT *, ts_rank(search_vector, to_tsquery('english', ?)) AS rank
                    FROM repository_chunk
                    WHERE repository_id = ? AND search_vector @@ to_tsquery('english', ?)
                    ORDER BY rank DESC
                    LIMIT ?
                    """, (rs, i) -> new QaCitation(
                    rs.getString("file_path"),
                    rs.getString("symbol_name"),
                    rs.getString("content"),
                    rs.getDouble("rank")
            ), tsQuery, repositoryId, tsQuery, topK);
        } catch (Exception e) {
            return List.of();
        }
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
