# Database

PostgreSQL 16 with the `vector` extension (pgvector 0.8), started by `docker compose up -d`. The schema is created by Flyway from a single migration: `backend/src/main/resources/db/migration/V1__init.sql`. Hibernate runs with `ddl-auto: validate`, so the app refuses to start if the entities and the schema disagree.

Verified on 2026-10-02 against an empty database: Flyway created `flyway_schema_history`, applied version `1 - init` (success = true), the `vector` extension is installed (0.8.7) and 22 knowledge chunks were loaded at startup. Output: [verification/](verification/).

## Entity relationships

```text
                       ┌──────────────────────┐
                       │ trial                │  1 row per trial: request, status, verdict
                       └──────────┬───────────┘
          ┌───────────────┬───────┴────────┬──────────────────┐
          │ 1..2          │ 0..n           │ 0..n             │ 0..1
┌─────────▼────────┐ ┌────▼─────┐ ┌────────▼───────┐ ┌────────▼────────┐
│ trial_run        │ │ evidence │ │ finding        │ │ explanation     │
│ BASELINE / TRIAL │ │ EVID-nnn │ │ cites evidence │ │ cites evidence  │
└─────────┬────────┘ └──────────┘ │ codes (JSONB)  │ │ + KB chunk ids  │
          │ 0..n                  └────────────────┘ └────────┬────────┘
┌─────────▼────────┐                                          │ ids (JSONB, no FK)
│ test_result      │                               ┌──────────▼──────────┐
└──────────────────┘                               │ knowledge_chunk     │ independent corpus
                                                   └─────────────────────┘
```

All child tables use `ON DELETE CASCADE` on `trial_id` / `run_id`. Evidence codes inside `finding` and `explanation` are JSONB arrays of codes rather than foreign keys: a finding points at "EVIDENCE-003", which is unique per trial (`uq_evidence_code (trial_id, code)`).

## Mapping to the names in the brief

| Asked for | Actual table / column | Notes |
|---|---|---|
| trial | `trial` | |
| run | `trial_run` | `run` is a common SQL word; `trial_run` is explicit. Unique on `(trial_id, side)` |
| test_result | `test_result` | |
| evidence_item | `evidence` | Java record is `comparison.EvidenceItem`; entity `trial.EvidenceRecord` |
| verdict | `trial.verdict` + `trial.verdict_reason`, plus the `finding` table | A verdict is one value per trial, so it is a column, not a table. Findings explain why |
| explanation | `explanation` | One per trial (`UNIQUE trial_id`) |
| knowledge_chunk | `knowledge_chunk` | |

No table exists only for appearance; each one is written by `TrialStore` or the explanation module and read by `TrialReportService`.

## Tables

### `trial`
The request and its lifecycle. Key columns: `repo_url`, `baseline_ref`, `baseline_commit` (resolved SHA), `change_type`, `change_spec` (JSONB of the request), `change_summary`, `applied_diff` (the real `git diff` of the trial workspace), `baseline_java` / `trial_java`, `baseline_boot` / `trial_boot`, `status`, `current_step`, `verdict`, `verdict_reason`, `failure_reason`, timestamps. Indexes on `created_at DESC` (dashboard) and `status` (recovery).

### `trial_run`
One row per side. `image`, `command`, `outcome` (`SUCCESS`, `FAILURE`, `TIMEOUT`), `exit_code`, `timed_out`, `phase_reached`, `duration_ms`, test counts, `compile_error_count`, `dependency_count`, `failure_summary`, and storage keys for the build log and dependency tree (files under `data/artifacts/`, not in the database).

Real rows from scenario 1:

```text
 side     | image                        | outcome | exit | phase  | passed | failed | deps
 BASELINE | maven:3.9-eclipse-temurin-17 | SUCCESS |    0 | VERIFY |      8 |      0 |   60
 TRIAL    | maven:3.9-eclipse-temurin-17 | FAILURE |    1 | TEST   |      6 |      2 |   62
```

### `test_result`
Every Surefire test case of a run: `class_name`, `test_name`, `outcome` (`PASSED`, `FAILED`, `ERROR`, `SKIPPED`), `failure_type`, `failure_message`, `stack_trace`, `duration_ms`.

### `evidence`
Facts recorded by the comparison engine, numbered per trial in a deterministic order: `code` (`EVIDENCE-001`), `kind` (`RUN_RESULT`, `TEST_PASS`, `TEST_FAILURE`, `COMPILE_ERROR`, `DEPENDENCY_CHANGE`, …), `side`, `title`, `detail`.

```text
 EVIDENCE-001 | RUN_RESULT        | BASELINE | Baseline run: SUCCESS
 EVIDENCE-002 | RUN_RESULT        | TRIAL    | Trial run: FAILURE
 EVIDENCE-003 | TEST_PASS         | BASELINE | Baseline test passed: …OrderControllerTest#listsOrdersWithTrailingSlash
 EVIDENCE-004 | TEST_FAILURE      | TRIAL    | Trial test FAILED: …OrderControllerTest#listsOrdersWithTrailingSlash
 EVIDENCE-007 | DEPENDENCY_CHANGE |          | Dependency change: ch.qos.logback:logback-classic 1.2.12 -> …
```

### `finding`
Ordered conclusions (`position`), each with `type`, `severity`, `title`, `description` and the evidence codes that support it on each side (`baseline_evidence`, `trial_evidence`, JSONB).

```text
 0 | BUILD_REGRESSION   | CRITICAL | Trial build fails (TEST)                 | ["EVIDENCE-001"] | ["EVIDENCE-002"]
 1 | TEST_REGRESSION    | HIGH     | Test regressed: …listsOrdersWithTrailing… | ["EVIDENCE-003"] | ["EVIDENCE-004"]
 2 | TEST_REGRESSION    | HIGH     | Test regressed: …returnsOneOrderWith…     | ["EVIDENCE-005"] | ["EVIDENCE-006"]
 3 | DEPENDENCY_CHANGED | INFO     | 49 resolved dependency change(s)          | []               | ["EVIDENCE-007", …]
```

### `explanation`
`status` (`GENERATED`, `NOT_NEEDED`, `UNAVAILABLE`, `FAILED`), `model`, `retrieval_method` (`VECTOR`, `FULL_TEXT`), `content` (Gemini's text), `status_reason`, and JSONB lists: `retrieved_chunks`, `cited_evidence`, `cited_knowledge`, `unknown_citations` (ids the model cited that it was never given).

### `knowledge_chunk`
The RAG corpus: `id` (`KB-SB30-03`), `source_title`, `source_url`, `title`, `content`, `content_hash` (re-embed only when content changes), `embedding vector(768)` with an HNSW cosine index, and `search_vector tsvector` generated from title + content with a GIN index for the full-text fallback.

## Useful queries

```bash
docker exec -it stacktrial-postgres psql -U stacktrial
```

```sql
SELECT id, change_summary, status, verdict FROM trial ORDER BY created_at DESC LIMIT 10;
SELECT side, outcome, phase_reached, tests_passed, tests_failed FROM trial_run WHERE trial_id = '<id>';
SELECT code, kind, title FROM evidence WHERE trial_id = '<id>' ORDER BY code;
SELECT version, description, success FROM flyway_schema_history;
SELECT extname, extversion FROM pg_extension;
```

## Changing the schema
Add `V2__something.sql`; never edit `V1__init.sql` after it has been applied (Flyway checksums it). Update the matching entity so `ddl-auto: validate` still passes.
