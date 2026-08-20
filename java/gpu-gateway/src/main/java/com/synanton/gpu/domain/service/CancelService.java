package com.synanton.gpu.domain.service;

import com.synanton.gpu.domain.model.Execution;
import com.synanton.gpu.domain.model.ExecutionError;
import com.synanton.gpu.domain.model.ExecutionState;
import com.synanton.gpu.domain.port.in.CancelUseCase;
import com.synanton.gpu.domain.port.out.ExecutionRepository;
import com.synanton.gpu.domain.port.out.ExecutionRuntime;
import com.synanton.gpu.domain.port.out.ExecutionScheduler;
import com.synanton.gpu.domain.port.out.ModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Best-effort cancellation of an in-flight GPU execution.
 * Does not imply rollback of any SynAnton Core business state.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CancelService implements CancelUseCase {

    private final ExecutionRepository executionRepository;
    private final ExecutionRuntime executionRuntime;
    private final ExecutionScheduler executionScheduler;
    private final ModelRepository modelRepository;

    @Override
    public Optional<Execution> cancel(String executionId) {
        Optional<Execution> found = executionRepository.findByExecutionId(executionId);
        if (found.isEmpty()) {
            log.debug("Cancel: execution_id={} not found", executionId);
            return Optional.empty();
        }

        Execution execution = found.get();
        if (execution.state().isTerminal()) {
            log.debug("Cancel: execution_id={} already in terminal state {}", executionId, execution.state());
            return found;
        }

        // Attempt runtime cancellation — best-effort, no transaction needed
        if (execution.state() == ExecutionState.RUNNING && execution.runtimeClass() != null) {
            modelRepository.getCapabilities(execution.modelId())
                    .map(caps -> executionScheduler.schedule(null, caps))
                    .ifPresent(target -> executionRuntime.cancel(executionId, target));
        }

        ExecutionError cancelError = ExecutionError.nonRetryable("EXECUTION_CANCELLED", "Cancelled by caller");
        boolean updated = executionRepository.completeFailure(
                executionId, execution.state(), ExecutionState.CANCELLED, cancelError);

        log.info("Cancel applied={} execution_id={}", updated, executionId);
        return executionRepository.findByExecutionId(executionId);
    }
}
