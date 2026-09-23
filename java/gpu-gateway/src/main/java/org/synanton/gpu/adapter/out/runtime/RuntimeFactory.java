package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.v1.Provider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RuntimeFactory {

    private final VllmRuntime vllmRuntime;
    private final OpenRouterRuntime openRouterRuntime;
    private final StubExecutionRuntime stubExecutionRuntime;

    public ExecutionRuntime getRuntime(Provider provider) {
        if (provider == Provider.OPENROUTER) {
            return openRouterRuntime;
        }
        return vllmRuntime;
    }

    public ExecutionRuntime getDefaultRuntime() {
        return vllmRuntime;
    }
}