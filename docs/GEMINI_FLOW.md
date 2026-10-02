# Gemini flow

> **Status: the Gemini integration is implemented but has NOT been verified against the live Gemini API.** No API key was available in the build environment. What *was* verified: the full explanation pipeline with a stub `LlmClient` (`ExplanationServiceIT`), and the no-key path (`UNAVAILABLE`) in every live scenario. Run [the check below](#verify-it-yourself) once with your key.

## Where Gemini sits

```text
ComparisonEngine ── verdict saved ──▶ ExplanationService.explain(input)
                                          │
                     PASS? ── yes ──▶ NOT_NEEDED (no call)
                                          │ no
                          KnowledgeRetriever.retrieve(query)       (see RAG_FLOW.md)
                                          │
              GeminiClient bean exists? ─ no ─▶ UNAVAILABLE + retrieved documentation
                                          │ yes
                  PromptBuilder.build(redacted input, chunks)
                                          │
                  GeminiClient.generate(SYSTEM_INSTRUCTION, prompt)
                                          │ exception? ─▶ FAILED + reason (report still complete)
                  CitationValidator.validate(answer, evidenceCodes, chunkIds)
                                          │
                               GENERATED explanation row
```

The verdict is already in the database before this step starts, and `ExplanationService` has no method that writes a verdict. Whatever Gemini says, it cannot change `PASS` into `REGRESSION` or the reverse.

## Enabling it

```bash
export GEMINI_API_KEY=your-key          # from Google AI Studio
export GEMINI_CHAT_MODEL=gemini-2.5-flash          # optional, default
export GEMINI_EMBEDDING_MODEL=gemini-embedding-001 # optional, default
```

`ExplanationConfiguration` creates the `GeminiClient` bean only when `stacktrial.gemini.api-key` is non-blank (`@ConditionalOnExpression`). Without it, nothing calls Gemini and nothing pretends to.

With a key, at startup `KnowledgeIngestionService` embeds all 22 knowledge chunks once, which switches retrieval from full-text to vector search.

## The HTTP calls (`explanation/GeminiClient.java`)

Base URL `https://generativelanguage.googleapis.com/v1beta`, Spring `RestClient` on the JDK HTTP client, connect/read timeout 60 s, API key sent in the `x-goog-api-key` header (never in the URL, never logged; error messages exclude the request).

**Explanation:** `POST /models/{chatModel}:generateContent`

```json
{
  "systemInstruction": {"parts": [{"text": "<rules: verdict is final, cite ids, no code …>"}]},
  "contents": [{"role": "user", "parts": [{"text": "<verdict, findings, evidence, knowledge>"}]}],
  "generationConfig": {"temperature": 0.1}
}
```
The text of `candidates[0].content.parts[*].text` is the answer; an empty answer raises `LlmException` (stored as `FAILED`).

**Embeddings:** `POST /models/{embeddingModel}:embedContent`

```json
{"model": "models/gemini-embedding-001", "content": {"parts": [{"text": "…"}]},
 "taskType": "RETRIEVAL_DOCUMENT" | "RETRIEVAL_QUERY", "outputDimensionality": 768}
```
768 matches the `vector(768)` column.

## The prompt (`explanation/PromptBuilder.java`)

System instruction, in short:
1. The VERDICT is final, computed by deterministic code. Never change or question it.
2. Use only the FINDINGS, EVIDENCE and KNOWLEDGE given. If the knowledge does not cover the cause, write exactly "No relevant source was found."
3. Every factual sentence cites at least one id in square brackets, e.g. `[EVIDENCE-003]` or `[KB-SB30-02]`. Only cite ids that appear below.
4. Do not write code or propose code changes.
5. If the evidence is not enough, say so.
Sections: `SUMMARY`, `WHY IT HAPPENED`, `RELEVANT DOCUMENTATION`, `GAPS`.

User prompt: the proposed change, `VERDICT (final, computed deterministically)`, each finding with its evidence codes, each evidence item (`[EVIDENCE-004] Trial test FAILED: … Status expected:<200> but was:<404>`), and each retrieved chunk (`[KB-SB30-03] Spring Boot 3.0 Migration Guide / Spring MVC and WebFlux: trailing slash matching disabled …`). Evidence is redacted with `LogRedactor` before it is added, and the whole prompt is redacted again.

## Reliability controls

| Risk | Control |
|---|---|
| AI changes the outcome | Verdict computed and stored before the AI step; prompt states it is final |
| Hallucinated sources | Only retrieved chunks are given; `CitationValidator` classifies each cited id as known evidence, known knowledge or **unknown**; unknown ids are shown as a warning on the report |
| No citations at all | Note "Warning: the explanation cites no evidence." |
| Gemini down / slow / quota | Timeouts; `FAILED` explanation with the reason; deterministic report unaffected |
| Secrets leaking to Gemini | Logs and evidence redacted before prompting |
| Non-determinism | Temperature 0.1; and the only non-deterministic part is the prose, never the verdict |

## Tested without the live API

`ExplanationServiceIT` replaces only the `LlmClient` with a stub that returns
`"SUMMARY\nThe trailing-slash test now returns 404 [EVIDENCE-002] as documented [KB-SB30-03]. [KB-FAKE-01]"`
and asserts: status `GENERATED`, `KB-SB30-03` was retrieved, cited evidence = `EVIDENCE-002`, cited knowledge = `KB-SB30-03`, unknown citations = `KB-FAKE-01`, the prompt contains "VERDICT (final, computed deterministically)", a secret in the evidence (`password=hunter2`) is not in the prompt, and both `GET /api/trials/{id}` and the report page show the explanation and the unknown citation.

## Verify it yourself

```bash
export GEMINI_API_KEY=your-key
cd backend && java -jar target/stacktrial-0.1.0-SNAPSHOT.jar
# startup log should say: "Embedded 22 knowledge chunks"
# then run scenario 1 (RUNBOOK.md) and check:
curl -s localhost:8080/api/trials/<id> | jq '.explanation | {status, model, retrievalMethod, citedEvidence, citedKnowledge, unknownCitations}'
# expected: status GENERATED, retrievalMethod VECTOR, citations present, unknownCitations empty
```

If `status` is `FAILED`, `note` contains the HTTP error (for example an invalid key or quota), and the rest of the report is unchanged.
