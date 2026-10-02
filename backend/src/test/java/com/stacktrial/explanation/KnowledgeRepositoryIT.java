package com.stacktrial.explanation;

import com.stacktrial.PostgresTestcontainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway migrations + pgvector + full-text search against a real PostgreSQL. */
@SpringBootTest
@Import(PostgresTestcontainer.class)
class KnowledgeRepositoryIT {

    @Autowired
    KnowledgeRepository repository;

    @Test
    void knowledgeBaseIsIngestedAtStartup() {
        assertThat(repository.count()).isGreaterThan(10);
    }

    @Test
    void fullTextSearchFindsTheTrailingSlashSection() {
        List<RetrievedChunk> hits = repository.searchByText(KnowledgeRetriever.terms(
                "Test regressed: OrderControllerTest#listsOrdersWithTrailingSlash Status expected:<200> but was:<404>"), 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunk().id()).isEqualTo("KB-SB30-03");
    }

    @Test
    void vectorSearchRanksByCosineSimilarity() {
        float[] a = unit(768, 0);
        float[] b = unit(768, 1);
        repository.upsert(new KnowledgeChunk("KB-VEC-01", "Test", null, "A", "alpha"), "h1");
        repository.upsert(new KnowledgeChunk("KB-VEC-02", "Test", null, "B", "beta"), "h2");
        repository.updateEmbedding("KB-VEC-01", a);
        repository.updateEmbedding("KB-VEC-02", b);

        List<RetrievedChunk> hits = repository.searchByVector(a, 5, 0.5);

        assertThat(hits).extracting(h -> h.chunk().id()).containsExactly("KB-VEC-01");
        assertThat(hits.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    private static float[] unit(int dims, int axis) {
        float[] v = new float[dims];
        v[axis] = 1f;
        return v;
    }
}
