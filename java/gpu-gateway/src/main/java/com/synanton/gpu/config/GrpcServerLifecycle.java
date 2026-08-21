package com.synanton.gpu.config;

import com.synanton.gpu.adapter.in.grpc.GpuCapacityGrpcAdapter;
import com.synanton.gpu.adapter.in.grpc.GpuExecutionGrpcAdapter;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Manages the gRPC server lifecycle as a Spring-managed component. */
@Component
@Slf4j
public class GrpcServerLifecycle implements SmartLifecycle {

    private volatile Server server;

    private final GpuGatewayProperties properties;
    private final GpuExecutionGrpcAdapter executionAdapter;
    private final GpuCapacityGrpcAdapter capacityAdapter;

    public GrpcServerLifecycle(GpuGatewayProperties properties,
                                GpuExecutionGrpcAdapter executionAdapter,
                                GpuCapacityGrpcAdapter capacityAdapter) {
        this.properties = properties;
        this.executionAdapter = executionAdapter;
        this.capacityAdapter = capacityAdapter;
    }

    @Override
    public void start() {
        try {
            server = NettyServerBuilder
                    .forPort(properties.getGrpcPort())
                    .maxInboundMessageSize(properties.getMaxInboundMessageSizeBytes())
                    .addService(executionAdapter)
                    .addService(capacityAdapter)
                    .build()
                    .start();
            log.info("gRPC server started on port {}", server.getPort());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to start gRPC server on port " + properties.getGrpcPort(), e);
        }
    }

    /** Actual bound port (ephemeral when {@code grpc-port} is 0). */
    public int getBoundPort() {
        if (server == null) {
            throw new IllegalStateException("gRPC server has not started");
        }
        return server.getPort();
    }

    @Override
    public void stop() {
        if (server != null && !server.isShutdown()) {
            log.info("Shutting down gRPC server");
            server.shutdown();
            try {
                if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
