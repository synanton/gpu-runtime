package com.synanton.gpu.adapter.out.model;

import com.synanton.gpu.domain.model.ModelStatus;
import com.synanton.gpu.domain.port.out.ModelManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Model manager for stub dispatch: configured models are treated as already loaded.
 * Avoids artifact download and vLLM load waits in local and integration tests.
 */
@Component
@ConditionalOnProperty(name = "gpu-gateway.dispatch.strategy", havingValue = "stub", matchIfMissing = true)
public class ReadyModelManager implements ModelManager {

    @Override
    public ModelStatus getStatus(String modelId) {
        return ModelStatus.READY;
    }

    @Override
    public void ensureReady(String modelId) {
        // Models are considered ready without contacting a GPU runtime.
    }
}
