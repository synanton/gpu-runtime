package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.config.GpuGatewayProperties;
import org.synanton.gpu.domain.model.RoutingDecision;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.domain.service.ProviderRouter;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #15 P0.5: a catalog model with {@code provider: MOCK} resolves through
 * ProviderRouter → ProviderRuntimeRegistry to the mock runtime, which calls the mock
 * provider with the provider model ID, the mock's credentials and configured headers.
 */
class ProviderRuntimeRegistryTest {

    private HttpServer mockProvider;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<Headers> receivedHeaders = new AtomicReference<>();
    private GpuGatewayProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        mockProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockProvider.createContext("/v1/chat/completions", exchange -> {
            receivedHeaders.set(exchange.getRequestHeaders());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"model\":\"mock-chat-1\",\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        mockProvider.start();

        properties = new GpuGatewayProperties();
        properties.getDispatch().setStrategy("external");
        var info = new GpuGatewayProperties.ModelCatalog.OperationModels.ModelInfo();
        info.setProvider("MOCK");
        info.setProviderModelId("mock-chat-1");
        properties.getModelCatalog().getOperations()
                .computeIfAbsent("SYNTHESIZE", k -> new GpuGatewayProperties.ModelCatalog.OperationModels())
                .getModels().put("synanton-mock-chat", info);
        var mock = new GpuGatewayProperties.ProviderConfig();
        mock.setBaseUrl("http://127.0.0.1:" + mockProvider.getAddress().getPort() + "/v1");
        mock.setApiKey("mock-key");
        mock.getHeaders().put("X-Title", "Synanton GPU Gateway");
        properties.getProviders().put("mock", mock);
    }

    @AfterEach
    void tearDown() {
        mockProvider.stop(0);
    }

    @Test
    void mockCatalogModelResolvesToTheMockRuntime() throws Exception {
        ProviderRouter router = new ProviderRouter(properties, new ModelCatalogService(properties));
        ProviderRuntimeRegistry registry =
                new ProviderRuntimeRegistry(properties, Duration.ofSeconds(5), new ObjectMapper());
        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setRequestId("r1").setTenantId("t").setModel("synanton-mock-chat").setModelVersion("1")
                .setOperation(Operation.SYNTHESIZE)
                .setPayload(ByteString.copyFromUtf8("{\"model\":\"synanton-mock-chat\",\"messages\":[]}"))
                .build();

        RoutingDecision decision = router.route(request);
        ExecutionRuntime runtime = registry.get(decision.providerId()).orElseThrow();
        ExecutionRuntime.RuntimeResult result = runtime.execute(request, registry.targetFor(decision));

        assertThat(decision.providerId()).isEqualTo("mock");
        assertThat(result).isInstanceOf(ExecutionRuntime.RuntimeResult.Success.class);
        assertThat(new ObjectMapper().readTree(receivedBody.get()).path("model").asText()).isEqualTo("mock-chat-1");
        assertThat(receivedHeaders.get().getFirst("Authorization")).isEqualTo("Bearer mock-key");
        assertThat(receivedHeaders.get().getFirst("X-Title")).isEqualTo("Synanton GPU Gateway");
    }

    @Test
    void disabledOrUnknownProvidersHaveNoRuntime() {
        properties.getProviders().get("mock").setEnabled(false);
        ProviderRuntimeRegistry registry =
                new ProviderRuntimeRegistry(properties, Duration.ofSeconds(5), new ObjectMapper());

        assertThat(registry.get("mock")).isEmpty();
        assertThat(registry.get("nope")).isEmpty();
    }
}
