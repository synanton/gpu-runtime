package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Invariant 1 on the cancel/reconcile paths: external executions never touch vLLM. */
class RuntimeFactoryTest {

    private final VllmRuntime vllm = mock(VllmRuntime.class);
    private final RuntimeTarget localTarget = new RuntimeTarget("http://envoy:8080", "vllm");

    private RuntimeFactory factory(String strategy, boolean mockEnabled) {
        GpuGatewayProperties p = new GpuGatewayProperties();
        p.getDispatch().setStrategy(strategy);
        GpuGatewayProperties.ProviderConfig mockProvider = new GpuGatewayProperties.ProviderConfig();
        mockProvider.setBaseUrl("http://mock:8080/v1");
        mockProvider.setApiKey("k");
        mockProvider.setEnabled(mockEnabled);
        p.getProviders().put("mock", mockProvider);
        return new RuntimeFactory(vllm, new ProviderRuntimeRegistry(p, Duration.ofSeconds(1), new ObjectMapper()), p);
    }

    @Test
    void externalExecutionBindsToItsProviderAtTheProviderEndpoint() {
        RuntimeFactory.RuntimeBinding b = factory("external", true).bindingFor("MOCK", localTarget);

        assertThat(b.runtime()).isInstanceOf(OpenAiProviderRuntime.class);
        assertThat(b.target().endpointUrl()).isEqualTo("http://mock:8080/v1");
    }

    @Test
    void externalModeNeverResolvesToLocalVllm() {
        RuntimeFactory.RuntimeBinding b = factory("external", true).bindingFor("vllm", localTarget);

        assertThat(b.runtime()).isNotSameAs(vllm).isSameAs(RuntimeFactory.UNROUTABLE);
        assertThat(b.runtime().ping("e1", b.target())).isEqualTo(ExecutionRuntime.RuntimeStatus.UNAVAILABLE);
    }

    @Test
    void disabledProviderExecutionDoesNotFallBackToLocalEvenInLocalMode() {
        RuntimeFactory.RuntimeBinding b = factory("direct", false).bindingFor("MOCK", localTarget);

        assertThat(b.runtime()).isSameAs(RuntimeFactory.UNROUTABLE);
    }

    @Test
    void localExecutionInLocalModeUsesVllm() {
        RuntimeFactory.RuntimeBinding b = factory("direct", true).bindingFor("vllm", localTarget);

        assertThat(b.runtime()).isSameAs(vllm);
        assertThat(b.target()).isEqualTo(localTarget);
    }
}
