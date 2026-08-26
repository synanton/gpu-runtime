package com.synanton.gpu.domain.service;

import org.synanton.gpu.v1.ExecutionRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a stable canonical hash over the immutable execution semantics of a request.
 *
 * <p>The hash covers: model, model_version, operation, execution_class, payload.
 * It deliberately excludes: request_id, tenant_id, trace_context.
 */
@Component
public class RequestCanonicalizer {

    public String canonicalize(ExecutionRequest request) {
        String canonicalForm = request.getModel()
                + "|" + request.getModelVersion()
                + "|" + request.getOperation().getNumber()
                + "|" + request.getExecutionClass()
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
