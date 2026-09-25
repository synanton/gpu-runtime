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

import java.util.ArrayList;
import java.util.List;
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
    private final ResponsesService responsesService;

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
        List<RoutingDecision> candidates = providerRouter.routeWithFallbacks(request);
        RoutingDecision decision = candidates.get(0);
        log.info("Routing decision: {} (+{} fallback(s))", decision, candidates.size() - 1);
        boolean responses = request.getOperation() == org.synanton.gpu.v1.Operation.RESPOND;
        if (streaming && request.getOperation() != org.synanton.gpu.v1.Operation.SYNTHESIZE && !responses) {
            // §10.1: never silently fall back to unary
            throw new RoutingDeniedException("capability_not_supported",
                    "streaming is supported for SYNTHESIZE and RESPOND only");
        }
        if (responses && decision.isLocal()) {
            // §4.6: the Responses API is a GPU-7 (external provider) capability
            throw new RoutingDeniedException("capability_not_supported",
                    "the Responses API is not available on the local (GPU-5) path");
        }
        // GPU-7 controls (sensitivity, provider health, budget, runtime control) per candidate —
        // fail closed, before admission. Only provider_unavailable is skippable (T-K8S-52);
        // every other denial applies to all candidates and is final. The runtime is resolved
        // here too, so a missing runtime is a denial, never an execution dangling in ACCEPTED.
        List<Candidate> usable = new ArrayList<>();
        RoutingDeniedException skipped = null;
        for (RoutingDecision candidate : candidates) {
            ExecutionRuntime rt;
            try {
                externalRoutingPolicy.check(candidate, request);
                rt = candidate.isLocal() ? vllmRuntime
                        : providerRuntimeRegistry.get(candidate.providerId())
                                .orElseThrow(() -> new RoutingDeniedException("provider_unavailable",
                                        "provider '" + candidate.providerId() + "' has no runtime"));
            } catch (RoutingDeniedException e) {
                if (!"provider_unavailable".equals(e.getCode())) {
                    throw e;
                }
                skipped = skipped == null ? e : skipped;
                continue;
            }
            if (streaming && !rt.supportsStreaming()) {
                skipped = skipped == null ? new RoutingDeniedException("capability_not_supported",
                        "streaming is supported on streaming-capable runtimes only") : skipped;
                continue;
            }
            usable.add(new Candidate(candidate, rt));
        }
        if (usable.isEmpty()) {
            throw skipped;
        }
        ExecutionRuntime runtime = usable.get(0).runtime();
        // Admission path: serialized per-model inside a PostgreSQL transaction
        Execution admitted = executionAdmissionService.admitAndPersist(request, requestHash);
        if (admitted.state().isTerminal()) {
            return admitted; // Race: another thread completed it
        }
        if (streaming) {
            listener.onAdmitted(admitted.executionId());
        }
        // RESPOND: the Gateway owns the response ID; stamp it into every event and the result
        String responseId = responses ? ResponsesService.responseIdFor(admitted.executionId()) : null;
        ExecutionRuntime.StreamChunkSink sink = !streaming ? null : responses
                ? frame -> listener.onChunk(responsesService.stampFrame(frame, responseId))
                : listener;
        Dispatch base = streaming
                ? (rt, target) -> rt.executeStreaming(request, target, sink)
                : (rt, target) -> rt.execute(request, target);
        Dispatch dispatch = !responses ? base : (rt, target) -> {
            ExecutionRuntime.RuntimeResult r = base.run(rt, target);
            return r instanceof ExecutionRuntime.RuntimeResult.Success ok && ok.result() != null && ok.result().length > 0
                    ? new ExecutionRuntime.RuntimeResult.Success(ok.usage(),
                            responsesService.stamp(ok.result(), responseId), ok.upstreamRequestId())
                    : r;
        };

        if (decision.isLocal()) {
            return loadAndDispatchLocal(request, admitted, dispatch);
        }
        Execution done = dispatchExternal(request, admitted, usable, dispatch);
        if (responses && done.state() == ExecutionState.SUCCEEDED) {
            responsesService.record(responseId, done); // GetResponse / DeleteResponse
        }
        return done;
    }

    /** A routing candidate with its resolved runtime. */
    private record Candidate(RoutingDecision decision, ExecutionRuntime runtime) {}

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
    /**
     * External dispatch with T-K8S-52 failover: the next candidate is tried only when the
     * current provider did NOT accept the request ({@link RetryDisposition#NOT_ACCEPTED}:
     * connect failure, open circuit, 429, 502/503/504) — so a request is never executed
     * twice. For streams this also means no frame has been emitted yet. Never local.
     */
    private Execution dispatchExternal(ExecutionRequest request, Execution admitted,
                                       List<Candidate> candidates, Dispatch dispatch) {
        String executionId = admitted.executionId();
        executionRepository.transitionState(executionId, ExecutionState.ACCEPTED, ExecutionState.QUEUED);
        executionRepository.transitionState(executionId, ExecutionState.QUEUED, ExecutionState.RUNNING);

        HeartbeatManager.HeartbeatHandle heartbeat = heartbeatManager.start(executionId);
        try {
            ExecutionRuntime.RuntimeResult result = null;
            RoutingDecision served = null;
            for (int i = 0; i < candidates.size(); i++) {
                Candidate c = candidates.get(i);
                served = c.decision();
                log.info("Dispatching execution_id={} provider={} mode=external attempt={}/{}",
                        executionId, served.providerId(), i + 1, candidates.size());
                result = dispatch.run(c.runtime(), providerRuntimeRegistry.targetFor(served));
                boolean notAccepted = result instanceof ExecutionRuntime.RuntimeResult.Failure f
                        && f.disposition() == RetryDisposition.NOT_ACCEPTED;
                if (!notAccepted || i == candidates.size() - 1) {
                    break;
                }
                log.warn("Provider {} did not accept execution_id={} ({}); failing over to {}",
                        served.providerId(), executionId,
                        ((ExecutionRuntime.RuntimeResult.Failure) result).error().code(),
                        candidates.get(i + 1).decision().providerId());
            }
            Execution done = recordResult(executionId, result);
            externalRoutingPolicy.recordUsage(served, request, done); // cost ledger (T-K8S-48)
            return done;
        } finally {
            heartbeat.stop();
        }
    }

    private Execution recordResult(String executionId, ExecutionRuntime.RuntimeResult result) {
        return switch (result) {
            case ExecutionRuntime.RuntimeResult.Success success -> {
                executionRepository.completeSuccess(
                        executionId, success.usage(), success.result(), success.upstreamRequestId());
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
