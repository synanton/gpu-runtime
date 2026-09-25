-- GPU-7 cost ledger (Deployment Plan §36, T-K8S-48). One row per successful external
-- execution; the source of truth for per-tenant daily budget enforcement (T-K8S-48b).
-- cost_usd is NULL when authoritative provider usage was unavailable — never zero.

CREATE TABLE IF NOT EXISTS cost_ledger (
    execution_id     TEXT           NOT NULL,
    request_id       TEXT           NOT NULL,
    tenant_id        TEXT           NOT NULL,
    provider_id      TEXT           NOT NULL,
    logical_model_id TEXT           NOT NULL,
    input_tokens     BIGINT         NOT NULL DEFAULT 0,
    output_tokens    BIGINT         NOT NULL DEFAULT 0,
    cost_usd         NUMERIC(18, 9),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_cost_ledger PRIMARY KEY (execution_id)
);

-- Budget query: sum per tenant for the current UTC day
CREATE INDEX IF NOT EXISTS idx_cost_ledger_tenant_created
    ON cost_ledger (tenant_id, created_at);
