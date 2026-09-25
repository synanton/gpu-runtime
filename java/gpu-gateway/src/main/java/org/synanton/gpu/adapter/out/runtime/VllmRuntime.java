package org.synanton.gpu.adapter.out.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.gpu.domain.model.ExecutionError;
import org.synanton.gpu.domain.model.ExecutionUsage;
import org.synanton.gpu.domain.model.RetryDisposition;
import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.domain.port.out.StreamingExecutionRuntime;
import org.synanton.gpu.domain.service.HeartbeatManager;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * vLLM HTTP runtime adapter. Active when {@code gpu-gateway.dispatch.strategy=vllm}.
 *
 * <p>Routes requests to the appropriate vLLM endpoint by operation:
 * <ul>
 *   <li>SYNTHESIZE → POST /v1/chat/completions</li>
 *   <li>EMBED      → POST /v1/embeddings</li>
 *   <li>RERANK     → POST /v1/rerank</li>
 * </ul>
 *
 * <p>{@link RetryDisposition} classification rules (HTTP-status based, not exception-based):
 * <ul>
 *   <li>ConnectException before sending → NOT_ACCEPTED</li>
 *   <li>4xx client error → DEFINITELY_FAILED</li>
 *   <li>5xx server error (502/503/504 before body) → NOT_ACCEPTED</li>
 *   <li>HttpTimeoutException → ACCEPTED_UNKNOWN (request may have completed)</li>
 *   <li>IOException after headers sent → ACCEPTED_UNKNOWN</li>
 *   <li>200 with vLLM error field → DEFINITELY_FAILED</li>
 * </ul>
 */
@Component
@Slf4j
public class VllmRuntime implements StreamingExecutionRuntime {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final HeartbeatManager heartbeatManager;
    private final Duration requestTimeout;

