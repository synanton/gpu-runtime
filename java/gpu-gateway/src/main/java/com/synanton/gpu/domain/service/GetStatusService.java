package com.synanton.gpu.domain.service;

import com.synanton.gpu.domain.model.Execution;
import com.synanton.gpu.domain.model.ExecutionError;
import com.synanton.gpu.domain.model.ExecutionState;
import com.synanton.gpu.domain.port.in.GetStatusUseCase;
import com.synanton.gpu.domain.port.out.ExecutionRepository;
import com.synanton.gpu.domain.port.out.ExecutionRuntime;
import com.synanton.gpu.domain.port.out.ExecutionScheduler;
import com.synanton.gpu.domain.port.out.ModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Authoritative status retrieval with lazy lease-based reconciliation.
 *
 * <p>No background controller exists. When a RUNNING execution has an expired lease,
 * this service pings the runtime and reconciles accordingly. The predicated UPDATE
 * prevents stale reconciliation from overwriting a terminal state written by another thread.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GetStatusService implements GetStatusUseCase {

    private final ExecutionRepository executionRepository;
    private final ExecutionRuntime executionRuntime;
    private final ExecutionScheduler executionScheduler;
    private final ModelRepository modelRepository;

    @Override
    public Optional<Execution> getStatus(String executionId) {
        Optional<Execution> found = executionRepository.findByExecutionId(executionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        Execution execution = found.get();
        if (execution.state().isTerminal()) {
            return found;
        }

        if (execution.state() == ExecutionState.RUNNING && execution.isLeaseExpired()) {
            reconcileExpiredLease(execution);
            return executionRepository.findByExecutionId(executionId);
        }

        return found;
    }

    /**
     * Pings the runtime. If alive, refreshes the lease. If dead or not found,
     * transitions to FAILED — but only if the row is still RUNNING (predicated UPDATE).
     */
    private void reconcileExpiredLease(Execution execution) {
        log.info("Lease expired for execution_id={}; pinging runtime", execution.executionId());

        if (execution.runtimeClass() == null) {
            markFailed(execution, "Runtime class unknown; cannot reconcile");
            return;
        }

        modelRepository.getCapabilities(execution.modelId()).ifPresentOrElse(
                capabilities -> {
                    var target = executionScheduler.schedule(null, capabilities);
                    var runtimeStatus = executionRuntime.ping(execution.executionId(), target);

                    switch (runtimeStatus) {
                        case ALIVE -> {
                            log.debug("Runtime alive for execution_id={}; refreshing lease", execution.executionId());
                            executionRepository.refreshLease(execution.executionId());
                        }
                        case NOT_FOUND, UNAVAILABLE -> {
                            log.warn("Runtime {} for execution_id={}; transitioning to FAILED",
                                    runtimeStatus, execution.executionId());
                            markFailed(execution, "Runtime " + runtimeStatus.name().toLowerCase()
                                    + " after lease expiry");
                        }
                    }
                },
                () -> markFailed(execution, "Model capabilities not found during reconciliation")
        );
    }

    private void markFailed(Execution execution, String reason) {
        ExecutionError error = ExecutionError.nonRetryable("RUNTIME_UNAVAILABLE", reason);
        // Predicated on RUNNING to prevent overwriting a terminal state written concurrently
        boolean updated = executionRepository.completeFailure(
                execution.executionId(), ExecutionState.RUNNING, ExecutionState.FAILED, error);
        log.info("Reconciliation FAILED applied={} execution_id={}", updated, execution.executionId());
    }
}
