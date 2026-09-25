package org.synanton.gpu.adapter.out.runtime;

import org.synanton.gpu.config.GpuGatewayProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** T-K8S-46: provider health tracked independently; unhealthy is reported, healthy restores. */
class ProviderHealthMonitorTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private GpuGatewayProperties.ProviderConfig config;
    private ProviderHealthMonitor monitor;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> {
            ex.sendResponseHeaders(status.get(), -1);
            ex.close();
        });
        server.start();
        GpuGatewayProperties p = new GpuGatewayProperties();
        config = new GpuGatewayProperties.ProviderConfig();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setApiKey("k");
        config.getHealth().setPath("/models");
        p.getProviders().put("mock", config);
        monitor = new ProviderHealthMonitor(p);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void failedProbeMarksUnhealthyAndSuccessRestores() {
        assertThat(monitor.probe("mock", config)).isTrue();
        assertThat(monitor.isHealthy("mock")).isTrue();

        status.set(500);
        monitor.probe("mock", config);
        assertThat(monitor.isHealthy("mock")).as("one transient miss is tolerated").isTrue();
        monitor.probe("mock", config);
        assertThat(monitor.isHealthy("mock")).as("threshold (2) consecutive misses → unhealthy").isFalse();

        status.set(200);
        monitor.probe("mock", config);
        assertThat(monitor.isHealthy("mock")).isTrue();
    }

    @Test
    void unprobedProvidersAreNotReportedUnhealthy() {
        assertThat(monitor.isHealthy("never-probed")).isTrue();
    }
}
