CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE incident_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(16) NOT NULL,
    scenario_id VARCHAR(64),
    repo_name VARCHAR(128) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(64),
    attempts_used INT NOT NULL DEFAULT 0,
    rag_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    root_cause_summary TEXT,
    generated_diff TEXT,
    pr_url VARCHAR(255),
    total_prompt_tokens INT NOT NULL DEFAULT 0,
    total_completion_tokens INT NOT NULL DEFAULT 0,
    estimated_cost_usd NUMERIC(10, 6) NOT NULL DEFAULT 0,
    duration_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_incident_runs_created_at ON incident_runs (created_at DESC);

CREATE TABLE incident_attempts (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES incident_runs(id) ON DELETE CASCADE,
    attempt_no INT NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    diff TEXT,
    sandbox_exit_code INT,
    tests_run INT,
    tests_failed INT,
    sandbox_duration_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (run_id, attempt_no)
);

CREATE TABLE llm_calls (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES incident_runs(id) ON DELETE CASCADE,
    attempt_no INT,
    model VARCHAR(64) NOT NULL,
    prompt_tokens INT NOT NULL,
    completion_tokens INT NOT NULL,
    cost_usd NUMERIC(10, 6) NOT NULL,
    latency_ms BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_llm_calls_created_at ON llm_calls (created_at);

CREATE TABLE incident_events (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES incident_runs(id) ON DELETE CASCADE,
    event_seq INT NOT NULL,
    stage VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (run_id, event_seq)
);

CREATE TABLE incident_memory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(16) NOT NULL,
    origin_bug_id VARCHAR(64),
    error_signature TEXT NOT NULL,
    resolution_summary TEXT NOT NULL,
    embedding vector(768),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- HNSW works on empty/small tables (ivfflat needs representative data at build time).
CREATE INDEX idx_incident_memory_embedding
    ON incident_memory USING hnsw (embedding vector_cosine_ops);

CREATE TABLE benchmark_reports (
    id BIGSERIAL PRIMARY KEY,
    model VARCHAR(64) NOT NULL,
    config JSONB NOT NULL,
    results JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
