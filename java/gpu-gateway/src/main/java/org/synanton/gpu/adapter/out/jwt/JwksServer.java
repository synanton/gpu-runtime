package org.synanton.gpu.adapter.out.jwt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves the GPU-5 execution-JWT JWKS on a dedicated internal port (Deployment Plan §12;
 * GPU-5 plan §13.3 J1). It is separate from actuator (:8091) and the gRPC API (:9090).
 *
 * <ul>
 *   <li>{@code GET /internal/.well-known/jwks.json}: both public keys (current + previous),
 *       {@code Cache-Control: max-age=300}, matching Envoy's 5-min JWKS cache;</li>
 *   <li>any other path: 404; any other method on that path: 405.</li>
 * </ul>
 *
 * <p>No authentication: the JWKS holds public keys only, and the NetworkPolicy admits Envoy
 * alone on this port. It starts early in the Spring lifecycle, so the readiness indicator,
 * and with it Gateway readiness, only turns UP once it listens (Gateway readiness precedes Envoy's).
 */
@Slf4j
public class JwksServer implements SmartLifecycle {

    public static final String PATH = "/internal/.well-known/jwks.json";

    private final int port;
    private final byte[] body;
    private volatile HttpServer server;
    private volatile ExecutorService executor;

    public JwksServer(int port, ExecutionJwtKeys keys) {
        this.port = port;
        this.body = keys.jwksJson().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(port), 16);
            s.createContext("/", this::handle);
            executor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "jwks-http");
                t.setDaemon(true);
                return t;
            });
            s.setExecutor(executor);
            s.start();
            server = s;
            log.info("JWKS listener started on port {} ({})", boundPort(), PATH);
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind the JWKS listener on port " + port
                    + " (gpu-gateway.execution-jwt.jwks-port)", e);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!PATH.equals(ex.getRequestURI().getPath())) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().add("Allow", "GET");
                ex.sendResponseHeaders(405, -1);
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.getResponseHeaders().add("Cache-Control", "public, max-age=300");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    @Override
    public void stop() {
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    /** Start before the gRPC server (default phase), so JWKS is up before traffic arrives. */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 1000;
    }

    public int boundPort() {
        HttpServer s = server;
        return s == null ? -1 : s.getAddress().getPort();
    }
}
