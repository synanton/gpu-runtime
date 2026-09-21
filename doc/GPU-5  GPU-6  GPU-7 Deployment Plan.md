# Synanton GPU Plane — Deployment Specification

**Version:** 1.0.0
**Status:** Execution baseline — pre-implementation
**Revision date:** 2026-09-21
**Supersedes:** None
**Superseded by:** None

---

## 1. Purpose

This document defines the deployment, security, API compatibility, runtime, observability, acceptance, and operational requirements for the Synanton GPU Plane.

The specification covers three deployment stages:

| Stage | Purpose                                   |    Local GPU |    Envoy |     vLLM | Status                     |
| ----- | ----------------------------------------- | -----------: | -------: | -------: | -------------------------- |
| GPU-5 | Home/reference local inference deployment |     Required | Required | Required | Implementing               |
| GPU-6 | Production hardening                      |     Required | Required | Required | Design only — deferred     |
| GPU-7 | Pure external-provider adapter deployment | Not required |       No |       No | Implementing independently |

GPU-5 and GPU-7 are intentionally separate deployment profiles.

GPU-6 is a production-hardening stage and is **not a prerequisite or release gate for GPU-5 or GPU-7**.

---

# 2. Architecture

## 2.1 GPU-5 Request Topology

```text
Client
  |
  | HTTPS
  v
Gateway
  |
  | signed execution JWT
  v
Envoy
  |
  | verified internal request
  v
vLLM
  |
  v
Local GPU
```

The Gateway is the public API boundary.

Envoy is a separate Deployment and Service. It is not a sidecar to the Gateway.

The Gateway signs an execution JWT for each request forwarded to the local inference execution perimeter.

Envoy verifies the JWT before forwarding to vLLM.

Direct client access to vLLM is prohibited.

---

## 2.2 GPU-5 Deployment Topology

Reference Kubernetes topology:

```text
                         +-------------------+
                         |      Client       |
                         +---------+---------+
                                   |
                                   | HTTPS
                                   v
                         +-------------------+
                         |      Gateway      |
                         |   Deployment      |
                         +---------+---------+
                                   |
                                   | execution JWT
                                   v
                         +-------------------+
                         |       Envoy       |
                         | Deployment/Service|
                         +---------+---------+
                                   |
                                   v
                         +-------------------+
                         |       vLLM        |
                         | Deployment        |
                         +---------+---------+
                                   |
                                   v
                              NVIDIA GPU
```

PostgreSQL is used by the Gateway for persistent control-plane state but is not part of the inference request path.

---

## 2.3 GPU-7 Topology

GPU-7 removes the local execution perimeter:

```text
Client
  |
  | HTTPS
  v
Gateway
  |
  | HTTPS
  v
External Provider
```

GPU-7 has:

* no local GPU requirement;
* no Envoy;
* no vLLM;
* no execution JWT;
* no local inference fallback.

PostgreSQL remains required for persistent Gateway control state.

GPU-7 may run on Kubernetes, but Kubernetes is not required. A single-node container deployment is sufficient for the reference external-provider profile.

---

# 3. Contract Ownership

The following sections are canonical definitions:

| Contract                        | Canonical section                   |
| ------------------------------- | ----------------------------------- |
| Public API surface              | §4.2                                |
| API compatibility               | §4.1                                |
| Responses API                   | §4.6                                |
| Image/version pinning           | §8                                  |
| Runtime baseline                | §9                                  |
| Streaming                       | §10                                 |
| Error envelope and mapping      | §16                                 |
| Rate limits and quota signaling | §20a                                |
| Request IDs                     | §21                                 |
| OpenAPI artifact                | `gpu-contract/openapi/openapi.yaml` |
| OpenAPI validation ownership    | T-K8S-15b                           |

Where a concept is repeated for readability, the canonical section is authoritative. Other occurrences are summaries, implementation references, or ticket-specific acceptance criteria.

Ticket sections do not create alternate API definitions.

---

# 4. External API

## 4.1 Compatibility Objective

The Gateway exposes an OpenAI-compatible external API for supported inference operations.

Compatibility includes:

* endpoint paths;
* request schemas;
* response schemas;
* authentication conventions;
* HTTP status codes;
* error envelopes;
* relevant HTTP headers;
* streaming SSE behavior;
* usage reporting;
* rate-limit signaling;
* request IDs.

Compatibility does **not** imply support for every OpenAI API feature.

Unsupported capabilities MUST be explicitly documented and MUST return the deterministic error envelope defined in §4.3 and §16.

The `/v1` path is independent of the OpenAPI contract revision.

The canonical OpenAPI contract is:

```text
gpu-contract/openapi/openapi.yaml
```

The Gateway-packaged OpenAPI artifact MUST be generated or copied from the canonical artifact.

It MUST NOT be independently authored.

CI MUST enforce equality between the canonical artifact and the packaged artifact.

T-K8S-15b owns:

* OpenAPI lint;
* contract validation;
* compatibility tests;
* breaking-change detection;
* canonical/package equality.

---

## 4.2 Endpoints — Canonical API Surface

```text
GET    /v1/models
GET    /v1/models/{model}

POST   /v1/chat/completions
POST   /v1/embeddings
POST   /v1/rerank

POST   /v1/responses
GET    /v1/responses/{id}
DELETE /v1/responses/{id}
```

Capability availability:

| Endpoint                    |                            GPU-5 |                           GPU-7 |
| --------------------------- | -------------------------------: | ------------------------------: |
| `/v1/models`                |                              Yes |                             Yes |
| `/v1/models/{model}`        |                              Yes |                             Yes |
| `/v1/chat/completions`      |                              Yes |                             Yes |
| `/v1/embeddings`            |                              Yes |                             Yes |
| `/v1/rerank`                |                              Yes | When provider/model supports it |
| `/v1/responses`             | Not required in current baseline |             Yes, when supported |
| `/v1/responses/{id}`        | Not required in current baseline |             Yes, when supported |
| `DELETE /v1/responses/{id}` | Not required in current baseline |             Yes, when supported |

---

## 4.3 OpenAI API Surfaces Out of Scope

The following surfaces are outside the current compatibility contract:

```text
/v1/moderations
/v1/audio/*
/v1/images/*
/v1/files
/v1/fine_tuning/*
/v1/batches
/v1/assistants/*
/v1/vector_stores/*
/v1/threads/*
```

