package org.synanton.gpu.integration;

import org.synanton.gpu.adapter.in.grpc.GpuExecutionGrpcAdapter;
import org.synanton.gpu.config.GrpcServerLifecycle;
import org.synanton.gpu.v1.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan §13 / T-K8S-7/8: mTLS on the gRPC transport and tenant authorization of the
 * caller principal (client-certificate CN). Certificates come from the operator script
 * deployments/external/scripts/gen-certs.sh (so the script is exercised too).
 *
 * <p>alice may act for tenant-a, bob for tenant-b; "stranger" has a valid CA-signed
 * certificate but is not a registered principal; "rogue" is signed by a foreign CA.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "gpu-gateway.security.mode=mtls",
        "gpu-gateway.security.principals.alice.tenants[0]=tenant-a",
        "gpu-gateway.security.principals.bob.tenants[0]=tenant-b",
        "spring.flyway.enabled=true"
})
@Testcontainers
class MtlsAuthorizationTest {

    private static final Path PKI;
    private static final Path FOREIGN_PKI;

    static {
        try {
            PKI = Files.createTempDirectory("gpu-mtls");
            FOREIGN_PKI = Files.createTempDirectory("gpu-mtls-foreign");
            genCerts(PKI, "alice", "bob", "stranger");
            genCerts(FOREIGN_PKI, "rogue");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void genCerts(Path dir, String... clients) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                new File("../../deployments/external/scripts/gen-certs.sh").getCanonicalPath(), dir.toString()));
        cmd.addAll(List.of(clients));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("gen-certs.sh failed: " + out);
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("gpu-gateway.security.tls.cert-chain", () -> PKI.resolve("server.crt").toString());
        r.add("gpu-gateway.security.tls.private-key", () -> PKI.resolve("server.key").toString());
        r.add("gpu-gateway.security.tls.client-ca", () -> PKI.resolve("ca.crt").toString());
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired GrpcServerLifecycle server;
    @Autowired org.synanton.gpu.domain.port.out.ExecutionRepository executions;
    private final List<ManagedChannel> channels = new ArrayList<>();

    @AfterEach
    void close() {
        channels.forEach(ManagedChannel::shutdownNow);
    }

    private GPUExecutionServiceGrpc.GPUExecutionServiceBlockingStub client(Path pki, String cn) throws Exception {
        SslContextBuilder ssl = GrpcSslContexts.forClient().trustManager(PKI.resolve("ca.crt").toFile());
        if (cn != null) {
            ssl.keyManager(pki.resolve(cn + ".crt").toFile(), pki.resolve(cn + ".key").toFile());
        }
        ManagedChannel ch = NettyChannelBuilder.forAddress("localhost", server.getBoundPort())
                .sslContext(ssl.build()).build();
        channels.add(ch);
        return GPUExecutionServiceGrpc.newBlockingStub(ch).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private static ExecutionRequest request(String tenant) {
        return ExecutionRequest.newBuilder().setRequestId("mtls-" + UUID.randomUUID()).setTenantId(tenant)
                .setModel("test-model").setModelVersion("1").setOperation(Operation.SYNTHESIZE)
                .setPayload(ByteString.copyFromUtf8("{}")).build();
    }

    private static String code(StatusRuntimeException e) {
        return e.getTrailers() == null ? null : e.getTrailers().get(GpuExecutionGrpcAdapter.ERROR_CODE_KEY);
    }

    @Test
    void callWithoutClientCertificateIsRejected() throws Exception {
        var anonymous = client(PKI, null);
        assertThatThrownBy(() -> anonymous.execute(request("tenant-a")))
                .isInstanceOf(StatusRuntimeException.class); // TLS handshake refuses the connection
    }

    @Test
    void certificateFromForeignCaIsRejected() throws Exception {
        var rogue = client(FOREIGN_PKI, "rogue");
        assertThatThrownBy(() -> rogue.execute(request("tenant-a")))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
    }

    @Test
    void unregisteredPrincipalIsUnauthenticated() throws Exception {
        var stranger = client(PKI, "stranger");
        assertThatThrownBy(() -> stranger.execute(request("tenant-a")))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(code(e)).isEqualTo("unauthenticated");
                });
    }

    @Test
    void principalCannotAssertAnotherTenant() throws Exception {
        // the budget-spoofing gap: alice claiming tenant-b must be refused
        var alice = client(PKI, "alice");
        assertThatThrownBy(() -> alice.execute(request("tenant-b")))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(code(e)).isEqualTo("tenant_not_allowed");
                });
    }

    @Test
    void principalMayActForItsOwnTenant() throws Exception {
        var alice = client(PKI, "alice");
        // authorization passes and the request reaches admission: unknown model → NOT_FOUND
        assertThatThrownBy(() -> alice.execute(request("tenant-a").toBuilder().setModel("no-such-model").build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
                    assertThat(code(e)).isEqualTo("model_not_found");
                });
    }

    @Test
    void anotherTenantsExecutionIsInvisible() throws Exception {
        String id = UUID.randomUUID().toString();
        executions.save(new org.synanton.gpu.domain.model.Execution(id, "req-" + id, "h", "tenant-a", "test-model",
                org.synanton.gpu.domain.model.ExecutionState.ACCEPTED, "vllm-test",
                java.time.Instant.now(), java.time.Instant.now(), null, null, null, null, null));
        var alice = client(PKI, "alice");
        var bob = client(PKI, "bob");
        var status = GetStatusRequest.newBuilder().setExecutionId(id).build();

        assertThat(alice.getStatus(status).getExecutionId()).isEqualTo(id);
        assertThatThrownBy(() -> bob.getStatus(status))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
        assertThat(bob.cancel(CancelRequest.newBuilder().setExecutionId(id).build()).getOutcome())
                .isEqualTo(CancellationOutcome.NOT_APPLICABLE);
        assertThat(alice.getStatus(status).getState()).as("bob's cancel had no effect")
                .isNotEqualTo(org.synanton.gpu.v1.ExecutionState.CANCELLED);
    }

    @Test
    void modelDiscoveryRequiresAnAuthorizedPrincipal() throws Exception {
        var alice = client(PKI, "alice");
        alice.getModels(GetModelsRequest.newBuilder().setOperation(Operation.SYNTHESIZE).build());
        assertThatThrownBy(() -> alice.getModels(GetModelsRequest.newBuilder()
                .setOperation(Operation.SYNTHESIZE).setTenantId("tenant-b").build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> assertThat(code(e)).isEqualTo("tenant_not_allowed"));
    }
}
