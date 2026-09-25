package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.v1.Provider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Legacy convenience facade over {@link ProviderRuntimeRegistry} for the
 * cancel/reconcile paths, which only know the wire {@link Provider} enum.
 * New code must route through {@code ProviderRouter} + {@code ProviderRuntimeRegistry}
 * (PR #15 review §5) — never through a provider-enum special case.
 */
@Component
@RequiredArgsConstructor
public class RuntimeFactory {

    private final VllmRuntime vllmRuntime;
    private final ProviderRuntimeRegistry providerRuntimeRegistry;

    public ExecutionRuntime getRuntime(Provider provider) {
        if (provider == Provider.EXTERNAL_OPENAI) {
            return providerRuntimeRegistry.get("openai").orElse(vllmRuntime);
        }
        return vllmRuntime;
    }

    public ExecutionRuntime getDefaultRuntime() {
        return vllmRuntime;
    }
}
