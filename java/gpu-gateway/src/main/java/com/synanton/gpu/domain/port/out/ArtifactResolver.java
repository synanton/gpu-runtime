package com.synanton.gpu.domain.port.out;

/**
 * Outbound port for resolving and ensuring model artifacts are present in the shared cache.
 *
 * <p>GPU-3 concern: implementations use PostgreSQL advisory locking to guarantee exactly one
 * artifact download per (model_id, digest) pair, even under concurrent execution admission.
 * The stub implementation in GPU-2 throws {@link UnsupportedOperationException}.
 */
public interface ArtifactResolver {

    /**
     * Ensures the artifact for the given model and digest is available in the shared cache.
     * Blocks until the artifact is ready or throws if resolution fails.
     *
     * @param modelId the model identifier
     * @param digest  the expected content digest (e.g. sha256:...)
     * @return the local path to the resolved artifact directory
     */
    String resolve(String modelId, String digest);

    /** Describes a resolved artifact in the shared cache. */
    record Artifact(String modelId, String digest, String localPath) {}
}
