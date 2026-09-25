package org.synanton.gpu.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/** Typed configuration for the GPU Gateway service. */
@ConfigurationProperties(prefix = "gpu-gateway")
public class GpuGatewayProperties {

    private int grpcPort;
    private int maxInboundMessageSizeBytes;
    private Dispatch dispatch = new Dispatch();
    private Execution execution = new Execution();
    private Artifacts artifacts = new Artifacts();
    private Map<String, ProviderConfig> providers = new HashMap<>();
    private Routing routing = new Routing();
    private Sensitivity sensitivity = new Sensitivity();
    private Budget budget = new Budget();
    private Usage usage = new Usage();
    private Security security = new Security();
    private ExecutionJwt executionJwt = new ExecutionJwt();
    private ModelCatalog modelCatalog = new ModelCatalog();
    private Map<String, ModelConfig> models = new HashMap<>();

    public static class Dispatch {
        private String strategy;
        private String vllmEndpoint;
        private int timeoutMs;
        private int modelLoadTimeoutMs;
        /**
         * Local model readiness: {@code poll} (default) asks {@code <vllm-endpoint>/v1/models}
         * before dispatch. {@code static} is for Envoy-fronted GPU-5 backends: each model is an
         * always-on pod whose readiness Kubernetes owns, and one Envoy endpoint can't answer
         * {@code /v1/models} for three backends. A down backend then surfaces as Envoy 503
         * (NOT_ACCEPTED). GPU-5 plan §13.3 J3.
         */
        private String modelReadiness = "poll";
        /** Liveness path under the endpoint for GetStatus reconciliation (Envoy: {@code /healthz}). */
        private String healthPath = "/health";

        public String getModelReadiness() { return modelReadiness; }
        public void setModelReadiness(String modelReadiness) { this.modelReadiness = modelReadiness; }
        public boolean isStaticModelReadiness() { return "static".equalsIgnoreCase(modelReadiness); }
        public String getHealthPath() { return healthPath; }
        public void setHealthPath(String healthPath) { this.healthPath = healthPath; }

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public String getVllmEndpoint() { return vllmEndpoint; }
        public void setVllmEndpoint(String vllmEndpoint) { this.vllmEndpoint = vllmEndpoint; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getModelLoadTimeoutMs() { return modelLoadTimeoutMs; }
        public void setModelLoadTimeoutMs(int modelLoadTimeoutMs) {
            this.modelLoadTimeoutMs = modelLoadTimeoutMs;
        }
    }

    /**
     * GPU-5 execution JWT (Deployment Plan §12, T-K8S-6a). When enabled, every Gateway→Envoy
     * request carries an ES256 token that Envoy's {@code jwt_authn} verifies against the JWKS
     * served on {@code jwks-port}. Keys are file-mounted from a Kubernetes Secret, never
     * passed via the environment or logged: {@code current.key} (PKCS#8 EC P-256, signs) plus
     * exactly two public keys, {@code current.pub} and {@code previous.pub} (SPKI PEM, both
     * published). Not used by GPU-7.
     */
    public static class ExecutionJwt {
        private boolean enabled = false;
        private String keyDir = "/etc/gpu-gateway/keys";
        private String issuer = "synanton-gpu-gateway";
        private String audience = "gpu-plane-execution";
        private int ttlSeconds = 60;
        private int jwksPort = 8090;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getKeyDir() { return keyDir; }
        public void setKeyDir(String keyDir) { this.keyDir = keyDir; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public int getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(int ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        public int getJwksPort() { return jwksPort; }
        public void setJwksPort(int jwksPort) { this.jwksPort = jwksPort; }
    }

    /**
     * Provider registry (PR #15 review §5): keyed by provider id
     * ({@code gpu-gateway.providers.<id>}), e.g. {@code openai}, {@code mock}.
     *
     * <p>All keys are enforced: {@code api-key}, {@code base-url}, {@code enabled},
     * {@code headers}, {@code allowed-model-pattern}, {@code circuit-breaker},
     * {@code health} (ProviderHealthMonitor).
     */
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
        private boolean enabled = true;
        /**
         * Optional regex every catalog provider-model-id of this provider must match,
         * checked at startup (fail closed). E.g. {@code .*:free} keeps a spend-capped
         * test key on OpenRouter's free models only.
         */
        private String allowedModelPattern;
        /** Extra request headers sent to this provider (e.g. OpenRouter attribution). */
        private Map<String, String> headers = new HashMap<>();
        /**
         * Optional request header carrying a stable per-tenant session ID (a SHA-256 prefix,
         * never the tenant ID). Example: {@code x-opencode-session} for opencode.ai context
         * caching.
         */
        private String sessionHeader;
        private Health health = new Health();
        private CircuitBreaker circuitBreaker = new CircuitBreaker();

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getAllowedModelPattern() { return allowedModelPattern; }
        public void setAllowedModelPattern(String allowedModelPattern) { this.allowedModelPattern = allowedModelPattern; }
        public String getSessionHeader() { return sessionHeader; }
        public void setSessionHeader(String sessionHeader) { this.sessionHeader = sessionHeader; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Health getHealth() { return health; }
        public void setHealth(Health health) { this.health = health; }
        public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
        public void setCircuitBreaker(CircuitBreaker circuitBreaker) { this.circuitBreaker = circuitBreaker; }

        /** Usable = enabled with base URL and credentials (Plan §4.4, §5.5, §29). */
        public boolean isUsable() {
            return enabled && hasText(baseUrl) && hasText(apiKey);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        /** T-K8S-46: probed by ProviderHealthMonitor when {@code path} is set. */
        public static class Health {
            private String path;
            private int intervalSeconds = 60;
            /** Consecutive failed probes before the provider is marked unhealthy. */
            private int failureThreshold = 2;

            public int getFailureThreshold() { return failureThreshold; }
            public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }

            public String getPath() { return path; }
            public void setPath(String path) { this.path = path; }
            public int getIntervalSeconds() { return intervalSeconds; }
            public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }
        }

        /** Enforced: consecutive provider failures open the circuit; see T-K8S-45. */
        public static class CircuitBreaker {
            private int failureThreshold = 5;
            private int resetSeconds = 60;

            public int getFailureThreshold() { return failureThreshold; }
            public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
            public int getResetSeconds() { return resetSeconds; }
            public void setResetSeconds(int resetSeconds) { this.resetSeconds = resetSeconds; }
        }
    }

