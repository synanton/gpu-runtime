package org.synanton.gpu.domain.service;

import org.synanton.gpu.adapter.out.runtime.ProviderRuntimeRegistry;
import org.synanton.gpu.adapter.out.runtime.VllmModelManager;
import org.synanton.gpu.adapter.out.runtime.VllmRuntime;
import org.synanton.gpu.domain.model.*;
import org.synanton.gpu.domain.port.in.*;
import org.synanton.gpu.domain.port.out.ExecutionRepository;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.domain.port.out.ExecutionScheduler;
import org.synanton.gpu.domain.port.out.ModelManager;
import org.synanton.gpu.domain.port.out.ModelRepository;
import org.synanton.gpu.v1.ExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * ExecuteService with authoritative routing (PR #15 review P0.3/P0.4/P1.1, §5).
 *
 * <p>Every request passes through {@link ProviderRouter} before admission:
 * <ul>
 *   <li>{@code local} decision → {@link VllmRuntime} (GPU-5), with model loading
 *       via {@link VllmModelManager}.</li>
 *   <li>{@code external} decision → the provider runtime from
 *       {@link ProviderRuntimeRegistry}; the provider owns model lifecycle, so no
 *       MODEL_LOADING phase exists on this path. The runtime applies the
 *       logical → provider model-ID rewrite (P1.1).</li>
 * </ul>
 *
 * <p>{@link RoutingDeniedException} propagates to the gRPC adapter before admission:
 * a denied request is never persisted and never dispatched (no local fallback).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExecuteService implements ExecuteUseCase {

    private final RequestCanonicalizer canonicalizer;
    private final AdmissionService admissionService;
    private final IdempotencyService idempotencyService;
    private final ExecutionAdmissionService executionAdmissionService;
    private final ExecutionRepository executionRepository;
    private final ExecutionScheduler executionScheduler;
    private final VllmRuntime vllmRuntime;
    private final VllmModelManager vllmModelManager;
    private final ModelRepository modelRepository;
    private final HeartbeatManager heartbeatManager;
    private final ProviderRouter providerRouter;
    private final ProviderRuntimeRegistry providerRuntimeRegistry;

    @Override
    public Execution execute(ExecutionRequest request) {
        admissionService.validateFields(request); // §21: invalid_request before anything else
        String requestHash = canonicalizer.canonicalize(request);

        // Fast path: idempotency hit — return existing execution without entering a transaction
        Optional<Execution> existing =
                idempotencyService.lookupExistingExecution(request.getRequestId(), requestHash);
        if (existing.isPresent()) {
            log.debug("Idempotency hit for request_id={}", request.getRequestId());
            return existing.get();
        }

        // Routing is decided BEFORE admission: denied requests are never persisted.
        // Throws RoutingDeniedException on any fail-closed rule (kill switch,
        // unknown model/provider, disabled provider, local fallback attempt).
        RoutingDecision decision = providerRouter.route(request);
        log.info("Routing decision: {}", decision);
        // Resolve the external runtime before admission too, so a missing runtime is a
        // denial — never an admitted execution left dangling in ACCEPTED.
        ExecutionRuntime externalRuntime = decision.isLocal() ? null
                : providerRuntimeRegistry.get(decision.providerId())
                        .orElseThrow(() -> new RoutingDeniedException("provider_unavailable",
                                "provider '" + decision.providerId() + "' has no runtime"));

        // Admission path: serialized per-model inside a PostgreSQL transaction
        Execution admitted = executionAdmissionService.admitAndPersist(request, requestHash);
        if (admitted.state().isTerminal()) {
            return admitted; // Race: another thread completed it
        }

        return decision.isLocal()
                ? loadAndDispatchLocal(request, admitted)
                : dispatchExternal(request, admitted, decision, externalRuntime);
    }

    /** Local (GPU-5) path: model load phase + vLLM dispatch. */
    private Execution loadAndDispatchLocal(ExecutionRequest request, Execution admitted) {
        String executionId = admitted.executionId();

        // ACCEPTED → QUEUED
        executionRepository.transitionState(executionId, ExecutionState.ACCEPTED, ExecutionState.QUEUED);

        // Ensure model is ready (may block; transitions to MODEL_LOADING if loading required)
        try {
            ModelStatus status = vllmModelManager.getStatus(request.getModel());
            if (status != ModelStatus.READY) {
                executionRepository.transitionState(
                        executionId, ExecutionState.QUEUED, ExecutionState.MODEL_LOADING);
                log.info("Model loading: execution_id={} model={}", executionId, request.getModel());
                vllmModelManager.ensureReady(request.getModel());
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
        log.info("Dispatching execution_id={} target={} mode=local", executionId, target.endpointUrl());

        HeartbeatManager.HeartbeatHandle heartbeat = heartbeatManager.start(executionId);
        try {
            ExecutionRuntime.RuntimeResult result = vllmRuntime.execute(request, target);
            return recordResult(executionId, result);
        } finally {
            heartbeat.stop();
        }
    }

    /**
     * External (GPU-7) path: dispatch to the provider from the routing decision.
     * The provider owns model lifecycle — there is no MODEL_LOADING phase here.
     * The runtime rewrites the payload's model to the provider model ID (P1.1)
     * and restores the logical ID on every downstream body, including SSE chunks (P1.2).
     */
    private Execution dispatchExternal(ExecutionRequest request, Execution admitted,
                                       RoutingDecision decision, ExecutionRuntime runtime) {
        String executionId = admitted.executionId();

        RuntimeTarget target = providerRuntimeRegistry.targetFor(decision);

        executionRepository.transitionState(executionId, ExecutionState.ACCEPTED, ExecutionState.QUEUED);
        executionRepository.transitionState(executionId, ExecutionState.QUEUED, ExecutionState.RUNNING);
        log.info("Dispatching execution_id={} provider={} mode=external",
                executionId, decision.providerId());

        HeartbeatManager.HeartbeatHandle heartbeat = heartbeatManager.start(executionId);
        try {
            ExecutionRuntime.RuntimeResult result = runtime.execute(request, target);
            return recordResult(executionId, result);
        } finally {
            heartbeat.stop();
        }
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