Requests to unsupported surfaces MUST return HTTP 400 using:

```json
{
  "error": {
    "message": "The requested capability is not supported by this deployment.",
    "type": "invalid_request_error",
    "param": null,
    "code": "capability_not_supported"
  }
}
```

The Gateway MUST NOT silently translate an unsupported capability into another API operation.

---

## 4.4 Model Discovery

`GET /v1/models` returns models available through the active deployment mode.

GPU-5 returns locally configured models.

GPU-7 returns models exposed through configured external-provider mappings.

A model MUST NOT be advertised through `/v1/models` unless it can actually be resolved by the active deployment.

---

## 4.5 Model Object

The canonical OpenAI-compatible model object is:

```json
{
  "id": "model-id",
  "object": "model",
  "created": 1686935002,
  "owned_by": "organization-owner"
}
```

`shutdown_date` is a **Synanton extension**, not an OpenAI core field.

It MAY be emitted when configured.

For an active model without an explicit retirement date it MAY be `null`.

The extension MUST NOT be represented as part of the canonical OpenAI-compatible model object definition.

---

## 4.6 Responses API

The Responses API is a GPU-7 capability.

Supported operations:

```text
POST   /v1/responses
GET    /v1/responses/{id}
DELETE /v1/responses/{id}
```

The provider adapter MUST preserve Responses API semantics.

A provider that does not support Responses API MUST NOT be silently converted to Chat Completions.

GPU-5 does not require Responses API support in the current baseline.

If GPU-5 Responses support is explicitly enabled through deployment configuration, the implementation and acceptance suite MUST support the capability. Startup and CI validation MUST prevent an invalid configuration in which the capability is enabled but not implemented.

---

## 4.7 API Compatibility Versioning

API compatibility is versioned independently from implementation versions.

The `/v1` path identifies the external API compatibility generation.

Breaking changes to the public API require a deliberate contract revision and OpenAPI validation.

Implementation upgrades MUST NOT silently change externally observable API semantics.

---

# 5. Deployment Modes

## 5.1 Supported Modes

The current deployment baselines support:

```text
local-only
external-only
fail-closed
```

`auto` is **reserved for a future hybrid deployment profile**.

It is not a valid mode for either GPU-5 or GPU-7.

No current deployment profile assigns `auto`.

---

## 5.2 Mode Selection

GPU-5 supports:

```text
local-only
fail-closed
```

GPU-7 supports:

```text
external-only
fail-closed
```

GPU-7 MUST reject:

```text
local-only
auto
```

GPU-5 MUST NOT silently route local requests to an external provider.

GPU-7 MUST NOT silently fall back to local inference.

---

## 5.3 GPU-5 Mode

The reference GPU-5 mode is:

```text
local-only
```

All inference requests resolve to local model execution through:

```text
Gateway → Envoy → vLLM
```

---

## 5.4 GPU-7 Mode

The reference GPU-7 mode is:

```text
external-only
```

All inference requests resolve through configured external-provider mappings.

The request path is:

```text
Gateway → external provider
```

---

## 5.5 Startup Validation

Deployment startup MUST fail closed when required configuration is inconsistent.

Examples include:

* unsupported deployment mode;
* missing required API-key pepper;
* missing provider credentials for enabled external mappings;
* enabled capability without implementation;
* invalid model mapping;
* invalid routing configuration;
* kill switch configured inconsistently;
* budget enforcement configured without required persistent state.

Configuration errors MUST prevent the Gateway from becoming ready.

---

# 6. GPU-5 Execution Perimeter

GPU-5 requires:

* Gateway;
* Envoy;
* vLLM;
* local NVIDIA GPU;
* NVIDIA GPU Operator/device plugin;
* RuntimeClass `nvidia`.

The execution perimeter is:

```text
Gateway → Envoy → vLLM
```

Envoy MUST verify the Gateway-signed execution JWT.

Direct access from clients to vLLM MUST be denied.

vLLM MUST NOT be publicly exposed.

Envoy MUST NOT accept unauthenticated execution traffic.

---

# 7. GPU-7 External Provider Perimeter

GPU-7 has no local execution perimeter.

The Gateway directly communicates with external providers over HTTPS.

Required controls include:

* provider credentials;
* provider/model mappings;
* external routing policy;
* provider health;
* circuit breaker;
* usage accounting;
* cost ledger;
* budget enforcement;
* sensitivity policy;
* kill switch.

There is no execution JWT in GPU-7.

---

# 8. Image and Version Pinning

All production and acceptance deployments MUST use immutable image references.

Floating image tags are prohibited for execution deployments.

Before implementation freeze, exact image versions and immutable digests MUST be recorded for:

* Gateway;
* Envoy;
* vLLM;
* PostgreSQL;
* NVIDIA runtime components;
* supporting deployment images.

Model weights MUST be identified by explicit version.

Configuration values MUST be version controlled.

The following pinned operational values are defined in their respective canonical sections:

| Value                     | Reference | Canonical definition            |
| ------------------------- | --------- | ------------------------------- |
| Envoy listener            | `8080`    | §2.2 / deployment configuration |
| Envoy → vLLM              | `8000`    | §2.2 / deployment configuration |
| Gateway health/metrics    | `8091`    | deployment configuration        |
| Envoy → vLLM timeout      | `300 s`   | §31                             |
| JWKS cache TTL            | `5 min`   | §12                             |
| Gateway drain             | `30 s`    | §31                             |
| Gateway termination grace | `45 s`    | §31                             |
| Idempotency retention     | `24 h`    | §18                             |

The table is an **index**, not a second normative definition.

If a value conflicts with its canonical section, the canonical section is authoritative.

---

# 9. Runtime Baselines

The following are **reference compatibility baselines carried forward from the validated deployment design**:

```text
Envoy       1.31.x
vLLM        0.6.x
PostgreSQL  16.x
```

These values are not claims that these are the newest upstream versions as of 2026-09-21.

They define the currently documented compatibility baseline.

Any runtime upgrade is a compatibility change and MUST be validated against:

* Gateway integration;
* Envoy configuration;
* JWT authentication;
* streaming;
* model loading;
* GPU memory behavior;
* Chat Completions;
* Embeddings;
* Rerank;
* graceful shutdown;
* acceptance suite.

