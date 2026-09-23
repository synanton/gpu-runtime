# GPU-7 External Provider Deployment — Implementation Plan

**Status:** Package defined — gateway adapter partially in flight (`ExternalProviderRuntime` port, `OpenRouterRuntime`, `OpenRouterModelManager`, `ModelCatalogService` exist in `java/gpu-gateway`, dispatch strategy `openrouter`); remaining tickets T-K8S-38..52 land in the same module
**Revision date:** 2026-09-23
**Canonical spec:** `../../doc/GPU-5  GPU-6  GPU-7 Deployment Plan.md` (§2.3, §5.4, §7, §26, §29, §32–§42, §44b, §46)
**Consumer context:** `platform/docs/research/gpu-plane-integration-tickets.md` — the platform retrieval benchmark (T02/T03) is blocked until a reachable embedding endpoint exists; GPU-7 (external) and GPU-5 (local) are the two unblocking profiles.

---

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

`config/gateway-external.yaml` is the **deployment contract** the T-K8S-38..52 tickets implement against. It extends the existing code schema (`gpu-gateway.providers` / `gpu-gateway.model-catalog` from `OpenRouterRuntime`/`ModelCatalogService`) with the ticket-owned keys marked `[T-K8S-xx]`. Per spec it encodes:

| Spec | Control | Config key |
|---|---|---|
| §5.4/§39 | mode `external-only`; reject `local-only`/`auto` at startup (fail closed, §5.5) | `gpu-gateway.dispatch.strategy: external` |
| §26/T-K8S-40 | provider registry | `gpu-gateway.providers.<id>` (code schema exists: `providers.openrouter`) |
| T-K8S-41 | logical model → provider/model mapping | `gpu-gateway.model-catalog` (code schema exists) |
| T-K8S-42/§35 | OpenAI-compatible adapter: chat, embeddings, responses (when supported), streaming, usage, error mapping §16 | `ExternalProviderRuntime` port + `OpenRouterRuntime` (in flight); mock adapter for acceptance |
| T-K8S-43/§29 | provider credentials from env, never logged, HTTPS only | `providers.<id>.api-key: ${ENV_VAR}` |
| T-K8S-44/§39 | routing modes | `gpu-gateway.routing.external-enabled` |
| T-K8S-45 | circuit breaker per provider | `gpu-gateway.providers.<id>.circuit-breaker` |
| T-K8S-46 | provider health, independent of Gateway health | `gpu-gateway.providers.<id>.health` |
| T-K8S-47 | provider usage capture | usage accounting (PostgreSQL) |
| T-K8S-48/48b | cost ledger + budget enforcement, fail closed | `gpu-gateway.usage.cost-ledger`, `gpu-gateway.budget` |
| T-K8S-49 | sensitivity policy, fail closed | `gpu-gateway.sensitivity` |
| T-K8S-39/§32 | external routing kill switch, fail closed | `gpu-gateway.routing.external-enabled: false` |
| T-K8S-38/§33 | persistent routing control state (survives restart) | PostgreSQL |
| T-K8S-50/§16 | provider errors → canonical envelope | adapter (code) |

Rerank (§40): advertised only when the provider/model mapping supports it — with the
mock provider it is supported (`MOCK_RERANK_SUPPORTED=1`); when a provider lacks
rerank, `/v1/rerank` must return `capability_not_supported` (§16), never a silent
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
| 0 | — | `cp .env.example .env` and fill in; `docker compose up -d postgres mock-provider` | both healthy; mock answers `GET /v1/models` directly on :18080 |
| 1 | T-K8S-38/39/44 | routing control state + kill switch + mode validation | gateway with `mode=local-only` or `auto` refuses readiness; kill switch on → external calls denied |
| 2 | T-K8S-40..43 | provider registry, model mapping, adapter, credentials | `GET /v1/models` lists only mock-mapped models (§4.4: nothing advertised that can't resolve) |
| 3 | T-K8S-45..50 | circuit breaker, health, usage, cost ledger, budget, sensitivity, error mapping | usage rows in PostgreSQL; budget exhaustion → 429 `budget_exceeded`; provider 5xx → 502 `upstream_provider_error`; timeout → 504 |
| 4 | T-K8S-51 | acceptance suite against mock provider | §37 checklist green (below) |
| 5 | T-K8S-52/53 | multi-provider routing; package freeze | §46 packaging checklist green |

Smoke test at any point after phase 2: `./scripts/smoke-test.sh` (curl-based), or the
richer repo-root CLI (validates §10 streaming, §16 envelopes, §21 request IDs):

```bash
tools/gpu-plane-check.py --profile gpu7 --api-key "$GPU_DEV_API_KEY" all
```

## 5. Acceptance (spec §37 — T-K8S-51, all against the mock provider)

`/v1/models` returns external models · chat routes to provider · embeddings route · rerank routes when configured · Responses API routes when supported · streaming preserved · provider usage captured · provider request IDs preserved · cost ledger records usage · budget enforcement works · sensitivity policy blocks prohibited routing · kill switch fails closed · circuit breaker works · provider errors map through §16 · provider timeouts map through §16 · **no local fallback** · no Envoy · no vLLM · no execution JWT.

## 6. Secrets

`.env` (git-ignored) holds: `POSTGRES_PASSWORD`, `GPU_API_KEY_PEPPER`, `MOCK_PROVIDER_API_KEY`, and any real provider key (`OPENROUTER_API_KEY`, …). Provider credentials flow into config only as `${ENV_VAR}` references (§29), never as inline values, never logged. Real-provider traffic is HTTPS-only; the mock is plain HTTP inside the compose network (no external surface).

## 7. Relationship to GPU-5

Same gateway image, same PostgreSQL schema, different mode + config. GPU-5 adds the Envoy/JWT/vLLM execution perimeter on the homelab cluster (`deployments/homelab/`); GPU-7 removes it. GPU-6 hardening is deferred and gates neither (§25/§34).