    /**
     * Caller authentication and tenant authorization (Plan §13, T-K8S-7/8).
     * {@code mode: mtls} (default, fail closed) requires client certificates; the
     * certificate CN is the principal, mapped to the tenants it may act for.
     * {@code insecure-plaintext} disables transport security and tenant checks — for
     * tests and loopback-only development, never for a shared network.
     */
    public static class Security {
        private String mode = "mtls";
        private Tls tls = new Tls();
        private Map<String, CallerPrincipal> principals = new HashMap<>();

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public boolean isMtls() { return "mtls".equals(mode); }
        public Tls getTls() { return tls; }
        public void setTls(Tls tls) { this.tls = tls; }
        public Map<String, CallerPrincipal> getPrincipals() { return principals; }
        public void setPrincipals(Map<String, CallerPrincipal> principals) { this.principals = principals; }

        public static class Tls {
            private String certChain;
            private String privateKey;
            private String clientCa;

            public String getCertChain() { return certChain; }
            public void setCertChain(String certChain) { this.certChain = certChain; }
            public String getPrivateKey() { return privateKey; }
            public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
            public String getClientCa() { return clientCa; }
            public void setClientCa(String clientCa) { this.clientCa = clientCa; }
        }

        /** A caller principal (client-certificate CN) and what it may do. */
        public static class CallerPrincipal {
            /** Tenants this principal may assert in tenant_id; {@code "*"} = any tenant. */
            private java.util.List<String> tenants = new java.util.ArrayList<>();
            /** Roles; {@code admin} is required for the routing-control RPCs. */
            private java.util.List<String> roles = new java.util.ArrayList<>();

            public java.util.List<String> getTenants() { return tenants; }
            public void setTenants(java.util.List<String> tenants) { this.tenants = tenants; }
            public java.util.List<String> getRoles() { return roles; }
            public void setRoles(java.util.List<String> roles) { this.roles = roles; }

            public boolean mayActFor(String tenantId) {
                return tenants.contains("*") || tenants.contains(tenantId);
            }
        }
    }

    /** T-K8S-49 sensitivity policy: fail closed. */
    public static class Sensitivity {
        /** Model tags / request data_tags that forbid external routing. */
        private java.util.List<String> blockExternalTags = new java.util.ArrayList<>();

        public java.util.List<String> getBlockExternalTags() { return blockExternalTags; }
        public void setBlockExternalTags(java.util.List<String> v) { this.blockExternalTags = v; }
    }

    /** T-K8S-48b per-tenant daily budget (UTC day), enforced from the cost ledger. */
    public static class Budget {
        /** {@code enabled} | {@code disabled}; anything else fails startup. */
        private String enforcement = "disabled";
        private java.math.BigDecimal defaultDailyUsd;
        private Map<String, java.math.BigDecimal> tenantDailyUsd = new HashMap<>();

