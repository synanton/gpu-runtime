package com.synanton.gpu.adapter.out.registry;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** JDBC access to the {@code artifact_cache} table. */
@Repository
@RequiredArgsConstructor
class ArtifactCacheRepository {

    private final JdbcTemplate jdbcTemplate;

    /** Returns the local path if the artifact is already cached, otherwise empty. */
    Optional<String> findLocalPath(String modelId, String digest) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT local_path FROM artifact_cache WHERE model_id = ? AND digest = ?",
                String.class, modelId, digest);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Records a successfully published artifact. */
    void recordPublished(String modelId, String digest, String localPath) {
        jdbcTemplate.update(
                """
                INSERT INTO artifact_cache (model_id, digest, local_path)
                VALUES (?, ?, ?)
                ON CONFLICT (model_id, digest) DO UPDATE SET local_path = EXCLUDED.local_path
                """,
                modelId, digest, localPath);
    }
}