Exact patch versions and immutable image digests MUST be pinned before execution.

Floating tags MUST NOT be used.

---

# 10. Streaming SSE Contract

## 10.1 Chat Completions

For Chat Completions streaming:

* every data event MUST contain JSON conforming to the `chat.completion.chunk` schema;
* SSE framing MUST be valid;
* `[DONE]` MUST be emitted exactly once after the final completion chunk;
* no arbitrary non-contract JSON events may be inserted into the stream.

When:

```text
stream_options.include_usage = true
```

is requested and authoritative usage is available, the Gateway **MUST** emit terminal usage according to the external contract.

When authoritative usage is unavailable, §10.2 applies.

---

## 10.2 Usage

Provider-reported usage is authoritative.

If authoritative usage is unavailable and no supported pre-flight estimate exists, the terminal usage value MUST be:

```json
"usage": null
```

A zero-valued usage object MUST NOT be substituted for unavailable authoritative usage.

The terminal usage block MUST still be represented according to the external streaming contract when usage was requested.

---

## 10.3 GPU-7 Usage

The GPU-7 adapter MAY inject:

```text
stream_options.include_usage=true
```

upstream when required to obtain authoritative usage from the provider.

Provider request and response semantics MUST remain compatible with the Gateway's external API contract.

Provider usage MUST be propagated into the cost ledger.

---

## 10.4 Responses API SSE Wire Format

Responses API streaming uses explicit SSE event types.

The wire format is:

```text
event: response.created
data: {"type":"response.created", ...}

event: response.in_progress
data: {"type":"response.in_progress", ...}

event: response.completed
data: {"type":"response.completed", ...}
```

The `event:` field identifies the SSE event type.

The JSON `data` payload contains the corresponding Responses API event object and MUST include the matching `type`.

Responses API streaming MUST NOT use:

```text
data: [DONE]
```

as the terminal protocol.

The adapter MUST preserve provider-supported Responses API lifecycle semantics.

---

# 11. Request Processing

The Gateway processing sequence is:

```text
1. Receive request
2. Validate request ID
3. Authenticate API key
4. Resolve organization/project identity
5. Validate request schema
6. Resolve logical model
7. Resolve deployment mode
8. Apply sensitivity policy
9. Apply rate limits/quota
10. Apply budget controls
11. Resolve execution/provider target
12. Execute request
13. Record usage/cost state
14. Return response
```

GPU-5 additionally performs:

```text
Gateway
  → sign execution JWT
  → Envoy
  → vLLM
```

GPU-7 performs:

```text
Gateway
  → provider adapter
  → external provider
```

---

# 12. GPU-5 Execution JWT

GPU-5 execution JWT configuration:

```text
Algorithm: ES256
Issuer:    synanton-gpu-gateway
Audience:  gpu-plane-execution
```

The JWT contains a request-body hash.

The hash is:

```text
SHA-256(raw HTTP request body bytes)
```

The hash MUST be computed from the raw request body as received by the Gateway before JSON parsing.

The Gateway MUST NOT use canonicalized JSON for the current hash definition.

RFC 8785 canonical JSON MAY be considered in a future contract revision.

JWKS endpoint:

```text
/internal/.well-known/jwks.json
```

Key policy:

* exactly two active signing keys;
* current key;
* previous key.

JWKS cache TTL:

```text
5 minutes
```

Gateway signing keys MUST be file-mounted from a Kubernetes Secret.

They MUST NOT be passed through environment variables.

They MUST NOT be logged.

Envoy MUST fail closed if required JWKS verification material is unavailable.

Gateway readiness MUST precede Envoy readiness.

These requirements apply only to GPU-5.

They do not apply to GPU-7.

---

# 13. API-Key Authentication

## 13.1 Authorization Schemes

The Gateway MUST support:

```text
Authorization: Bearer <key>
Authorization: Api-Key <key>
```

---

## 13.2 API-Key Formats

The OpenAI-compatible key format MUST be supported:

```text
sk-syn-...
```

Native Synanton clients MAY/SHOULD use:

```text
syn_live_<key_id>_<secret>
```

Supporting both prefixes MUST NOT create different authentication, identity, quota, or authorization semantics.

API-key pepper MUST be provisioned separately.

The pepper MUST:

* never be committed to source control;
* never be logged;
* never be exposed through API responses.

---

## 13.3 Organization and Project Headers

The Gateway MUST accept the OpenAI-compatible identity headers:

```text
OpenAI-Organization: <organization-id>
OpenAI-Project: <project-id>
```

The headers participate in resolving the request's Gateway identity.

The resolved identity MUST be checked against the authenticated API key.

### Project Resolution

When `OpenAI-Project` is supplied:

1. the Gateway resolves the supplied project identifier;
2. the authenticated API key is checked for authorization to that project;
3. if the key does not match the requested project, the request is rejected.

The rejection MUST be:

```text
HTTP 401
```

with:

```json
{
  "error": {
    "message": "The API key does not match the requested project.",
    "type": "authentication_error",
    "param": "OpenAI-Project",
    "code": "project_mismatch"
  }
}
```

If no `OpenAI-Project` header is supplied, the Gateway uses the project identity associated with the authenticated API key according to deployment configuration.

`OpenAI-Organization` participates in organization resolution and MUST NOT override the organization associated with the authenticated key.

If supplied organization identity is inconsistent with the authenticated identity, the Gateway MUST reject the request rather than silently changing tenant identity.

The previous public custom header:

```text
X-Project-ID
```

is removed from the API contract.

Clients MUST use:

```text
OpenAI-Project
```

instead.

Internal Gateway metadata MAY propagate tenant/project identity between internal components, but such metadata is not part of the public API contract.

---

# 14. Model Registry

The model registry maps logical Synanton model IDs to execution targets.

GPU-5 mappings resolve to local vLLM deployments.

GPU-7 mappings resolve to external provider/model pairs.

A model MUST NOT be advertised unless its configured target is valid.

At least one local rerank-capable model MUST be available in the GPU-5 reference deployment.

---

# 15. OpenAPI Validation

The canonical OpenAPI artifact is:

```text
gpu-contract/openapi/openapi.yaml
```

The contract MUST be validated in CI.

