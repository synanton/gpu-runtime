-- GPU-7 provider request-ID preservation (Deployment Plan §21, §35). The external
-- provider's request ID for support correlation; NULL for local (GPU-5) executions.
ALTER TABLE executions ADD COLUMN IF NOT EXISTS upstream_request_id TEXT;
