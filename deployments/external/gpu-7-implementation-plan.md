# GPU-7 External Provider Deployment — Implementation Plan

**Status:** Deployment contract defined; transport is gRPC `synanton.gpu.v1` (Deployment Plan v3.0.0 §4); implementation state in §0
**Revision date:** 2026-09-24
**Canonical spec:** `../../doc/GPU-5  GPU-6  GPU-7 Deployment Plan.md` (§2.3, §5.4, §7, §26, §29, §32–§42, §44b, §46)
**Consumer context:** `platform/docs/research/gpu-plane-integration-tickets.md` — the platform retrieval benchmark (T02/T03) is blocked until a reachable embedding endpoint exists; GPU-7 (external) and GPU-5 (local) are the two unblocking profiles.

---

## 0. Status — contract vs. implementation vs. acceptance (2026-09-25)

| Layer | State |
| --- | --- |
| **Deployment contract** | **Defined** — Deployment Plan **v3.1.0**; transport = gRPC `synanton.gpu.v1` over mTLS (§4, §13) |
| **Implementation** | **Complete for the contract** (table below). Only the §49 freeze attestation (a reviewer sign-off) is outstanding |
| **Real external arms** | Switchable in `.env`: **opencode.ai** (`providers.opencode`, current: cheap paid models, no embeddings; live `gpu7-check` 15/15, estimated spend $0.000063 = cost ledger) and **OpenRouter** (`providers.openai`, free models incl. EMBED; unreachable from this network since 2026-09-25, Cloudflare 403) — see §3a |
| **Acceptance (§37, T-K8S-51)** | **Passing** — `ExternalAcceptanceTest` (31 cases: real gRPC, PostgreSQL, fake providers); packaged `scripts/smoke-test.sh` (27 mock / 32 with the live arm); live `tools/gpu7-check` (20, OpenRouter free models incl. all three embedding arms and the `synanton-benchmark` principal, zero spend); `tools/gpu7-package-check.py --live` §46 checklist 16/16 |

| Capability | Ticket / spec | Where |
| --- | --- | --- |
| mTLS on gRPC; principal (cert CN) → tenant authorization | T-K8S-7/8, §13 | `CallerPrincipalInterceptor`, `CallerAuthorization`, `scripts/gen-certs.sh`, `doc/GPU Plane mTLS Setup.md` |
| Routing mode `external`, fail-closed startup | T-K8S-44, §5.5 | `ProviderRouter`, `GatewayStartupValidator` |
| Kill switch — configuration floor + runtime, persisted, audited | T-K8S-39, T-K8S-38 | `GPUControlService`, `RoutingControlService`, V4 |
| Provider registry, credentials, headers, spend guard | T-K8S-40, 43 | `ProviderRuntimeRegistry`, `allowed-model-pattern` |
| Logical → provider mapping + rewrite (every chunk/event) | T-K8S-41 | `ProviderRouter` → `OpenAiProviderRuntime` / `SseRelay` |
| Adapter: chat, embeddings, rerank, streaming, usage | T-K8S-42 | `OpenAiProviderRuntime` |
| **Responses API**: RESPOND, typed-event streaming, GetResponse/DeleteResponse | §4.6, §10.4 | `ResponsesService`, V5 |
| Circuit breaker; provider health | T-K8S-45, 46 | `CircuitBreaker`, `ProviderHealthMonitor` |
| Usage, cost ledger, budget | T-K8S-47, 48, 48b | `ExternalRoutingPolicy`, V2 |
| Sensitivity (model tags + request `data_tags`) | T-K8S-49 | `ExternalRoutingPolicy` |
| Canonical errors; upstream request IDs | T-K8S-50, §21/§35 | `ErrorInfo.code`, trailer, V3 |
| Multi-provider failover (never local, never duplicate) | T-K8S-52, §39a | `routeWithFallbacks`, `ExecuteService` |
| Zero-prompt logging verified | §20, T-K8S-12 | acceptance test + packaged smoke |
| Packaging: digest pinning, §46 checklist | T-K8S-53, §8 | `tools/pin-image-digests.sh`, `tools/gpu7-package-check.py` |

**By design, not implemented:** cross-replica shared health/circuit *decisions* (each replica protects its own traffic; state is visible via `GetRoutingControl`). GPU-6 hardening (HA, certificate rotation automation, CRL/OCSP) is out of scope (§25).

## 1. What GPU-7 is

Pure external-provider profile (spec §2.3):

```text
Client
  | HTTPS
  v
Gateway  (mode = external-only)
  | HTTPS, provider credentials
  v
External Provider (OpenAI-compatible)
```

Present: Gateway, PostgreSQL, provider adapter, routing/cost/budget/sensitivity controls.
Absent (§26): local GPU, NVIDIA GPU Operator, Envoy, vLLM, execution JWT.

```text
NO  local GPU     NO  Envoy     NO  vLLM     NO  execution JWT
```

## 2. Deployment form