Validation includes:

* syntax;
* schema correctness;
* endpoint compatibility;
* request/response examples;
* error schemas;
* breaking changes;
* canonical/package equality.

T-K8S-15b owns OpenAPI contract validation.

---

# 16. Error Contract

## 16.1 Canonical Error Envelope

All client-visible errors MUST use the canonical envelope:

```json
{
  "error": {
    "message": "string",
    "type": "string",
    "param": "string or null",
    "code": "string or null"
  }
}
```

The complete status/type/code mapping below is authoritative.

Implementations MUST NOT invent alternate client-visible error structures for conditions covered by this table.

## 16.2 Canonical Status/Type/Code Mapping

| Condition                          | HTTP | `type`                  | `code`                             |
| ---------------------------------- | ---: | ----------------------- | ---------------------------------- |
| Invalid API key                    |  401 | `authentication_error`  | `invalid_api_key`                  |
| Project mismatch                   |  401 | `authentication_error`  | `project_mismatch`                 |
| Invalid request ID                 |  400 | `invalid_request_error` | `invalid_request_id`               |
| Invalid JSON                       |  400 | `invalid_request_error` | `invalid_json`                     |
| Schema validation failure          |  400 | `invalid_request_error` | `schema_validation_failed`         |
| Unsupported content type           |  400 | `invalid_request_error` | `unsupported_content_type`         |
| Payload too large                  |  413 | `invalid_request_error` | `payload_too_large`                |
| Model not found                    |  404 | `invalid_request_error` | `model_not_found`                  |
| Capability unsupported             |  400 | `invalid_request_error` | `capability_not_supported`         |
| Idempotency conflict               |  409 | `invalid_request_error` | `idempotency_conflict`             |
| Sensitive external routing blocked |  403 | `permission_error`      | `sensitive_model_external_blocked` |
| Request rate limit                 |  429 | `requests`              | `rate_limit_exceeded`              |
| Token rate limit                   |  429 | `tokens`                | `rate_limit_exceeded`              |
| Daily request limit                |  429 | `requests`              | `rate_limit_exceeded`              |
| Daily token limit                  |  429 | `tokens`                | `rate_limit_exceeded`              |
| Budget exhausted                   |  429 | `insufficient_quota`    | `budget_exceeded`                  |
| Concurrency limit                  |  429 | `concurrency`           | `concurrency_limit_reached`        |
| Provider failure                   |  502 | `api_error`             | `upstream_provider_error`          |
| Provider timeout                   |  504 | `api_error`             | `upstream_provider_timeout`        |
| Internal Gateway failure           |  500 | `server_error`          | `internal_error`                   |

### Rate-Limit Error Semantics

429 responses MUST distinguish the exhausted control dimension.

Request-based limits use:

```text
type: requests
code: rate_limit_exceeded
```

Token-based limits use:

```text
type: tokens
code: rate_limit_exceeded
```

Budget exhaustion uses:

```text
type: insufficient_quota
code: budget_exceeded
```

Concurrency exhaustion uses:

```text
type: concurrency
code: concurrency_limit_reached
```

Where a reliable retry interval is available, the Gateway SHOULD provide:

```text
retry-after-ms
Retry-After
```

The Gateway MUST NOT synthesize an inaccurate retry interval.

### Request ID and Error Body

Every response includes:

```text
x-request-id
```

The request ID MUST NOT be duplicated into the canonical error body as a custom extension.

---

# 17. GPU-5 JWT Tickets

| Ticket   | Scope                     |
| -------- | ------------------------- |
| T-K8S-6a | JWT signing / JWKS        |
| T-K8S-6b | Envoy `jwt_authn`         |
| T-K8S-6  | Envoy execution perimeter |

T-K8S-6a owns Gateway JWT signing and JWKS publication.

T-K8S-6b owns Envoy JWT verification.

T-K8S-6 owns the complete Envoy execution perimeter.

These tickets do not apply to GPU-7.

---

# 18. Idempotency

Async and retryable operations MUST support idempotency.

The idempotency key is caller-generated and identifies a logical submission/request.

The same key with the same request MUST return the same logical operation/result.

The same key with a different request MUST return:

```text
HTTP 409
type: invalid_request_error
code: idempotency_conflict
```

The Gateway MUST NOT silently reuse an idempotency key for a different request.

Reference retention:

```text
24 hours
```

Idempotency state is stored in PostgreSQL.

GPU-6 defines the production shared-idempotency requirements.

---

# 19. Network Security

GPU-5 network boundaries MUST enforce:

```text
Client → Gateway
Gateway → Envoy
Envoy → vLLM
Gateway → PostgreSQL
```

Clients MUST NOT directly access:

* Envoy internal execution endpoints;
* vLLM;
* PostgreSQL;
* internal JWKS endpoint unless explicitly required by the deployment architecture.

Kubernetes NetworkPolicy MUST restrict traffic to required paths.

GPU-7 MUST restrict outbound network access to configured external providers and required infrastructure.

---

# 20. Observability

Logs, metrics, traces, and error reporting MUST NOT contain:

* prompts;
* completions;
* credentials;
* API keys;
* provider secrets;
* raw sensitive request content;
* access tokens;
* arbitrary token contents.

T-K8S-12 owns zero-prompt verification.

Verification MUST cover:

* active logs;
* rotated logs;
* compressed logs;
* traces;
* metrics;
* error responses.

T-K8S-29 owns:

* alert definitions;
* deployment;
* routing;
* dashboards;
* observability baseline.

T-K8S-35 validates:

* dashboards;
* thresholds;
* SLOs;
* operational observability behavior.

---

# 20a. Rate Limits and Quota Signaling

This section is the canonical rate-limit definition.

Authenticated responses for:

```text
chat
embeddings
rerank
models
models/{model}
responses
```

when applicable, MUST emit:

```text
x-ratelimit-limit-requests
x-ratelimit-limit-tokens
x-ratelimit-remaining-requests
x-ratelimit-remaining-tokens
x-ratelimit-reset-requests
x-ratelimit-reset-tokens
```

A shared tenant/project bucket is the default.

API keys do not receive independent buckets unless policy explicitly configures an independent rate-limit domain.

Supported policy dimensions MAY include:

* RPM;
* TPM;
* RPD;
* TPD;
* concurrency.

