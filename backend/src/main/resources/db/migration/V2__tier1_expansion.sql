-- StackTrial schema v2: Tier 1 Platform Expansion

CREATE TABLE imported_repository (
    id                   UUID PRIMARY KEY,
    name                 VARCHAR(255) NOT NULL,
    repo_url             TEXT         NOT NULL,
    default_ref          VARCHAR(255) NOT NULL,
    local_path           TEXT         NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    status_message       TEXT,
    scan_result          JSONB,
    architecture_result  JSONB,
    doctor_result        JSONB,
    security_result      JSONB,
    readiness_result     JSONB,
    impact_result        JSONB,
    interview_result     JSONB,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_imported_repo_created ON imported_repository (created_at DESC);
CREATE INDEX idx_imported_repo_url ON imported_repository (repo_url);

-- Code chunks for Repository AI Q&A
CREATE TABLE repository_chunk (
    id                   UUID PRIMARY KEY,
    repository_id        UUID         NOT NULL REFERENCES imported_repository (id) ON DELETE CASCADE,
    file_path            TEXT         NOT NULL,
    chunk_index          INTEGER      NOT NULL,
    symbol_name          TEXT,
    content              TEXT         NOT NULL,
    embedding            vector(768),
    search_vector        tsvector GENERATED ALWAYS AS (to_tsvector('english', file_path || ' ' || coalesce(symbol_name, '') || ' ' || content)) STORED,
    created_at           TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_repo_chunk_repo ON repository_chunk (repository_id);
CREATE INDEX idx_repo_chunk_search ON repository_chunk USING GIN (search_vector);
CREATE INDEX idx_repo_chunk_embedding ON repository_chunk USING hnsw (embedding vector_cosine_ops);

-- Standalone Sandbox Runs (Run Project)
CREATE TABLE project_run (
    id                   UUID PRIMARY KEY,
    repository_id        UUID         NOT NULL REFERENCES imported_repository (id) ON DELETE CASCADE,
    command              TEXT         NOT NULL,
    image                TEXT         NOT NULL,
    outcome              VARCHAR(16)  NOT NULL,
    exit_code            INTEGER      NOT NULL,
    duration_ms          BIGINT       NOT NULL,
    console_log          TEXT,
    created_at           TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_project_run_repo ON project_run (repository_id, created_at DESC);
