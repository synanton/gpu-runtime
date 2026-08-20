-- Artifact cache: records model artifacts that have been successfully downloaded
-- and published to the shared model cache directory.
--
-- Advisory lock invariant:
--   All download operations must be preceded by
--   pg_advisory_lock(hashtext(model_id || ':' || digest)) to guarantee
--   exactly one download per (model_id, digest) pair across all Gateway instances.
--   The lock must be released (pg_advisory_unlock) after publication.

CREATE TABLE IF NOT EXISTS artifact_cache (
    model_id    TEXT        NOT NULL,
    digest      TEXT        NOT NULL,
    local_path  TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_artifact_cache PRIMARY KEY (model_id, digest)
);

CREATE INDEX IF NOT EXISTS idx_artifact_cache_model_id ON artifact_cache (model_id);