        public String getEnforcement() { return enforcement; }
        public void setEnforcement(String enforcement) { this.enforcement = enforcement; }
        public boolean isEnabled() { return "enabled".equals(enforcement); }
        public java.math.BigDecimal getDefaultDailyUsd() { return defaultDailyUsd; }
        public void setDefaultDailyUsd(java.math.BigDecimal v) { this.defaultDailyUsd = v; }
        public Map<String, java.math.BigDecimal> getTenantDailyUsd() { return tenantDailyUsd; }
        public void setTenantDailyUsd(Map<String, java.math.BigDecimal> v) { this.tenantDailyUsd = v; }

        public java.math.BigDecimal limitFor(String tenantId) {
            return tenantDailyUsd.getOrDefault(tenantId, defaultDailyUsd);
        }
    }

    /** T-K8S-48 cost ledger. */
    public static class Usage {
        /** {@code enabled} | {@code disabled}; anything else fails startup. */
        private String costLedger = "disabled";

        public String getCostLedger() { return costLedger; }
        public void setCostLedger(String costLedger) { this.costLedger = costLedger; }
        public boolean isCostLedgerEnabled() { return "enabled".equals(costLedger); }
    }

    /** GPU-7 routing controls (§32/§39). */
    public static class Routing {
        /** Kill switch: when false, ALL external routing is denied (fail closed). */
        private boolean externalEnabled = true;

        public boolean isExternalEnabled() { return externalEnabled; }
        public void setExternalEnabled(boolean externalEnabled) { this.externalEnabled = externalEnabled; }
    }

    public static class ModelCatalog {
        private Map<String, OperationModels> operations = new HashMap<>();
        private Map<String, TenantModelOverrides> tenants = new HashMap<>();

        public Map<String, OperationModels> getOperations() { return operations; }
        public void setOperations(Map<String, OperationModels> operations) { this.operations = operations; }
        public Map<String, TenantModelOverrides> getTenants() { return tenants; }
        public void setTenants(Map<String, TenantModelOverrides> tenants) { this.tenants = tenants; }

        public static class OperationModels {
            private String defaultModel;
            private Map<String, ModelInfo> models = new HashMap<>();

            public String getDefaultModel() { return defaultModel; }
            public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
            public Map<String, ModelInfo> getModels() { return models; }
            public void setModels(Map<String, ModelInfo> models) { this.models = models; }

            public static class ModelInfo {
                private String providerModelId;
                private String displayName;
                private String provider = "OPENAI";
                private boolean isDefault = false;
                private int maxInputTokens = 8192;
                private int maxOutputTokens = 2048;
                private int embeddingDim = 1536;
                /** Sensitivity tags (T-K8S-49); tagged models never route externally if blocked. */
                private java.util.List<String> tags = new java.util.ArrayList<>();
                /** Price per million input/output tokens (T-K8S-48). Required for external
                 *  models when budget enforcement is enabled (startup fails closed otherwise). */
                private java.math.BigDecimal inputUsdPerMillion;
                private java.math.BigDecimal outputUsdPerMillion;

                /**
                 * T-K8S-52 ordered external fallbacks, tried only when a provider did not
                 * accept the request. Never LOCAL (startup fails otherwise).
                 */
                private java.util.List<Fallback> fallbacks = new java.util.ArrayList<>();

                public java.util.List<Fallback> getFallbacks() { return fallbacks; }
                public void setFallbacks(java.util.List<Fallback> fallbacks) { this.fallbacks = fallbacks; }

                public static class Fallback {
                    private String provider;
                    private String providerModelId;
                    /** Optional; default to the model's prices. */
                    private java.math.BigDecimal inputUsdPerMillion;
                    private java.math.BigDecimal outputUsdPerMillion;

                    public String getProvider() { return provider; }
                    public void setProvider(String provider) { this.provider = provider; }
                    public String getProviderModelId() { return providerModelId; }
                    public void setProviderModelId(String providerModelId) { this.providerModelId = providerModelId; }
                    public java.math.BigDecimal getInputUsdPerMillion() { return inputUsdPerMillion; }
                    public void setInputUsdPerMillion(java.math.BigDecimal v) { this.inputUsdPerMillion = v; }
                    public java.math.BigDecimal getOutputUsdPerMillion() { return outputUsdPerMillion; }
                    public void setOutputUsdPerMillion(java.math.BigDecimal v) { this.outputUsdPerMillion = v; }
                }

                public java.util.List<String> getTags() { return tags; }
                public void setTags(java.util.List<String> tags) { this.tags = tags; }
                public java.math.BigDecimal getInputUsdPerMillion() { return inputUsdPerMillion; }
                public void setInputUsdPerMillion(java.math.BigDecimal v) { this.inputUsdPerMillion = v; }
                public java.math.BigDecimal getOutputUsdPerMillion() { return outputUsdPerMillion; }
                public void setOutputUsdPerMillion(java.math.BigDecimal v) { this.outputUsdPerMillion = v; }

