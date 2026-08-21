package com.synanton.gpu.adapter.out.schedule;

import com.synanton.gpu.domain.model.ModelCapabilities;
import com.synanton.gpu.domain.model.RuntimeTarget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DirectSchedulerTest {

    private final DirectScheduler scheduler = new DirectScheduler("http://vllm-service:8000");

    @Test
    void shouldReturnConfiguredEndpoint() {
        ModelCapabilities capabilities = new ModelCapabilities("model-a", 8, 4096, "vllm-a100");

        RuntimeTarget target = scheduler.schedule(null, capabilities);

        assertThat(target).isEqualTo(new RuntimeTarget("http://vllm-service:8000", "vllm-a100"));
    }

    @Test
    void shouldUseDefaultRuntimeClassWhenCapabilitiesNull() {
        RuntimeTarget target = scheduler.schedule(null, null);

        assertThat(target.endpointUrl()).isEqualTo("http://vllm-service:8000");
        assertThat(target.runtimeClass()).isEqualTo("vllm");
    }

    @Test
    void shouldNeverLeakKubernetesTopology() {
        ModelCapabilities capabilities = new ModelCapabilities("llama", 4, 8192, "vllm-h100");

        RuntimeTarget target = scheduler.schedule(null, capabilities);

        // The target must contain only the endpoint URL and runtime class — no pod names,
        // node IPs, GPU IDs, or any infrastructure topology
        assertThat(target.endpointUrl()).doesNotContain("pod-", "node-", "gpu-");
    }
}