Reference windows:

```text
RPM / TPM: 60 seconds
RPD / TPD: 24 hours
```

These are policy defaults and are not §8 deployment constants.

### Unlimited Dimensions

When a rate-limit dimension is not enforced for the resolved tenant/project, its corresponding limit header MUST still be emitted.

For an unlimited or unconfigured dimension:

```text
0 = unlimited
```

A deployment MAY configure a finite limit, but the advertised finite limit MUST actually be enforced.

The Gateway MUST NOT advertise a finite limit that it does not enforce.

### Metrics

Required metrics:

```text
gpu_gateway_rate_limit_total{dimension,model,status}

gpu_gateway_rate_limit_remaining_requests

gpu_gateway_rate_limit_remaining_tokens

gpu_gateway_429_total{dimension,model}
```

A tenant label MAY be added only after cardinality has been validated.

### 429 Semantics

429 behavior is defined by §16.

Request limits:

```text
type = requests
code = rate_limit_exceeded
```

Token limits:

```text
type = tokens
code = rate_limit_exceeded
```

Daily request and token limits use the corresponding `requests` or `tokens` type with:

```text
code = rate_limit_exceeded
```

Budget exhaustion:

```text
type = insufficient_quota
code = budget_exceeded
```

Concurrency:

```text
type = concurrency
code = concurrency_limit_reached
```

---

# 21. Request IDs

Every response MUST contain:

```text
x-request-id
```

If the client supplies a valid `x-request-id`, the Gateway MUST preserve it.

Valid request IDs are:

* 1–128 characters;
* ASCII printable;
* no whitespace;
* no control characters;
* no Unicode normalization ambiguity.

Invalid values MUST be rejected at the Gateway boundary:

```json
{
  "error": {
    "message": "The supplied x-request-id is invalid.",
    "type": "invalid_request_error",
    "param": "x-request-id",
    "code": "invalid_request_id"
  }
}
```

Missing request IDs MUST be generated as UUIDv4.

The request ID MUST NOT be added to the error body as a custom extension.

---

# 22. GPU-5 Ticket Sequence

The complete GPU-5 implementation sequence is:

| Ticket    | Scope                                      |
| --------- | ------------------------------------------ |
| T-K8S-0   | Cluster                                    |
| T-K8S-0b  | Namespace / registry                       |
| T-K8S-1   | Gateway containerization                   |
| T-K8S-1a  | OpenAPI artifact and packaging             |
| T-K8S-2   | Build / push                               |
| T-K8S-3   | PostgreSQL                                 |
| T-K8S-4   | Model registry / routing / mode validation |
| T-K8S-5   | vLLM                                       |
| T-K8S-6a  | JWT signing / JWKS                         |
| T-K8S-6b  | Envoy `jwt_authn`                          |
| T-K8S-6   | Envoy execution perimeter                  |
| T-K8S-7   | TLS / API exposure                         |
| T-K8S-8   | API-key authentication                     |
| T-K8S-8a  | API-key issuance runbook                   |
| T-K8S-8b  | API-key pepper provisioning                |
| T-K8S-9   | Health / Prometheus                        |
| T-K8S-9a  | Tenant cardinality rule                    |
| T-K8S-10  | Node-local model storage                   |
| T-K8S-11  | NetworkPolicy                              |
| T-K8S-12  | Zero-prompt logging                        |
| T-K8S-13  | Idempotency                                |
| T-K8S-14  | Acceptance suite                           |
| T-K8S-15a | Protobuf contract mirror                   |
| T-K8S-15b | OpenAPI contract validation                |

Mode validation belongs to T-K8S-4.

There is no T-K8S-4a.

---

# 23. Policy Model

The Gateway policy model controls:

* tenant/project identity;
* model access;
* rate limits;
* quota;
* concurrency;
* budget;
* sensitivity;
* deployment routing;
* provider access.

Rate-limit dimensions are defined canonically in §20a.

By default, quota/rate-limit policy is associated with the resolved tenant/project identity rather than individual API keys.

Independent API-key buckets are permitted only when explicitly configured.

---

# 24. GPU-5 Acceptance

T-K8S-14 owns the GPU-5 acceptance suite.

Acceptance MUST be executable against the packaged deployment rather than only against unit-test or development classpaths.

## 24.1 Positive Cases

The suite MUST verify:

1. API-key authentication succeeds with `Bearer`.
2. API-key authentication succeeds with `Api-Key`.
3. `sk-syn-...` keys are accepted.
4. `/v1/models` returns the configured local models.
5. `GET /v1/models/{model}` resolves a configured model.
6. Chat Completions succeeds.
7. Embeddings succeeds.
8. Rerank succeeds against a configured rerank-capable model.
9. Chat streaming produces valid `chat.completion.chunk` events.
10. `[DONE]` is emitted exactly once.
11. Usage is returned when authoritative usage is available.
12. `x-request-id` is present on every response.
13. A valid client-supplied `x-request-id` is preserved.
14. `OpenAI-Project` resolves the authenticated project correctly.
15. `OpenAI-Organization` is accepted.
16. Idempotent repeated requests resolve to the same logical operation/result.
17. Envoy accepts correctly signed Gateway execution JWTs.
18. Valid requests reach vLLM.

## 24.2 Negative Cases

The suite MUST verify:

1. Invalid API key → `invalid_api_key`.
2. Project mismatch → `project_mismatch`.
3. Invalid request ID → `invalid_request_id`.
4. Invalid JSON → `invalid_json`.
5. Schema failure → `schema_validation_failed`.
6. Unsupported content type → `unsupported_content_type`.
7. Oversized payload → `payload_too_large`.
8. Unknown model → `model_not_found`.
9. Unsupported API capability → `capability_not_supported`.
10. Same idempotency key with a different request → `idempotency_conflict`.
11. Direct vLLM access is denied.
12. Invalid execution JWT is rejected by Envoy.
13. Missing/invalid JWT verification material fails closed.
14. Public `X-Project-ID` is not treated as the project identity header.

## 24.3 Rate-Limit Cases

The suite MUST verify:

