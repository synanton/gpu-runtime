package org.synanton.gpu.integration;

import org.synanton.gpu.adapter.in.grpc.GpuExecutionGrpcAdapter;
import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.config.GrpcServerLifecycle;
import org.synanton.gpu.v1.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GPU-7 acceptance (Deployment Plan §37, PR #15 review §6) over the real platform transport:
 * gRPC client → GpuExecutionGrpcAdapter → ExecuteService → ProviderRouter / ExternalRoutingPolicy
 * → provider runtime → fake providers, with PostgreSQL (Testcontainers) for executions and the
 * cost ledger. A fake local vLLM counts hits: it must never be called (no local fallback).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("gpu7-acceptance")
@Testcontainers
class ExternalAcceptanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();
    private static final Map<String, String> LAST_UPSTREAM_MODEL = new ConcurrentHashMap<>();
    private static final HttpServer FAKE;

    static {
        try {
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FAKE.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            FAKE.createContext("/good/v1/chat/completions", ExternalAcceptanceTest::goodChat);
            FAKE.createContext("/good/v1/embeddings", ex -> {
                JsonNode body = read(ex, "good-embed");
                respond(ex, 200, "{\"object\":\"list\",\"model\":\"" + body.path("model").asText()
                        + "\",\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}],\"usage\":{\"prompt_tokens\":2}}");
            });
            FAKE.createContext("/good/v1/rerank", ex -> {
                read(ex, "good-rerank");
                respond(ex, 400, "{\"error\":{\"message\":\"rerank not supported by this model\"}}");
            });
            FAKE.createContext("/flaky/v1/chat/completions", ex -> {
                read(ex, "flaky");
                respond(ex, 500, "{\"error\":{\"message\":\"secret upstream stack trace\"}}");
            });
            FAKE.createContext("/fragile/v1/chat/completions", ex -> {
                read(ex, "fragile");
                respond(ex, 500, "{}");
            });
            FAKE.createContext("/slow/v1/chat/completions", ex -> {
                read(ex, "slow");
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                respond(ex, 200, "{}");
            });
            FAKE.createContext("/busy/v1/chat/completions", ex -> {
                read(ex, "busy");
                respond(ex, 503, "{\"error\":{\"message\":\"overloaded\"}}");
            });
            FAKE.createContext("/local", ex -> {
                read(ex, "local-vllm");
                respond(ex, 200, "{}");
            });
            FAKE.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("fake.base", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort());
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired GrpcServerLifecycle grpcServer;
    @Autowired GpuGatewayProperties properties;
    @Autowired JdbcTemplate jdbc;

    private ManagedChannel channel;
    private GPUExecutionServiceGrpc.GPUExecutionServiceBlockingStub stub;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress("localhost", grpcServer.getBoundPort()).usePlaintext().build();
        stub = GPUExecutionServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
        assertThat(hits("local-vllm")).as("invariant 1: the local runtime is never called in GPU-7").isZero();
    }

    // ─── fake provider ───────────────────────────────────────────────────────

    private static JsonNode read(HttpExchange ex, String counter) throws IOException {
        HITS.computeIfAbsent(counter, k -> new AtomicInteger()).incrementAndGet();
        byte[] raw = ex.getRequestBody().readAllBytes();
        JsonNode body = raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw);
        LAST_UPSTREAM_MODEL.put(counter, body.path("model").asText());
        String rid = ex.getRequestHeaders().getFirst("x-request-id");
        ex.getResponseHeaders().set("x-request-id", "upstream-" + rid); // provider-assigned ID
        return body;
    }

    private static void goodChat(HttpExchange ex) throws IOException {
        JsonNode body = read(ex, "good-chat");
        String model = body.path("model").asText();
        if (body.path("stream").asBoolean()) {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                for (String part : List.of("A", "B", "C")) {
                    os.write(("data: {\"id\":\"c1\",\"model\":\"" + model + "\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"content\":\"" + part + "\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
                }
                if (body.path("stream_options").path("include_usage").asBoolean()) {
                    os.write(("data: {\"id\":\"c1\",\"model\":\"" + model + "\",\"choices\":[],"
                            + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}\n\n").getBytes(StandardCharsets.UTF_8));
                }
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        respond(ex, 200, "{\"id\":\"c1\",\"object\":\"chat.completion\",\"model\":\"" + model + "\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}");
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static int hits(String counter) {
        return HITS.getOrDefault(counter, new AtomicInteger()).get();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static ExecutionRequest.Builder request(String model, Operation op, String payload) {
        return ExecutionRequest.newBuilder()
                .setRequestId("acc-" + UUID.randomUUID())
                .setTenantId("tenant-acc")
                .setModel(model).setModelVersion("1").setOperation(op)
                .setPayload(ByteString.copyFromUtf8(payload));
    }

    private static ExecutionRequest chat(String model) {
        return request(model, Operation.SYNTHESIZE,
                "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}").build();
    }

    private static void assertDenied(Runnable call, Status.Code status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
            assertThat(e.getStatus().getCode()).isEqualTo(status);
            assertThat(e.getTrailers().get(GpuExecutionGrpcAdapter.ERROR_CODE_KEY)).isEqualTo(code);
        });
    }

    private static void assertFailed(ExecutionResponse response, String code) {
        assertThat(response.getState()).isEqualTo(ExecutionState.FAILED);
        assertThat(response.getError().getCode()).isEqualTo(code);
    }

    // ─── review §6 acceptance cases ──────────────────────────────────────────

    @Test
    void externalOnlyWithLocalModelOfSameNameNeverRoutesLocally() {
        assertDenied(() -> stub.execute(chat("shared-local")), Status.Code.PERMISSION_DENIED, "no_local_fallback");
        assertDenied(() -> stub.execute(chat("gpt-4o-shared").toBuilder().setProvider(Provider.LOCAL).build()),
                Status.Code.PERMISSION_DENIED, "no_local_fallback");
    }

    @Test
    void killSwitchOffDeniesAllExternalRouting() {
        properties.getRouting().setExternalEnabled(false);
        try {
            int before = hits("good-chat");
            assertDenied(() -> stub.execute(chat("gpt-4o-shared")), Status.Code.PERMISSION_DENIED, "routing_disabled");
            assertThat(hits("good-chat")).isEqualTo(before);
        } finally {
            properties.getRouting().setExternalEnabled(true);
        }
    }

    @Test
    void provider500MapsToUpstreamProviderError() {
        ExecutionResponse r = stub.execute(chat("flaky-chat"));

        assertFailed(r, "upstream_provider_error");
        assertThat(r.getError().getReason()).isEqualTo(ErrorReason.EXECUTION_FAILED);
        assertThat(r.getError().getMessage()).doesNotContain("secret upstream stack trace");
    }

    @Test
    void providerTimeoutMapsToCanonicalTimeout() {
        ExecutionResponse r = stub.execute(chat("slow-chat"));

        assertFailed(r, "upstream_provider_timeout");
        assertThat(r.getError().getReason()).isEqualTo(ErrorReason.EXECUTION_TIMEOUT);
    }

    @Test
    void circuitOpensAndDeniesWithoutProviderCall() {
        assertFailed(stub.execute(chat("fragile-chat")), "upstream_provider_error");
        assertFailed(stub.execute(chat("fragile-chat")), "upstream_provider_error");
        int callsWhenOpened = hits("fragile");

        assertFailed(stub.execute(chat("fragile-chat")), "circuit_open");
        assertThat(hits("fragile")).as("no provider call while the circuit is open").isEqualTo(callsWhenOpened);
    }

    @Test
    void budgetExhaustedIsBudgetExceeded() {
        ExecutionRequest first = chat("gpt-4o-shared").toBuilder().setTenantId("poor-tenant").build();
        ExecutionResponse spent = stub.execute(first); // spend 0 < limit → allowed; costs > limit
        assertThat(spent.getState()).isEqualTo(ExecutionState.SUCCESS);

        assertDenied(() -> stub.execute(chat("gpt-4o-shared").toBuilder().setTenantId("poor-tenant").build()),
                Status.Code.RESOURCE_EXHAUSTED, "budget_exceeded");
    }

    @Test
    void sensitiveModelOrRequestIsDeniedExternalRouting() {
        int before = hits("good-chat");
        assertDenied(() -> stub.execute(chat("sensitive-chat")),
                Status.Code.PERMISSION_DENIED, "sensitive_model_external_blocked");
        assertDenied(() -> stub.execute(chat("gpt-4o-shared").toBuilder().addDataTags("pii").build()),
                Status.Code.PERMISSION_DENIED, "sensitive_model_external_blocked");
        assertThat(hits("good-chat")).isEqualTo(before);
    }

    @Test
    void providerModelMappingRewritesLogicalIdUpstream() {
        stub.execute(chat("gpt-4o-shared"));

        assertThat(LAST_UPSTREAM_MODEL.get("good-chat")).isEqualTo("openai/gpt-4o");
    }

    @Test
    void providerModelIdIsNeverExposedDownstream() throws Exception {
        ExecutionResponse r = stub.execute(chat("gpt-4o-shared"));
        String result = r.getResult().toStringUtf8();
        assertThat(JSON.readTree(result).path("model").asText()).isEqualTo("gpt-4o-shared");
        assertThat(result).doesNotContain("openai/gpt-4o");

        GetModelsResponse models = stub.getModels(GetModelsRequest.newBuilder().setOperation(Operation.SYNTHESIZE).build());
        assertThat(models.toString()).doesNotContain("openai/gpt-4o");
        assertThat(models.getModelsList()).extracting(ModelInfo::getModelId)
                .contains("gpt-4o-shared").doesNotContain("shared-local", "off-chat");
    }

    @Test
    void providerRequestIdIsPreserved() {
        ExecutionRequest req = chat("gpt-4o-shared");
        ExecutionResponse r = stub.execute(req);

        assertThat(r.getUpstreamRequestId()).isEqualTo("upstream-" + req.getRequestId());
        ExecutionStatus status = stub.getStatus(GetStatusRequest.newBuilder().setExecutionId(r.getExecutionId()).build());
        assertThat(status.getUpstreamRequestId()).isEqualTo("upstream-" + req.getRequestId());
    }

    private List<ExecutionChunk> stream(ExecutionRequest req) {
        List<ExecutionChunk> chunks = new ArrayList<>();
        stub.executeStream(req).forEachRemaining(chunks::add);
        return chunks;
    }

    @Test
    void providerSseIsPreservedDownstream() throws Exception {
        List<ExecutionChunk> chunks = stream(chat("gpt-4o-shared"));

        assertThat(chunks).hasSize(4); // A, B, C, terminal
        List<String> contents = new ArrayList<>();
        for (ExecutionChunk c : chunks.subList(0, 3)) {
            JsonNode data = JSON.readTree(c.getData().toStringUtf8());
            assertThat(data.path("model").asText()).isEqualTo("gpt-4o-shared");
            contents.add(data.path("choices").get(0).path("delta").path("content").asText());
        }
        assertThat(contents).containsExactly("A", "B", "C");
        assertThat(chunks.get(3).hasTerminal()).isTrue();
        assertThat(chunks.get(3).getTerminal().getState()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(chunks.toString()).doesNotContain("openai/gpt-4o").doesNotContain("[DONE]");
    }

    @Test
    void includeUsageYieldsUsageChunkAndTerminalUsage() throws Exception {
        List<ExecutionChunk> chunks = stream(request("gpt-4o-shared", Operation.SYNTHESIZE,
                "{\"model\":\"gpt-4o-shared\",\"messages\":[],\"stream_options\":{\"include_usage\":true}}").build());

        ExecutionChunk usageChunk = chunks.get(chunks.size() - 2);
        assertThat(JSON.readTree(usageChunk.getData().toStringUtf8()).path("usage").path("prompt_tokens").asInt())
                .isEqualTo(11);
        assertThat(chunks.get(chunks.size() - 1).getTerminal().getUsage().getInputTokens()).isEqualTo(11);
    }

    @Test
    void providerLacksRerankIsCapabilityNotSupported() {
        ExecutionResponse r = stub.execute(request("shared-rerank", Operation.RERANK,
                "{\"model\":\"shared-rerank\",\"query\":\"q\",\"documents\":[\"a\"]}").build());
        assertFailed(r, "capability_not_supported");

        // catalog-level gap: a chat model on RERANK is denied before dispatch
        assertDenied(() -> stub.execute(request("gpt-4o-shared", Operation.RERANK, "{}").build()),
                Status.Code.FAILED_PRECONDITION, "capability_not_supported");
    }

    @Test
    void unavailableProviderNeverFallsBackToLocal() {
        assertDenied(() -> stub.execute(chat("off-chat")), Status.Code.UNAVAILABLE, "provider_unavailable");

        ExecutionResponse r = stub.execute(chat("dead-chat")); // connection refused
        assertFailed(r, "provider_unavailable");
        // @AfterEach asserts the local vLLM fake was never called
    }

    @Test
    void embeddingsRouteToProviderWithRewrite() {
        ExecutionResponse r = stub.execute(request("shared-embed", Operation.EMBED,
                "{\"model\":\"shared-embed\",\"input\":\"x\"}").build());

        assertThat(r.getState()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(LAST_UPSTREAM_MODEL.get("good-embed")).isEqualTo("prov-embed");
        assertThat(r.getResult().toStringUtf8()).contains("\"model\":\"shared-embed\"").doesNotContain("prov-embed");
    }

    @Test
    void costLedgerRecordsProviderUsage() {
        ExecutionResponse r = stub.execute(chat("gpt-4o-shared"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT provider_id, logical_model_id, input_tokens, output_tokens, cost_usd FROM cost_ledger WHERE execution_id = ?",
                r.getExecutionId());
        assertThat(row.get("provider_id")).isEqualTo("mock");
        assertThat(row.get("logical_model_id")).isEqualTo("gpt-4o-shared");
        assertThat(((Number) row.get("input_tokens")).longValue()).isEqualTo(7);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.000013"); // (7*1 + 3*2)/1e6
    }

    @Test
    void responsesApiIsNotPartOfTheContract() {
        // Plan v3.0.0 §4.6: deferred — the service exposes no Responses RPC
        assertThat(GPUExecutionServiceGrpc.getServiceDescriptor().getMethods())
                .extracting(m -> m.getBareMethodName())
                .containsExactlyInAnyOrder("Execute", "ExecuteStream", "Cancel", "GetStatus", "GetCapacity", "GetModels");
    }

    // ─── T-K8S-38: persisted runtime routing control via GPUControlService ───

    @Test
    void runtimeKillSwitchViaControlServiceDeniesAndIsPersisted() {
        var control = GPUControlServiceGrpc.newBlockingStub(channel);
        try {
            RoutingControlState off = control.setExternalRouting(SetExternalRoutingRequest.newBuilder()
                    .setEnabled(false).setReason("acceptance: incident drill").build());
            assertThat(off.getExternalRoutingEnabled()).isFalse();
            assertThat(off.getExternalRoutingConfigEnabled()).isTrue();

            int before = hits("good-chat");
            assertDenied(() -> stub.execute(chat("gpt-4o-shared")), Status.Code.PERMISSION_DENIED, "routing_disabled");
            assertThat(hits("good-chat")).isEqualTo(before);
            assertThat(stub.getModels(GetModelsRequest.newBuilder().setOperation(Operation.SYNTHESIZE).build())
                    .getModelsList()).as("nothing external advertised while the kill switch is off").isEmpty();

            // persisted: the row and its audit trail are in PostgreSQL (survives restarts, shared by replicas)
            assertThat(jdbc.queryForObject("SELECT enabled FROM routing_control WHERE scope = 'external-routing'",
                    Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM routing_control_audit WHERE reason = ?",
                    Integer.class, "acceptance: incident drill")).isEqualTo(1);
        } finally {
            control.setExternalRouting(SetExternalRoutingRequest.newBuilder()
                    .setEnabled(true).setReason("acceptance: restore").build());
        }
        assertThat(stub.execute(chat("gpt-4o-shared")).getState()).isEqualTo(ExecutionState.SUCCESS);
    }

    @Test
    void runtimeProviderDisableViaControlService() {
        var control = GPUControlServiceGrpc.newBlockingStub(channel);
        try {
            RoutingControlState state = control.setProviderEnabled(SetProviderEnabledRequest.newBuilder()
                    .setProviderId("mock").setEnabled(false).setReason("acceptance: provider outage").build());
            assertThat(state.getProvidersList()).filteredOn(p -> p.getProviderId().equals("mock"))
                    .singleElement().satisfies(p -> {
                        assertThat(p.getEnabled()).isFalse();
                        assertThat(p.getConfigEnabled()).isTrue();
                        assertThat(p.getReason()).isEqualTo("acceptance: provider outage");
                    });
            assertDenied(() -> stub.execute(chat("gpt-4o-shared")), Status.Code.UNAVAILABLE, "provider_unavailable");
            assertThat(stub.getModels(GetModelsRequest.newBuilder().setOperation(Operation.SYNTHESIZE).build())
                    .getModelsList()).extracting(ModelInfo::getModelId).doesNotContain("gpt-4o-shared");
        } finally {
            control.setProviderEnabled(SetProviderEnabledRequest.newBuilder()
                    .setProviderId("mock").setEnabled(true).setReason("acceptance: restore").build());
        }
    }

    @Test
    void controlServiceRejectsEnablingWhatConfigurationDisabled() {
        var control = GPUControlServiceGrpc.newBlockingStub(channel);
        assertDenied(() -> control.setProviderEnabled(SetProviderEnabledRequest.newBuilder()
                        .setProviderId("offline").setEnabled(true).setReason("try").build()),
                Status.Code.FAILED_PRECONDITION, "config_disabled");
        assertDenied(() -> control.setExternalRouting(SetExternalRoutingRequest.newBuilder()
                        .setEnabled(false).build()),
                Status.Code.INVALID_ARGUMENT, "invalid_request"); // reason required
    }

    // ─── T-K8S-52: multi-provider failover (never local, never a duplicate execution) ───

    @Test
    void notAcceptedPrimaryFailsOverToTheNextProvider() throws Exception {
        int busyBefore = hits("busy");
        ExecutionResponse r = stub.execute(chat("failover-chat"));

        assertThat(r.getState()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(hits("busy")).isEqualTo(busyBefore + 1);
        assertThat(LAST_UPSTREAM_MODEL.get("good-chat")).isEqualTo("openai/gpt-4o"); // fallback's provider model ID
        assertThat(JSON.readTree(r.getResult().toStringUtf8()).path("model").asText()).isEqualTo("failover-chat");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT provider_id, cost_usd FROM cost_ledger WHERE execution_id = ?", r.getExecutionId());
        assertThat(row.get("provider_id")).as("ledger records the provider that served").isEqualTo("mock");
        assertThat((BigDecimal) row.get("cost_usd")).as("fallback prices").isEqualByComparingTo("0.000033"); // (7*3+3*4)/1e6
    }

    @Test
    void connectionRefusedPrimaryFailsOver() {
        assertThat(stub.execute(chat("dead-failover-chat")).getState()).isEqualTo(ExecutionState.SUCCESS);
    }

    @Test
    void acceptedFailureIsNeverRetriedOnAnotherProvider() {
        int mockBefore = hits("good-chat");
        ExecutionResponse r = stub.execute(chat("accepted-500-chat"));

        assertFailed(r, "upstream_provider_error"); // 500: the provider may have executed it
        assertThat(hits("good-chat")).as("no duplicate execution on the fallback").isEqualTo(mockBefore);
    }

    @Test
    void allCandidatesDownFailsWithTheLastErrorAndNeverFallsBackToLocal() {
        ExecutionResponse r = stub.execute(chat("all-down-chat"));

        assertFailed(r, "provider_unavailable");
        // @AfterEach: the local vLLM fake was not called
    }

    @Test
    void disabledPrimaryIsSkippedBeforeAdmission() {
        assertThat(stub.execute(chat("offline-primary-chat")).getState()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(stub.getModels(GetModelsRequest.newBuilder().setOperation(Operation.SYNTHESIZE).build())
                .getModelsList()).extracting(ModelInfo::getModelId)
                .as("advertised: servable through its fallback").contains("offline-primary-chat");
    }

    @Test
    void streamingFailsOverBeforeTheFirstChunk() throws Exception {
        List<ExecutionChunk> chunks = stream(chat("failover-chat"));

        assertThat(chunks).hasSize(4);
        assertThat(JSON.readTree(chunks.get(0).getData().toStringUtf8()).path("model").asText()).isEqualTo("failover-chat");
        assertThat(chunks.get(3).getTerminal().getState()).isEqualTo(ExecutionState.SUCCESS);
    }
}
