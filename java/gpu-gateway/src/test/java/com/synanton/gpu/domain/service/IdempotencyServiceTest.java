package com.synanton.gpu.domain.service;

import com.synanton.gpu.domain.model.*;
import com.synanton.gpu.domain.port.out.ExecutionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private ExecutionRepository executionRepository;

    @InjectMocks
    private IdempotencyService idempotencyService;

    @Test
    void shouldReturnEmptyWhenNoExistingExecution() {
        when(executionRepository.findByRequestId("req-1")).thenReturn(Optional.empty());

        Optional<Execution> result = idempotencyService.lookupExistingExecution("req-1", "hash-abc");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnExistingExecutionWhenHashMatches() {
        Execution existing = buildExecution("exec-1", "req-1", "hash-abc", ExecutionState.SUCCEEDED);
        when(executionRepository.findByRequestId("req-1")).thenReturn(Optional.of(existing));

        Optional<Execution> result = idempotencyService.lookupExistingExecution("req-1", "hash-abc");

        assertThat(result).contains(existing);
    }

    @Test
    void shouldThrowWhenRequestIdReusedWithDifferentHash() {
        Execution existing = buildExecution("exec-1", "req-1", "hash-original", ExecutionState.SUCCEEDED);
        when(executionRepository.findByRequestId("req-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() ->
                idempotencyService.lookupExistingExecution("req-1", "hash-different"))
                .isInstanceOf(IdempotencyService.RequestIdReuseException.class)
                .hasMessageContaining("req-1");
    }

    private Execution buildExecution(String execId, String reqId, String hash, ExecutionState state) {
        return new Execution(execId, reqId, hash, "tenant", "model",
                state, "vllm", Instant.now(), Instant.now(), null, null, null, null, null);
    }
}
