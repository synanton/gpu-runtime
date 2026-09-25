package org.synanton.gpu.domain.port.out;

import java.util.Optional;

/** Outbound port: Responses API response-ID → execution mapping (V5__responses.sql). */
public interface ResponseStore {

    record StoredResponseRef(String responseId, String executionId, String tenantId, boolean deleted) {}

    void create(String responseId, String executionId, String tenantId);

    Optional<StoredResponseRef> find(String responseId);

    /** Marks deleted and purges the stored response object; true if it existed and was live. */
    boolean delete(String responseId);
}
