package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.ExecutionError;
import org.synanton.gpu.domain.model.RetryDisposition;
import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.v1.ExecutionRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the runtime that owns an already-admitted execution, for the cancel and
 * lazy-reconciliation paths (which see only the persisted {@code runtime_class}).
 *
 * <p>Invariant 1 (PR #15): an external execution is never cancelled or pinged via the
 * local vLLM runtime, and in external-only mode nothing ever resolves to vLLM — an
 * execution whose provider is gone resolves to {@link #UNROUTABLE}, which reports
 * {@code UNAVAILABLE} so reconciliation fails it closed. New executions are routed by
 * {@code ProviderRouter}, never by this class.
 */
@Component
public class RuntimeFactory {

    /** Binding of a runtime to the target it must be called with. */
    public record RuntimeBinding(ExecutionRuntime runtime, RuntimeTarget target) {}

    private final VllmRuntime vllmRuntime;
    private final ProviderRuntimeRegistry providerRuntimeRegistry;
    private final GpuGatewayProperties properties;

    public RuntimeFactory(VllmRuntime vllmRuntime,
                          ProviderRuntimeRegistry providerRuntimeRegistry,
                          GpuGatewayProperties properties) {
        this.vllmRuntime = vllmRuntime;
        this.providerRuntimeRegistry = providerRuntimeRegistry;
        this.properties = properties;
    }

    /**
     * @param runtimeClass persisted runtime class of the execution (provider id for
     *                     external executions, e.g. {@code MOCK}; {@code vllm}/{@code tei} locally)
     * @param localTarget  scheduler target, used only for local executions
     */
    public RuntimeBinding bindingFor(String runtimeClass, RuntimeTarget localTarget) {
        String providerId = runtimeClass == null ? "" : runtimeClass.toLowerCase(Locale.ROOT);
        Optional<ExecutionRuntime> external = providerRuntimeRegistry.get(providerId);
        if (external.isPresent()) {
            GpuGatewayProperties.ProviderConfig config = properties.getProviders().get(providerId);
            return new RuntimeBinding(external.get(),
                    new RuntimeTarget(config.getBaseUrl(), providerId, null));
        }
        if ("external".equals(properties.getDispatch().getStrategy())
                || properties.getProviders().containsKey(providerId)) {
            // external execution whose provider is disabled/removed, or external-only mode:
            // never fall back to local
            return new RuntimeBinding(UNROUTABLE, localTarget);
        }
        return new RuntimeBinding(vllmRuntime, localTarget);
    }

    /** Runtime that never reaches any backend: fail closed for unresolvable executions. */
    static final ExecutionRuntime UNROUTABLE = new ExecutionRuntime() {
        @Override
        public RuntimeResult execute(ExecutionRequest request, RuntimeTarget target) {
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("no_local_fallback", "no runtime for this execution"),
                    RetryDisposition.DEFINITELY_FAILED);
        }

        @Override
        public CancellationResult cancel(String executionId, RuntimeTarget target) {
            return new CancellationResult.NotFound();
        }

        @Override
        public RuntimeStatus ping(String executionId, RuntimeTarget target) {
            return RuntimeStatus.UNAVAILABLE;
        }
    };
}
