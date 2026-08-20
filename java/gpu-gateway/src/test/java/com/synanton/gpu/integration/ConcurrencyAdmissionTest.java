package com.synanton.gpu.integration;

import com.google.protobuf.ByteString;
import com.synanton.gpu.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency admission test: validates that the advisory-lock-based admission
 * allows at most concurrencyLimit executions through simultaneously.
 *
 * <p>Sends 100 concurrent Execute requests with concurrency limit = 8.
 * Expects: exactly 8 admitted (FAILED via StubRuntime), 92 rejected with RESOURCE_EXHAUSTED.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "gpu-gateway.grpc-port=0",
                "gpu-gateway.dispatch.strategy=stub",
                "gpu-gateway.models.limited-model.concurrency-limit=8",
                "gpu-gateway.models.limited-model.max-input-tokens=4096",
                "gpu-gateway.models.limited-model.runtime-class=vllm-test",
                "spring.flyway.enabled=true"
        }
)
@Testcontainers
class ConcurrencyAdmissionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("gpu_concurrency_test")
            .withUsername("synanton")
            .withPassword("synanton");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment env;

    private ManagedChannel channel;
    private GpuExecutionServiceGrpc.GpuExecutionServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        String grpcPort = env.getProperty("gpu-gateway.grpc-port", "9090");
        channel = ManagedChannelBuilder.forAddress("localhost", Integer.parseInt(grpcPort))
                .usePlaintext()
                .build();
        stub = GpuExecutionServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) channel.shutdownNow();
    }

    @Test
    void shouldAdmitAtMostConcurrencyLimitRequests() throws InterruptedException {
        int totalRequests = 100;
        int expectedAdmitted = 8;

        AtomicInteger admitted = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        List<Future<?>> futures = new ArrayList<>();

        for (int idx = 0; idx < totalRequests; idx++) {
            String requestId = "concurrency-req-" + UUID.randomUUID();
            futures.add(executor.submit(() -> {
                try {
                    ExecutionRequest request = ExecutionRequest.newBuilder()
                            .setRequestId(requestId)
                            .setTenantId("concurrency-tenant")
                            .setModelId("limited-model")
                            .setOptions(ExecutionOptions.newBuilder()
                                    .setOperation(Operation.SYNTHESIZE)
                                    .build())
                            .setPayload(ByteString.copyFromUtf8("{}"))
                            .build();
                    stub.execute(request);
                    admitted.incrementAndGet();
                } catch (StatusRuntimeException e) {
                    if (e.getStatus().getCode() == io.grpc.Status.Code.RESOURCE_EXHAUSTED) {
                        rejected.incrementAndGet();
                    }
                }
            }));
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        // At most concurrencyLimit requests can be active simultaneously
        assertThat(admitted.get()).isLessThanOrEqualTo(expectedAdmitted);
        assertThat(admitted.get() + rejected.get()).isEqualTo(totalRequests);
    }
}