    public VllmRuntime(ObjectMapper objectMapper,
                       HeartbeatManager heartbeatManager,
                       Duration dispatchTimeout) {
        this.objectMapper = objectMapper;
        this.heartbeatManager = heartbeatManager;
        this.requestTimeout = dispatchTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public RuntimeResult execute(ExecutionRequest request, RuntimeTarget target) {
        String endpoint = resolveEndpoint(target.endpointUrl(), request.getOperation());
        String executionId = request.getRequestId(); // used for heartbeat key; overridden at call site

        log.info("vLLM dispatch: operation={} endpoint={}", request.getOperation(), endpoint);

        HeartbeatManager.HeartbeatHandle heartbeat = heartbeatManager.start(executionId);
        long startMs = System.currentTimeMillis();

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request.getPayload().toByteArray()))
                    .timeout(requestTimeout)
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            long durationMs = System.currentTimeMillis() - startMs;
            return parseResponse(response, durationMs, target.runtimeClass());

        } catch (ConnectException e) {
            log.warn("vLLM connect failed: {}", e.getMessage());
            return new RuntimeResult.Failure(
                    ExecutionError.retryable("RUNTIME_UNAVAILABLE", "vLLM connection refused: " + e.getMessage()),
                    RetryDisposition.NOT_ACCEPTED);

        } catch (HttpTimeoutException e) {
            log.warn("vLLM request timed out after {}ms", System.currentTimeMillis() - startMs);
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("RUNTIME_TIMEOUT", "vLLM request timed out"),
                    RetryDisposition.ACCEPTED_UNKNOWN);

        } catch (IOException e) {
            log.warn("vLLM IO error (request may have been received): {}", e.getMessage());
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("RUNTIME_FAILED", "IO error: " + e.getMessage()),
                    RetryDisposition.ACCEPTED_UNKNOWN);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("RUNTIME_FAILED", "Interrupted"),
                    RetryDisposition.ACCEPTED_UNKNOWN);
        } finally {
            heartbeat.stop();
        }
    }

    /**
     * GPU-5 streaming (P1.2): vLLM serves OpenAI-compatible SSE. The served model name
     * is the logical ID, so no rewrite is needed; the shared {@link SseRelay} enforces
     * the §10 framing and usage rules identically to the GPU-7 provider path.
     */
    @Override
    public RuntimeResult executeStreaming(ExecutionRequest request, RuntimeTarget target,
                                          StreamChunkSink sink) {
        long startMs = System.currentTimeMillis();
        try {
            JsonNode original = objectMapper.readTree(request.getPayload().toByteArray());
            if (!(original instanceof com.fasterxml.jackson.databind.node.ObjectNode payload)) {
                return new RuntimeResult.Failure(
                        ExecutionError.nonRetryable("invalid_request", "payload must be a JSON object"),
                        RetryDisposition.DEFINITELY_FAILED);
            }
            boolean clientWantsUsage = SseRelay.clientRequestedUsage(payload);
            SseRelay.requestStreamingWithUsage(payload);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(resolveEndpoint(target.endpointUrl(), request.getOperation())))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(payload)))
                    .timeout(requestTimeout)
                    .build();
            HttpResponse<java.util.stream.Stream<String>> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                try (var lines = response.body()) {
                    lines.forEach(l -> { }); // drain; body is not echoed (may contain prompt text)
                }
                return new RuntimeResult.Failure(
                        response.statusCode() >= 500
                                ? ExecutionError.retryable("RUNTIME_UNAVAILABLE", "vLLM HTTP " + response.statusCode())
                                : ExecutionError.nonRetryable("RUNTIME_FAILED", "vLLM HTTP " + response.statusCode()),
                        response.statusCode() >= 500 ? RetryDisposition.NOT_ACCEPTED : RetryDisposition.DEFINITELY_FAILED);
            }
            SseRelay.Outcome outcome;
            try (var lines = response.body()) {
                outcome = SseRelay.relay(lines, request.getModel(), clientWantsUsage,
                        objectMapper, sink, target.runtimeClass(), startMs, requestTimeout);
            }
            if (outcome.timedOut()) {
                return new RuntimeResult.Failure(
                        ExecutionError.nonRetryable("RUNTIME_TIMEOUT", "vLLM stream exceeded the deadline"),
                        RetryDisposition.ACCEPTED_UNKNOWN);
            }
            if (!outcome.sawDone()) {
                return new RuntimeResult.Failure(
                        ExecutionError.nonRetryable("RUNTIME_FAILED", "vLLM stream ended without [DONE]"),
                        RetryDisposition.COMPLETED_UNKNOWN);
            }
            return new RuntimeResult.Success(outcome.usage(), new byte[0]);
        } catch (ConnectException e) {
            return new RuntimeResult.Failure(
                    ExecutionError.retryable("RUNTIME_UNAVAILABLE", "vLLM connection refused"),
                    RetryDisposition.NOT_ACCEPTED);
        } catch (HttpTimeoutException e) {
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("RUNTIME_TIMEOUT", "vLLM stream timed out"),
                    RetryDisposition.ACCEPTED_UNKNOWN);
        } catch (IOException | java.io.UncheckedIOException e) {
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("RUNTIME_FAILED", "vLLM stream IO error"),
                    RetryDisposition.ACCEPTED_UNKNOWN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("RUNTIME_FAILED", "Interrupted"),
                    RetryDisposition.ACCEPTED_UNKNOWN);
        }
    }

    @Override
    public CancellationResult cancel(String executionId, RuntimeTarget target) {
        // vLLM does not expose a per-request cancellation endpoint in standard deployments.
        // Best-effort: log and report as not-applicable; the lease will expire naturally.
        log.info("Cancel requested for execution_id={} — vLLM cancellation not supported", executionId);
        return new CancellationResult.NotFound();
    }

    @Override
    public RuntimeStatus ping(String executionId, RuntimeTarget target) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(target.endpointUrl() + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? RuntimeStatus.ALIVE : RuntimeStatus.UNAVAILABLE;

        } catch (ConnectException e) {
            return RuntimeStatus.UNAVAILABLE;
        } catch (Exception e) {
            log.warn("Ping failed for execution_id={}: {}", executionId, e.getMessage());
            return RuntimeStatus.UNAVAILABLE;
        }
    }

    private RuntimeResult parseResponse(HttpResponse<String> response,
                                         long durationMs,
                                         String runtimeClass) {
        int status = response.statusCode();

        if (status >= 400 && status < 500) {
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("RUNTIME_FAILED",
                            "vLLM client error HTTP " + status + ": " + truncate(response.body())),
                    RetryDisposition.DEFINITELY_FAILED);
        }

        if (status == 503 || status == 502 || status == 504) {
            return new RuntimeResult.Failure(
                    ExecutionError.retryable("RUNTIME_UNAVAILABLE",
                            "vLLM unavailable HTTP " + status),
                    RetryDisposition.NOT_ACCEPTED);
        }

        if (status >= 500) {
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("RUNTIME_FAILED",
                            "vLLM server error HTTP " + status),
                    RetryDisposition.DEFINITELY_FAILED);
        }

        try {
            JsonNode body = objectMapper.readTree(response.body());

            // vLLM error field present in 200 response (rare)
            if (body.hasNonNull("error")) { // Responses objects always carry "error": null
                String errMsg = body.path("error").path("message").asText("unknown error");
                return new RuntimeResult.Failure(
                        ExecutionError.nonRetryable("RUNTIME_FAILED", "vLLM error: " + errMsg),
                        RetryDisposition.DEFINITELY_FAILED);
            }

            ExecutionUsage usage = extractUsage(body, durationMs, runtimeClass);
            return new RuntimeResult.Success(usage, response.body().getBytes());

        } catch (Exception e) {
            log.warn("Failed to parse vLLM response: {}", e.getMessage());
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("RUNTIME_FAILED", "Failed to parse vLLM response"),
                    RetryDisposition.COMPLETED_UNKNOWN);
        }
    }

    private ExecutionUsage extractUsage(JsonNode body, long durationMs, String runtimeClass) {
        JsonNode usage = body.path("usage");
        long inputTokens = usage.path("prompt_tokens").asLong(0);
        long outputTokens = usage.path("completion_tokens").asLong(0);
        return new ExecutionUsage(inputTokens, outputTokens, durationMs / 1000.0, runtimeClass);
    }

    private String resolveEndpoint(String baseUrl, Operation operation) {
        return switch (operation) {
            case SYNTHESIZE -> baseUrl + "/v1/chat/completions";
            case EMBED      -> baseUrl + "/v1/embeddings";
            case RERANK     -> baseUrl + "/v1/rerank";
            default         -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
    }

    private String truncate(String text) {
        return text != null && text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
