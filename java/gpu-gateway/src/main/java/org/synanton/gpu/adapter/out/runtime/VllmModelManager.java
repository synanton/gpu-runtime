package org.synanton.gpu.adapter.out.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.gpu.domain.model.ModelStatus;
import org.synanton.gpu.domain.port.out.ModelManager;
import org.synanton.gpu.domain.port.out.ModelManager.ModelLoadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * ModelManager that queries the vLLM runtime via GET /v1/models.
 * Active when {@code gpu-gateway.dispatch.strategy=vllm}.
 */
@Component
@Slf4j
public class VllmModelManager implements ModelManager {

    private final String vllmEndpoint;
    private final Duration modelLoadTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * {@code static} readiness (GPU-5 plan §13.3 J3): Envoy-fronted local models are always-on
     * pods owned by Kubernetes readiness, so there's nothing to poll. One Envoy endpoint can't
     * answer {@code /v1/models} for three backends, and a down backend surfaces as Envoy 503
     * (NOT_ACCEPTED) at dispatch.
     */
    private final boolean staticReadiness;
    private final org.synanton.gpu.adapter.out.jwt.ExecutionJwtSigner jwtSigner;

    public VllmModelManager(String vllmEndpointUrl,
                             Duration modelLoadTimeout,
                             ObjectMapper objectMapper) {
        this(vllmEndpointUrl, modelLoadTimeout, objectMapper, false, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VllmModelManager(String vllmEndpointUrl,
                             Duration modelLoadTimeout,
                             ObjectMapper objectMapper,
                             org.synanton.gpu.config.GpuGatewayProperties properties,
                             org.springframework.beans.factory.ObjectProvider<org.synanton.gpu.adapter.out.jwt.ExecutionJwtSigner> jwtSigner) {
        this(vllmEndpointUrl, modelLoadTimeout, objectMapper,
                properties.getDispatch().isStaticModelReadiness(), jwtSigner.getIfAvailable());
    }

    public VllmModelManager(String vllmEndpointUrl,
                             Duration modelLoadTimeout,
                             ObjectMapper objectMapper,
                             boolean staticReadiness,
                             org.synanton.gpu.adapter.out.jwt.ExecutionJwtSigner jwtSigner) {
        this.staticReadiness = staticReadiness;
        this.jwtSigner = jwtSigner;
        if (staticReadiness) {
            log.info("Model readiness: static (Envoy-fronted local backends; Kubernetes readiness is authoritative)");
        }
        this.vllmEndpoint = vllmEndpointUrl;
        this.modelLoadTimeout = modelLoadTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelStatus getStatus(String modelId) {
        if (staticReadiness) {
            return ModelStatus.READY;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(vllmEndpoint + "/v1/models"))
                    .GET()
                    .timeout(Duration.ofSeconds(5));
            if (jwtSigner != null) {
                builder.header("Authorization", "Bearer " + jwtSigner.sign(
                        new org.synanton.gpu.adapter.out.jwt.ExecutionJwtSigner.Subject("MODELS", modelId, null, null, null),
                        new byte[0]));
            }
            HttpRequest request = builder.build();

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
            log.warn("Failed to query vLLM /v1/models for model={}: {}", modelId, e.getMessage());
            return ModelStatus.UNKNOWN;
        }
    }

    @Override
    public void ensureReady(String modelId) {
        long deadlineMs = System.currentTimeMillis() + modelLoadTimeout.toMillis();
        log.info("Ensuring model={} is ready (timeout={})", modelId, modelLoadTimeout);

        while (System.currentTimeMillis() < deadlineMs) {
            ModelStatus status = getStatus(modelId);
            switch (status) {
                case READY -> {
                    log.debug("Model={} is ready", modelId);
                    return;
                }
                case FAILED -> throw new ModelLoadException(modelId, "Model reported FAILED by runtime");
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
