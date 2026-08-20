package com.synanton.gpu.domain.service;

import com.synanton.gpu.domain.model.Execution;
import com.synanton.gpu.domain.port.out.ExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Validates idempotency constraints for incoming Execute requests.
 *
 * <p>Invariant: same request_id + same hash → return existing execution (idempotent).
 * Invariant: same request_id + different hash → throw {@link RequestIdReuseException}.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyService {

    private final ExecutionRepository executionRepository;

    /**
     * Checks whether a prior execution exists for the given request_id.
     *
     * @param requestId    the caller-supplied idempotency key
     * @param requestHash  the canonical hash of the current request's immutable semantics
     * @return an existing execution if one was found with a matching hash
     * @throws RequestIdReuseException if request_id is reused with a different hash
     */
    public Optional<Execution> lookupExistingExecution(String requestId, String requestHash) {
        return executionRepository.findByRequestId(requestId)
                .map(existing -> {
                    if (!existing.requestHash().equals(requestHash)) {
                        throw new RequestIdReuseException(requestId, existing.requestHash(), requestHash);
                    }
                    return existing;
                });
    }

    /** Thrown when a caller reuses a request_id with a different payload hash. */
    @SuppressWarnings("serial")
    public static class RequestIdReuseException extends RuntimeException {
        private final String requestId;

        public RequestIdReuseException(String requestId, String existingHash, String newHash) {
            super("request_id '" + requestId + "' already used with hash " + existingHash
                    + "; cannot reuse with hash " + newHash);
            this.requestId = requestId;
        }

        public String getRequestId() {
            return requestId;
        }
    }
}
