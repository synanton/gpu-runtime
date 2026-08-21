package com.synanton.gpu.adapter.out.registry;

import com.synanton.gpu.domain.port.out.ArtifactResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Active when strategy=stub; SecureRegistryFetcher handles the vllm path. */
@Component
@ConditionalOnProperty(name = "gpu-gateway.dispatch.strategy", havingValue = "stub", matchIfMissing = true)
public class NoOpArtifactResolver implements ArtifactResolver {

    @Override
    public String resolve(String modelId, String digest) {
        throw new UnsupportedOperationException(
                "Artifact resolution not implemented in GPU-2. Implement in GPU-3 (VllmRuntime).");
    }
}
