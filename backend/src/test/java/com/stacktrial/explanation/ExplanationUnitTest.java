package com.stacktrial.explanation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExplanationUnitTest {

    @Test
    void citationsAreCheckedAgainstWhatWasProvided() {
        String answer = "The build broke [EVIDENCE-002] because of trailing slashes [KB-SB30-03]. See also [KB-SB30-99] [EVIDENCE-777].";
        CitationValidator.Citations c = CitationValidator.validate(answer, Set.of("EVIDENCE-002"), Set.of("KB-SB30-03"));

        assertThat(c.evidence()).containsExactly("EVIDENCE-002");
        assertThat(c.knowledge()).containsExactly("KB-SB30-03");
        assertThat(c.unknown()).containsExactly("KB-SB30-99", "EVIDENCE-777");
    }

    @Test
    void knowledgeFilesAreSplitIntoStableChunks() {
        var chunks = KnowledgeLoader.parse("""
                # Guide
                id: KB-T
                source: https://example.org

                ## First
                one
                ## Second
                two
                """);
        assertThat(chunks).extracting(KnowledgeChunk::id).containsExactly("KB-T-01", "KB-T-02");
        assertThat(chunks.get(1).content()).isEqualTo("two");
        assertThat(chunks.get(0).sourceUrl()).isEqualTo("https://example.org");
    }

    @Test
    void bundledKnowledgeBaseLoads() {
        assertThat(new KnowledgeLoader().load()).hasSizeGreaterThan(10)
                .allSatisfy(c -> assertThat(c.id()).matches("KB-[A-Z0-9]+-\\d{2}"));
    }

    @Test
    void searchTermsSplitCamelCaseAndKeepStatusCodes() {
        assertThat(KnowledgeRetriever.terms("Test regressed: OrderControllerTest#listsOrdersWithTrailingSlash expected:<200> but was:<404> at line 44"))
                .contains("trailing", "slash", "404", "200")
                .doesNotContain("44", "test");
    }

    @Test
    void weakFullTextHitsAreDroppedRelativeToTheBestHit() {
        List<RetrievedChunk> hits = List.of(hit("KB-A-01", 0.50), hit("KB-B-01", 0.20), hit("KB-C-01", 0.10));
        assertThat(KnowledgeRetriever.keepStrongHits(hits)).extracting(h -> h.chunk().id())
                .containsExactly("KB-A-01", "KB-B-01"); // 0.10 < 35% of 0.50
        assertThat(KnowledgeRetriever.keepStrongHits(List.of())).isEmpty();
    }

    private static RetrievedChunk hit(String id, double score) {
        return new RetrievedChunk(new KnowledgeChunk(id, "src", null, "title", "content"), score);
    }
}
