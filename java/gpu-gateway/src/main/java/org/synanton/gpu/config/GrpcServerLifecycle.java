package org.synanton.gpu.config;

import org.synanton.gpu.adapter.in.grpc.GpuExecutionGrpcAdapter;
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

    public GrpcServerLifecycle(GpuGatewayProperties properties,
                                GpuExecutionGrpcAdapter executionAdapter) {
        this.properties = properties;
        this.executionAdapter = executionAdapter;
    }

    @Override
    public void start() {
        try {
            server = NettyServerBuilder
                    .forPort(properties.getGrpcPort())
                    .maxInboundMessageSize(properties.getMaxInboundMessageSizeBytes())
                    .addService(executionAdapter)
                    .build()
                    .start();
            log.info("gRPC server started on port {}", server.getPort());
            // gRPC-Netty threads are daemon threads and this service has no servlet
            // container, so without a non-daemon waiter the JVM exits right after
            // startup (observed in the GPU-7 compose run). Block a non-daemon thread
            // until the server terminates; stop() shuts the server down, releasing it.
            Thread awaiter = new Thread(() -> {
                try {
                    server.awaitTermination();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "grpc-server-awaiter");
            awaiter.setDaemon(false);
            awaiter.start();
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