1. request rate-limit exhaustion;
2. token rate-limit exhaustion;
3. daily request exhaustion;
4. daily token exhaustion;
5. concurrency exhaustion;
6. budget exhaustion where budget enforcement is enabled;
7. correct 429 status;
8. correct `type`;
9. correct `code`;
10. rate-limit headers are emitted;
11. unlimited dimensions use `0`;
12. finite advertised limits are actually enforced;
13. retry headers are emitted only when a reliable retry interval is available.

## 24.4 Identity Cases

The suite MUST verify:

1. authenticated API key resolves to the expected project;
2. `OpenAI-Project` matching the key succeeds;
3. `OpenAI-Project` mismatching the key fails with HTTP 401;
4. `OpenAI-Organization` is accepted;
5. inconsistent organization identity is rejected according to configured identity policy;
6. public `X-Project-ID` does not override authenticated identity.

## 24.5 Contract Cases

The suite MUST verify:

1. all supported endpoints match the canonical OpenAPI contract;
2. error responses use the canonical envelope;
3. all normative error codes in §16 are exercised;
4. Chat Completions SSE conforms to the OpenAPI-defined chunk schema;
5. `[DONE]` is emitted exactly once;
6. usage semantics match §10;
7. request IDs match §21;
8. rate-limit headers match §20a;
9. unsupported capabilities return `capability_not_supported`;
10. packaged OpenAPI equals the canonical artifact.

---

# 25. GPU-6 Definition of Done

GPU-6 is production hardening and is deferred.

It covers:

* HA;
* PostgreSQL HA;
* distributed quota/rate limiting;
* production secrets;
* JWT/JWKS rotation;
* certificate rotation;
* network hardening;
* observability;
* dashboards and alerting;
* backup/recovery;
* failure/rollback;
* Helm/Kustomize;
* CI/CD;
* security scanning;
* automated rollback validation;
* operational SLO/dashboard validation;
* runbooks;
* capacity and graceful lifecycle validation.

GPU-6 is not required to release GPU-5 or GPU-7 under this specification.

---

# 26. GPU-7 Scope

GPU-7 is a pure external-provider deployment.

Required:

* Gateway;
* PostgreSQL;
* external provider credentials;
* provider registry;
* logical-model mappings;
* provider adapter;
* routing control;
* provider health;
* circuit breaker;
* usage accounting;
* cost ledger;
* budget enforcement;
* sensitivity policy;
* kill switch.

Not required:

* local GPU;
* NVIDIA GPU Operator;
* Envoy;
* vLLM;
* execution JWT.

---

# 27. GPU-6 Ticket Ownership

| Ticket   | Scope                                          |
| -------- | ---------------------------------------------- |
| T-K8S-20 | HA / graceful shutdown                         |
| T-K8S-21 | PostgreSQL HA / schema evolution               |
| T-K8S-22 | Shared idempotency                             |
| T-K8S-23 | Distributed quota / rate limiting              |
| T-K8S-24 | Production secrets                             |
| T-K8S-25 | JWT / JWKS rotation                            |
| T-K8S-26 | Certificate rotation                           |
| T-K8S-27 | Network hardening                              |
| T-K8S-28 | Observability                                  |
| T-K8S-29 | Dashboards / alerting / observability baseline |
| T-K8S-30 | Backup / recovery                              |
| T-K8S-31 | Failure / rollback                             |
| T-K8S-32 | Helm / Kustomize                               |
| T-K8S-33 | CI/CD / security scans                         |
| T-K8S-34 | Automated rollback validation                  |
| T-K8S-35 | Operational dashboards / SLO validation        |
| T-K8S-36 | Operational runbooks                           |
| T-K8S-37 | Capacity / graceful lifecycle validation       |

Tickets 16–19 are reserved.

T-K8S-37 validates:

* startup;
* replacement;
* Gateway rolling restart;
* SSE graceful shutdown;
* GPU maintenance.

It also documents capacity assumptions including:

* RPS;
* concurrency;
* tokens/sec;
* maximum generation;
* GPU utilization;
* GPU memory.

---

# 28. GPU-6 PostgreSQL

GPU-6 requires PostgreSQL HA.

Schema evolution follows expand/contract principles.

Migrations MUST execute against the current primary.

The migration mechanism MUST support controlled deployment ordering.

---

# 29. GPU-7 Security

GPU-7 MUST fail closed for:

* missing provider credentials;
* invalid routing configuration;
* disabled/blocked provider;
* sensitivity policy violation;
* budget exhaustion;
* routing kill switch;
* invalid provider/model mapping.

Provider credentials MUST be stored as secrets.

Provider credentials MUST NOT be logged.

External provider communication MUST use HTTPS.

---

# 30. SLO and Dashboard Validation

Operational dashboards MUST expose the health of:

* Gateway;
* PostgreSQL;
* provider adapters;
* provider latency;
* provider failures;
* request throughput;
* rate limits;
* budget consumption;
* inference usage;
* external routing;
* GPU utilization for GPU-5.

T-K8S-35 validates dashboard and SLO behavior.

T-K8S-29 owns the deployed dashboard and alert configuration.

---

# 31. Timeouts and Graceful Lifecycle

## GPU-5

Envoy → vLLM timeout:

```text
300 seconds
```

Gateway drain:

```text
30 seconds
```

Gateway termination grace:

```text
45 seconds
```

During drain:

* new requests are rejected;
* existing SSE streams may continue for up to the drain period;
* remaining streams are cancelled;
* the pod exits within the termination-grace budget.

The 300-second upstream timeout is not a guarantee that a request survives a 30-second Gateway drain.

## GPU-7

GPU-7 has no Envoy/vLLM timeout.

Provider requests are governed by the Gateway lifecycle and provider adapter timeout policy.

---

# 32. GPU-7 Kill Switch

The external-routing kill switch is fail closed.

When activated:

```text
external provider routing = denied
```

The Gateway MUST NOT silently route to an alternate local execution target.

Kill-switch state MUST be persistently represented where required by the deployment architecture.

T-K8S-39 owns the external routing kill switch.

---

# 33. GPU-7 Provider Control

Provider control state includes:

* enabled/disabled state;
* health;
* circuit breaker state;
* routing policy;
* credentials;
* logical model mappings;
* budget state;
* sensitivity policy.

Provider control state MUST be persisted where required for restart consistency.

T-K8S-38 owns external routing control state.

---

# 34. GPU-6 Acceptance Gate

