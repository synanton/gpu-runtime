package org.synanton.gpu.adapter.in.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

/**
 * Extracts the caller principal from the verified mTLS client certificate (its CN) and
 * binds it to the gRPC {@link Context} (Plan §13.1). In mTLS mode a call without a
 * verifiable client certificate is rejected {@code UNAUTHENTICATED} before any handler
 * runs. In insecure-plaintext mode no principal is bound.
 */
public class CallerPrincipalInterceptor implements ServerInterceptor {

    /** The authenticated caller principal (certificate CN); null in insecure-plaintext mode. */
    public static final Context.Key<String> PRINCIPAL = Context.key("synanton-caller-principal");

    private final boolean mtls;

    public CallerPrincipalInterceptor(boolean mtls) {
        this.mtls = mtls;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        if (!mtls) {
            return next.startCall(call, headers);
        }
        String principal = principalOf(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION));
        if (principal == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("unauthenticated: client certificate required"),
                    GpuExecutionGrpcAdapter.errorCodeTrailers("unauthenticated"));
            return new ServerCall.Listener<>() { };
        }
        return Contexts.interceptCall(Context.current().withValue(PRINCIPAL, principal), call, headers, next);
    }

    static String principalOf(SSLSession session) {
        if (session == null) {
            return null;
        }
        try {
            Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate cert)) {
                return null;
            }
            for (Rdn rdn : new LdapName(cert.getSubjectX500Principal().getName()).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return rdn.getValue().toString();
                }
            }
            return null;
        } catch (SSLPeerUnverifiedException | javax.naming.InvalidNameException e) {
            return null;
        }
    }
}
