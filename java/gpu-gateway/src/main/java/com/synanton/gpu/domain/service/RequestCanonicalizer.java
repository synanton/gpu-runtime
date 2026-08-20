package com.synanton.gpu.domain.service;

import com.synanton.gpu.v1.ExecutionRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a stable canonical hash over the immutable execution semantics of a request.
 *
 * <p>The hash covers: model_id, operation, max_tokens, execution_class, payload.
 * It deliberately excludes: request_id, tenant_id, trace_context.
 * Two requests with the same hash represent the same GPU workload.
 *
 * <p>This hash is used by {@link IdempotencyService} to detect request_id reuse with
 * a different payload, which is a caller error.
 */
@Component
public class RequestCanonicalizer {

    public String canonicalize(ExecutionRequest request) {
        String canonicalForm = request.getModelId()
                + "|" + request.getOptions().getOperation().getNumber()
                + "|" + request.getOptions().getMaxTokens()
                + "|" + request.getOptions().getExecutionClass()
                + "|" + HexFormat.of().formatHex(request.getPayload().toByteArray());
        return sha256Hex(canonicalForm);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
