package org.synanton.gpu.adapter.out.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.gpu.domain.model.ModelStatus;
import org.synanton.gpu.domain.port.out.ModelManager;
import org.synanton.gpu.domain.port.out.ModelManager.ModelLoadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * ModelManager that queries the OpenRouter runtime via GET /v1/models.
 * Active when {@code gpu-gateway.dispatch.strategy=openrouter}.
 */
@Component
@Slf4j
public class OpenRouterModelManager implements ModelManager {

    private final String baseUrl;
    private final String apiKey;
    private final Duration modelLoadTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenRouterModelManager(@Qualifier("openRouterBaseUrl") String baseUrl,
                                   @Qualifier("openRouterApiKey") String apiKey,
                                   Duration modelLoadTimeout,
                                   ObjectMapper objectMapper) {
        this.baseUrl = baseUrl != null ? baseUrl : "https://openrouter.ai/api/v1";
        this.apiKey = apiKey;
        this.modelLoadTimeout = modelLoadTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelStatus getStatus(String modelId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode body = objectMapper.readTree(response.body());
                JsonNode data = body.get("data");
                if (data != null && data.isArray()) {
                    for (JsonNode model : data) {
                        if (modelId.equals(model.path("id").asText())) {
                            return ModelStatus.READY;
                        }
                    }
                }
            }
            return ModelStatus.LOADING;
        } catch (Exception e) {
            log.warn("Failed to query OpenRouter /v1/models for model={}: {}", modelId, e.getMessage());
            return ModelStatus.UNKNOWN;
        }
    }

    @Override
    public void ensureReady(String modelId) {
        long deadlineMs = System.currentTimeMillis() + modelLoadTimeout.toMillis();
        log.info("Ensuring model={} is ready via OpenRouter (timeout={})", modelId, modelLoadTimeout);

        while (System.currentTimeMillis() < deadlineMs) {
            ModelStatus status = getStatus(modelId);
            switch (status) {
                case READY -> {
                    log.debug("Model={} is ready on OpenRouter", modelId);
                    return;
                }
                case FAILED -> throw new ModelLoadException(modelId, "Model reported FAILED by OpenRouter");
                case LOADING, UNKNOWN -> {
                    log.debug("Model={} status={}, waiting...", modelId, status);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ModelLoadException(modelId, "Interrupted while waiting for model load");
                    }
                }
            }
        }
        throw new ModelLoadException(modelId,
                "Model did not become ready within " + modelLoadTimeout);
    }
}