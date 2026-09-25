package org.synanton.gpu.adapter.in.grpc;

import org.synanton.gpu.domain.model.Execution;
import org.synanton.gpu.domain.model.ExecutionState;
import org.synanton.gpu.domain.model.ExecutionUsage;
import org.synanton.gpu.domain.port.in.*;
import org.synanton.gpu.domain.service.RoutingDeniedException;
import org.synanton.gpu.v1.ExecutionChunk;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.GPUExecutionServiceGrpc;
import org.synanton.gpu.v1.Operation;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Deployment Plan §10.1 over a real gRPC channel: data chunks, then exactly one terminal. */
class ExecuteStreamGrpcTest {

    private final ExecuteUseCase execute = mock(ExecuteUseCase.class);
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new GpuExecutionGrpcAdapter(execute, mock(CancelUseCase.class),
                        mock(GetStatusUseCase.class), mock(GetCapacityUseCase.class),
                        mock(GetModelsUseCase.class), new ResponseMapper(),
                        new CallerAuthorization(plaintextProperties()),
                        mock(org.synanton.gpu.domain.service.ResponsesService.class)))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static org.synanton.gpu.config.GpuGatewayProperties plaintextProperties() {
        var p = new org.synanton.gpu.config.GpuGatewayProperties();
        p.getSecurity().setMode("insecure-plaintext");
        return p;
    }

    private static ExecutionRequest request() {
        return ExecutionRequest.newBuilder().setRequestId("req-s").setTenantId("t").setModel("m")
                .setModelVersion("1").setOperation(Operation.SYNTHESIZE).build();
    }

    @Test
    void streamsDataChunksThenExactlyOneTerminal() {
        when(execute.executeStream(any(), any())).thenAnswer(inv -> {
            ExecuteUseCase.StreamListener l = inv.getArgument(1);
            l.onAdmitted("exec-1");
            l.onChunk("data: {\"model\":\"m\",\"n\":1}\n\n".getBytes(StandardCharsets.UTF_8));
            l.onChunk("data: {\"model\":\"m\",\"n\":2}\n\n".getBytes(StandardCharsets.UTF_8));
            l.onChunk("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            return new Execution("exec-1", "req-s", "h", "t", "m", ExecutionState.SUCCEEDED, "mock",
                    Instant.now(), Instant.now(), null, null, new ExecutionUsage(5, 2, 0.1, "mock"), null, new byte[0]);
        });

        List<ExecutionChunk> chunks = new ArrayList<>();
        GPUExecutionServiceGrpc.newBlockingStub(channel).executeStream(request()).forEachRemaining(chunks::add);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.subList(0, 2)).allSatisfy(c -> {
            assertThat(c.hasData()).isTrue();
            assertThat(c.getExecutionId()).isEqualTo("exec-1");
            assertThat(c.getData().toStringUtf8()).startsWith("{").doesNotContain("data:").doesNotContain("[DONE]");
        });
        ExecutionChunk terminal = chunks.get(2);
        assertThat(terminal.hasTerminal()).isTrue();
        assertThat(terminal.getTerminal().getState()).isEqualTo(org.synanton.gpu.v1.ExecutionState.SUCCESS);
        assertThat(terminal.getTerminal().getUsage().getInputTokens()).isEqualTo(5);
    }

    @Test
    void denialFailsTheStreamWithCanonicalCode() {
        when(execute.executeStream(any(), any()))
                .thenThrow(new RoutingDeniedException("capability_not_supported", "EMBED cannot stream"));

        assertThatThrownBy(() -> GPUExecutionServiceGrpc.newBlockingStub(channel)
                .executeStream(request()).forEachRemaining(c -> { }))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
                    assertThat(e.getTrailers().get(GpuExecutionGrpcAdapter.ERROR_CODE_KEY))
                            .isEqualTo("capability_not_supported");
                });
    }
}
