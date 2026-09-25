package org.synanton.gpu.adapter.out.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.gpu.adapter.out.jwt.ExecutionJwtKeys;
import org.synanton.gpu.adapter.out.jwt.ExecutionJwtSigner;
import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.domain.model.ModelStatus;
import org.synanton.gpu.domain.service.HeartbeatManager;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * T-K8S-6a steps 4–5: every Gateway→Envoy call carries an execution JWT whose body_sha256 matches
 * the bytes received; static readiness skips the /v1/models poll; Envoy 401 → execution_jwt_rejected.
 */
class VllmRuntimeExecutionJwtTest {

    record Seen(String method, String path, String authorization, byte[] body) {}

    @TempDir Path keyDir;
    private HttpServer server;
    private String base;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private ExecutionJwtSigner signer;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        var cur = g.generateKeyPair();
        var prev = g.generateKeyPair();
        Files.writeString(keyDir.resolve("current.key"), pem("PRIVATE KEY", cur.getPrivate().getEncoded()));
        Files.writeString(keyDir.resolve("current.pub"), pem("PUBLIC KEY", cur.getPublic().getEncoded()));
        Files.writeString(keyDir.resolve("previous.pub"), pem("PUBLIC KEY", prev.getPublic().getEncoded()));
        signer = new ExecutionJwtSigner(ExecutionJwtKeys.load(keyDir), "synanton-gpu-gateway", "gpu-plane-execution",
                60, Clock.systemUTC());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestHeaders().getFirst("Authorization"), body));
            String path = ex.getRequestURI().getPath();
            byte[] out;
            if (status != 200) {
                out = "{\"error\":\"Jwt is missing\"}".getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/v1/chat/completions") && new String(body).contains("\"stream\":true")) {
                out = ("data: {\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                        + "data: {\"model\":\"m\",\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/v1/embeddings")) {
                out = "{\"object\":\"list\",\"model\":\"m\",\"data\":[{\"index\":0,\"embedding\":[0.1]}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}"
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                out = "{}".getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + label + "-----\n";
    }

    private VllmRuntime runtime(ExecutionJwtSigner s) {
        HeartbeatManager hb = mock(HeartbeatManager.class);
        org.mockito.Mockito.when(hb.start(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(mock(HeartbeatManager.HeartbeatHandle.class));
        return new VllmRuntime(json, hb, Duration.ofSeconds(10), s, "/healthz");
    }

    private static ExecutionRequest req(Operation op, String payload) {
        return ExecutionRequest.newBuilder().setRequestId("req-" + op).setTenantId("rb-fixed-g5")
                .setModel("synanton-bge-base-embedding").setOperation(op)
                .setPayload(ByteString.copyFromUtf8(payload)).build();
    }

    private JsonNode claims(String authorization) throws Exception {
        assertThat(authorization).startsWith("Bearer ");
        String[] parts = authorization.substring(7).split("\\.");
        return json.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private static String sha(byte[] b) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    void unaryCallCarriesAJwtWhoseBodyHashMatchesTheBytesReceived() throws Exception {
        var r = runtime(signer).execute(req(Operation.EMBED, "{\"model\":\"synanton-bge-base-embedding\",\"input\":[\"a\"]}"),
                new RuntimeTarget(base, "tei"));
        assertThat(r).isInstanceOf(ExecutionRuntime.RuntimeResult.Success.class);

        Seen s = seen.get(0);
        JsonNode c = claims(s.authorization());
        assertThat(c.get("body_sha256").asText()).isEqualTo(sha(s.body()));
        assertThat(c.get("op").asText()).isEqualTo("EMBED");
        assertThat(c.get("tenant_id").asText()).isEqualTo("rb-fixed-g5");
        assertThat(c.get("model").asText()).isEqualTo("synanton-bge-base-embedding");
    }

    @Test
    void streamHashesTheRewrittenBodyItActuallySends() throws Exception {
        List<byte[]> frames = new ArrayList<>();
        var r = runtime(signer).executeStreaming(req(Operation.SYNTHESIZE, "{\"model\":\"m\",\"messages\":[]}"),
                new RuntimeTarget(base, "vllm"), frames::add);
        assertThat(r).isInstanceOf(ExecutionRuntime.RuntimeResult.Success.class);

        Seen s = seen.get(0);
        assertThat(new String(s.body())).contains("\"stream\":true");  // rewritten payload
        assertThat(claims(s.authorization()).get("body_sha256").asText()).isEqualTo(sha(s.body()));
    }

    @Test
    void pingUsesTheConfiguredHealthPathAndIsSigned() throws Exception {
        var status = runtime(signer).ping("exec-1", new RuntimeTarget(base, "vllm"));
        assertThat(status).isEqualTo(ExecutionRuntime.RuntimeStatus.ALIVE);
        Seen s = seen.get(0);
        assertThat(s.path()).isEqualTo("/healthz");
        assertThat(claims(s.authorization()).get("body_sha256").asText()).isEqualTo(sha(new byte[0]));
    }

    @Test
    void envoyRejectionIsReportedAsExecutionJwtRejected() {
        status = 401;
        var r = runtime(signer).execute(req(Operation.EMBED, "{}"), new RuntimeTarget(base, "tei"));
        assertThat(r).isInstanceOfSatisfying(ExecutionRuntime.RuntimeResult.Failure.class,
                f -> assertThat(f.error().code()).isEqualTo("execution_jwt_rejected"));
    }

    @Test
    void withoutASignerNoAuthorizationHeaderIsSent() {
        runtime(null).execute(req(Operation.EMBED, "{}"), new RuntimeTarget(base, "tei"));
        assertThat(seen.get(0).authorization()).isNull();
    }

    @Test
    void staticReadinessNeverPollsAndPollModeSignsItsRequest() throws Exception {
        var staticMgr = new VllmModelManager(base, Duration.ofSeconds(1), json, true, signer);
        assertThat(staticMgr.getStatus("synanton-bge-base-embedding")).isEqualTo(ModelStatus.READY);
        staticMgr.ensureReady("synanton-bge-base-embedding");
        assertThat(seen).isEmpty();

        var pollMgr = new VllmModelManager(base, Duration.ofSeconds(1), json, false, signer);
        pollMgr.getStatus("synanton-bge-base-embedding");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).path()).isEqualTo("/v1/models");
        assertThat(claims(seen.get(0).authorization()).get("op").asText()).isEqualTo("MODELS");
    }
}
