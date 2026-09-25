package org.synanton.gpu.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.synanton.gpu.domain.model.Execution;
import org.synanton.gpu.domain.port.out.ExecutionRepository;
import org.synanton.gpu.domain.port.out.ResponseStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Responses API (Deployment Plan §4.6): Gateway-assigned response IDs and storage.
 *
 * <p>The Gateway, not the provider, owns response IDs ({@code resp_<execution-id>}): the
 * provider's own ID is replaced in the response object and in every stream event, so a
 * provider identifier never reaches the client, and GetResponse/DeleteResponse work even
 * for providers that don't store responses (e.g. OpenRouter). Storage is the execution's
 * result; {@link ResponseStore} maps IDs and records deletion.
 */
@Service
@RequiredArgsConstructor
public class ResponsesService {

    private final ResponseStore store;
    private final ExecutionRepository executions;
    private final ObjectMapper objectMapper;

    public record ResponseView(String responseId, Execution execution) {}

    public static String responseIdFor(String executionId) {
        return "resp_" + executionId.replace("-", "");
    }

    /** Replace the provider's response ID in a response object or stream event. */
    public byte[] stamp(byte[] json, String responseId) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!(node instanceof ObjectNode object)) {
                return json;
            }
            if ("response".equals(object.path("object").asText()) && object.has("id")) {
                object.put("id", responseId);           // a response object
            }
            if (object.get("response") instanceof ObjectNode r && r.has("id")) {
                r.put("id", responseId);                // a stream event carrying the response
            }
            if (object.has("response_id")) {
                object.put("response_id", responseId);
            }
            return objectMapper.writeValueAsBytes(object);
        } catch (Exception e) {
            return json;
        }
    }

    /** SSE frame variant of {@link #stamp(byte[], String)}. */
    public byte[] stampFrame(byte[] frame, String responseId) {
        String text = new String(frame, StandardCharsets.UTF_8).strip();
        if (!text.startsWith("data:") || text.endsWith("[DONE]")) {
            return frame;
        }
        byte[] data = text.substring(5).strip().getBytes(StandardCharsets.UTF_8);
        return ("data: " + new String(stamp(data, responseId), StandardCharsets.UTF_8) + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    public void record(String responseId, Execution execution) {
        store.create(responseId, execution.executionId(), execution.tenantId());
    }

    public Optional<String> tenantOf(String responseId) {
        return store.find(responseId).map(ResponseStore.StoredResponseRef::tenantId);
    }

    public Optional<ResponseView> get(String responseId) {
        return store.find(responseId)
                .filter(ref -> !ref.deleted())
                .flatMap(ref -> executions.findByExecutionId(ref.executionId()))
                .map(e -> new ResponseView(responseId, e));
    }

    public boolean delete(String responseId) {
        return store.delete(responseId);
    }
}