                public String getProviderModelId() { return providerModelId; }
                public void setProviderModelId(String providerModelId) { this.providerModelId = providerModelId; }
                public String getDisplayName() { return displayName; }
                public void setDisplayName(String displayName) { this.displayName = displayName; }
                public String getProvider() { return provider; }
                public void setProvider(String provider) { this.provider = provider; }
                public boolean isDefault() { return isDefault; }
                public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
                public int getMaxInputTokens() { return maxInputTokens; }
                public void setMaxInputTokens(int maxInputTokens) { this.maxInputTokens = maxInputTokens; }
                public int getMaxOutputTokens() { return maxOutputTokens; }
                public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
                public int getEmbeddingDim() { return embeddingDim; }
                public void setEmbeddingDim(int embeddingDim) { this.embeddingDim = embeddingDim; }
            }
        }

        public static class TenantModelOverrides {
            private Map<String, String> modelOverrides = new HashMap<>();

            public Map<String, String> getModelOverrides() { return modelOverrides; }
            public void setModelOverrides(Map<String, String> modelOverrides) { this.modelOverrides = modelOverrides; }
        }
    }

    public static class Execution {
        private int leaseTimeoutSeconds;
        private int heartbeatIntervalSeconds;

        public int getLeaseTimeoutSeconds() { return leaseTimeoutSeconds; }
        public void setLeaseTimeoutSeconds(int leaseTimeoutSeconds) {
            this.leaseTimeoutSeconds = leaseTimeoutSeconds;
        }
        public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
        public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
            this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        }
    }

    public static class Artifacts {
        private String cacheDir;
        private String registryBaseUrl;

        public String getCacheDir() { return cacheDir; }
        public void setCacheDir(String cacheDir) { this.cacheDir = cacheDir; }
        public String getRegistryBaseUrl() { return registryBaseUrl; }
        public void setRegistryBaseUrl(String registryBaseUrl) {
            this.registryBaseUrl = registryBaseUrl;
        }
    }

    public static class ModelConfig {
        private int concurrencyLimit;
        private int maxInputTokens;
        private String runtimeClass;
        private String digest;

        public int getConcurrencyLimit() { return concurrencyLimit; }
        public void setConcurrencyLimit(int concurrencyLimit) {
            this.concurrencyLimit = concurrencyLimit;
        }
        public int getMaxInputTokens() { return maxInputTokens; }
        public void setMaxInputTokens(int maxInputTokens) { this.maxInputTokens = maxInputTokens; }
        public String getRuntimeClass() { return runtimeClass; }
        public void setRuntimeClass(String runtimeClass) { this.runtimeClass = runtimeClass; }
        public String getDigest() { return digest; }
        public void setDigest(String digest) { this.digest = digest; }
    }

    public int getGrpcPort() { return grpcPort; }
    public void setGrpcPort(int grpcPort) { this.grpcPort = grpcPort; }
    public int getMaxInboundMessageSizeBytes() { return maxInboundMessageSizeBytes; }
    public void setMaxInboundMessageSizeBytes(int maxInboundMessageSizeBytes) {
        this.maxInboundMessageSizeBytes = maxInboundMessageSizeBytes;
    }
    public Dispatch getDispatch() { return dispatch; }
    public void setDispatch(Dispatch dispatch) { this.dispatch = dispatch; }
    public Execution getExecution() { return execution; }
    public void setExecution(Execution execution) { this.execution = execution; }
    public Artifacts getArtifacts() { return artifacts; }
    public void setArtifacts(Artifacts artifacts) { this.artifacts = artifacts; }
    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }
    public Routing getRouting() { return routing; }
    public Sensitivity getSensitivity() { return sensitivity; }
    public void setSensitivity(Sensitivity sensitivity) { this.sensitivity = sensitivity; }
    public Budget getBudget() { return budget; }
    public void setBudget(Budget budget) { this.budget = budget; }
    public Usage getUsage() { return usage; }
    public Security getSecurity() { return security; }
    public ExecutionJwt getExecutionJwt() { return executionJwt; }
    public void setExecutionJwt(ExecutionJwt executionJwt) { this.executionJwt = executionJwt; }
    public void setSecurity(Security security) { this.security = security; }
    public void setUsage(Usage usage) { this.usage = usage; }
    public void setRouting(Routing routing) { this.routing = routing; }
    public ModelCatalog getModelCatalog() { return modelCatalog; }
    public void setModelCatalog(ModelCatalog modelCatalog) { this.modelCatalog = modelCatalog; }
    public Map<String, ModelConfig> getModels() { return models; }
    public void setModels(Map<String, ModelConfig> models) { this.models = models; }
}
