-- GPU-7 persisted routing control state (Deployment Plan §33/§38, T-K8S-38).
-- Runtime overrides set through GPUControlService; configuration remains the floor
-- (a scope disabled in configuration can never be enabled here).
--   scope = 'external-routing' (kill switch) | 'provider:<id>'
CREATE TABLE IF NOT EXISTS routing_control (
    scope       TEXT        NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    updated_by  TEXT        NOT NULL,
    reason      TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_routing_control PRIMARY KEY (scope)
);

-- Append-only audit trail of every change.
CREATE TABLE IF NOT EXISTS routing_control_audit (
    id          BIGSERIAL   PRIMARY KEY,
    scope       TEXT        NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    updated_by  TEXT        NOT NULL,
    reason      TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
