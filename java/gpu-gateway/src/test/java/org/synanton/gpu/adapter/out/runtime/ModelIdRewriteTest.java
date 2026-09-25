package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.RoutingDecision;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.domain.port.out.StreamingExecutionRuntime;
import org.synanton.gpu.domain.service.ProviderRouter;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #15 P1.1 (literal review case): request {@code gpt-4o-shared}; the provider receives
 * {@code openai/gpt-4o}; the client response — unary and every stream chunk — contains
 * {@code gpt-4o-shared} and never {@code openai/gpt-4o}. Exercised through the real
 * routing path: ProviderRouter → RoutingDecision → registry → runtime.
 */
class ModelIdRewriteTest {

    private static final String LOGICAL = "gpt-4o-shared";
    private static final String PROVIDER = "openai/gpt-4o";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> upstreamModel = new AtomicReference<>();
    private HttpServer provider;
    private ProviderRouter router;
    private ProviderRuntimeRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            var body = json.readTree(exchange.getRequestBody().readAllBytes());
            upstreamModel.set(body.path("model").asText());
            if (body.path("stream").asBoolean()) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    for (String c : List.of("he", "llo")) {
                        os.write(("data: {\"model\":\"" + PROVIDER + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                                + c + "\"}}],\"usage\":null}\n\n").getBytes(StandardCharsets.UTF_8));
                    }
                    os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            byte[] out = ("{\"id\":\"c1\",\"model\":\"" + PROVIDER + "\",\"choices\":[],"
                    + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        provider.start();

        GpuGatewayProperties p = new GpuGatewayProperties();
        p.getDispatch().setStrategy("external");
        var info = new GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo();
        info.setProvider("OPENAI");
        info.setProviderModelId(PROVIDER);
        p.getModelCatalog().getOperations()
                .computeIfAbsent("SYNTHESIZE", k -> new GpuGatewayProperties.ModelCatalog.OperationModels())
                .getModels().put(LOGICAL, info);
        var openai = new GpuGatewayProperties.ProviderConfig();
        openai.setBaseUrl("http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
        openai.setApiKey("k");
        p.getProviders().put("openai", openai);
        router = new ProviderRouter(p, new ModelCatalogService(p));
        registry = new ProviderRuntimeRegistry(p, Duration.ofSeconds(5), json);
    }

    @AfterEach
    void tearDown() {
        provider.stop(0);
    }

    private ExecutionRequest request(boolean stream) {
        return ExecutionRequest.newBuilder()
                .setRequestId("r").setTenantId("t").setModel(LOGICAL).setModelVersion("1")
                .setOperation(Operation.SYNTHESIZE)
                .setPayload(ByteString.copyFromUtf8("{\"model\":\"" + LOGICAL + "\",\"messages\":[]"
                        + (stream ? ",\"stream\":true" : "") + "}"))
                .build();
    }

    @Test
    void unaryRequestIsRewrittenUpstreamAndRestoredDownstream() throws Exception {
        RoutingDecision decision = router.route(request(false));
        var result = registry.get(decision.providerId()).orElseThrow()
                .execute(request(false), registry.targetFor(decision));

        assertThat(upstreamModel.get()).isEqualTo(PROVIDER);
        byte[] body = ((ExecutionRuntime.RuntimeResult.Success) result).result();
        assertThat(json.readTree(body).path("model").asText()).isEqualTo(LOGICAL);
        assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain(PROVIDER);
    }

    @Test
    void everyStreamChunkCarriesTheLogicalId() throws Exception {
        RoutingDecision decision = router.route(request(true));
        List<String> frames = new ArrayList<>();
        ((StreamingExecutionRuntime) registry.get(decision.providerId()).orElseThrow())
                .executeStreaming(request(true), registry.targetFor(decision),
                        f -> frames.add(new String(f, StandardCharsets.UTF_8)));

        assertThat(upstreamModel.get()).isEqualTo(PROVIDER);
        assertThat(frames).isNotEmpty().noneMatch(f -> f.contains(PROVIDER));
        assertThat(frames.stream().filter(f -> f.startsWith("data: {")))
                .hasSize(2)
                .allMatch(f -> f.contains("\"model\":\"" + LOGICAL + "\""));
    }
}
