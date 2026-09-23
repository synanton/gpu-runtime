package org.synanton.gpu.domain.service;

import org.synanton.gpu.adapter.out.runtime.ModelCatalogService;
import org.synanton.gpu.adapter.out.runtime.OpenRouterModelManager;
import org.synanton.gpu.adapter.out.runtime.OpenRouterRuntime;
import org.synanton.gpu.adapter.out.runtime.VllmModelManager;
import org.synanton.gpu.adapter.out.runtime.VllmRuntime;
import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.*;
import org.synanton.gpu.domain.port.in.*;
import org.synanton.gpu.domain.port.out.ExecutionRepository;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.domain.port.out.ExecutionScheduler;
import org.synanton.gpu.domain.port.out.ModelManager;
import org.synanton.gpu.domain.port.out.ModelRepository;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;
import org.synanton.gpu.v1.Provider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Enhanced ExecuteService with provider routing support for GPU-7 (GPU-5 + GPU-7 external providers).
 *
 * Provider routing logic:
 * - LOCAL provider → VllmRuntime (GPU-5 local vLLM)
 * - OPENROUTER provider → OpenRouterRuntime (GPU-7 external provider)
 * - Tenant-specific model selection from ModelCatalogService
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExecuteService implements ExecuteUseCase {

    private final RequestCanonicalizer canonicalizer;
    private final IdempotencyService idempotencyService;
    private final ExecutionAdmissionService executionAdmissionService;
    private final ExecutionRepository executionRepository;
    private final ExecutionScheduler executionScheduler;
    private final VllmRuntime vllmRuntime;
    private final VllmModelManager vllmModelManager;
    private final OpenRouterRuntime openRouterRuntime;
    private final OpenRouterModelManager openRouterModelManager;
    private final ModelRepository modelRepository;
    private final HeartbeatManager heartbeatManager;
    private final ModelCatalogService modelCatalogService;

    @Override
    public Execution execute(ExecutionRequest request) {
        String requestHash = canonicalizer.canonicalize(request);

        // Fast path: idempotency hit — return existing execution without entering a transaction
        Optional<Execution> existing =
                idempotencyService.lookupExistingExecution(request.getRequestId(), requestHash);
        if (existing.isPresent()) {
            log.debug("Idempotency hit for request_id={}", request.getRequestId());
            return existing.get();
        }

        // Admission path: serialized per-model inside a PostgreSQL transaction
        Execution admitted = executionAdmissionService.admitAndPersist(request, requestHash);
        if (admitted.state().isTerminal()) {
            return admitted; // Race: another thread completed it
        }

        // GPU-7 execution path: route to appropriate runtime based on provider
        return routeToRuntime(request, admitted);
    }

    /**
     * Route execution to appropriate runtime based on provider (GPU-5 vs GPU-7).
     * This is the main provider routing implementation for Phase 1.
     */
    private Execution routeToRuntime(ExecutionRequest request, Execution admitted) {
        String executionId = admitted.executionId();
        Provider provider = request.getProvider();

        log.info("Routing execution_id={} to provider={}", executionId, provider);

        ExecutionRuntime executionRuntime;
        ModelManager modelManager;

        if (provider == Provider.OPENROUTER) {
            executionRuntime = openRouterRuntime;
            modelManager = openRouterModelManager;
            log.info("Routing to OPENROUTER provider for execution_id={}", executionId);
        } else {
            executionRuntime = vllmRuntime;
            modelManager = vllmModelManager;
            log.info("Routing to LOCAL provider (GPU-5) for execution_id={}", executionId);
        }

        return loadAndDispatchWithProvider(request, admitted, executionRuntime, modelManager);
    }

    /**
     * Load and dispatch execution with the selected runtime.
     * This is the refactored version of the original loadAndDispatch method.
     */
    private Execution loadAndDispatchWithProvider(
            ExecutionRequest request,
            Execution admitted,
            ExecutionRuntime executionRuntime,
            ModelManager providerModelManager) {

        String executionId = admitted.executionId();

        // ACCEPTED → QUEUED
        executionRepository.transitionState(executionId, ExecutionState.ACCEPTED, ExecutionState.QUEUED);

        // Ensure model is ready (may block; transitions to MODEL_LOADING if loading required)
        try {
            ModelStatus status = providerModelManager.getStatus(request.getModel());
            if (status != ModelStatus.READY) {
                executionRepository.transitionState(
                        executionId, ExecutionState.QUEUED, ExecutionState.MODEL_LOADING);
                log.info("Model loading: execution_id={} model={}", executionId, request.getModel());
                providerModelManager.ensureReady(request.getModel());
                // MODEL_LOADING → QUEUED: ready to be dispatched
                executionRepository.transitionState(
                        executionId, ExecutionState.MODEL_LOADING, ExecutionState.QUEUED);
            }
        } catch (ModelManager.ModelLoadException e) {
            log.error("Model load failed: model={} execution_id={}", request.getModel(), executionId, e);
            ExecutionError loadError = ExecutionError.nonRetryable(
                    "MODEL_LOAD_FAILED", e.getMessage());
            executionRepository.completeFailure(
                    executionId, ExecutionState.MODEL_LOADING, ExecutionState.FAILED, loadError);
            int cascaded = executionRepository.failAllQueuedForModel(
                    request.getModel(), loadError);
            log.warn("Cascaded model load failure to {} queued executions for model={}",
                    cascaded, request.getModel());
            return executionRepository.findByExecutionId(executionId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Execution not found after model load failure: " + executionId));
        }

        // QUEUED → RUNNING: schedule target and start heartbeat
        ModelCapabilities capabilities = modelRepository.getCapabilities(request.getModel())
                .orElseThrow(() -> new IllegalStateException(
                        "Model capabilities disappeared after admission: " + request.getModel()));
        RuntimeTarget target = executionScheduler.schedule(request, capabilities);
        executionRepository.transitionState(executionId, ExecutionState.QUEUED, ExecutionState.RUNNING);
        log.info("Dispatching execution_id={} target={} provider={}", executionId, target.endpointUrl(),
                request.getProvider());

        // Use provider-specific execution runtime
        HeartbeatManager.HeartbeatHandle heartbeat = heartbeatManager.start(executionId);
        try {
            ExecutionRuntime.RuntimeResult result = executionRuntime.execute(request, target);
            return recordResult(executionId, result);
        } finally {
            heartbeat.stop();
        }
    }

    /**
     * Helper method to resolve provider model ID using ModelCatalogService.
     * This provides tenant-specific model selection and mapping.
     */
    private String resolveProviderModelId(String tenantId, String logicalModelId, Provider provider, Operation operation) {
        return modelCatalogService.resolveProviderModelId(logicalModelId, operation);
    }

    private Execution recordResult(String executionId, ExecutionRuntime.RuntimeResult result) {
        return switch (result) {
            case ExecutionRuntime.RuntimeResult.Success success -> {
                executionRepository.completeSuccess(
                        executionId, success.usage(), success.result());
                log.info("Execution succeeded: execution_id={}", executionId);
                yield executionRepository.findByExecutionId(executionId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Execution not found after success: " + executionId));
            }
            case ExecutionRuntime.RuntimeResult.Failure failure -> {
                ExecutionError error = failure.error();
                executionRepository.completeFailure(
                        executionId, ExecutionState.RUNNING, ExecutionState.FAILED, error);
                log.warn("Execution failed: execution_id={} code={} retryable={} disposition={}",
                        executionId, error.code(), error.retryable(), failure.disposition());
                yield executionRepository.findByExecutionId(executionId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Execution not found after failure: " + executionId));
            }
        };
    }
}