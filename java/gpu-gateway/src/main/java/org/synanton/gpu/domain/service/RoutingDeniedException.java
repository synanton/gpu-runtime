package org.synanton.gpu.domain.service;

/**
 * Fail-closed routing denial (PR #15 review P0.4/§5). Thrown by {@link ProviderRouter}
 * before admission — a denied request is never persisted, never dispatched, and never
 * falls back to another provider.
 *
 * <p>{@link #getCode()} uses the canonical §16-style codes
 * ({@code routing_disabled}, {@code capability_not_supported}, …) so the API layer
 * can map them onto the canonical error envelope without re-interpretation.
 */
@SuppressWarnings("serial")
public class RoutingDeniedException extends RuntimeException {

    private final String code;

    public RoutingDeniedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
