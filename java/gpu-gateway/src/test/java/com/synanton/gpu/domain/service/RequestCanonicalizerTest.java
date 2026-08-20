package com.synanton.gpu.domain.service;

import com.google.protobuf.ByteString;
import com.synanton.gpu.v1.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCanonicalizerTest {

    private final RequestCanonicalizer canonicalizer = new RequestCanonicalizer();

    @Test
    void shouldProduceSameHashForIdenticalRequests() {
        ExecutionRequest request1 = buildRequest("req-1", "tenant-a", "model-x", "payload-bytes");
        ExecutionRequest request2 = buildRequest("req-2", "tenant-b", "model-x", "payload-bytes");

        String hash1 = canonicalizer.canonicalize(request1);
        String hash2 = canonicalizer.canonicalize(request2);

        // Different request_id and tenant_id must NOT affect the hash
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void shouldProduceDifferentHashForDifferentModel() {
        ExecutionRequest request1 = buildRequest("req-1", "tenant-a", "model-x", "payload");
        ExecutionRequest request2 = buildRequest("req-1", "tenant-a", "model-y", "payload");

        assertThat(canonicalizer.canonicalize(request1))
                .isNotEqualTo(canonicalizer.canonicalize(request2));
    }

    @Test
    void shouldProduceDifferentHashForDifferentPayload() {
        ExecutionRequest request1 = buildRequest("req-1", "tenant-a", "model-x", "payload-A");
        ExecutionRequest request2 = buildRequest("req-1", "tenant-a", "model-x", "payload-B");

        assertThat(canonicalizer.canonicalize(request1))
                .isNotEqualTo(canonicalizer.canonicalize(request2));
    }

    @Test
    void shouldExcludeTraceContextFromHash() {
        ExecutionRequest withTrace = buildRequest("req-1", "tenant-a", "model-x", "payload")
                .toBuilder()
                .putTraceContext("traceparent", "00-abc123-def456-01")
                .build();
        ExecutionRequest withoutTrace = buildRequest("req-1", "tenant-a", "model-x", "payload");

        assertThat(canonicalizer.canonicalize(withTrace))
                .isEqualTo(canonicalizer.canonicalize(withoutTrace));
    }

    @Test
    void shouldProduceSha256HexString() {
        String hash = canonicalizer.canonicalize(buildRequest("req-1", "t1", "model", "data"));
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    private ExecutionRequest buildRequest(String requestId, String tenantId,
                                          String modelId, String payload) {
        return ExecutionRequest.newBuilder()
                .setRequestId(requestId)
                .setTenantId(tenantId)
                .setModelId(modelId)
                .setOptions(ExecutionOptions.newBuilder()
                        .setOperation(Operation.SYNTHESIZE)
                        .setMaxTokens(512)
                        .build())
                .setPayload(ByteString.copyFromUtf8(payload))
                .build();
    }
}
