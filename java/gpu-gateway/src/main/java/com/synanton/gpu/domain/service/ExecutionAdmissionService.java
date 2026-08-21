package com.synanton.gpu.domain.service;

import com.synanton.gpu.domain.model.Execution;
import com.synanton.gpu.domain.model.ExecutionState;
import com.synanton.gpu.domain.model.ModelCapabilities;
import com.synanton.gpu.domain.port.out.ExecutionRepository;
import com.synanton.gpu.v1.ExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs the admission sequence inside a Spring-managed transaction so advisory lock,
 * concurrency COUNT, and INSERT share one PostgreSQL connection.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExecutionAdmissionService {

    private final IdempotencyService idempotencyService;
    private final AdmissionService admissionService;
    private final ExecutionRepository executionRepository;

    /**
     * Advisory lock → idempotency re-check → field validation → concurrency check → INSERT.
     */
    @Transactional
    public Execution admitAndPersist(ExecutionRequest request, String requestHash) {
        executionRepository.acquireModelAdmissionLock(request.getModelId());

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
}
