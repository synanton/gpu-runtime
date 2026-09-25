package org.synanton.gpu.adapter.out.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.synanton.gpu.domain.model.ExecutionError;
import org.synanton.gpu.domain.model.ExecutionUsage;
import org.synanton.gpu.domain.model.RetryDisposition;
import org.synanton.gpu.domain.model.RoutingDecision;
import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.domain.port.out.StreamingExecutionRuntime;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Generic OpenAI-compatible provider runtime (PR #15 review P0.3/P1.1/P1.2, §5).
 * One parameterized implementation serves every configured provider —
 * {@code openai}, {@code mock}, future providers — replacing the hard-coded
 * openai special case. OpenAi's extra attribution headers are supplied
 * via {@code extraHeaders} by {@link ProviderRuntimeRegistry}.
 *
 * <p>Contract guarantees:
 * <ul>
 *   <li><b>P1.1 model rewriting:</b> the outbound payload's {@code model} is the
 *       <i>provider</i> model ID; every downstream body (unary response and every
 *       SSE chunk) carries the <i>logical</i> model ID. The provider model ID never
 *       leaks downstream.</li>
 *   <li><b>P1.2 streaming:</b> SSE frames are forwarded as they arrive; usage-bearing
 *       terminal chunks are preserved; {@code data: [DONE]} is emitted exactly once.</li>
 *   <li><b>Canonical errors:</b> provider 5xx → {@code upstream_provider_error},
 *       timeout → {@code provider_timeout}, connect failure → {@code provider_unavailable},
 *       auth → {@code provider_auth_failed}, 4xx capability gaps →
 *       {@code capability_not_supported}, open circuit → {@code circuit_open}
 *       (denied <i>without</i> a provider call).</li>
 *   <li><b>§20:</b> request/response bodies are never logged.</li>
 * </ul>
 */
@Slf4j
public class OpenAiProviderRuntime implements StreamingExecutionRuntime {

    private final String providerId;
    private final String baseUrl;
    private final String apiKey;
    private final Map<String, String> extraHeaders;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final HttpClient httpClient;

    public OpenAiProviderRuntime(String providerId,
                                 String baseUrl,
                                 String apiKey,
                                 Map<String, String> extraHeaders,
                                 Duration requestTimeout,
                                 CircuitBreaker circuitBreaker,
                                 ObjectMapper objectMapper) {
        this.providerId = providerId;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.extraHeaders = extraHeaders != null ? extraHeaders : Map.of();
        this.requestTimeout = requestTimeout;
        this.circuitBreaker = circuitBreaker;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ─── ExecutionRuntime (unary) ────────────────────────────────────────────

    @Override
    public RuntimeResult execute(ExecutionRequest request, RuntimeTarget target) {
        long startMs = System.currentTimeMillis();
        try {
            byte[] payload = rewritePayloadForProvider(request, target, false);
            HttpRequest httpRequest = buildRequest(target, request.getOperation(), payload, false);

            circuitBreaker.beforeCall();
            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long durationMs = System.currentTimeMillis() - startMs;

            return parseUnaryResponse(response, durationMs, request);

        } catch (CircuitBreaker.CircuitOpenException e) {
            return failure("circuit_open", "provider '" + providerId + "' circuit is open",
                    false, RetryDisposition.NOT_ACCEPTED);
        } catch (ConnectException e) {
            circuitBreaker.onFailure();
            return failure("provider_unavailable", "provider connect failed", true,
                    RetryDisposition.NOT_ACCEPTED);
        } catch (HttpTimeoutException e) {
            circuitBreaker.onFailure();
            return failure("provider_timeout", "provider request timed out", false,
                    RetryDisposition.ACCEPTED_UNKNOWN);
        } catch (IOException e) {
            circuitBreaker.onFailure();
            return failure("provider_unavailable", "provider IO error", false,
                    RetryDisposition.ACCEPTED_UNKNOWN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("runtime_failed", "interrupted", false, RetryDisposition.ACCEPTED_UNKNOWN);
        } catch (ModelRewriteException e) {
            return failure("invalid_request", e.getMessage(), false, RetryDisposition.DEFINITELY_FAILED);
        }
    }

    // ─── StreamingExecutionRuntime ───────────────────────────────────────────

    @Override
    public RuntimeResult executeStreaming(ExecutionRequest request,
                                          RuntimeTarget target,
                                          StreamChunkSink sink) {
        long startMs = System.currentTimeMillis();
        try {
            byte[] payload = rewritePayloadForProvider(request, target, true);
            HttpRequest httpRequest = buildRequest(target, request.getOperation(), payload, true);

            circuitBreaker.beforeCall();
            HttpResponse<InputStream> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                // No frame has been emitted: safe to fail like a unary call.
                return parseUnaryResponse(readDiscarding(response), System.currentTimeMillis() - startMs, request);
            }

            return relayStream(response, request, sink, startMs);

        } catch (CircuitBreaker.CircuitOpenException e) {
            return failure("circuit_open", "provider '" + providerId + "' circuit is open",
                    false, RetryDisposition.NOT_ACCEPTED);
        } catch (ConnectException e) {
            circuitBreaker.onFailure();
            return failure("provider_unavailable", "provider connect failed", true,
                    RetryDisposition.NOT_ACCEPTED);
        } catch (HttpTimeoutException e) {
            circuitBreaker.onFailure();
            return failure("provider_timeout", "provider stream timed out", false,
                    RetryDisposition.ACCEPTED_UNKNOWN);
        } catch (IOException e) {
            circuitBreaker.onFailure();
            return failure("provider_unavailable", "provider IO error", false,
                    RetryDisposition.ACCEPTED_UNKNOWN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("runtime_failed", "interrupted", false, RetryDisposition.ACCEPTED_UNKNOWN);
        } catch (ModelRewriteException e) {
            return failure("invalid_request", e.getMessage(), false, RetryDisposition.DEFINITELY_FAILED);
        }
    }

    private RuntimeResult relayStream(HttpResponse<InputStream> response,
                                      ExecutionRequest request,
                                      StreamChunkSink sink,
                                      long startMs) throws IOException {
        String logicalModelId = request.getModel();
        ExecutionUsage usage = null;
        boolean sawDone = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue; // comments/keep-alives are not forwarded
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    if (!sawDone) {
                        sawDone = true;
                        sink.onChunk("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    }
                    continue; // suppress duplicate [DONE] frames (§10: exactly once)
                }
                JsonNode chunk;
                try {
                    chunk = objectMapper.readTree(data);
                } catch (Exception e) {
                    log.warn("provider {} sent a non-JSON SSE frame; dropping it", providerId);
                    continue;
                }
                if (chunk.has("usage") && !chunk.path("usage").isNull()) {
                    usage = extractUsage(chunk, System.currentTimeMillis() - startMs);
                }
                restoreLogicalModelId(chunk, logicalModelId);
                sink.onChunk(("data: " + objectMapper.writeValueAsString(chunk) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        }

        if (!sawDone) {
            // §10: the terminal frame is the contract; a stream without it broke mid-flight.
            circuitBreaker.onFailure();
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("upstream_provider_error",
                            "provider stream ended without [DONE]"),
                    RetryDisposition.COMPLETED_UNKNOWN);
        }
        circuitBreaker.onSuccess();
        long durationMs = System.currentTimeMillis() - startMs;
        return new RuntimeResult.Success(
                usage != null ? usage
                        : new ExecutionUsage(0, 0, durationMs / 1000.0, providerId),
                new byte[0]);
    }

    // ─── cancel / ping ───────────────────────────────────────────────────────

    @Override
    public CancellationResult cancel(String executionId, RuntimeTarget target) {
        log.info("Cancel requested for execution_id={} — provider {} cancellation not supported",
                executionId, providerId);
        return new CancellationResult.NotFound();
    }

    @Override
    public RuntimeStatus ping(String executionId, RuntimeTarget target) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(target.endpointUrl() + "/models"))
                    .GET()
                    .timeout(Duration.ofSeconds(5));
            if (apiKey != null && !apiKey.isBlank()) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? RuntimeStatus.ALIVE : RuntimeStatus.UNAVAILABLE;
        } catch (Exception e) {
            return RuntimeStatus.UNAVAILABLE;
        }
    }

    // ─── internals ───────────────────────────────────────────────────────────

    private HttpRequest buildRequest(RuntimeTarget target, Operation operation,
                                     byte[] payload, boolean streaming) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(resolveEndpoint(target.endpointUrl(), operation)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .timeout(requestTimeout);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        extraHeaders.forEach(builder::header);
        if (streaming) {
            builder.header("Accept", "text/event-stream");
        }
        return builder.build();
    }

    private String resolveEndpoint(String targetEndpoint, Operation operation) {
        String base = targetEndpoint != null && !targetEndpoint.isBlank() ? targetEndpoint : baseUrl;
        return switch (operation) {
            case SYNTHESIZE -> base + "/chat/completions";
            case EMBED      -> base + "/embeddings";
            case RERANK     -> base + "/rerank";
            default         -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
    }

    /** Outbound: logical → provider model ID; optionally force {@code stream: true}. */
    private byte[] rewritePayloadForProvider(ExecutionRequest request, RuntimeTarget target,
                                             boolean streaming) {
        try {
            JsonNode payload = objectMapper.readTree(request.getPayload().toByteArray());
            if (!payload.isObject()) {
                throw new ModelRewriteException("payload must be a JSON object");
            }
            ObjectNode object = (ObjectNode) payload;
            // P1.1: the provider always sees the provider model ID from the routing
            // decision (RuntimeTarget.providerModelId), never the logical ID.
            object.put("model", providerModelIdOf(request, target));
            if (streaming) {
                object.put("stream", true); // never clobber stream_options (e.g. include_usage)
            }
            return objectMapper.writeValueAsBytes(object);
        } catch (ModelRewriteException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelRewriteException("payload is not valid JSON");
        }
    }

    private String providerModelIdOf(ExecutionRequest request, RuntimeTarget target) {
        return target.providerModelId() != null ? target.providerModelId() : request.getModel();
    }

    /** Inbound (unary or SSE chunk): provider → logical model ID. */
    private void restoreLogicalModelId(JsonNode body, String logicalModelId) {
        if (body instanceof ObjectNode object && object.has("model")) {
            object.put("model", logicalModelId);
        }
    }

    private RuntimeResult parseUnaryResponse(HttpResponse<String> response,
                                             long durationMs,
                                             ExecutionRequest request) {
        int status = response.statusCode();

        if (status == 401 || status == 403) {
            // auth failures are config faults, not provider health; don't trip the circuit
            return failure("provider_auth_failed", "provider rejected credentials (HTTP " + status + ")",
                    false, RetryDisposition.DEFINITELY_FAILED);
        }
        if (status == 429) {
            circuitBreaker.onFailure();
            return failure("provider_rate_limited", "provider rate limited the request",
                    true, RetryDisposition.NOT_ACCEPTED);
        }
        if (status >= 400 && status < 500) {
            circuitBreaker.onSuccess();
            if (status == 400 && indicatesCapabilityGap(response.body())) {
                return failure("capability_not_supported",
                        "provider does not support this operation (§40)",
                        false, RetryDisposition.DEFINITELY_FAILED);
            }
            return failure("invalid_request", "provider client error HTTP " + status,
                    false, RetryDisposition.DEFINITELY_FAILED);
        }
        if (status >= 500) {
            circuitBreaker.onFailure();
            return failure("upstream_provider_error", "provider server error HTTP " + status,
                    status == 502 || status == 503 || status == 504,
                    status == 502 || status == 503 || status == 504
                            ? RetryDisposition.NOT_ACCEPTED : RetryDisposition.DEFINITELY_FAILED);
        }

        try {
            JsonNode body = objectMapper.readTree(response.body());
            if (body.has("error")) {
                circuitBreaker.onSuccess(); // a well-formed provider-level error is not a health signal
                return failure("upstream_provider_error",
                        "provider returned an error object", false, RetryDisposition.DEFINITELY_FAILED);
            }
            circuitBreaker.onSuccess();
            restoreLogicalModelId(body, request.getModel());
            ExecutionUsage usage = extractUsage(body, durationMs);
            return new RuntimeResult.Success(usage, objectMapper.writeValueAsBytes(body));
        } catch (Exception e) {
            return failure("upstream_provider_error", "unparseable provider response",
                    false, RetryDisposition.COMPLETED_UNKNOWN);
        }
    }

    private ExecutionUsage extractUsage(JsonNode body, long durationMs) {
        JsonNode usage = body.path("usage");
        long inputTokens = usage.path("prompt_tokens").asLong(0);
        long outputTokens = usage.path("completion_tokens").asLong(0);
        return new ExecutionUsage(inputTokens, outputTokens, durationMs / 1000.0, providerId);
    }

    private boolean indicatesCapabilityGap(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase();
        return lower.contains("not support") || lower.contains("unsupported")
                || lower.contains("capability");
    }

    private HttpResponse<String> readDiscarding(HttpResponse<InputStream> response) throws IOException {
        byte[] body = response.body().readAllBytes();
        return new StringifyingResponse<>(response, new String(body, StandardCharsets.UTF_8));
    }

    private RuntimeResult failure(String code, String message, boolean retryable,
                                  RetryDisposition disposition) {
        return new RuntimeResult.Failure(
                ExecutionError.of(code, message, retryable), disposition);
    }

    @SuppressWarnings("serial")
    private static class ModelRewriteException extends RuntimeException {
        ModelRewriteException(String message) {
            super(message);
        }
    }

    /** Minimal HttpResponse adapter to reuse {@link #parseUnaryResponse} for failed streams. */
    private record StringifyingResponse<T>(HttpResponse<T> delegate, String body)
            implements HttpResponse<String> {
        @Override public int statusCode() { return delegate.statusCode(); }
        @Override public HttpRequest request() { return delegate.request(); }
        @Override public java.util.Optional<HttpResponse<String>> previousResponse() {
            return java.util.Optional.empty();
        }
        @Override public java.net.http.HttpHeaders headers() { return delegate.headers(); }
        @Override public String body() { return body; }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }
        @Override public URI uri() { return delegate.uri(); }
        @Override public java.net.http.HttpClient.Version version() { return delegate.version(); }
    }
}
