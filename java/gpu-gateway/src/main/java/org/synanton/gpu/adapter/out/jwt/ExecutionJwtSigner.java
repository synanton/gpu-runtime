package org.synanton.gpu.adapter.out.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

/**
 * Signs GPU-5 execution JWTs (Deployment Plan §12, T-K8S-6a), ES256 over P-256.
 *
 * <p>Header: {@code {"alg":"ES256","typ":"JWT","kid":<current kid>}}.
 *
 * <p>Claims:
 * <ul>
 *   <li>{@code iss}, {@code aud}: {@code synanton-gpu-gateway}, {@code gpu-plane-execution};</li>
 *   <li>{@code iat}, {@code nbf}, and {@code exp} = iat + ttl. Envoy checks these on arrival
 *       only, so a long generation is unaffected;</li>
 *   <li>{@code jti}: a random UUID per request;</li>
 *   <li>{@code sub}: the caller's mTLS principal, when known;</li>
 *   <li>{@code tenant_id}, {@code request_id}, {@code op}, {@code model} (logical ID);</li>
 *   <li>{@code body_sha256}: base64url(SHA-256 of the exact body bytes sent to Envoy; an
 *       empty body for GETs). Envoy enforcement is a T-K8S-6b follow-up (GPU-5 plan §13.3 J2).</li>
 * </ul>
 *
 * <p>JDK only: {@code SHA256withECDSAinP1363Format} yields the raw {@code r‖s} form JWS needs.
 */
public class ExecutionJwtSigner {

    private final ExecutionJwtKeys keys;
    private final String issuer;
    private final String audience;
    private final int ttlSeconds;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String encodedHeader;

    public ExecutionJwtSigner(ExecutionJwtKeys keys, String issuer, String audience, int ttlSeconds, Clock clock) {
        this.keys = keys;
        this.issuer = issuer;
        this.audience = audience;
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
        this.encodedHeader = b64(("{\"alg\":\"ES256\",\"typ\":\"JWT\",\"kid\":\"" + keys.currentKid() + "\"}")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** What the token is for; {@code principal} may be null (unknown / plaintext mode). */
    public record Subject(String operation, String model, String tenantId, String requestId, String principal) {}

    public String sign(Subject subject, byte[] body) {
        long now = clock.instant().getEpochSecond();
        ObjectNode c = mapper.createObjectNode();
        c.put("iss", issuer);
        c.put("aud", audience);
        c.put("iat", now);
        c.put("nbf", now);
        c.put("exp", now + ttlSeconds);
        c.put("jti", UUID.randomUUID().toString());
        if (subject.principal() != null && !subject.principal().isBlank()) {
            c.put("sub", subject.principal());
        }
        putIfPresent(c, "tenant_id", subject.tenantId());
        putIfPresent(c, "request_id", subject.requestId());
        putIfPresent(c, "op", subject.operation());
        putIfPresent(c, "model", subject.model());
        c.put("body_sha256", bodySha256(body));
        try {
            String signingInput = encodedHeader + "." + b64(mapper.writeValueAsBytes(c));
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(keys.signingKey());
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64(s.sign());
        } catch (GeneralSecurityException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("execution JWT signing failed", e);
        }
    }

    public static String bodySha256(byte[] body) {
        try {
            return b64(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public ExecutionJwtKeys keys() {
        return keys;
    }

    private static void putIfPresent(ObjectNode c, String name, String value) {
        if (value != null && !value.isEmpty()) {
            c.put(name, value);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
