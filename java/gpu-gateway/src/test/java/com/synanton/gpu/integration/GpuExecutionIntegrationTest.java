package com.synanton.gpu.integration;

import com.google.protobuf.ByteString;
import com.synanton.gpu.domain.port.out.ExecutionRepository;
import com.synanton.gpu.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test: full Spring context + real PostgreSQL via Testcontainers + in-process gRPC.
 * Uses StubExecutionRuntime, so no live vLLM required.
 *
 * <p>Requires Docker (Colima or Docker Desktop). Run manually with Docker available.
 */
//@org.junit.jupiter.api.Disabled("Requires Docker — run manually with Colima/Docker Desktop")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "gpu-gateway.grpc-port=0",
                "gpu-gateway.dispatch.strategy=stub",
                "gpu-gateway.models.test-model.concurrency-limit=8",
                "gpu-gateway.models.test-model.max-input-tokens=4096",
                "gpu-gateway.models.test-model.runtime-class=vllm-test",
                "spring.flyway.enabled=true"
        }
)
@Testcontainers
class GpuExecutionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("gpu_gateway_test")
            .withUsername("synanton")
            .withPassword("synanton");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private com.synanton.gpu.config.GrpcServerLifecycle grpcServerLifecycle;

    private ManagedChannel channel;
    private GpuExecutionServiceGrpc.GpuExecutionServiceBlockingStub executionStub;
    private GpuCapacityServiceGrpc.GpuCapacityServiceBlockingStub capacityStub;

    @BeforeEach
    void setUp() {
        int grpcPort = grpcServerLifecycle.getBoundPort();
        channel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        executionStub = GpuExecutionServiceGrpc.newBlockingStub(channel);
        capacityStub = GpuCapacityServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdownNow();
        }
    }

    @Test
    void shouldCreateExecutionAndPersistItToPostgres() {
        String requestId = "req-" + UUID.randomUUID();
        ExecutionRequest request = buildRequest(requestId, "test-model");

        ExecutionResponse response = executionStub.execute(request);

        assertThat(response.getRequestId()).isEqualTo(requestId);
        assertThat(response.getExecutionId()).isNotBlank();
        // StubRuntime returns RUNTIME_UNAVAILABLE → FAILED
        assertThat(response.getState()).isIn(ExecutionState.FAILED, ExecutionState.SUCCEEDED);

        // Verify the execution was persisted in PostgreSQL
        assertThat(executionRepository.findByExecutionId(response.getExecutionId())).isPresent();
    }

    @Test
    void shouldReturnSameExecutionIdForDuplicateRequestId() {
        String requestId = "req-idempotent-" + UUID.randomUUID();
        ExecutionRequest request = buildRequest(requestId, "test-model");

        ExecutionResponse response1 = executionStub.execute(request);
        ExecutionResponse response2 = executionStub.execute(request);

        assertThat(response1.getExecutionId()).isEqualTo(response2.getExecutionId());
        assertThat(response1.getRequestId()).isEqualTo(requestId);
    }

    @Test
    void shouldReturnNotFoundForUnknownExecutionId() {
        StatusRequest statusRequest = StatusRequest.newBuilder()
                .setExecutionId("nonexistent-exec-id")
                .build();

        assertThatThrownBy(() -> executionStub.getStatus(statusRequest))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("NOT_FOUND");
    }

    @Test
    void shouldReturnAdvisoryCapacityForKnownModel() {
        CapacityRequest request = CapacityRequest.newBuilder()
                .setModelId("test-model")
                .build();

        CapacityResponse response = capacityStub.getCapacity(request);

        assertThat(response.getModelId()).isEqualTo("test-model");
        assertThat(response.getRuntimeClass()).isEqualTo("vllm-test");
    }

    @Test
    void shouldRejectExecutionForMissingRequestId() {
        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setTenantId("tenant")
                .setModelId("test-model")
                .setOptions(ExecutionOptions.newBuilder().setOperation(Operation.SYNTHESIZE).build())
                .build();

        assertThatThrownBy(() -> executionStub.execute(request))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("INVALID_ARGUMENT");
    }

    private ExecutionRequest buildRequest(String requestId, String modelId) {
        return ExecutionRequest.newBuilder()
                .setRequestId(requestId)
                .setTenantId("integration-tenant")
                .setModelId(modelId)
                .setOptions(ExecutionOptions.newBuilder()
                        .setOperation(Operation.SYNTHESIZE)
                        .setMaxTokens(256)
                        .build())
                .setPayload(ByteString.copyFromUtf8("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
                .build();
    }
}
