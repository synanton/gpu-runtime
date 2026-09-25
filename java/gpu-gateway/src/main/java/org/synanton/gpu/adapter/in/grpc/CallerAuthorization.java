package org.synanton.gpu.adapter.in.grpc;

import org.synanton.gpu.config.GpuGatewayProperties;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

/**
 * Tenant authorization for the authenticated caller (Plan §13.2): {@code tenant_id} is
 * an assertion, trusted only if the mTLS principal may act for that tenant. Closes the
 * gap where any caller could spend (or evade) another tenant's budget by asserting a
 * different {@code tenant_id}. No-op in insecure-plaintext mode.
 */
@Component
public class CallerAuthorization {

    private final GpuGatewayProperties.Security security;

    public CallerAuthorization(GpuGatewayProperties properties) {
        this.security = properties.getSecurity();
    }

    /** Current principal's config, or UNAUTHENTICATED if unknown (mTLS mode only). */
    private GpuGatewayProperties.Security.CallerPrincipal principal() {
        String name = CallerPrincipalInterceptor.PRINCIPAL.get();
        GpuGatewayProperties.Security.CallerPrincipal p = name == null ? null : security.getPrincipals().get(name);
        if (p == null) {
            throw denial(Status.UNAUTHENTICATED, "unauthenticated",
                    "client principal is not registered in gpu-gateway.security.principals");
        }
        return p;
    }

    /** The caller must be a registered principal (for RPCs without a tenant). */
    public void requireAuthenticated() {
        if (security.isMtls()) {
            principal();
        }
    }

    /** The caller must be allowed to act for {@code tenantId}. */
    public void authorizeTenant(String tenantId) {
        if (security.isMtls() && !principal().mayActFor(tenantId)) {
            throw denial(Status.PERMISSION_DENIED, "tenant_not_allowed",
                    "principal may not act for tenant '" + tenantId + "'");
        }
    }

    /** Whether the caller may see a resource owned by {@code tenantId} (else: NOT_FOUND, no leak). */
    public boolean maySee(String tenantId) {
        return !security.isMtls() || principal().mayActFor(tenantId);
    }

    /** The caller must hold {@code role} (e.g. admin for routing control). */
    public void requireRole(String role) {
        if (security.isMtls() && !principal().getRoles().contains(role)) {
            throw denial(Status.PERMISSION_DENIED, "role_required", "principal lacks role '" + role + "'");
        }
    }

    static StatusRuntimeException denial(Status status, String code, String message) {
        return status.withDescription(code + ": " + message)
                .asRuntimeException(GpuExecutionGrpcAdapter.errorCodeTrailers(code));
    }
}