GPU-6 acceptance is design-only in the current specification.

It is not a release gate for GPU-5 or GPU-7.

Production hardening acceptance will be defined when GPU-6 implementation begins.

---

# 35. GPU-7 Provider Adapter

The external provider adapter MUST support, where the provider supports the corresponding capability:

* Chat Completions;
* Embeddings;
* Responses API;
* streaming;
* usage;
* error mapping;
* `include_usage`;
* provider request-ID preservation.

The adapter MUST normalize provider behavior into the canonical Synanton external API contract.

Provider-specific errors MUST be mapped according to §16.

Unsupported provider capabilities MUST NOT be silently converted into another operation.

---

# 36. PostgreSQL

GPU-5 reference PostgreSQL:

```text
PostgreSQL 16.x
```

Reference migrations:

```text
V1
V2
```

PostgreSQL stores persistent Gateway state including, as applicable:

* idempotency records;
* cost ledger;
* provider/control state;
* quota state;
* budget state;
* routing state.

GPU-5 reference deployment uses a single PostgreSQL instance.

GPU-6 defines PostgreSQL HA.

Schema changes MUST use expand/contract migration practices.

Idempotency retention is:

```text
24 hours
```

---

# 37. GPU-7 Acceptance

T-K8S-51 owns GPU-7 external routing acceptance.

The acceptance environment MUST include a mock provider.

The suite MUST verify:

* `/v1/models` returns external models;
* Chat Completions routes to provider;
* Embeddings routes to provider;
* Rerank routes when configured;
* Responses API routes when supported;
* streaming is preserved;
* provider usage is captured;
* provider request IDs are preserved;
* cost ledger records usage;
* budget enforcement works;
* sensitivity policy blocks prohibited routing;
* kill switch fails closed;
* circuit breaker behavior works;
* provider errors map through §16;
* provider timeouts map through §16;
* no local fallback occurs;
* no Envoy is required;
* no vLLM is required;
* no execution JWT is required.

---

# 38. GPU-7 Routing Control State

T-K8S-38 owns persistent external routing control state.

The state MUST distinguish:

* routing enabled;
* routing disabled;
* provider enabled;
* provider disabled;
* model mapping;
* provider health;
* circuit breaker state.

Control state MUST survive Gateway restart where persistence is required by policy.

---

# 39. GPU-7 Routing Modes

GPU-7 supports:

```text
external-only
fail-closed
```

The following are invalid for GPU-7:

```text
local-only
auto
```

Invalid routing mode configuration MUST prevent readiness.

---

# 40. Rerank and Provider Compatibility

Rerank is not assumed to be universally supported by external providers.

The Gateway MUST advertise or accept a rerank model only when the corresponding provider mapping supports it.

When a configured provider does not support rerank, the operation MUST return a deterministic capability error rather than silently converting the request.

---

# 41. vLLM Lifecycle

GPU-5 vLLM runs as a dedicated inference workload.

Each inference workload receives one GPU.

Reference policy:

* one GPU per inference workload;
* no MIG;
* no time slicing.

NVIDIA GPU Operator/device plugin provides GPU scheduling.

RuntimeClass:

```text
nvidia
```

Model weights are stored on node-local SSD.

Longhorn is not required for model weights.

Model weights MUST be preloaded from a versioned source.

Pod startup MUST NOT perform uncontrolled model downloads.

---

# 42. GPU-7 Ticket Sequence

The complete GPU-7 sequence is:

| Ticket    | Scope                                  |
| --------- | -------------------------------------- |
| T-K8S-38  | External routing control state         |
| T-K8S-39  | External routing kill switch           |
| T-K8S-40  | Provider registry                      |
| T-K8S-41  | Logical model → external model mapping |
| T-K8S-42  | OpenAI-compatible provider adapter     |
| T-K8S-43  | Provider credentials / secrets         |
| T-K8S-44  | Routing modes                          |
| T-K8S-45  | External provider circuit breaker      |
| T-K8S-46  | Provider health                        |
| T-K8S-47  | Provider usage                         |
| T-K8S-48  | Cost ledger                            |
| T-K8S-48b | Budget enforcement                     |
| T-K8S-49  | Sensitivity policy                     |
| T-K8S-50  | Error mapping                          |
| T-K8S-51  | External routing acceptance suite      |
| T-K8S-52  | Multi-provider routing                 |
| T-K8S-53  | GPU-7 deployment-mode packaging        |

T-K8S-53 depends on T-K8S-38 through T-K8S-52.

T-K8S-53 is expected to be the final GPU-7 ticket.

---

# 43. Security Boundaries

## GPU-5

```text
Public
  |
  v
Gateway
  |
  | signed JWT
  v
Envoy
  |
  v
vLLM
  |
  v
GPU
```

The security boundary prevents:

```text
Client → vLLM
Client → Envoy execution endpoint
```

## GPU-7

```text
Public
  |
  v
Gateway
  |
  | authenticated HTTPS
  v
External Provider
```

There is no trusted internal execution JWT boundary.

---

# 44. Design Decisions

1. GPU-5 and GPU-7 are separate deployment profiles.
2. GPU-5 uses Gateway → Envoy → vLLM.
3. Envoy is a separate Deployment/Service.
4. Envoy verifies Gateway-signed ES256 JWTs.
5. Direct client access to vLLM is prohibited.
6. GPU-7 has no Envoy or vLLM.
7. GPU-7 has no local fallback.
8. Local model weights are node-local and versioned.
9. One GPU is assigned per inference workload.
10. MIG and time slicing are not used.
11. PostgreSQL is required for persistent Gateway state.
12. API compatibility is OpenAI-oriented but limited to explicitly supported surfaces.
13. Unsupported capabilities return deterministic errors.
14. Error semantics are canonicalized in §16.
15. Rate-limit semantics are canonicalized in §20a.
16. Request IDs are canonicalized in §21.
17. Organization/project identity uses OpenAI-compatible headers.
18. Public `X-Project-ID` is removed.
19. Provider-reported usage is authoritative.
20. Unavailable usage is represented as `null`, not zero.
21. GPU-6 production hardening is deferred.
22. GPU-6 is not a GPU-5/GPU-7 release gate.
23. `auto` is reserved for a future hybrid deployment profile and is not valid for GPU-5 or GPU-7.

