package org.synanton.gpu.adapter.out.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider request headers: configured headers (including a custom User-Agent, which opencode.ai
 * requires) and the optional per-tenant session header ({@code x-opencode-session}).
 */
class OpenAiProviderRuntimeHeadersTest {

    private HttpServer server;
    private final List<Map<String, List<String>>> seen = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            seen.add(Map.copyOf(ex.getRequestHeaders()));
            byte[] body = "{\"id\":\"c1\",\"object\":\"chat.completion\",\"model\":\"qwen3.8-flash\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1,\"total_tokens\":6}}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static ExecutionRequest req(String tenant) {
        return ExecutionRequest.newBuilder().setRequestId("r-" + tenant).setTenantId(tenant)
                .setModel("synanton-external-chat").setOperation(Operation.SYNTHESIZE)
                .setPayload(ByteString.copyFromUtf8("{\"model\":\"synanton-external-chat\",\"messages\":[]}")).build();
    }

    private static String header(Map<String, List<String>> h, String name) {
        return h.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(e -> e.getValue().get(0)).findFirst().orElse(null);
    }

    @Test
    void configuredHeadersIncludingUserAgentAndPerTenantSessionAreSent() throws Exception {
        String base = start();
        var runtime = new OpenAiProviderRuntime("opencode", base, "k",
                Map.of("User-Agent", "synanton/1.0 (Synthesis & Semantics App)"),
                Duration.ofSeconds(10), new CircuitBreaker(5, Duration.ofSeconds(30)), new ObjectMapper(),
                "x-opencode-session");
        var target = new RuntimeTarget(base, "opencode", "qwen3.8-flash");

        assertThat(runtime.execute(req("tenant-a"), target)).isInstanceOf(ExecutionRuntime.RuntimeResult.Success.class);
        runtime.execute(req("tenant-a"), target);
        runtime.execute(req("tenant-b"), target);

        assertThat(header(seen.get(0), "User-Agent")).isEqualTo("synanton/1.0 (Synthesis & Semantics App)");
        String a1 = header(seen.get(0), "x-opencode-session");
        assertThat(a1).startsWith("synanton-").doesNotContain("tenant-a");
        assertThat(header(seen.get(1), "x-opencode-session")).isEqualTo(a1);          // reused per tenant
        assertThat(header(seen.get(2), "x-opencode-session")).isNotEqualTo(a1);       // distinct tenants
        assertThat(OpenAiProviderRuntime.sessionId("openai", "tenant-a")).isNotEqualTo(a1); // per provider
    }

    @Test
    void noSessionHeaderUnlessConfigured() throws Exception {
        String base = start();
        var runtime = new OpenAiProviderRuntime("openai", base, "k", Map.of(), Duration.ofSeconds(10),
                new CircuitBreaker(5, Duration.ofSeconds(30)), new ObjectMapper());
        runtime.execute(req("tenant-a"), new RuntimeTarget(base, "openai", "m"));
        assertThat(header(seen.get(0), "x-opencode-session")).isNull();
    }
}
