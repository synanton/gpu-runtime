package org.synanton.gpu.adapter.out.database;

import org.synanton.gpu.domain.port.out.ResponseStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** PostgreSQL Responses API store (V5__responses.sql). */
@Repository
@RequiredArgsConstructor
public class JdbcResponseStore implements ResponseStore {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void create(String responseId, String executionId, String tenantId) {
        jdbcTemplate.update("INSERT INTO responses (response_id, execution_id, tenant_id) VALUES (?, ?, ?) "
                + "ON CONFLICT (response_id) DO NOTHING", responseId, executionId, tenantId);
    }

    @Override
    public Optional<StoredResponseRef> find(String responseId) {
        return jdbcTemplate.query(
                "SELECT response_id, execution_id, tenant_id, deleted_at FROM responses WHERE response_id = ?",
                (rs, n) -> new StoredResponseRef(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4) != null), responseId).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean delete(String responseId) {
        int updated = jdbcTemplate.update(
                "UPDATE responses SET deleted_at = NOW() WHERE response_id = ? AND deleted_at IS NULL", responseId);
        if (updated == 1) {
            // purge the stored response object (data minimisation): the execution row keeps
            // only its state/usage for accounting
            jdbcTemplate.update("UPDATE executions SET result = NULL WHERE execution_id = "
                    + "(SELECT execution_id FROM responses WHERE response_id = ?)", responseId);
        }
        return updated == 1;
    }
}