---

# 44b. GPU-7 Design Decisions

1. External providers are accessed directly from the Gateway.
2. Provider credentials are external secrets.
3. Provider/model mappings are explicit.
4. Provider health is tracked independently from Gateway health.
5. Circuit breakers prevent repeated unhealthy upstream calls.
6. Budget enforcement fails closed.
7. Sensitivity policy fails closed.
8. External routing kill switch fails closed.
9. Provider usage feeds the cost ledger.
10. Multi-provider routing is implemented only after the single-provider path is validated.
11. Provider capability gaps are surfaced explicitly.
12. Provider errors are normalized through §16.
13. Responses API is preserved where provider support exists.
14. The adapter MUST NOT silently convert unsupported Responses API requests to Chat Completions.

---

# 45. Execution Graph

## GPU-5

```text
T-K8S-0
  ↓
T-K8S-0b
  ↓
T-K8S-1
  ↓
T-K8S-1a
  ↓
T-K8S-2
  ↓
T-K8S-3
  ↓
T-K8S-4
  ↓
T-K8S-5
  ↓
T-K8S-6a
  ↓
T-K8S-6b
  ↓
T-K8S-6
  ↓
T-K8S-7
  ↓
T-K8S-8
  ↓
T-K8S-8a
  ↓
T-K8S-8b
  ↓
T-K8S-9
  ↓
T-K8S-9a
  ↓
T-K8S-10
  ↓
T-K8S-11
  ↓
T-K8S-12
  ↓
T-K8S-13
  ↓
T-K8S-14
  ↓
T-K8S-15a
  ↓
T-K8S-15b
```

## GPU-7

```text
T-K8S-38
  ↓
T-K8S-39
  ↓
T-K8S-40
  ↓
T-K8S-41
  ↓
T-K8S-42
  ↓
T-K8S-43
  ↓
T-K8S-44
  ↓
T-K8S-45
  ↓
T-K8S-46
  ↓
T-K8S-47
  ↓
T-K8S-48
  ↓
T-K8S-48b
  ↓
T-K8S-49
  ↓
T-K8S-50
  ↓
T-K8S-51
  ↓
T-K8S-52
  ↓
T-K8S-53
```

GPU-6 remains deferred:

```text
T-K8S-20 → T-K8S-37
```

It is not on the GPU-5 or GPU-7 release path.

---

# 46. T-K8S-53 Packaging

T-K8S-53 produces the GPU-7 deployment package.

The resulting package MUST demonstrate:

* no GPU nodes;
* no Envoy;
* no vLLM;
* external-only `/v1/models`;
* Chat Completions routed to provider;
* Embeddings routed to provider;
* Rerank routed when configured;
* Responses API routed when supported;
* no execution JWT;
* kill switch fail closed;
* budget enforcement fail closed;
* sensitivity policy fail closed;
* cost ledger records usage;
* T-K8S-51 passes against the mock provider.

The package MAY target Kubernetes or a single-node container deployment.

---

# 47. Change Summary — v2.1.9

The v2.1.9 change summary intentionally restarts numbering for this revision.

1. **Restored MUST semantics for Chat Completions terminal usage**

    * When `stream_options.include_usage=true` is requested and authoritative usage is available, the Gateway MUST emit terminal usage.
    * Unavailable authoritative usage continues to use the §10.2 `usage: null` contract.

2. **Clarified §8 title and pinned-value navigation**

    * §8 is now explicitly "Image and Version Pinning".
    * Added an index of operational pinned values.
    * Canonical definitions remain in their respective sections to prevent duplication drift.

3. **Clarified deployment-mode scope**

    * Removed `auto` from the current supported deployment baseline.
    * Explicitly reserved `auto` for a future hybrid deployment profile.
    * Confirmed `auto` is invalid for GPU-5 and GPU-7.

4. **Preserved all v2.1.8 normative restorations**

    * Complete §16 error mapping remains authoritative.
    * §13 organization/project headers and identity semantics remain authoritative.
    * §24 acceptance enumeration remains present.
    * v2.1.7 runtime-baseline clarification remains.
    * Complete GPU-5 and GPU-7 ticket sequences remain.
    * Responses API SSE wire format remains pinned.

---

# 48. Execution Instruction

Implementation MUST proceed from this specification and the ticket ownership defined herein.

Before execution freeze:

1. pin exact runtime versions;
2. pin immutable image digests;
3. validate canonical OpenAPI artifact;
4. validate packaged OpenAPI equality;
5. execute GPU-5 acceptance suite;
6. execute GPU-7 acceptance suite when GPU-7 implementation reaches T-K8S-51;
7. perform a section-by-section diff against v2.1.6;
8. confirm that no normative content was unintentionally removed;
9. record reviewer/approver;
10. record the freeze reference.

No implementation should treat an unpopulated freeze attestation as a frozen release.

---

# 49. Freeze Attestation

**Status:** Pre-implementation baseline

```text
Specification version: 2.1.9

Frozen by:
<reviewer/approver>

Frozen at:
<commit hash or document version>

Freeze date:
<date>

Supersedes:
2.1.8

Superseded by:
none
```

The specification MUST NOT be considered execution-frozen until the reviewer/approver and freeze reference are populated.

The v2.1.9 release candidate SHOULD be subjected to a section-by-section comparison against v2.1.6 before those fields are populated.

---

# 50. Final Scope Statement

Synanton GPU Plane v2.1.9 defines:

* a local GPU execution profile for GPU-5;
* a pure external-provider profile for GPU-7;
* a deferred production-hardening profile for GPU-6;
* an OpenAI-compatible external API for explicitly supported capabilities;
* canonical error and rate-limit semantics;
* OpenAI-compatible organization/project identity headers;
* explicit streaming and usage contracts;
* GPU-5 execution JWT security;
* GPU-7 external-provider controls;
* PostgreSQL-backed persistent control state;
* explicit ticket ownership and execution sequences;
* executable GPU-5 and GPU-7 acceptance requirements;
* deployment packaging requirements;
* a pre-freeze verification process.

The specification is intended to serve as the implementation baseline without silently dropping normative content from prior revisions.

**v2.1.9 is the candidate freeze revision pending the required v2.1.6 → v2.1.9 fidelity diff and freeze attestation.**