Spec §2.3: *"Kubernetes is not required. A single-node container deployment is sufficient for the reference external-provider profile."* This package therefore uses **Docker Compose** — matching the platform repo's own preference for compose-first iteration (`retrieval-evaluation-benchmark-plan.md` §7).

```text
deployments/external/
├── gpu-7-implementation-plan.md   # this document
├── compose.yaml                   # gateway + postgres + mock-provider
├── .env.example                   # secrets template (copy to .env; .env is git-ignored)
├── config/
│   └── gateway-external.yaml      # deployment contract: providers, model mappings,
│                                  # routing mode, kill switch, budget, sensitivity
├── mock-provider/
│   ├── Dockerfile
│   └── mock_provider.py           # stdlib-only OpenAI-compatible mock (T-K8S-51)
└── scripts/
    └── smoke-test.sh              # positive + negative path checks against the mock
```

## 3. Configuration contract

`config/gateway-external.yaml` is the **deployment contract** the T-K8S-38..52 tickets implement against. It extends the existing code schema (`gpu-gateway.providers` / `gpu-gateway.model-catalog` from `OpenAiRuntime`/`ModelCatalogService`) with the ticket-owned keys marked `[T-K8S-xx]`. Per spec it encodes:

| Spec | Control | Config key                                                                                                                                                |
|---|---|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| §5.4/§39 | mode `external-only`; reject `local-only`/`auto` at startup (fail closed, §5.5) | `gpu-gateway.dispatch.strategy: external`                                                                                                                 |
| §26/T-K8S-40 | provider registry | `gpu-gateway.providers.<id>` (code schema exists: `providers.openai`)                                                                                     |
| T-K8S-41 | logical model → provider/model mapping | `gpu-gateway.model-catalog` (code schema exists)                                                                                                          |
| T-K8S-42/§35 | OpenAI-compatible adapter: chat, embeddings, rerank (when supported), streaming, usage, Responses API (RESPOND + GetResponse/DeleteResponse, Plan §4.6), error mapping §16 | `OpenAiProviderRuntime` (generic, all configured providers) — implemented at the runtime layer; Responses API + client-facing mapping await the HTTP face |
| T-K8S-43/§29 | provider credentials from env, never logged, HTTPS only | `providers.<id>.api-key: ${ENV_VAR}`                                                                                                                      |
| T-K8S-44/§39 | routing modes | `gpu-gateway.routing.external-enabled`                                                                                                                    |
| T-K8S-45 | circuit breaker per provider | `gpu-gateway.providers.<id>.circuit-breaker`                                                                                                              |
| T-K8S-46 | provider health, independent of Gateway health | `gpu-gateway.providers.<id>.health`                                                                                                                       |
| T-K8S-47 | provider usage capture | usage accounting (PostgreSQL)                                                                                                                             |
| T-K8S-48/48b | cost ledger + budget enforcement, fail closed | `gpu-gateway.usage.cost-ledger`, `gpu-gateway.budget`                                                                                                     |
| T-K8S-49 | sensitivity policy, fail closed | `gpu-gateway.sensitivity`                                                                                                                                 |
| T-K8S-39/§32 | external routing kill switch, fail closed | `gpu-gateway.routing.external-enabled: false`                                                                                                             |
| T-K8S-38/§33 | persistent routing control state (survives restart) | PostgreSQL                                                                                                                                                |
| T-K8S-50/§16 | provider errors → canonical envelope | adapter (code)                                                                                                                                            |

Rerank (§40): advertised only when the provider/model mapping supports it — with the
mock provider it is supported (`MOCK_RERANK_SUPPORTED=1`); when a provider lacks
rerank, RERANK executions must fail with `capability_not_supported` (§16), never a silent
conversion to another operation.

## 4. Phased bring-up

Prereq: build the gateway jar and image (same Dockerfile as GPU-5):

```bash
./gradlew bootJar
docker build -f deployments/homelab/docker/gateway.Dockerfile -t gpu-gateway:0.1.0 .
```

Then, from `deployments/external/`:

