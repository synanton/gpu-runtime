package org.synanton.gpu.adapter.out.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.gpu.domain.model.ExecutionError;
import org.synanton.gpu.domain.model.ExecutionUsage;
import org.synanton.gpu.domain.model.RetryDisposition;
import org.synanton.gpu.domain.model.RuntimeTarget;
import org.synanton.gpu.domain.port.out.ExecutionRuntime;
import org.synanton.gpu.domain.service.HeartbeatManager;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;
import org.synanton.gpu.v1.Provider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * OpenRouter HTTP runtime adapter. Active when {@code gpu-gateway.dispatch.strategy=openrouter}.
 *
 * <p>Routes requests to OpenRouter.ai API endpoints by operation:
 * <ul>
 *   <li>SYNTHESIZE → POST /v1/chat/completions</li>
 *   <li>EMBED      → POST /v1/embeddings</li>
 *   <li>RERANK     → POST /v1/rerank</li>
 * </ul>
 *
 * <p>OpenRouter provides OpenAI-compatible API with access to multiple free and paid models.
 */
@Component
@Slf4j
public class OpenRouterRuntime implements ExecutionRuntime {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final HeartbeatManager heartbeatManager;
    private final Duration requestTimeout;
    private final String apiKey;
    private final String baseUrl;

    public OpenRouterRuntime(ObjectMapper objectMapper,
                             HeartbeatManager heartbeatManager,
                             Duration dispatchTimeout,
                             @Qualifier("openRouterApiKey") String apiKey,
                             @Qualifier("openRouterBaseUrl") String baseUrl) {
        this.objectMapper = objectMapper;
        this.heartbeatManager = heartbeatManager;
        this.requestTimeout = dispatchTimeout;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : "https://openrouter.ai/api/v1";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public RuntimeResult execute(ExecutionRequest request, RuntimeTarget target) {
        String endpoint = resolveEndpoint(target.endpointUrl(), request.getOperation());
        String executionId = request.getRequestId();

        log.info("OpenRouter dispatch: operation={} endpoint={}", request.getOperation(), endpoint);

        HeartbeatManager.HeartbeatHandle heartbeat = heartbeatManager.start(executionId);
        long startMs = System.currentTimeMillis();

        try {
            byte[] payload = request.getPayload().toByteArray();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", "https://synanton.ai")
                    .header("X-Title", "Synanton GPU Gateway")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .timeout(requestTimeout)
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            long durationMs = System.currentTimeMillis() - startMs;
            return parseResponse(response, durationMs, target.runtimeClass());

        } catch (ConnectException e) {
            log.warn("OpenRouter connect failed: {}", e.getMessage());
            return new RuntimeResult.Failure(
                    ExecutionError.retryable("RUNTIME_UNAVAILABLE", "OpenRouter connection refused: " + e.getMessage()),
                    RetryDisposition.NOT_ACCEPTED);

        } catch (HttpTimeoutException e) {
            log.warn("OpenRouter request timed out after {}ms", System.currentTimeMillis() - startMs);
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("RUNTIME_TIMEOUT", "OpenRouter request timed out"),
                    RetryDisposition.ACCEPTED_UNKNOWN);

        } catch (IOException e) {
            log.warn("OpenRouter IO error (request may have been received): {}", e.getMessage());
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

    @Override
    public CancellationResult cancel(String executionId, RuntimeTarget target) {
        log.info("Cancel requested for execution_id={} — OpenRouter cancellation not supported", executionId);
        return new CancellationResult.NotFound();
    }

    @Override
    public RuntimeStatus ping(String executionId, RuntimeTarget target) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(target.endpointUrl() + "/models"))
                    .header("Authorization", "Bearer " + apiKey)
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
                            "OpenRouter client error HTTP " + status + ": " + truncate(response.body())),
                    RetryDisposition.DEFINITELY_FAILED);
        }

        if (status == 503 || status == 502 || status == 504) {
            return new RuntimeResult.Failure(
                    ExecutionError.retryable("RUNTIME_UNAVAILABLE",
                            "OpenRouter unavailable HTTP " + status),
                    RetryDisposition.NOT_ACCEPTED);
        }

        if (status >= 500) {
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("RUNTIME_FAILED",
                            "OpenRouter server error HTTP " + status),
                    RetryDisposition.DEFINITELY_FAILED);
        }

        try {
            JsonNode body = objectMapper.readTree(response.body());

            if (body.has("error")) {
                String errMsg = body.path("error").path("message").asText("unknown error");
                return new RuntimeResult.Failure(
                        ExecutionError.nonRetryable("RUNTIME_FAILED", "OpenRouter error: " + errMsg),
                        RetryDisposition.DEFINITELY_FAILED);
            }

            ExecutionUsage usage = extractUsage(body, durationMs, runtimeClass);
            return new RuntimeResult.Success(usage, response.body().getBytes());

        } catch (Exception e) {
            log.warn("Failed to parse OpenRouter response: {}", e.getMessage());
            return new RuntimeResult.Failure(
                    ExecutionError.nonRetryable("RUNTIME_FAILED", "Failed to parse OpenRouter response"),
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
        String providerBaseUrl = baseUrl != null && !baseUrl.isEmpty() ? baseUrl : this.baseUrl;
        return switch (operation) {
            case SYNTHESIZE -> providerBaseUrl + "/chat/completions";
            case EMBED      -> providerBaseUrl + "/embeddings";
            case RERANK     -> providerBaseUrl + "/rerank";
            default         -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
    }

    private String truncate(String text) {
        return text != null && text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}