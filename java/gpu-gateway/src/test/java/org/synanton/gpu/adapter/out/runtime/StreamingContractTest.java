package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.domain.service.HeartbeatManager;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * PR #15 P1.2 / Deployment Plan §10: the streaming contract on every runtime selected by
 * ProviderRouter — GPU-7 provider runtime, GPU-5 vLLM runtime, and the stub.
 */
class StreamingContractTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<JsonNode> upstream = new AtomicReference<>();
    private volatile boolean emitUsage = true;
    private HttpServer server;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            upstream.set(json.readTree(ex.getRequestBody().readAllBytes()));
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(": keep-alive comment\n\n".getBytes(StandardCharsets.UTF_8));
                os.write("data: {\"model\":\"up\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                if (emitUsage) {
                    os.write(("data: {\"model\":\"up\",\"choices\":[],\"usage\":{\"prompt_tokens\":5,"
                            + "\"completion_tokens\":1,\"total_tokens\":6}}\n\n").getBytes(StandardCharsets.UTF_8));
                }
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static ExecutionRequest request(boolean includeUsage) {
        String payload = "{\"model\":\"logical-chat\",\"messages\":[]"
                + (includeUsage ? ",\"stream_options\":{\"include_usage\":true}" : "") + "}";
        return ExecutionRequest.newBuilder().setRequestId("r").setTenantId("t").setModel("logical-chat")
                .setModelVersion("1").setOperation(Operation.SYNTHESIZE)
                .setPayload(ByteString.copyFromUtf8(payload)).build();
    }

    private OpenAiProviderRuntime provider() {
        return new OpenAiProviderRuntime("mock", base + "/v1", "k", Map.of(), Duration.ofSeconds(5),
                new CircuitBreaker(5, Duration.ofSeconds(30)), json);
    }

    private static List<String> collect(ExecutionRuntime rt, ExecutionRequest req, RuntimeTarget target,
                                        AtomicReference<ExecutionRuntime.RuntimeResult> result) {
        List<String> frames = new ArrayList<>();
        result.set(rt.executeStreaming(req, target, f -> frames.add(new String(f, StandardCharsets.UTF_8))));
        return frames;
    }

    @Test
    void includeUsageRequested_usageChunkForwardedAndTerminalUsageSet() {
        var result = new AtomicReference<ExecutionRuntime.RuntimeResult>();
        List<String> frames = collect(provider(), request(true), new RuntimeTarget(base + "/v1", "mock", "up"), result);

        assertThat(frames).hasSize(3).last().isEqualTo("data: [DONE]\n\n");
        assertThat(frames.get(1)).contains("\"usage\"").contains("\"model\":\"logical-chat\"");
        var success = (ExecutionRuntime.RuntimeResult.Success) result.get();
        assertThat(success.usage().inputTokens()).isEqualTo(5);
    }

    @Test
    void includeUsageNotRequested_usageStillCapturedUpstreamButNotForwarded() {
        var result = new AtomicReference<ExecutionRuntime.RuntimeResult>();
        List<String> frames = collect(provider(), request(false), new RuntimeTarget(base + "/v1", "mock", "up"), result);

        // §10.3: authoritative usage requested upstream regardless of the client
        assertThat(upstream.get().path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(frames).hasSize(2).noneMatch(f -> f.contains("\"usage\""));
        assertThat(((ExecutionRuntime.RuntimeResult.Success) result.get()).usage().inputTokens()).isEqualTo(5);
    }

    @Test
    void unavailableUsageIsNullNeverZero() {
        emitUsage = false;
        var result = new AtomicReference<ExecutionRuntime.RuntimeResult>();
        collect(provider(), request(true), new RuntimeTarget(base + "/v1", "mock", "up"), result);

        assertThat(((ExecutionRuntime.RuntimeResult.Success) result.get()).usage()).isNull(); // §10.2
    }

    @Test
    void vllmStreamsThroughTheSameRelay() {
        VllmRuntime vllm = new VllmRuntime(json, mock(HeartbeatManager.class), Duration.ofSeconds(5));
        var result = new AtomicReference<ExecutionRuntime.RuntimeResult>();
        List<String> frames = collect(vllm, request(false), new RuntimeTarget(base, "vllm"), result);

        assertThat(vllm.supportsStreaming()).isTrue();
        assertThat(frames).hasSize(2).last().isEqualTo("data: [DONE]\n\n");
        assertThat(frames.get(0)).contains("\"model\":\"logical-chat\"").doesNotContain("keep-alive");
        assertThat(result.get()).isInstanceOf(ExecutionRuntime.RuntimeResult.Success.class);
    }

    @Test
    void stubDoesNotStreamAndSaysSo() {
        StubExecutionRuntime stub = new StubExecutionRuntime();

        assertThat(stub.supportsStreaming()).isFalse();
        assertThatThrownBy(() -> stub.executeStreaming(request(false), new RuntimeTarget("x", "stub"), f -> { }))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private HttpServer hangingServer(boolean sendDone) throws Exception {
        HttpServer h = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        h.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            os.write("data: {\"model\":\"up\",\"choices\":[]}\n\n".getBytes(StandardCharsets.UTF_8));
            if (sendDone) {
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
            os.flush();
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { } // connection stays open
            os.close();
        });
        h.start();
        return h;
    }

    @Test
    void relayStopsAtDoneEvenIfTheProviderKeepsTheConnectionOpen() throws Exception {
        HttpServer h = hangingServer(true);
        try {
            String url = "http://127.0.0.1:" + h.getAddress().getPort() + "/v1";
            var rt = new OpenAiProviderRuntime("mock", url, "k", Map.of(), Duration.ofSeconds(30),
                    new CircuitBreaker(5, Duration.ofSeconds(30)), json);
            long t0 = System.currentTimeMillis();
            var result = rt.executeStreaming(request(false), new RuntimeTarget(url, "mock", "up"), f -> { });

            assertThat(result).isInstanceOf(ExecutionRuntime.RuntimeResult.Success.class);
            assertThat(System.currentTimeMillis() - t0).as("returned at [DONE], not at EOF").isLessThan(3000);
        } finally {
            h.stop(0);
        }
    }

    @Test
    void stalledStreamTimesOutInsteadOfHangingForever() throws Exception {
        HttpServer h = hangingServer(false);
        try {
            String url = "http://127.0.0.1:" + h.getAddress().getPort() + "/v1";
            var rt = new OpenAiProviderRuntime("mock", url, "k", Map.of(), Duration.ofMillis(400),
                    new CircuitBreaker(5, Duration.ofSeconds(30)), json);
            long t0 = System.currentTimeMillis();
            var result = rt.executeStreaming(request(false), new RuntimeTarget(url, "mock", "up"), f -> { });

            assertThat(((ExecutionRuntime.RuntimeResult.Failure) result).error().code())
                    .isEqualTo("upstream_provider_timeout");
            assertThat(System.currentTimeMillis() - t0).isLessThan(3000);
        } finally {
            h.stop(0);
        }
    }
}
