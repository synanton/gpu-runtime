# Synanton GPU Plane — Deployment Specification

**Version:** 3.1.0
**Status:** Implementation baseline — platform transport is gRPC `synanton.gpu.v1` (§4)
**Revision date:** 2026-09-25
**Supersedes:** 3.0.0 (Responses API added, §4.6; see §47)
**Superseded by:** None

---

## 1. Purpose

This document defines the deployment, security, platform-transport contract, runtime, observability, acceptance, and operational requirements for the Synanton GPU Plane.

The GPU Plane's API is the gRPC contract `synanton.gpu.v1` consumed by the Synanton Platform (§4). There is no OpenAI-compatible REST API.

The specification covers three deployment stages:

| Stage | Purpose                                   |    Local GPU |    Envoy |     vLLM | Status                     |
| ----- | ----------------------------------------- | -----------: | -------: | -------: | -------------------------- |
| GPU-5 | Home/reference local inference deployment |     Required | Required | Required | Implementing — see §1a     |
| GPU-6 | Production hardening                      |     Required | Required | Required | Design only — deferred     |
| GPU-7 | Pure external-provider adapter deployment | Not required |       No |       No | Implemented — see §1a      |

GPU-5 and GPU-7 are intentionally separate deployment profiles.

GPU-6 is a production-hardening stage and is **not a prerequisite or release gate for GPU-5 or GPU-7**.

## 1a. Contract vs. Implementation vs. Acceptance (status 2026-09-25)

This specification is the **contract**. What the code implements and what acceptance proves are tracked separately so the document never overstates the build:

| Profile | Deployment contract | Implementation | Acceptance |
| --- | --- | --- | --- |
| GPU-5 | Defined (this spec; `deployments/homelab/`) | Manifests, Gateway routing config, gRPC transport, streaming (vLLM/TEI) done. **Missing:** execution-JWT signing + JWKS (T-K8S-6a) — Envoy therefore rejects Gateway→backend calls (fail closed); mTLS (T-K8S-7/8) | Phases 0–4 and per-service smoke executable; **§24 end-to-end blocked on T-K8S-6a**; no PoC run yet (plan §11) |
| GPU-7 | Defined (this spec; `deployments/external/`) | Complete for §4/§10/§16/§21/§29–§40, incl. registry, mapping, streaming, health, circuit breaker, cost ledger, budget, sensitivity, kill switch. **Not implemented:** persisted runtime control state (T-K8S-38, §33), mTLS (T-K8S-7/8), multi-provider selection policy (T-K8S-52). Responses API deferred (§4.6) | **§37 executable and passing:** `ExternalAcceptanceTest` (17) + packaged `smoke-test.sh` (23, incl. live OpenRouter free-model arm) |

---

# 2. Architecture

## 2.1 GPU-5 Request Topology

```text
Synanton Platform
  |
  | gRPC synanton.gpu.v1 (:9090)
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

The Gateway is the sole entry point of the GPU Plane; its API is the gRPC contract (§4).

Envoy is a separate Deployment and Service. It is not a sidecar to the Gateway.

The Gateway signs an execution JWT for each request forwarded to the local inference execution perimeter.

Envoy verifies the JWT before forwarding to vLLM.

Direct client access to vLLM is prohibited.

---

## 2.2 GPU-5 Deployment Topology

Reference Kubernetes topology:

```text
                         +-------------------+
                         | Synanton Platform |
                         +---------+---------+
                                   |
                                   | gRPC synanton.gpu.v1
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
Synanton Platform
  |
  | gRPC synanton.gpu.v1 (:9090)
  v
Gateway
  |
  | HTTPS (OpenAI-compatible provider API)
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
| Platform transport (gRPC RPCs)  | §4.2                                |
| Transport decision              | §4.1                                |
| Responses API (deferred)        | §4.6                                |
| Image/version pinning           | §8                                  |
| Runtime baseline                | §9                                  |
| Streaming                       | §10                                 |
| Error envelope and mapping      | §16                                 |
| Quota and admission signaling   | §20a                                |
| Request IDs                     | §21                                 |
| Contract artifact (protobuf)    | `java/gpu-contract/src/main/proto/synanton/gpu/v1/gpu_execution_service.proto` |
| Contract validation ownership   | T-K8S-15a (§15)                     |

Where a concept is repeated for readability, the canonical section is authoritative. Other occurrences are summaries, implementation references, or ticket-specific acceptance criteria.

Ticket sections do not create alternate API definitions.

---

# 4. Platform Transport (gRPC)

