-- GPU Execution Plane — executions table.
-- PostgreSQL is the authoritative source of truth for execution state.
-- No Redis, Cassandra, or in-memory state may replicate or shadow this table.
--
-- Advisory lock invariant:
--   All admission operations (countActive + INSERT) must be preceded by
--   pg_advisory_xact_lock(hashtext(model_id)) within the same transaction.

CREATE TABLE IF NOT EXISTS executions (
    execution_id  TEXT        NOT NULL,
    request_id    TEXT        NOT NULL,
    request_hash  TEXT        NOT NULL,
    tenant_id     TEXT        NOT NULL,
    model_id      TEXT        NOT NULL,
    state         TEXT        NOT NULL,
    runtime_class TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ,
    leased_until  TIMESTAMPTZ,
    usage         JSONB,
    error         JSONB,
    result        BYTEA,

    CONSTRAINT pk_executions       PRIMARY KEY (execution_id),
    CONSTRAINT chk_execution_state CHECK (state IN (
        'ACCEPTED', 'QUEUED', 'MODEL_LOADING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'
    ))
);

-- Fast lookup by caller-supplied idempotency key
CREATE UNIQUE INDEX IF NOT EXISTS idx_executions_request_id
    ON executions (request_id);

-- Partial index for active executions per model — used by admission COUNT query
CREATE INDEX IF NOT EXISTS idx_executions_model_state_active
    ON executions (model_id, state)
    WHERE state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED');

-- Index for lease expiry scans (lazy reconciliation in GetStatus)
CREATE INDEX IF NOT EXISTS idx_executions_leased_until
    ON executions (leased_until)
    WHERE state = 'RUNNING';
