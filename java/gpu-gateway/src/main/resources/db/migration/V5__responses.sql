-- GPU-7 Responses API storage (Deployment Plan §4.6). The response object itself is the
-- execution's result; this table maps the Gateway-assigned response ID to it.
-- DeleteResponse sets deleted_at and purges the stored object (executions.result).
CREATE TABLE IF NOT EXISTS responses (
    response_id   TEXT        NOT NULL,
    execution_id  TEXT        NOT NULL,
    tenant_id     TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ,

    CONSTRAINT pk_responses PRIMARY KEY (response_id)
);

CREATE INDEX IF NOT EXISTS idx_responses_execution ON responses (execution_id);