## 4.1 Transport Decision

**Revision 3.0.0 (PR #15):** the GPU Plane's API is the gRPC service `synanton.gpu.v1.GPUExecutionService`, not an OpenAI-compatible REST API.

Rationale:

* the Synanton Platform — the GPU Plane's only client — integrates through `synanton.gpu.v1` (`platform/java/gateway/.../GpuExecutionClient`, gRPC on port `9090`);
* Platform Architecture 1.0 §8 requires the GPU runtime to conform to the **Platform-owned contract**, which is this protobuf contract;
* one transport avoids two divergent public contracts.

The earlier OpenAI-compatible REST surface (`/v1/models`, `/v1/chat/completions`, `/v1/embeddings`, `/v1/rerank`, `/v1/responses`), API-key headers and HTTP error envelopes are **removed from this specification**. The GPU Plane does not serve them. OpenAI-compatible JSON survives only *inside* the contract, as the `ExecutionRequest.payload` / `ExecutionResponse.result` / `ExecutionChunk.data` bytes exchanged with the runtimes.

The canonical contract artifact is:

```text
java/gpu-contract/src/main/proto/synanton/gpu/v1/gpu_execution_service.proto
```

It is **byte-identical** in `gpu-runtime` and `platform` and CI-enforced by `scripts/verify-gpu-contract-mirror.sh` (§15). A contract change is made in both repositories in the same change set.

## 4.2 RPCs — Canonical API Surface

| RPC | Semantics | GPU-5 | GPU-7 |
| --- | --- | :---: | :---: |
| `Execute(ExecutionRequest) → ExecutionResponse` | Unary SYNTHESIZE / EMBED / RERANK; blocks until completion or deadline | Yes | Yes |
| `ExecuteStream(ExecutionRequest) → stream ExecutionChunk` | Streaming SYNTHESIZE (§10) | Yes | Yes |
| `Cancel(CancelRequest) → CancelResponse` | Best-effort cancellation | Yes | Yes (no-op for providers without cancellation) |
| `GetStatus(GetStatusRequest) → ExecutionStatus` | Authoritative status; lazy lease reconciliation | Yes | Yes |
| `GetCapacity(GetCapacityRequest) → CapacityResponse` | Advisory capacity | Yes | Yes |
| `GetModels(GetModelsRequest) → GetModelsResponse` | Model discovery (§4.4) | Yes | Yes |
| `GetResponse(GetResponseRequest) → StoredResponse` | Responses API retrieve (§4.6) | No | Yes |
| `DeleteResponse(DeleteResponseRequest) → DeleteResponseResponse` | Responses API delete (§4.6) | No | Yes |
| `GPUControlService.*` | Routing control, admin role (§32/§33) | — | Yes |

The operation is selected by `ExecutionRequest.operation` (`SYNTHESIZE`, `EMBED`, `RERANK`, `RESPOND`), not by path. RERANK is available only where the model/provider mapping supports it (§40).

Ports: gRPC `9090`; actuator health/metrics `8091` (HTTP, not an API surface).

## 4.3 Out of Scope

The following are outside the contract and are not served:

```text
OpenAI REST API (any /v1/* path)            moderations, audio, images, files
fine-tuning, batches
assistants, vector stores, threads          API-key (sk-syn-/syn_live_) auth
```

An `ExecutionRequest` whose operation or model mapping is unsupported is denied with code `capability_not_supported` (§16). The Gateway MUST NOT silently translate an unsupported capability into another operation.

## 4.4 Model Discovery

`GetModels(operation, provider, tenant_id)` returns the models the active deployment can resolve for that operation.

* GPU-5 returns locally configured models; GPU-7 returns models mapped to configured external providers.
* A model MUST NOT be advertised unless it can actually be resolved and admitted by the active deployment.
* Clients see only **logical** model IDs. `ModelInfo.provider_model_id` is deprecated and never populated: provider model IDs are never exposed downstream.

## 4.5 Model Object

`synanton.gpu.v1.ModelInfo` is the canonical model object: `model_id` (logical), `display_name`, `provider` (provider name), `operation`, `is_default`, `max_input_tokens`, `max_output_tokens`, `embedding_dim`.

## 4.6 Responses API (GPU-7)

The OpenAI Responses API is part of the contract **for GPU-7** (revision 3.1.0). GPU-5 does not offer it: a `RESPOND` request on the local path is denied with `capability_not_supported`.

| Responses API operation | Contract |
| --- | --- |
| create | `Execute` / `ExecuteStream` with `operation = RESPOND`; `payload` = Responses request body |
| retrieve | `GetResponse(response_id)` |
| delete | `DeleteResponse(response_id)` |

Rules:

* **The Gateway owns response IDs.** The result is the response object with `id = resp_<execution-id>`. The provider's own ID is replaced in the object and in every stream event. Retrieve and delete are served from Gateway storage (PostgreSQL `responses`, V5), so they work with providers that do not store responses.
* The model ID is restored to the logical ID at the top level and in nested `response.model`. Provider model IDs never appear downstream.
* Usage comes from the response's `usage` (`input_tokens` / `output_tokens`) and feeds the cost ledger and budget like any other operation.
* `GetResponse` / `DeleteResponse` of an unknown, deleted, or other-tenant response → `NOT_FOUND` / `response_not_found`. Delete purges the stored object.
* A provider without Responses support fails the execution. The request is never converted to Chat Completions.

## 4.7 Contract Versioning

The protobuf package `synanton.gpu.v1` identifies the compatibility generation. Changes within `v1` are additive only (new fields, new RPCs, deprecation — never removal or renumbering). Breaking changes require `synanton.gpu.v2`.

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
| Gateway gRPC API          | `9090`    | §4.2                            |
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

# 10. Streaming Contract (`ExecuteStream`)

## 10.1 Stream Framing

`ExecuteStream` returns a server stream of `ExecutionChunk` messages:

* zero or more `data` messages — each carries **one** OpenAI-compatible `chat.completion.chunk` JSON object, in the order the runtime streamed it;
* then **exactly one** `terminal` message (`ExecutionResponse`: final state, usage, error, `upstream_request_id`), always last.

The terminal message replaces the SSE `data: [DONE]` marker: the runtime's `[DONE]` is consumed by the Gateway and MUST NOT appear in `data`. Duplicate provider `[DONE]` markers are suppressed; a provider stream that ends without `[DONE]` terminates with `upstream_provider_error`.

Every `data` chunk carries the **logical** model ID; the provider model ID never appears downstream. SSE comment/keep-alive lines are not forwarded. No non-contract JSON is inserted.

Only SYNTHESIZE may stream; other operations on `ExecuteStream` are denied with `capability_not_supported`. A runtime without streaming support fails the same way — never by silently switching to unary.

An idempotent replay of a completed request returns only the terminal message (streamed content is not retained).

## 10.2 Usage

Provider/runtime-reported usage is authoritative. When the request payload sets `stream_options.include_usage = true`, the usage-bearing chunk is forwarded as a `data` message and the same usage appears in the terminal `usage`.

When authoritative usage is unavailable, the terminal `usage` field is **unset** (the protobuf equivalent of `usage: null`). A zero-valued usage MUST NOT be substituted.

## 10.3 GPU-7 Usage

The GPU-7 adapter always requests `stream_options.include_usage = true` upstream so that authoritative usage reaches the cost ledger (§36). The usage-bearing chunk is forwarded downstream only when the client asked for it.

## 10.4 Responses API Streaming

`ExecuteStream` with `RESPOND` yields one `data` message per typed Responses event (the event JSON carries its `type`: `response.created`, `response.output_text.delta`, …), followed by exactly one `terminal` message. The stream ends at the first terminal event type — `response.completed`, `response.incomplete` or `response.failed`. `[DONE]` is never part of the Responses protocol and is never forwarded, even if a provider sends it. The terminal message's `result` is the final response object, retrievable later with `GetResponse`. `stream_options.include_usage` does not apply: usage is inside the terminal event.

---

# 11. Request Processing

The Gateway processing sequence is:

```text
1. Receive gRPC request (Execute / ExecuteStream)
2. Authenticate caller (mTLS principal — §13; not yet implemented)
3. Validate fields (§21)
4. Idempotency lookup on request_id (§18)
5. Resolve routing decision: deployment mode, logical model, provider (ProviderRouter)
6. Apply provider health, sensitivity policy and budget controls (GPU-7, §29)
7. Admit: model concurrency (§20a)
8. Rewrite logical → provider model ID and execute
9. Record usage and cost ledger entry (GPU-7)
10. Return response / stream
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
SHA-256(raw request body bytes forwarded to Envoy)
```

The hash MUST be computed over the exact body bytes the Gateway forwards (the `ExecutionRequest.payload` after model-ID rewriting), before any JSON re-serialization by a downstream hop.

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

# 13. Caller Authentication and Tenant Identity

## 13.1 Caller

The only client of the GPU Plane is the Synanton Platform, as a service principal.

The contract-level authentication mechanism is **mTLS** on the gRPC channel (T-K8S-7 transport security, T-K8S-8 principal validation). `ErrorReason.UNAUTHORIZED` is reserved for mTLS failures.

**Implementation state:** implemented. `gpu-gateway.security.mode: mtls` (the default) requires a client certificate signed by the configured CA; the certificate CN is the principal, mapped in `gpu-gateway.security.principals.<cn>.tenants` to the tenants it may assert. Missing/foreign certificate → handshake refused; unregistered CN → `UNAUTHENTICATED` (`unauthenticated`); unauthorized `tenant_id` → `PERMISSION_DENIED` (`tenant_not_allowed`); another tenant's execution on `GetStatus`/`Cancel` → not found. `insecure-plaintext` exists for tests only. Setup with a self-signed PKI: `doc/GPU Plane mTLS Setup.md`.

## 13.2 Tenant Identity

`ExecutionRequest.tenant_id` is a tenant **assertion**, not a credential. The Gateway validates it against the authenticated principal's permitted tenants (`tenant_not_allowed` on mismatch) before any routing, budget or admission step. Budget and quota controls key on `tenant_id`.

API keys (`sk-syn-...`, `syn_live_...`), `OpenAI-Organization` / `OpenAI-Project` headers and API-key peppers are **not part of the contract** (§4.1); end-user and project identity is resolved by the Platform (Identity 1.29) before it calls the GPU Plane.

---

# 14. Model Registry

The model registry maps logical Synanton model IDs to execution targets.

GPU-5 mappings resolve to local vLLM deployments.

GPU-7 mappings resolve to external provider/model pairs.

A model MUST NOT be advertised unless its configured target is valid.

At least one local rerank-capable model MUST be available in the GPU-5 reference deployment.

---

# 15. Contract Validation

The canonical artifact is the protobuf contract (§4.1).

CI MUST enforce:

* byte-identical `gpu_execution_service.proto` in `gpu-runtime` and `platform` (`scripts/verify-gpu-contract-mirror.sh`, wired into `./gradlew check`; T-K8S-15a);
* successful code generation and compilation of both repositories against it;
* additive-only evolution within `synanton.gpu.v1` (§4.7).

T-K8S-15b (formerly OpenAPI validation) is retired: there is no OpenAPI artifact.

---

# 16. Error Contract

## 16.1 Error Surfaces

Errors reach the client on exactly one of two surfaces:

1. **Pre-execution denial** — the request is never admitted or dispatched. The RPC fails with a gRPC status; the canonical code is in the trailer `x-synanton-error-code` and prefixed to the status description (`<code>: <message>`).
2. **Execution failure** — the request was admitted. The RPC succeeds with `state = FAILED` and `error = ErrorInfo{reason, code, message, retryable}` (for `ExecuteStream`, in the terminal message).

`ErrorInfo.code` is the authoritative fine-grained code; `ErrorInfo.reason` is the coarse `ErrorReason` category. `message` is diagnostic only, never contains provider response bodies, prompts, or credentials, and is not for user-facing rendering.

## 16.2 Canonical Code Mapping

| Condition | Surface | gRPC status / `reason` | `code` |
| --- | --- | --- | --- |
| No/unregistered client principal (mTLS) | denial | `UNAUTHENTICATED` | `unauthenticated` |
| Principal may not act for `tenant_id` | denial | `PERMISSION_DENIED` | `tenant_not_allowed` |
| Field validation failure (incl. request ID) | denial | `INVALID_ARGUMENT` | `invalid_request` |
| Same `request_id`, different request | denial | `INVALID_ARGUMENT` | `idempotency_conflict` |
| Model not in catalog/registry | denial | `NOT_FOUND` | `model_not_found` |
| Model lacks the operation / non-streamable operation | denial | `FAILED_PRECONDITION` | `capability_not_supported` |
| Catalog provider not configured | denial | `FAILED_PRECONDITION` | `provider_not_configured` |
| Provider disabled or unhealthy | denial | `UNAVAILABLE` | `provider_unavailable` |
| External routing kill switch off | denial | `PERMISSION_DENIED` | `routing_disabled` |
| LOCAL request in external-only mode | denial | `PERMISSION_DENIED` | `no_local_fallback` |
| Sensitive model/request routed externally | denial | `PERMISSION_DENIED` | `sensitive_model_external_blocked` |
| Tenant daily budget exhausted | denial | `RESOURCE_EXHAUSTED` | `budget_exceeded` |
| Budget/ledger state unavailable | denial | `UNAVAILABLE` | `budget_state_unavailable` |
| Model concurrency limit | denial | `RESOURCE_EXHAUSTED` | `concurrency_limit_reached` |
| Capacity / quota exceeded | denial | `RESOURCE_EXHAUSTED` | `capacity_exceeded` |
| Provider 5xx / malformed response / broken stream | failure | `EXECUTION_FAILED` | `upstream_provider_error` |
| Provider timeout | failure | `EXECUTION_TIMEOUT` | `upstream_provider_timeout` |
| Provider connection failure | failure | `GPU_UNAVAILABLE` | `provider_unavailable` |
| Provider circuit open (no provider call made) | failure | `GPU_UNAVAILABLE` | `circuit_open` |
| Provider rate limit (429) | failure | `GPU_UNAVAILABLE` | `provider_rate_limited` |
| Provider rejected credentials (401/403) | failure | `EXECUTION_FAILED` | `provider_auth_failed` |
| Provider capability gap (e.g. rerank unsupported) | failure | `INVALID_REQUEST` | `capability_not_supported` |
| Local model load failure | failure | `MODEL_LOAD_TIMEOUT` | `model_load_failed` |
| Local runtime unavailable / failed | failure | `GPU_UNAVAILABLE` / `EXECUTION_FAILED` | `runtime_unavailable` / `runtime_failed` |
| Cancelled | failure | `EXECUTION_CANCELLED` | `execution_cancelled` |
| Unexpected Gateway failure | denial | `INTERNAL` | `internal_error` |

Implementations MUST NOT invent alternate codes for conditions covered by this table.

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

The idempotency key is `ExecutionRequest.request_id`; it is caller-generated and identifies a logical submission/request.

The same key with the same request MUST return the same logical operation/result.

The same key with a different request MUST return:

```text
gRPC INVALID_ARGUMENT
x-synanton-error-code: idempotency_conflict
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
Platform → Gateway (gRPC)
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

**Implementation state (GPU-7 path):** verified by `ExternalAcceptanceTest.promptsCompletionsAndCredentialsNeverReachTheLogs`. Prompt and completion canaries go through unary, streaming, embeddings, Responses, provider-failure and sensitivity-denial paths with gateway logging at DEBUG, and the captured logs contain no canary, no provider credential and no provider error body. `deployments/external/scripts/smoke-test.sh` repeats the check against the packaged container's logs. GPU-5 (Envoy/vLLM log levels, rotated logs) remains part of T-K8S-12 bring-up.

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

# 20a. Quota and Admission Signaling

**Implemented:** per-model admission concurrency (`gpu-gateway.models.<id>.concurrency-limit`; catalog models default to 8) → `RESOURCE_EXHAUSTED` / `concurrency_limit_reached`; GPU-7 per-tenant daily budget (§33) → `RESOURCE_EXHAUSTED` / `budget_exceeded`.

**Not implemented:** request/token rate limits (RPM, TPM, RPD, TPD). They are not enforced and therefore MUST NOT be advertised by the Gateway. There are no rate-limit headers (the transport is gRPC).

`GetCapacity` is advisory only and never reserves capacity.

---

# 21. Request IDs

`ExecutionRequest.request_id` is required and is the Platform's originating request identity **and** the idempotency key (§18).

Validation at the Gateway boundary (`invalid_request` on failure):

* `request_id`, `tenant_id`, `model`: 1–255 characters;
* `model_version`: 1–128 characters;
* `payload`: at most 4 MB (gRPC max inbound message size).

Every `ExecutionResponse`, `ExecutionStatus` and `ExecutionChunk` echoes `request_id`. `execution_id` is Gateway-generated.

GPU-7: the Gateway sends `request_id` to the provider as `x-request-id`, and records the provider's returned request ID as `upstream_request_id` (§35).

---

# 22. GPU-5 Ticket Sequence

The complete GPU-5 implementation sequence is:

| Ticket    | Scope                                      |
| --------- | ------------------------------------------ |
| T-K8S-0   | Cluster                                    |
| T-K8S-0b  | Namespace / registry                       |
| T-K8S-1   | Gateway containerization                   |
| T-K8S-1a  | Contract (proto) packaging                 |
| T-K8S-2   | Build / push                               |
| T-K8S-3   | PostgreSQL                                 |
| T-K8S-4   | Model registry / routing / mode validation |
| T-K8S-5   | vLLM                                       |
| T-K8S-6a  | JWT signing / JWKS                         |
| T-K8S-6b  | Envoy `jwt_authn`                          |
| T-K8S-6   | Envoy execution perimeter                  |
| T-K8S-7   | gRPC transport security (mTLS) / exposure  |
| T-K8S-8   | Caller principal validation (mTLS)         |
| T-K8S-8a  | Retired (API keys removed, §4.1)           |
| T-K8S-8b  | Retired (API-key pepper removed, §4.1)     |
| T-K8S-9   | Health / Prometheus                        |
| T-K8S-9a  | Tenant cardinality rule                    |
| T-K8S-10  | Node-local model storage                   |
| T-K8S-11  | NetworkPolicy                              |
| T-K8S-12  | Zero-prompt logging                        |
| T-K8S-13  | Idempotency                                |
| T-K8S-14  | Acceptance suite                           |
| T-K8S-15a | Protobuf contract mirror                   |
| T-K8S-15b | Retired (no OpenAPI artifact, §15)         |

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

Quota and budget policy is keyed on `ExecutionRequest.tenant_id` (§13.2); there are no API keys in the contract.

---

# 24. GPU-5 Acceptance

T-K8S-14 owns the GPU-5 acceptance suite. It MUST run against the packaged deployment over the gRPC transport (`tools/gpu-grpc-call.sh`, `deployments/homelab/scripts/smoke-test.sh`), not only against unit-test classpaths.

## 24.1 Positive Cases

1. `GetModels` returns exactly the configured local models (logical IDs only).
2. `Execute` SYNTHESIZE succeeds (Qwen3-4B on node3).
3. `Execute` EMBED succeeds (BGE-base via TEI on node1).
4. `Execute` RERANK succeeds against the configured rerank-capable model (node2).
5. `ExecuteStream` yields `chat.completion.chunk` data messages followed by exactly one terminal message (§10).
6. Usage is reported when authoritative usage is available; unset otherwise.
7. `request_id` is echoed on every response.
8. Repeated identical requests resolve to the same execution (§18).
9. `GetStatus` is authoritative after an `Execute` deadline.
10. Envoy accepts correctly signed Gateway execution JWTs and valid requests reach the backends.

## 24.2 Negative Cases

1. Unknown model → `NOT_FOUND` / `model_not_found`.
2. Invalid/oversized fields → `INVALID_ARGUMENT` / `invalid_request`.
3. Same `request_id`, different request → `idempotency_conflict`.
4. Unsupported operation for a model → `capability_not_supported`.
5. Direct access to vLLM/TEI is denied (NetworkPolicy).
6. Invalid execution JWT is rejected by Envoy.
7. Missing/invalid JWT verification material fails closed.
8. Gateway gRPC is not reachable from outside the platform network (§13.1).

## 24.3 Admission Cases

1. Model concurrency exhaustion → `RESOURCE_EXHAUSTED` / `concurrency_limit_reached`.
2. No rate-limit dimension that is not enforced is advertised (§20a).

## 24.4 Contract Cases

1. The packaged Gateway's proto equals the canonical artifact (§15).
2. Every denial carries `x-synanton-error-code`; every failure carries `ErrorInfo.code` (§16).
3. Stream framing matches §10.1 (exactly one terminal, no `[DONE]` in data).
4. Usage semantics match §10.2; request IDs match §21.

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

**Implementation state:** implemented at two levels. Configuration (`gpu-gateway.routing.external-enabled: false`) is the floor. The **runtime kill switch** is `GPUControlService.SetExternalRouting` (admin role, reason required). It is persisted in PostgreSQL (`routing_control`, audited in `routing_control_audit`), survives restarts, and reaches every replica within 1 s. Either level off → `routing_disabled`; nothing external is advertised. Runtime control cannot enable what configuration disabled (`config_disabled`). Unreadable control state denies external routing (`routing_state_unavailable`).

```bash
GPU_GRPC_CLIENT=synanton-platform tools/gpu-grpc-call.sh localhost:9090 \
  GPUControlService/SetExternalRouting '{"enabled":false,"reason":"incident 42"}'
```

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

**Implementation state (3.0.0):**

| Control | Implemented | Where the state lives |
| --- | --- | --- |
| Provider enabled/disabled | Yes | configuration floor + runtime `GPUControlService.SetProviderEnabled` (PostgreSQL `routing_control`, audited) |
| Credentials | Yes | environment → configuration (§29) |
| Logical model mappings | Yes | configuration (`model-catalog`) |
| Health | Yes (`ProviderHealthMonitor`, `health.path/interval-seconds/failure-threshold`) | observed per replica (re-probed at startup); reported by `GetRoutingControl` |
| Circuit breaker | Yes (`circuit-breaker.failure-threshold/reset-seconds`) | observed per replica; reported by `GetRoutingControl` |
| Kill switch | Yes | configuration floor + runtime `SetExternalRouting` (PostgreSQL, audited) |
| Budget | Yes (per-tenant UTC-day limit, `budget.*`) | PostgreSQL `cost_ledger` |
| Sensitivity policy | Yes (model `tags` + request `data_tags` vs `sensitivity.block-external-tags`) | configuration |
| Runtime-mutable control state | Yes — `GPUControlService` (admin role) | PostgreSQL `routing_control` + `routing_control_audit` (V4) |
| Cross-replica shared health/breaker *decisions* | No — each replica decides from its own observations (by design: a breaker protects the replica's own traffic) | — |

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
* Rerank (where the provider supports it);
* streaming;
* usage;
* error mapping;
* `include_usage`;
* provider request-ID preservation.

The adapter MUST normalize provider behavior into the `synanton.gpu.v1` contract: logical model IDs downstream, canonical error codes (§16), usage, and `upstream_request_id`.

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
V1  executions, artifact_cache
V2  cost_ledger (GPU-7 cost ledger / budget state)
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

T-K8S-51 owns GPU-7 external routing acceptance. The acceptance environment MUST include the mock provider. Executable forms: `ExternalAcceptanceTest` (in-process gRPC server → `ExecuteService` → `ProviderRouter` → fake provider, PostgreSQL via Testcontainers) and `deployments/external/scripts/smoke-test.sh` (packaged compose stack).

The suite MUST verify:

* `GetModels` returns external models by logical ID and never exposes provider model IDs;
* SYNTHESIZE / EMBED route to the provider; RERANK routes when configured;
* logical → provider model-ID rewrite upstream; logical ID restored on every downstream body, including every stream chunk;
* `ExecuteStream` preserves chunk order, exactly one terminal, `include_usage` → usage chunk + terminal usage;
* provider usage captured; `upstream_request_id` preserved;
* cost ledger records usage; budget exhaustion → `budget_exceeded`;
* sensitive model/request → `sensitive_model_external_blocked`;
* kill switch off → `routing_disabled`;
* circuit breaker opens and denies without a provider call;
* provider 5xx → `upstream_provider_error`; provider timeout → `upstream_provider_timeout`;
* provider lacking rerank → `capability_not_supported`;
* LOCAL request, unavailable provider, or unknown provider → denied — **no local fallback**;
* Responses API: `RESPOND` create (unary and streaming, typed events, no `[DONE]`), Gateway response IDs, `GetResponse` / `DeleteResponse`, `response_not_found` after delete;
* no Envoy, no vLLM, no execution JWT required.

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

**Implementation state:** routing enabled/disabled and provider enabled/disabled are persisted runtime state (PostgreSQL, via `GPUControlService`) layered over configuration, and survive restarts. Model mappings are configuration. Health and circuit-breaker state are observed per replica, rebuilt after a restart, and visible through `GetRoutingControl`.

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

# 39a. GPU-7 Multi-Provider Failover (T-K8S-52)

A catalog model may list ordered external `fallbacks` (`provider`, `provider-model-id`, optional prices). Failover rules:

* A fallback is tried **only when the current provider did not accept the request**: connect failure, open circuit, 429, 502/503/504, or a provider that is disabled, unhealthy or runtime-disabled before dispatch. An accepted request that then fails (e.g. provider 500, timeout, broken stream) is **never re-sent**, so no request executes twice. For streams, no chunk has been emitted when failover happens.
* Sensitivity, budget and the kill switch apply to every candidate. Their denials are final and never trigger failover.
* A fallback is never LOCAL (startup fails otherwise, invariant 1). `allowed-model-pattern` applies to fallbacks too.
* The cost ledger records the provider that served, at the fallback's prices when set, otherwise the model's.
* `GetModels` advertises a model if any candidate is usable.

**Implementation state:** implemented (`ProviderRouter.routeWithFallbacks`, `ExecuteService`); covered by `ExternalAcceptanceTest`.

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
Synanton Platform
  |
  | gRPC (mTLS — §13)
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
Platform/other → vLLM
Platform/other → Envoy execution endpoint
```

## GPU-7

```text
Synanton Platform
  |
  | gRPC (mTLS — §13)
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
12. The platform transport is gRPC `synanton.gpu.v1`; OpenAI-compatible JSON exists only inside payload/result bytes.
13. Unsupported capabilities return deterministic errors.
14. Error semantics are canonicalized in §16.
15. Quota/admission semantics are canonicalized in §20a.
16. Request IDs are canonicalized in §21.
17. Caller identity is the Platform service principal (mTLS); tenant_id is an assertion (§13).
18. API keys and OpenAI identity headers are not part of the contract.
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
13. The Responses API is a GPU-7 capability with Gateway-owned response IDs (§4.6).
14. Provider model IDs are never exposed downstream.

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
* external-only `GetModels`;
* SYNTHESIZE (unary and streaming) routed to provider;
* EMBED routed to provider;
* RERANK routed when configured;
* RESPOND (Responses API) create/retrieve/delete;
* no execution JWT;
* kill switch fail closed;
* budget enforcement fail closed;
* sensitivity policy fail closed;
* cost ledger records usage;
* T-K8S-51 passes against the mock provider.

The package MAY target Kubernetes or a single-node container deployment.

---

# 47. Change Summary — v3.1.0

1. **Responses API added (GPU-7):** `Operation.RESPOND`, `GetResponse`, `DeleteResponse`, Gateway-owned response IDs, typed-event streaming (§4.6, §10.4); PostgreSQL `responses` (V5).
2. **GPUControlService** routing-control RPCs (§32/§33/§38); mTLS caller authentication and tenant authorization (§13); multi-provider failover (§39a).

---

# 47-3.0.0. Change Summary — v3.0.0

1. **Transport decision (PR #15):** the GPU Plane's API is gRPC `synanton.gpu.v1` (§4). The OpenAI-compatible REST API, API-key authentication, OpenAI identity headers, HTTP error envelopes and rate-limit headers are removed; the Responses API is deferred.
2. **Contract additions (additive, mirrored in `platform`):** `ExecuteStream` RPC and `ExecutionChunk`; `upstream_request_id` on `ExecutionResponse`/`ExecutionStatus`; `ErrorInfo.code`; `ExecutionRequest.data_tags` (sensitivity); `ModelInfo.provider_model_id` deprecated and never populated.
3. **§10** streaming, **§13** caller auth, **§15** contract validation, **§16** error codes, **§20a** quota, **§21** request IDs, **§24**/**§37** acceptance rewritten for gRPC.
4. **Ticket changes:** T-K8S-7/8 reframed to mTLS; T-K8S-8a/8b/15b retired.

---

# 47a. Change Summary — v2.1.9 (superseded)

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
3. validate the protobuf contract mirror (§15);
4. validate the packaged Gateway was built from that contract;
5. execute GPU-5 acceptance suite;
6. execute GPU-7 acceptance suite when GPU-7 implementation reaches T-K8S-51;
7. perform a section-by-section diff against v2.1.9;
8. confirm that no normative content was unintentionally removed;
9. record reviewer/approver;
10. record the freeze reference.

No implementation should treat an unpopulated freeze attestation as a frozen release.

---

# 49. Freeze Attestation

**Status:** Implementation baseline (not frozen)

```text
Specification version: 3.1.0

Frozen by:
<reviewer/approver>

Frozen at:
<commit hash or document version>

Freeze date:
<date>

Supersedes:
3.0.0

Superseded by:
none
```

The specification MUST NOT be considered execution-frozen until the reviewer/approver and freeze reference are populated.

The v3.0.0 revision SHOULD be subjected to a section-by-section comparison against v2.1.9 before those fields are populated.

---

# 50. Final Scope Statement

Synanton GPU Plane v3.1.0 defines:

* a local GPU execution profile for GPU-5;
* a pure external-provider profile for GPU-7;
* a deferred production-hardening profile for GPU-6;
* a gRPC platform transport (`synanton.gpu.v1`) shared byte-identically with the Platform;
* canonical error and admission/quota semantics;
* explicit streaming (`ExecuteStream`) and usage contracts;
* GPU-5 execution JWT security;
* GPU-7 external-provider controls;
* PostgreSQL-backed persistent control state;
* explicit ticket ownership and execution sequences;
* executable GPU-5 and GPU-7 acceptance requirements;
* deployment packaging requirements;
* a pre-freeze verification process.

The specification is intended to serve as the implementation baseline without silently dropping normative content from prior revisions.

**v3.1.0 is the implementation baseline pending the fidelity diff and freeze attestation.**
