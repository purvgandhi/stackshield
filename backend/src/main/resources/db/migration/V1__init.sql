-- StackTrial schema v1
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE trial (
    id                   UUID PRIMARY KEY,
    repo_url             TEXT        NOT NULL,
    baseline_ref         TEXT        NOT NULL,
    baseline_commit      VARCHAR(64),
    change_type          VARCHAR(32) NOT NULL,
    change_spec          JSONB       NOT NULL,
    change_summary       TEXT,
    applied_diff         TEXT,
    baseline_java        INTEGER,
    trial_java           INTEGER,
    baseline_boot        VARCHAR(64),
    trial_boot           VARCHAR(64),
    status               VARCHAR(16) NOT NULL,
    current_step         TEXT,
    verdict              VARCHAR(16),
    verdict_reason       TEXT,
    failure_reason       TEXT,
    created_at           TIMESTAMPTZ NOT NULL,
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_trial_created_at ON trial (created_at DESC);
CREATE INDEX idx_trial_status ON trial (status);

-- One row per side (BASELINE / TRIAL) of a trial.
CREATE TABLE trial_run (
    id                   UUID PRIMARY KEY,
    trial_id             UUID        NOT NULL REFERENCES trial (id) ON DELETE CASCADE,
    side                 VARCHAR(8)  NOT NULL,
    image                TEXT        NOT NULL,
    command              TEXT        NOT NULL,
    outcome              VARCHAR(16) NOT NULL,
    exit_code            INTEGER     NOT NULL,
    timed_out            BOOLEAN     NOT NULL,
    phase_reached        VARCHAR(32) NOT NULL,
    duration_ms          BIGINT      NOT NULL,
    tests_total          INTEGER     NOT NULL,
    tests_passed         INTEGER     NOT NULL,
    tests_failed         INTEGER     NOT NULL,
    tests_errored        INTEGER     NOT NULL,
    tests_skipped        INTEGER     NOT NULL,
    compile_error_count  INTEGER     NOT NULL,
    dependency_count     INTEGER     NOT NULL,
    failure_summary      TEXT,
    build_log_key        TEXT,
    dependency_tree_key  TEXT,
    created_at           TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_trial_run_side UNIQUE (trial_id, side)
);

CREATE TABLE test_result (
    id                   UUID PRIMARY KEY,
    run_id               UUID        NOT NULL REFERENCES trial_run (id) ON DELETE CASCADE,
    class_name           TEXT        NOT NULL,
    test_name            TEXT        NOT NULL,
    outcome              VARCHAR(8)  NOT NULL,
    failure_type         TEXT,
    failure_message      TEXT,
    stack_trace          TEXT,
    duration_ms          BIGINT      NOT NULL
);
CREATE INDEX idx_test_result_run ON test_result (run_id);

-- Facts with stable per-trial codes (EVIDENCE-001 ...). Cited by findings and by the AI explanation.
CREATE TABLE evidence (
    id                   UUID PRIMARY KEY,
    trial_id             UUID        NOT NULL REFERENCES trial (id) ON DELETE CASCADE,
    code                 VARCHAR(16) NOT NULL,
    kind                 VARCHAR(32) NOT NULL,
    side                 VARCHAR(8),
    title                TEXT        NOT NULL,
    detail               TEXT,
    CONSTRAINT uq_evidence_code UNIQUE (trial_id, code)
);

CREATE TABLE finding (
    id                   UUID PRIMARY KEY,
    trial_id             UUID        NOT NULL REFERENCES trial (id) ON DELETE CASCADE,
    position             INTEGER     NOT NULL,
    type                 VARCHAR(32) NOT NULL,
    severity             VARCHAR(16) NOT NULL,
    title                TEXT        NOT NULL,
    description          TEXT,
    baseline_evidence    JSONB       NOT NULL,
    trial_evidence       JSONB       NOT NULL
);
CREATE INDEX idx_finding_trial ON finding (trial_id, position);

CREATE TABLE explanation (
    id                   UUID PRIMARY KEY,
    trial_id             UUID        NOT NULL UNIQUE REFERENCES trial (id) ON DELETE CASCADE,
    status               VARCHAR(16) NOT NULL,
    model                TEXT,
    retrieval_method     VARCHAR(16),
    content              TEXT,
    status_reason        TEXT,
    retrieved_chunks     JSONB       NOT NULL,
    cited_evidence       JSONB       NOT NULL,
    cited_knowledge      JSONB       NOT NULL,
    unknown_citations    JSONB       NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL
);

-- Curated knowledge for RAG. Embeddings are optional: without a Gemini key, full-text search is used.
CREATE TABLE knowledge_chunk (
    id                   VARCHAR(64) PRIMARY KEY,
    source_title         TEXT        NOT NULL,
    source_url           TEXT,
    title                TEXT        NOT NULL,
    content              TEXT        NOT NULL,
    content_hash         VARCHAR(64) NOT NULL,
    embedding            vector(768),
    search_vector        tsvector GENERATED ALWAYS AS (to_tsvector('english', title || ' ' || content)) STORED,
    updated_at           TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_knowledge_search ON knowledge_chunk USING GIN (search_vector);
CREATE INDEX idx_knowledge_embedding ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
