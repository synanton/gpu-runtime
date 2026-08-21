package com.synanton.gpu.adapter.out.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synanton.gpu.domain.model.Execution;
import com.synanton.gpu.domain.model.ExecutionError;
import com.synanton.gpu.domain.model.ExecutionState;
import com.synanton.gpu.domain.model.ExecutionUsage;
import com.synanton.gpu.domain.port.out.ExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link ExecutionRepository}.
 * Uses predicated UPDATEs and {@code ExecutionState.canTransitionTo} before every state write.
 */
@Repository
@RequiredArgsConstructor
public class JdbcExecutionRepository implements ExecutionRepository {

    private static final String ACTIVE_STATES_PREDICATE =
            "state NOT IN ('SUCCEEDED','FAILED','CANCELLED')";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Duration leaseTimeout;

    private final RowMapper<Execution> executionRowMapper = this::mapExecution;

    @Override
    public void save(Execution execution) {
        jdbcTemplate.update(
                """
                INSERT INTO executions (
                    execution_id, request_id, request_hash, tenant_id, model_id,
                    state, runtime_class, created_at, updated_at, expires_at, leased_until,
                    usage, error, result
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                """,
                execution.executionId(),
                execution.requestId(),
                execution.requestHash(),
                execution.tenantId(),
                execution.modelId(),
                execution.state().name(),
                execution.runtimeClass(),
                OffsetDateTime.ofInstant(execution.createdAt(), java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(execution.updatedAt(), java.time.ZoneOffset.UTC),
                toOffset(execution.expiresAt()),
                toOffset(execution.leasedUntil()),
                toJson(execution.usage()),
                toJson(execution.error()),
                execution.result()
        );
    }

    @Override
    public Optional<Execution> findByExecutionId(String executionId) {
        return jdbcTemplate.query(
                "SELECT * FROM executions WHERE execution_id = ?",
                executionRowMapper,
                executionId
        ).stream().findFirst();
    }

    @Override
    public Optional<Execution> findByRequestId(String requestId) {
        return jdbcTemplate.query(
                "SELECT * FROM executions WHERE request_id = ?",
                executionRowMapper,
                requestId
        ).stream().findFirst();
    }

    @Override
    public boolean transitionState(String executionId, ExecutionState expectedCurrentState, ExecutionState nextState) {
        guardTransition(expectedCurrentState, nextState);
        int updated = jdbcTemplate.update(
                """
                UPDATE executions
                   SET state = ?, updated_at = NOW()
                 WHERE execution_id = ? AND state = ?
                """,
                nextState.name(),
                executionId,
                expectedCurrentState.name()
        );
        return updated == 1;
    }

    @Override
    public boolean completeSuccess(String executionId, ExecutionUsage usage, byte[] result) {
        guardTransition(ExecutionState.RUNNING, ExecutionState.SUCCEEDED);
        int updated = jdbcTemplate.update(
                """
                UPDATE executions
                   SET state = ?, usage = CAST(? AS jsonb), result = ?, updated_at = NOW()
                 WHERE execution_id = ? AND state = ?
                """,
                ExecutionState.SUCCEEDED.name(),
                toJson(usage),
                result,
                executionId,
                ExecutionState.RUNNING.name()
        );
        return updated == 1;
    }

    @Override
    public boolean completeFailure(String executionId, ExecutionState expectedCurrentState,
                                   ExecutionState terminalState, ExecutionError error) {
        if (!terminalState.isTerminal()) {
            throw new IllegalArgumentException("completeFailure requires a terminal state, got " + terminalState);
        }
        guardTransition(expectedCurrentState, terminalState);
        int updated = jdbcTemplate.update(
                """
                UPDATE executions
                   SET state = ?, error = CAST(? AS jsonb), updated_at = NOW()
                 WHERE execution_id = ? AND state = ?
                """,
                terminalState.name(),
                toJson(error),
                executionId,
                expectedCurrentState.name()
        );
        return updated == 1;
    }

    @Override
    public void refreshLease(String executionId) {
        jdbcTemplate.update(
                """
                UPDATE executions
                   SET leased_until = NOW() + INTERVAL '%d seconds', updated_at = NOW()
                 WHERE execution_id = ? AND state = ?
                """.formatted(leaseTimeout.toSeconds()),
                executionId,
                ExecutionState.RUNNING.name()
        );
    }

    @Override
    public int countActiveExecutions(String modelId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM executions WHERE model_id = ? AND " + ACTIVE_STATES_PREDICATE,
                Integer.class,
                modelId
        );
        return count != null ? count : 0;
    }

    @Override
    public void acquireModelAdmissionLock(String modelId) {
        jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", modelId);
    }

    @Override
    public void acquireAdvisoryLock(long lockKey) {
        jdbcTemplate.queryForList("SELECT pg_advisory_lock(?)", lockKey);
    }

    @Override
    public void releaseAdvisoryLock(long lockKey) {
        jdbcTemplate.queryForList("SELECT pg_advisory_unlock(?)", lockKey);
    }

    @Override
    public int failAllQueuedForModel(String modelId, ExecutionError error) {
        return jdbcTemplate.update(
                """
                UPDATE executions
                   SET state = ?, error = CAST(? AS jsonb), updated_at = NOW()
                 WHERE model_id = ? AND state IN ('QUEUED', 'MODEL_LOADING')
                """,
                ExecutionState.FAILED.name(),
                toJson(error),
                modelId
        );
    }

    private static void guardTransition(ExecutionState current, ExecutionState next) {
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException("Invalid state transition: " + current + " → " + next);
        }
    }

    private Execution mapExecution(ResultSet resultSet, int rowNum) throws SQLException {
        return new Execution(
                resultSet.getString("execution_id"),
                resultSet.getString("request_id"),
                resultSet.getString("request_hash"),
                resultSet.getString("tenant_id"),
                resultSet.getString("model_id"),
                ExecutionState.valueOf(resultSet.getString("state")),
                resultSet.getString("runtime_class"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                instant(resultSet, "expires_at"),
                instant(resultSet, "leased_until"),
                fromJson(resultSet.getString("usage"), ExecutionUsage.class),
                fromJson(resultSet.getString("error"), ExecutionError.class),
                resultSet.getBytes("result")
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSONB value", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize JSONB value", e);
        }
    }
}
