# RAG flow

RAG (retrieval-augmented generation) gives Gemini a small set of relevant, curated documentation to ground its explanation, and gives the reader links to check. Retrieval is real in both modes: pgvector similarity when Gemini is configured, PostgreSQL full-text search when it is not. Package: `com.stacktrial.explanation`.

## 1. The knowledge base

`backend/src/main/resources/knowledge/*.md`: hand-written summaries of official sources, one Markdown file per source.

| File | Chunk ids | Source |
|---|---|---|
| `spring-boot-3.0-migration.md` | `KB-SB30-01` … `KB-SB30-08` | Spring Boot 3.0 Migration Guide |
| `spring-boot-3.x-release-notes.md` | `KB-SB3X-01` … `KB-SB3X-05` | Spring Boot 3.1–3.4 release notes |
| `java-21-upgrade.md` | `KB-JDK21-01` … `KB-JDK21-05` | Java 21 upgrade notes |
| `maven-dependency-upgrades.md` | `KB-MVN-01` … `KB-MVN-04` | Maven dependency behaviour |

22 chunks in total. Each `## ` section is one chunk; its id is the file's prefix plus the section number, so `KB-SB30-03` is the third section of the Boot 3.0 file ("Spring MVC and WebFlux: trailing slash matching disabled").

## 2. Ingestion (startup)

```text
ApplicationReadyEvent
  └─ KnowledgeIngestionService.ingest()
       KnowledgeLoader.load()              split each file on "## " → KnowledgeChunk(id, source, url, title, content)
       KnowledgeRepository.upsert(chunk, sha256(title+content))
           content changed? → embedding set to NULL (will be re-embedded)
       deleteAllExcept(current ids)        removed sections disappear
       if Gemini configured:
           for each chunk without embedding:
               GeminiClient.embed(text, DOCUMENT)  → 768 floats → UPDATE knowledge_chunk SET embedding
```

`knowledge_chunk` also has `search_vector tsvector GENERATED ALWAYS AS (to_tsvector('english', title || ' ' || content))`, so the full-text index needs no ingestion code at all.

## 3. Building the query

`PromptBuilder.retrievalQuery(input)` uses only what broke, because run summaries and long dependency lists would drown the signal:

```text
change summary                      "Upgrade Spring Boot to 3.0.13 — Spring Boot 2.7.18 -> 3.0.13"
titles of non-INFO findings         "Test regressed: …OrderControllerTest#listsOrdersWithTrailingSlash"
first 2 lines of failure evidence   "java.lang.AssertionError: Status expected:<200> but was:<404>"
(capped at 4000 chars, then redacted)
```

## 4. Retrieval

`KnowledgeRetriever.retrieve(query)`:

**Vector mode** (Gemini configured **and** every chunk has an embedding):
```sql
SELECT *, embedding <=> ?::vector AS distance FROM knowledge_chunk
WHERE embedding IS NOT NULL ORDER BY distance LIMIT 5;      -- then keep distance ≤ 0.45
```
`<=>` is pgvector's cosine distance; the HNSW index (`vector_cosine_ops`) serves it. If embedding the query fails, it falls back to full text.

**Full-text mode** (otherwise):
1. `terms(query)`: lower-case words of 3+ letters, camelCase split (`listsOrdersWithTrailingSlash` → `trailing`, `slash`), stop words removed (generic words like `test`, `failed`, `spring`, `maven`), numbers kept only with exactly three digits (HTTP codes like `404`), at most 40 terms.
2. OR query ranked by `ts_rank`:
   ```sql
   SELECT *, ts_rank(search_vector, to_tsquery('english', 'trailing | slash | 404 | …')) AS rank
   FROM knowledge_chunk WHERE search_vector @@ to_tsquery(…) ORDER BY rank DESC, id LIMIT 5;
   ```
3. `keepStrongHits`: ranks have no absolute scale, so any hit below 35 % of the best hit's rank is dropped.

The report records which method ran (`retrievalMethod`).

## 5. Use of the retrieved chunks

- With Gemini: the chunks go into the prompt under `KNOWLEDGE` with their ids, and Gemini must cite them as `[KB-…]` (see [GEMINI_FLOW.md](GEMINI_FLOW.md)).
- Without Gemini: the report shows them under "Relevant documentation" with a note that they were selected without AI.

## Real results (full-text mode, no key)

| Scenario | Retrieved | Assessment |
|---|---|---|
| 1 Boot 2.7 → 3.0, trailing-slash tests 404 | `KB-SB30-03` (first), `KB-JDK21-04` | The top hit is exactly the cause. The second is weak |
| 4 diff calling `StringUtils.truncateMiddle` | `KB-SB30-02`, `KB-SB3X-01` | Weak: the corpus has no entry for "code calls a method that does not exist", and lexical search matched "cannot find symbol" in the Jakarta section |
| 2, 3, 5 (PASS) | nothing | No retrieval: a PASS needs no explanation |

Vector retrieval has been verified with fixed vectors in `KnowledgeRepositoryIT` (real PostgreSQL + pgvector), but **not with real Gemini embeddings**, because no API key was available while building. Tuning `max-cosine-distance` on real embeddings is pending.

## Tests
- `KnowledgeRepositoryIT` (Testcontainers pgvector): the knowledge base is ingested at startup; full-text search finds the trailing-slash chunk; vector search ranks by cosine similarity and drops a chunk beyond the distance cut-off.
- `ExplanationUnitTest`: chunk splitting and ids, the bundled knowledge base loads, query terms (camelCase split, stop words, 3-digit codes), the relative-rank cut-off, citation validation.
- `ExplanationServiceIT`: retrieval → prompt → stub LLM → citation validation → stored explanation → rendered report.

## Limits
The corpus is small and hand-curated, so answers are only as good as its coverage. Growing it means adding sections to the Markdown files (or new files); ingestion picks them up on the next start.
