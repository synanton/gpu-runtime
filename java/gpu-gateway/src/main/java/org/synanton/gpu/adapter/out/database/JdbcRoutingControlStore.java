package org.synanton.gpu.adapter.out.database;

import org.synanton.gpu.domain.model.ControlOverride;
import org.synanton.gpu.domain.port.out.RoutingControlStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/** PostgreSQL routing-control store (V4__routing_control.sql). */
@Repository
@RequiredArgsConstructor
public class JdbcRoutingControlStore implements RoutingControlStore {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Map<String, ControlOverride> all() {
        Map<String, ControlOverride> out = new HashMap<>();
        jdbcTemplate.query("SELECT scope, enabled, updated_by, reason, updated_at FROM routing_control", rs -> {
            OffsetDateTime at = rs.getObject("updated_at", OffsetDateTime.class);
            out.put(rs.getString("scope"), new ControlOverride(rs.getString("scope"), rs.getBoolean("enabled"),
                    rs.getString("updated_by"), rs.getString("reason"), at == null ? null : at.toInstant()));
        });
        return out;
    }

    @Override
    @Transactional
    public void set(String scope, boolean enabled, String updatedBy, String reason) {
        jdbcTemplate.update("""
                INSERT INTO routing_control (scope, enabled, updated_by, reason, updated_at)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (scope) DO UPDATE
                   SET enabled = EXCLUDED.enabled, updated_by = EXCLUDED.updated_by,
                       reason = EXCLUDED.reason, updated_at = NOW()
                """, scope, enabled, updatedBy, reason);
        jdbcTemplate.update("INSERT INTO routing_control_audit (scope, enabled, updated_by, reason) VALUES (?, ?, ?, ?)",
                scope, enabled, updatedBy, reason);
    }
}
