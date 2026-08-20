package com.synanton.gpu.domain.service;

import com.synanton.gpu.domain.model.*;
import com.synanton.gpu.domain.port.in.ExecuteUseCase;
import com.synanton.gpu.domain.port.out.*;
import com.synanton.gpu.v1.ExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Core use case: admit, persist, dispatch, and record a GPU execution.
 *
 * <p>State progression: ACCEPTED → QUEUED → MODEL_LOADING (if needed) → RUNNING → terminal.
 *
 * <p>Admission invariant: advisory lock + concurrency check + INSERT are inside a single
 * PostgreSQL transaction, preventing any two concurrent calls from consuming the same slot.
 *
 * <p>Heartbeat invariant: a lease refresh fires every {@code heartbeatInterval} while the
 * execution is RUNNING. GetStatusService uses the expired lease to detect crashed Gateways.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExecuteService implements ExecuteUseCase {

    private final RequestCanonicalizer canonicalizer;
    private final IdempotencyService idempotencyService;
    private final AdmissionService admissionService;
    private final ExecutionRepository executionRepository;
    private final ExecutionScheduler executionScheduler;
    private final ExecutionRuntime executionRuntime;
    private final ModelRepository modelRepository;
    private final ModelManager modelManager;
    private final HeartbeatManager heartbeatManager;

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
        Execution admitted = admitAndPersist(request, requestHash);
        if (admitted.state().isTerminal()) {
            return admitted; // Race: another thread completed it
        }

        // GPU-3 execution path: model load → schedule → dispatch with heartbeat
        return loadAndDispatch(request, admitted);
    }

    /**
     * Runs the full admission sequence inside a single transaction:
     * advisory lock → idempotency re-check → field validation → concurrency check → INSERT.
     */
    @Transactional
    protected Execution admitAndPersist(ExecutionRequest request, String requestHash) {
        executionRepository.acquireModelAdmissionLock(request.getModelId());

        // Re-check inside the lock — another thread may have raced ahead
        Optional<Execution> raceExecution =
                idempotencyService.lookupExistingExecution(request.getRequestId(), requestHash);
        if (raceExecution.isPresent()) {
            log.debug("Idempotency hit inside admission lock for request_id={}", request.getRequestId());
            return raceExecution.get();
        }

        ModelCapabilities capabilities = admissionService.admit(request);

        String executionId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Execution execution = new Execution(
                executionId,
                request.getRequestId(),
                requestHash,
                request.getTenantId(),
                request.getModelId(),
                ExecutionState.ACCEPTED,
                capabilities.runtimeClass(),
                now, now, null, null, null, null, null
        );
        executionRepository.save(execution);
        log.info("Admitted execution_id={} model={} request_id={}",
                executionId, request.getModelId(), request.getRequestId());
        return execution;
    }

    /**
     * Drives the execution through ACCEPTED → QUEUED → MODEL_LOADING → RUNNING → terminal.
     * The runtime call is never transactional. Heartbeat fires during RUNNING.
     */
    private Execution loadAndDispatch(ExecutionRequest request, Execution admitted) {
        String executionId = admitted.executionId();

        // ACCEPTED → QUEUED
        executionRepository.transitionState(executionId, ExecutionState.ACCEPTED, ExecutionState.QUEUED);

        // Ensure model is ready (may block; transitions to MODEL_LOADING if loading required)
        try {
            ModelStatus status = modelManager.getStatus(request.getModelId());
            if (status != ModelStatus.READY) {
                executionRepository.transitionState(
                        executionId, ExecutionState.QUEUED, ExecutionState.MODEL_LOADING);
                log.info("Model loading: execution_id={} model={}", executionId, request.getModelId());
                modelManager.ensureReady(request.getModelId());
                // MODEL_LOADING → QUEUED: ready to be dispatched
                executionRepository.transitionState(
                        executionId, ExecutionState.MODEL_LOADING, ExecutionState.QUEUED);
            }
        } catch (ModelManager.ModelLoadException e) {
            log.error("Model load failed: model={} execution_id={}", request.getModelId(), executionId, e);
            ExecutionError loadError = ExecutionError.nonRetryable(
                    "MODEL_LOAD_FAILED", e.getMessage());
            executionRepository.completeFailure(
                    executionId, ExecutionState.MODEL_LOADING, ExecutionState.FAILED, loadError);
            int cascaded = executionRepository.failAllQueuedForModel(
                    request.getModelId(), loadError);
            log.warn("Cascaded model load failure to {} queued executions for model={}",
                    cascaded, request.getModelId());
            return executionRepository.findByExecutionId(executionId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Execution not found after model load failure: " + executionId));
        }

        // QUEUED → RUNNING: schedule target and start heartbeat
        ModelCapabilities capabilities = modelRepository.getCapabilities(request.getModelId())
                .orElseThrow(() -> new IllegalStateException(
                        "Model capabilities disappeared after admission: " + request.getModelId()));
        RuntimeTarget target = executionScheduler.schedule(request, capabilities);
        executionRepository.transitionState(executionId, ExecutionState.QUEUED, ExecutionState.RUNNING);
        log.info("Dispatching execution_id={} target={}", executionId, target.endpointUrl());

        HeartbeatManager.HeartbeatHandle heartbeat = heartbeatManager.start(executionId);
        try {
            ExecutionRuntime.RuntimeResult result = executionRuntime.execute(request, target);
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
