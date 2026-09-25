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
    private final ExternalRoutingPolicy externalRoutingPolicy;

    /** The one step unary and streaming execution differ in. */
    @FunctionalInterface
    private interface Dispatch {
        ExecutionRuntime.RuntimeResult run(ExecutionRuntime runtime, RuntimeTarget target);
    }

    @Override
    public Execution execute(ExecutionRequest request) {
        return run(request, null);
    }

    @Override
    public Execution executeStream(ExecutionRequest request, StreamListener listener) {
        return run(request, java.util.Objects.requireNonNull(listener, "listener"));
    }

    private Execution run(ExecutionRequest request, StreamListener listener) {
        boolean streaming = listener != null;
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
        // GPU-7 controls (sensitivity, provider health, budget) — fail closed, before admission
        externalRoutingPolicy.check(decision, request);
        // Resolve the external runtime before admission too, so a missing runtime is a
        // denial — never an admitted execution left dangling in ACCEPTED.
        ExecutionRuntime runtime = decision.isLocal() ? vllmRuntime
                : providerRuntimeRegistry.get(decision.providerId())
                        .orElseThrow(() -> new RoutingDeniedException("provider_unavailable",
                                "provider '" + decision.providerId() + "' has no runtime"));
        if (streaming && (request.getOperation() != org.synanton.gpu.v1.Operation.SYNTHESIZE
                || !runtime.supportsStreaming())) {
            // §10.1: never silently fall back to unary
            throw new RoutingDeniedException("capability_not_supported",
                    "streaming is supported for SYNTHESIZE on streaming-capable runtimes only");
        }
        Dispatch dispatch = streaming
                ? (rt, target) -> rt.executeStreaming(request, target, listener)
                : (rt, target) -> rt.execute(request, target);

        // Admission path: serialized per-model inside a PostgreSQL transaction
        Execution admitted = executionAdmissionService.admitAndPersist(request, requestHash);
        if (admitted.state().isTerminal()) {
            return admitted; // Race: another thread completed it
        }
        if (streaming) {
            listener.onAdmitted(admitted.executionId());
        }

        if (decision.isLocal()) {
            return loadAndDispatchLocal(request, admitted, dispatch);
        }
        Execution done = dispatchExternal(request, admitted, decision, runtime, dispatch);
        externalRoutingPolicy.recordUsage(decision, request, done); // cost ledger (T-K8S-48)
        return done;
    }

    /** Local (GPU-5) path: model load phase + vLLM dispatch. */
    private Execution loadAndDispatchLocal(ExecutionRequest request, Execution admitted, Dispatch dispatch) {
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
            ExecutionRuntime.RuntimeResult result = dispatch.run(vllmRuntime, target);
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
                                       RoutingDecision decision, ExecutionRuntime runtime,
                                       Dispatch dispatch) {
        String executionId = admitted.executionId();

        RuntimeTarget target = providerRuntimeRegistry.targetFor(decision);

        executionRepository.transitionState(executionId, ExecutionState.ACCEPTED, ExecutionState.QUEUED);
        executionRepository.transitionState(executionId, ExecutionState.QUEUED, ExecutionState.RUNNING);
        log.info("Dispatching execution_id={} provider={} mode=external",
                executionId, decision.providerId());

        HeartbeatManager.HeartbeatHandle heartbeat = heartbeatManager.start(executionId);
        try {
            ExecutionRuntime.RuntimeResult result = dispatch.run(runtime, target);
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
