package com.synanton.gpu.domain.service;

import com.synanton.gpu.domain.port.out.ExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages per-execution lease heartbeats for RUNNING executions.
 *
 * <p>For each active Execute() call a heartbeat task fires every
 * {@code heartbeatInterval} seconds and refreshes {@code leased_until} in PostgreSQL.
 * The heartbeat must be stopped (via {@link HeartbeatHandle#stop()}) when the execution
 * reaches a terminal state, whether through success, failure, or cancellation.
 *
 * <p>Lazy reconciliation in {@link GetStatusService} will mark lease-expired RUNNING
 * executions as FAILED if the heartbeat was not stopped cleanly (e.g. Gateway crash).
 */
@Component
@Slf4j
public class HeartbeatManager {

    private final ScheduledExecutorService scheduler;
    private final ExecutionRepository executionRepository;
    private final Duration heartbeatInterval;

    public HeartbeatManager(ExecutionRepository executionRepository,
                             Duration heartbeatInterval) {
        this.executionRepository = executionRepository;
        this.heartbeatInterval = heartbeatInterval;
        this.scheduler = Executors.newScheduledThreadPool(
                Runtime.getRuntime().availableProcessors(),
                runnable -> {
                    Thread thread = new Thread(runnable, "heartbeat-worker");
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    /**
     * Starts a heartbeat for the given execution and returns a handle to stop it.
     * The first tick fires after {@code heartbeatInterval}; subsequent ticks are periodic.
     */
    public HeartbeatHandle start(String executionId) {
        long intervalSeconds = heartbeatInterval.toSeconds();
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> refreshLease(executionId),
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );
        log.debug("Heartbeat started for execution_id={} interval={}s", executionId, intervalSeconds);
        return new HeartbeatHandle(executionId, future);
    }

    private void refreshLease(String executionId) {
        try {
            executionRepository.refreshLease(executionId);
            log.debug("Lease refreshed for execution_id={}", executionId);
        } catch (Exception e) {
            log.warn("Failed to refresh lease for execution_id={}: {}", executionId, e.getMessage());
        }
    }

    /** Handle for stopping a running heartbeat. */
    public static final class HeartbeatHandle {
        private final String executionId;
        private final ScheduledFuture<?> future;

        private HeartbeatHandle(String executionId, ScheduledFuture<?> future) {
            this.executionId = executionId;
            this.future = future;
        }

        /** Cancels the heartbeat. Safe to call multiple times. */
        public void stop() {
            boolean cancelled = future.cancel(false);
            if (cancelled) {
                log.debug("Heartbeat stopped for execution_id={}", executionId);
            }
        }

        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(HeartbeatHandle.class);
    }
}