| Phase | Tickets | Action | Exit criteria |
|---|---|---|---|
| 0 | — | `cp .env.example .env` and fill in; `docker compose up -d postgres mock-provider` | both healthy; mock answers `GET /v1/models` directly on :18080 (provider-side HTTP) |
| 1 | T-K8S-38/39/44 | routing control state + kill switch + mode validation | gateway with `mode=local-only` or `auto` refuses readiness; kill switch on → external calls denied |
| 2 | T-K8S-40..43 | provider registry, model mapping, adapter, credentials | `GetModels` lists only mock-mapped models by logical ID (§4.4: nothing advertised that can't resolve) |
| 3 | T-K8S-45..50 | circuit breaker, health, usage, cost ledger, budget, sensitivity, error mapping | ledger rows in PostgreSQL; budget exhaustion → `RESOURCE_EXHAUSTED` `budget_exceeded`; provider 5xx → `upstream_provider_error`; timeout → `upstream_provider_timeout` |
| 4 | T-K8S-51 | acceptance suite against mock provider | §37 checklist green (below) |
| 5 | T-K8S-52/53 | multi-provider routing; package freeze | §46 packaging checklist green |

Smoke test at any point after phase 2 (requires `grpcurl`):

```bash
./scripts/smoke-test.sh                      # packaged stack, gRPC :9090
../../tools/gpu-grpc-call.sh localhost:9090 GetModels '{"operation":"SYNTHESIZE"}'
```

## 5. Acceptance (spec §37 — T-K8S-51, all against the mock provider)

`GetModels` returns external models · chat routes to provider · embeddings route · rerank routes when configured · streaming (`ExecuteStream`) preserved · provider usage captured · provider request IDs preserved · cost ledger records usage · budget enforcement works · sensitivity policy blocks prohibited routing · kill switch fails closed · circuit breaker works · provider errors map through §16 · provider timeouts map through §16 · **no local fallback** · no Envoy · no vLLM · no execution JWT.

Executable forms: unit/component tests in `java/gpu-gateway` and `scripts/smoke-test.sh` against the packaged compose stack (both over the gRPC contract).

## 3a. Real external provider arms (switchable)

| | OpenRouter | opencode.ai |
|---|---|---|
| Provider id | `openai` | `opencode` |
| Base URL | `https://openrouter.ai/api/v1` | `https://opencode.ai/zen/v1` |
| Switch / key | `OPENAI_PROVIDER_ENABLED`, `OPENAI_API_KEY` | `OPENCODE_PROVIDER_ENABLED`, `OPENCODE_API_KEY` |
| Logical models | `synanton-free-chat`, `synanton-free-responses`, `synanton-free-embedding[-nemotron-vl\|-lfm]` | `synanton-external-chat`, `synanton-external-responses` |
| Provider models (`.env`) | `OPENROUTER_*_MODEL` (free) | `OPENCODE_CHAT_MODEL=qwen3.8-flash`, `OPENCODE_CHAT_FALLBACK_MODEL=glm-5.3-flash`, `OPENCODE_RESPONSES_MODEL=gpt-6-luna` |
| Spend guard | `allowed-model-pattern: ".*:free"` | allowlist `OPENCODE_ALLOWED_MODEL_PATTERN` + catalog prices (`OPENCODE_*_USD_PER_M`) → cost ledger + budget (`GPU7_SMOKE_DAILY_USD`) |
| Operations | chat, streaming, Responses, EMBED | chat, streaming, Responses. **No** `/embeddings` or `/rerank` (404) |
| Headers | OpenRouter attribution | `User-Agent: synanton/1.0 (Synthesis & Semantics App)` (opencode.ai blocklists default UAs) and `x-opencode-session`, a stable per-tenant SHA-256 prefix for context caching (`providers.<id>.session-header`) |

Selection rationale (opencode.ai, probed 2026-09-25):
- **Free tier:** `*-free` and `big-pickle` are refused outside the OpenCode app (403 `FreeTierError`); the gateway doesn't impersonate the OpenCode client.
- **Chat:** `qwen3.8-flash` is the cheapest paid model that served chat completions. `jev-1.13` (cheaper) returned 503, and `glm-5.3-flash` is the fallback. The catalog price is the max of the two, so the ledger never under-counts a failed-over call.
- **Responses:** `gpt-6-luna` ($0.10/$0.50) is served only on `/responses`.

`GPU7_EXTERNAL_PROVIDER` selects the arm `tools/gpu7-check` validates. The retrieval benchmark's
EMBED arms need OpenRouter (or GPU-5); opencode.ai can't serve them.

## 6. Secrets and mTLS

`.env` (git-ignored; template `.env.example`) holds `POSTGRES_PASSWORD`, `MOCK_PROVIDER_API_KEY`, and the real provider keys and switches (`OPENAI_*` for OpenRouter, `OPENCODE_*` for opencode.ai, model IDs, prices, budgets). The gateway reads it via compose `env_file`. Provider credentials flow into config only as `${ENV_VAR}` references (§29), never inline, never logged. Real-provider traffic is HTTPS-only; the mock is plain HTTP inside the compose network.

The Gateway's gRPC port is **mTLS-only**. Generate the self-signed dev PKI before `docker compose up`:

```bash
./scripts/gen-certs.sh        # → ./certs (git-ignored): CA, server, clients synanton-platform, gpu7-smoke, synanton-benchmark
```

Principals (client-certificate CN → allowed tenants) are in `config/gateway-external.yaml` under `gpu-gateway.security`. Full instructions — verification, adding principals, rotation, revocation, Platform client settings, troubleshooting — are in `doc/GPU Plane mTLS Setup.md`.

## 7. Relationship to GPU-5

Same gateway image, same PostgreSQL schema, different mode + config. GPU-5 adds the Envoy/JWT/vLLM execution perimeter on the homelab cluster (`deployments/homelab/`); GPU-7 removes it. GPU-6 hardening is deferred and gates neither (§25/§34).
