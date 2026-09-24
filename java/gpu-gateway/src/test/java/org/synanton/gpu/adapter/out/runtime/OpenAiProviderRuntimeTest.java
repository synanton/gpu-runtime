package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.domain.port.out.StreamingExecutionRuntime;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #15 review §6 acceptance (runtime layer), against a fake provider:
 * model-ID rewriting upstream and back (P1.1, incl. every SSE chunk), SSE
 * preservation with exactly-one [DONE] and terminal usage (P1.2), canonical error
 * mapping (500/timeout/401), and circuit-breaker denial without a provider call.
 */
class OpenAiProviderRuntimeTest {

    private static final String LOGICAL = "gpt-4o-mini";
    private static final String PROVIDER_MODEL = "openai/gpt-4o-mini";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();

    /** Response mode of the fake provider per test. */
    private volatile String mode = "unary";

    private OpenAiProviderRuntime runtime;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            hits.incrementAndGet();
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            switch (mode) {
                case "fail500" -> respond(exchange, 500, "{\"error\":{\"message\":\"boom\"}}");
                case "slow" -> {
                    sleep(2000);
                    respond(exchange, 200, "{}");
                }
                case "sse" -> respondSse(exchange);
                case "sse-double-done" -> respondSse(exchange);
                default -> respond(exchange, 200, unaryResponse());
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        runtime = newRuntime(5, Duration.ofSeconds(30), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private OpenAiProviderRuntime newRuntime(int breakerThreshold, Duration breakerReset, Duration timeout) {
        return new OpenAiProviderRuntime("openrouter", baseUrl, "test-key", Map.of(),
                timeout, new CircuitBreaker(breakerThreshold, breakerReset), objectMapper);
    }

    private ExecutionRequest request(boolean streaming) {
        String body = "{\"model\":\"" + LOGICAL + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]"
                + (streaming ? ",\"stream\":true,\"stream_options\":{\"include_usage\":true}" : "") + "}";
        return ExecutionRequest.newBuilder()
                .setRequestId("req-1")
                .setTenantId("tenant-abc")
                .setModel(LOGICAL)
                .setModelVersion("1.0")
                .setOperation(Operation.SYNTHESIZE)
                .setPayload(ByteString.copyFromUtf8(body))
                .build();
    }

    private RuntimeTarget target() {
        return new RuntimeTarget(baseUrl, "openrouter", PROVIDER_MODEL);
    }

    private static String unaryResponse() {
        return "{\"id\":\"chatcmpl-1\",\"model\":\"" + PROVIDER_MODEL + "\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}";
    }

    private void respondSse(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            // chunks carry the PROVIDER model id, as a real provider would emit
            os.write(chunk("{\"delta\":{\"content\":\"he\"}}", null).getBytes(StandardCharsets.UTF_8));
            os.write(chunk("{\"delta\":{\"content\":\"llo\"}}", null).getBytes(StandardCharsets.UTF_8));
            os.write(chunk("{\"delta\":{}}",
                    "{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":9}")
                    .getBytes(StandardCharsets.UTF_8));
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            if ("sse-double-done".equals(mode)) {
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); // must be suppressed
            }
            os.flush();
        }
    }

    private static String chunk(String choicesInner, String usage) {
        return "data: {\"id\":\"chatcmpl-1\",\"model\":\"" + PROVIDER_MODEL + "\","
                + "\"choices\":[{\"index\":0," + choicesInner.substring(1) + "],"
                + "\"usage\":" + (usage == null ? "null" : usage) + "}\n\n";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── P1.1: model-ID rewriting ────────────────────────────────────────────

    @Test
    void rewritesLogicalModelIdUpstreamAndRestoresItDownstream() throws Exception {
        ExecutionRuntime.RuntimeResult result = runtime.execute(request(false), target());

        // provider received the provider model ID
        JsonNode upstream = objectMapper.readTree(lastRequestBody.get());
        assertThat(upstream.path("model").asText()).isEqualTo(PROVIDER_MODEL);

        // gateway result carries the logical model ID; provider ID never leaks
        assertThat(result).isInstanceOf(ExecutionRuntime.RuntimeResult.Success.class);
        var success = (ExecutionRuntime.RuntimeResult.Success) result;
        JsonNode downstream = objectMapper.readTree(success.result());
        assertThat(downstream.path("model").asText()).isEqualTo(LOGICAL);
        assertThat(success.result()).asString().doesNotContain(PROVIDER_MODEL);
        assertThat(success.usage().inputTokens()).isEqualTo(7);
        assertThat(success.usage().outputTokens()).isEqualTo(3);
    }

    @Test
    void rewritesModelIdInEverySseChunkAndPreservesTerminalUsage() throws Exception {
        mode = "sse";
        List<byte[]> frames = new ArrayList<>();

        ExecutionRuntime.RuntimeResult result = ((StreamingExecutionRuntime) runtime)
                .executeStreaming(request(true), target(), frames::add);

        assertThat(result).isInstanceOf(ExecutionRuntime.RuntimeResult.Success.class);

        String all = frames.stream()
                .map(f -> new String(f, StandardCharsets.UTF_8))
                .reduce("", String::concat);

        // every data chunk carries the logical id; provider id appears nowhere downstream
        assertThat(all).doesNotContain(PROVIDER_MODEL);
        long chunkCount = all.lines().filter(l -> l.startsWith("data: {")).count();
        assertThat(chunkCount).isEqualTo(3);
        for (String line : all.lines().filter(l -> l.startsWith("data: {")).toList()) {
            JsonNode chunk = objectMapper.readTree(line.substring(5).trim());
            assertThat(chunk.path("model").asText()).isEqualTo(LOGICAL);
        }
        // exactly one [DONE] (§10), terminal usage preserved
        assertThat(all.split("\\Qdata: [DONE]\\E", -1)).hasSize(2);
        var success = (ExecutionRuntime.RuntimeResult.Success) result;
        assertThat(success.usage().inputTokens()).isEqualTo(7);
        assertThat(success.usage().outputTokens()).isEqualTo(2);
    }

    @Test
    void duplicateDoneFramesAreSuppressed() throws Exception {
        mode = "sse-double-done";
        List<byte[]> frames = new ArrayList<>();

        ((StreamingExecutionRuntime) runtime).executeStreaming(request(true), target(), frames::add);

        String all = frames.stream()
                .map(f -> new String(f, StandardCharsets.UTF_8))
                .reduce("", String::concat);
        assertThat(all.split("\\Qdata: [DONE]\\E", -1)).hasSize(2); // exactly one
    }

    @Test
    void streamingRequestForcesStreamTrueUpstreamButKeepsStreamOptions() throws Exception {
        mode = "sse";

        ((StreamingExecutionRuntime) runtime)
                .executeStreaming(request(true), target(), f -> { });

        JsonNode upstream = objectMapper.readTree(lastRequestBody.get());
        assertThat(upstream.path("stream").asBoolean()).isTrue();
        assertThat(upstream.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(upstream.path("model").asText()).isEqualTo(PROVIDER_MODEL);
    }

    // ─── canonical error mapping (§6) ────────────────────────────────────────

    @Test
    void provider500MapsToUpstreamProviderError() {
        mode = "fail500";

        var result = runtime.execute(request(false), target());

        var failure = (ExecutionRuntime.RuntimeResult.Failure) result;
        assertThat(failure.error().code()).isEqualTo("upstream_provider_error");
        assertThat(failure.error().message()).doesNotContain("boom"); // provider body not leaked verbatim
    }

    @Test
    void providerTimeoutMapsToCanonicalTimeout() {
        mode = "slow";
        OpenAiProviderRuntime impatient =
                newRuntime(5, Duration.ofSeconds(30), Duration.ofMillis(150));

        var result = impatient.execute(request(false), target());

        var failure = (ExecutionRuntime.RuntimeResult.Failure) result;
        assertThat(failure.error().code()).isEqualTo("provider_timeout");
    }

    @Test
    void circuitOpensAndDeniesWithoutProviderCall() {
        mode = "fail500";
        OpenAiProviderRuntime fragile = newRuntime(2, Duration.ofSeconds(60), Duration.ofSeconds(5));

        fragile.execute(request(false), target());
        fragile.execute(request(false), target());
        assertThat(hits.get()).isEqualTo(2);

        var third = fragile.execute(request(false), target());

        var failure = (ExecutionRuntime.RuntimeResult.Failure) third;
        assertThat(failure.error().code()).isEqualTo("circuit_open");
        assertThat(hits.get()).as("no provider call while circuit is open").isEqualTo(2);
    }

    @Test
    void authFailureDoesNotTripCircuit() {
        // 401 is a config fault; the circuit must not count it
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 401, "{\"error\":{\"message\":\"invalid key\"}}");
        });

        var result = runtime.execute(request(false), target());

        var failure = (ExecutionRuntime.RuntimeResult.Failure) result;
        assertThat(failure.error().code()).isEqualTo("provider_auth_failed");
        var second = runtime.execute(request(false), target());
        assertThat(hits.get()).as("auth failures never open the circuit").isEqualTo(2);
        assertThat(second).isInstanceOf(ExecutionRuntime.RuntimeResult.Failure.class);
    }
}
