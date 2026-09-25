# Synanton GPU Runtime

[![Status](https://img.shields.io/badge/Status-Development-blue)](https://github.com/Synanton/gpu-runtime)
[![Java](https://img.shields.io/badge/Java-21-red)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![gRPC](https://img.shields.io/badge/gRPC-Protobuf-purple)](https://grpc.io/)

## Overview

The **GPU Runtime** is a physically isolated, on-premise optimized service responsible for executing GPU-intensive inference workloads (LLMs, Embeddings, Reranking) on behalf of the **Synanton Platform**.

It acts as the exclusive bridge between the business logic of Synanton and the physical GPU infrastructure, ensuring a strict separation of concerns.

### Architectural Invariant
- **Synanton Platform** determines **WHAT** should execute (business intent, model selection, tenant identity).
- **GPU Runtime** determines **HOW** it executes (scheduling, admission, runtime lifecycle).
- **Kubernetes** determines **WHERE** it executes (node placement, GPU device allocation).

> **Critical Constraint**: The Main Platform is strictly prohibited from directly discovering or accessing Kubernetes Pods, GPU nodes, physical GPUs, or vLLM endpoints. The GPU Gateway is the **sole network entry point**.

---

## Key Features

- **Physical Isolation**: Runs in a dedicated Kubernetes cluster, completely separated from the Main Synanton control plane.
- **Idempotent Execution**: Uses `request_id` + deterministic request hashing to guarantee at-most-once execution semantics, backed by a fail-closed PostgreSQL store.
- **Intelligent Admission Control**: Validates tenant assertions, model capabilities, token context limits, and dynamic concurrency quotas *before* scheduling.
- **vLLM Integration**: Abstracts vLLM (and future runtimes like TGI) behind a clean `ExecutionRuntime` interface with robust retry safety (`RetryDisposition`).
- **Logical Capacity API**: Exposes only logical model concurrency (`available_concurrency`) to the Main Platform, never physical GPU topology.
- **Shared Model Artifact Cache**: Downloads models once from the internal registry, verifies integrity via SHA-256 digests, and shares them read-only across all vLLM replicas.
- **Cost Attribution**: Reports `input_tokens`, `output_tokens`, and duration back to the Main Platform (`UsageReport` on `synanton.gpu.v1`) for tenant chargeback, without exposing infrastructure details.
- **Lazy Reconciliation**: Handles runtime failures deterministically via `GetStatus()` calls—no complex background controllers required in v1.20.

---

## Architecture

### High-Level Component Flow

```text
┌─────────────────────────────────────────────────────────────────┐
│                     MAIN Synanton PLATFORM                      │
│  (Business Intent, Model Selection, Tenant Auth, CPU Fallback)  │
└───────────────────────────────┬─────────────────────────────────┘
                                │ gRPC / mTLS
                                │ Signed Execution Assertion
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                      GPU Runtime                        │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    GPU Gateway                            │  │
│  │  (AuthN/AuthZ, Idempotency, Execution State)              │  │
│  └───────────────────────────┬───────────────────────────────┘  │
│                              ▼                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                Admission Controller                       │  │
│  │  (Quotas, Concurrency, Token Limits, Model Capabilities)  │  │
│  └───────────────────────────┬───────────────────────────────┘  │
│                              ▼                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │               Execution Scheduler                         │  │
│  │  DirectScheduler (Default)  │  EqualixScheduler (Future)  │  │
│  └───────────────────────────┬───────────────────────────────┘  │
│                              ▼                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                   Model Manager                           │  │
│  │  (Lifecycle, Capabilities, Artifact Resolution)           │  │
│  └───────────────────────────┬───────────────────────────────┘  │
│                              ▼                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │   ProviderRouter → VllmRuntime (GPU-5) |                  │  │
│  │                    OpenAiProviderRuntime (GPU-7)          │  │
│  └───────────────────────────┬───────────────────────────────┘  │
│                              ▼                                  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                       vLLM                                │  │
│  │  (StatefulSets/Deployments on GPU Nodes)                  │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  PostgreSQL (HA)        Shared Model Cache (ROX)                │
└─────────────────────────────────────────────────────────────────┘
```

### State Machine (Simplified)



```text
ACCEPTED → (Model Hot) → RUNNING → SUCCEEDED / FAILED / CANCELLED
ACCEPTED → QUEUED → MODEL_LOADING → RUNNING → SUCCEEDED / FAILED / CANCELLED

                        ACCEPTED
                           │
                      ┌────┴────┐
                      │         │
                 model READY  model loading
                      │         │
                      │       QUEUED ──────────────────────────┐
                      │         │                              │
                      │   MODEL_LOADING ─── load failure       │
                      │         │                              │
                      └────►  QUEUED (ready to dispatch)       │
                               │                               │
                            RUNNING ◄──────────────────────────┘
                               │
                  ┌────────────┼────────────┐
                  ▼            ▼            ▼
             SUCCEEDED      FAILED      CANCELLED
```



All non-terminal states can transition to `FAILED` or `CANCELLED`. Terminal states are irreversible.

---

## Technology Stack

| Layer                | Technology                                              |
| -------------------- | ------------------------------------------------------- |
| **Language**         | Java 21                                                 |
| **Framework**        | Spring Boot 3.x                                         |
| **Build**            | Gradle (Kotlin DSL)                                     |
| **RPC / API**        | gRPC + Protobuf + Protovalidate (PGV)                   |
| **Persistence**      | PostgreSQL + Flyway (plain JDBC / `JdbcTemplate`)       |
| **Observability**    | Micrometer + Prometheus, OpenTelemetry                  |
| **Testing**          | JUnit 5, AssertJ, Mockito, Testcontainers               |
| **Orchestration**    | Kubernetes + Helm                                       |
| **GPU Runtime**      | vLLM (initial), TGI (future)                            |
| **Artifact Storage** | Internal Registry + Shared Read-Only Cache (NFS/CephFS) |

> **Important**: The GPU Execution Plane deliberately **does not** use Redis, Kafka, or Cassandra. PostgreSQL is the sole authoritative source of execution state.

------

## Repository Structure


```text
gpu-runtime/                               # generated from `git ls-files` (2026-09-25)
├── build.gradle.kts · settings.gradle.kts · gradle.properties · gradle/libs.versions.toml
├── .cursor/rules/                         # java-rules.mdc, gpu-execution-rules.mdc
├── .github/workflows/                     # gradle.yml, gpu-contract-mirror.yml
│
├── doc/
│   ├── GPU-5  GPU-6  GPU-7 Deployment Plan.md   # canonical spec v3.0.0 (gRPC platform transport)
│   ├── GPU-5 Local Models Setup.md              # node-local model download/verify (uv venv)
│   ├── GPU Plane mTLS Setup.md                  # self-signed PKI, principals, rotation
│   ├── TEST_ENVIRONMENT_SETUP.md
│   └── GPU Execution Plane Implementation Plan v1.20.md / v1.21.md
│
├── deployments/
│   ├── homelab/                           # [GPU-5] local inference, one workload per GPU
│   │   ├── gpu-5-implementation-plan.md   #   phased plan, decisions D1–D7, acceptance
│   │   ├── claster-as-build.md            #   observed 4-node cluster inventory
│   │   ├── blueprints/                    #   plain manifests (phased kubectl bring-up)
│   │   │   ├── namespace.yaml
│   │   │   ├── tei/embedding.yaml         #     node1 GTX 1650 — TEI, BGE-base
│   │   │   ├── vllm/reranker.yaml         #     node2 RTX 4060 Ti — Qwen3-Reranker-0.6B
│   │   │   ├── vllm/synthesis.yaml        #     node3 RTX 5060 Ti — Qwen3-4B
│   │   │   ├── gateway/gateway.yaml       #     gRPC :9090, actuator :8091, routing ConfigMap
│   │   │   ├── envoy/                     #     execution perimeter (jwt_authn)
│   │   │   ├── postgres/postgres.yaml
│   │   │   └── network-policy/network-policies.yaml
│   │   ├── helm/gpu-plane/                #   chart mirroring the blueprints
│   │   ├── docker/gateway.Dockerfile      #   gateway image (T-K8S-1)
│   │   ├── docs/                          #   architecture, benchmarks, observability, troubleshooting
│   │   └── scripts/                       #   mirror-images, build-and-push, create-registry-secret,
│   │                                      #   deploy, smoke-test, record-vram, cluster-stop/start
│   └── external/                          # [GPU-7] external providers (no GPU/Envoy/vLLM)
│       ├── gpu-7-implementation-plan.md
│       ├── compose.yaml                   #   gateway + PostgreSQL + mock provider
│       ├── .env.example                   #   secrets template (.env is git-ignored)
│       ├── config/gateway-external.yaml   #   providers, catalog, kill switch, budget, sensitivity
│       ├── mock-provider/                 #   stdlib-only OpenAI-compatible mock (T-K8S-51)
│       └── scripts/                       #   gen-certs.sh (self-signed mTLS PKI), smoke-test.sh
│
├── java/
│   ├── gpu-contract/                      # synanton.gpu.v1 — byte-identical mirror of platform
│   │   └── src/main/proto/synanton/gpu/v1/gpu_execution_service.proto
│   └── gpu-gateway/                       # Spring Boot service (gRPC API; actuator only on HTTP)
│       └── src/main/
│           ├── java/org/synanton/gpu/
│           │   ├── adapter/in/grpc/       #   GpuExecutionGrpcAdapter, GpuControlGrpcAdapter,
│           │   │                          #   CallerPrincipalInterceptor, CallerAuthorization (mTLS)
│           │   ├── adapter/out/runtime/   #   VllmRuntime, OpenAiProviderRuntime, SseRelay,
│           │   │                          #   ProviderRuntimeRegistry, CircuitBreaker, ProviderHealthMonitor
│           │   ├── adapter/out/database/  #   JdbcExecutionRepository, JdbcCostLedger
│           │   ├── adapter/out/{registry,model,schedule}/
│           │   ├── domain/service/        #   ExecuteService, ProviderRouter, ExternalRoutingPolicy, …
│           │   ├── domain/{model,port}/
│           │   └── config/                #   GpuGatewayProperties, GatewayStartupValidator, gRPC lifecycle
│           └── resources/
│               ├── application.yml
│               └── db/migration/          #   V1 executions, V2 cost_ledger, V3 upstream_request_id,
│                                          #   V4 routing_control, V5 responses
│
├── scripts/verify-gpu-contract-mirror.sh  # proto mirror check vs platform (in ./gradlew check)
├── tools/
│   ├── gpu-grpc-call.sh                   # grpcurl wrapper (mTLS) for both gRPC services
│   ├── gpu7-check/                        # live GPU-7 validation, OpenRouter free models only (uv venv)
│   ├── gpu7-package-check.py              # §46 packaging checklist (T-K8S-53)
│   └── pin-image-digests.sh               # §8 digest pinning
└── README.md · LICENSE
```

------

## Roadmap

| Milestone | Purpose | Status |
|---|---|---|
| GPU-1 | Contract & deterministic semantics | Complete |
| GPU-2 | Domain core & persistence | Complete |
| GPU-3 | Runtime & model lifecycle | Complete |
| GPU-4 | Main Platform integration / contract mirror | Contract complete; runtime routing being validated |
| **GPU-5** | **Homelab local inference (Qwen3-4B synthesis + BGE-base embedding via TEI + Qwen3-Reranker 0.6B — one workload per GPU)** | **Contract defined; implementation done except execution-JWT (T-K8S-6a); acceptance blocked on T-K8S-6a + PoC run** |
| **GPU-7** | **External-provider profile (OpenAI-compatible provider adapter, no local GPU)** | **Complete for contract v3.1.0; §37 acceptance passing; freeze attestation pending sign-off** |
| **GPU-6** | **Production deployment and operational hardening** | **Deferred — not a GPU-5/GPU-7 gate** |

### Status: contract vs. implementation vs. acceptance (2026-09-25, PR #15)

The platform transport is **gRPC `synanton.gpu.v1` over mTLS** on :9090 (Deployment
Plan v3.1.0 §4, §13). There is no REST API; actuator (health/metrics) runs on :8091.
mTLS setup with self-signed certificates: [`doc/GPU Plane mTLS Setup.md`](doc/GPU%20Plane%20mTLS%20Setup.md).

| | GPU-5 (`deployments/homelab/`) | GPU-7 (`deployments/external/`) |
| --- | --- | --- |
| **Deployment contract** | Defined | Defined (v3.1.0) |
| **Implementation** | One workload per GPU (node1 TEI embedding, node2 vLLM reranker, node3 vLLM synthesis); Gateway routing ConfigMap; mTLS; streaming. **Missing:** execution-JWT signing/JWKS (T-K8S-6a) | **Complete:** mTLS + tenant authorization, provider registry, logical→provider rewrite, streaming, **Responses API**, canonical errors, circuit breaker, health, cost ledger, budget, sensitivity, kill switch (config + persisted runtime `GPUControlService`), multi-provider failover, upstream request IDs, zero-prompt logging verified, digest-pinned packaging |
| **Acceptance** | **Blocked:** Envoy rejects Gateway→backend calls until T-K8S-6a (fail closed); no PoC run yet. Phases 0–4 + per-service smoke executable | **Passing:** `ExternalAcceptanceTest` 31/31; packaged `smoke-test.sh`; live [`tools/gpu7-check`](tools/gpu7-check/README.md) 20/20 (OpenRouter free models, zero spend); §46 checklist `tools/gpu7-package-check.py --live` 16/16. Freeze attestation (§49) awaits reviewer sign-off |

GPU-5 model state: Qwen3 weights verified; `bge-base-en-v1.5` complete on all nodes
(mirrored); `bge-small-en-v1.5` fallback complete on all nodes. Manual downloads use a `uv` venv +
`HF_ENDPOINT=https://hf-mirror.com` workaround (Local Models Setup §4).

GPU-6 should be driven by evidence from GPU-5 rather than by prematurely introducing production-scale scheduling complexity.

## Related Synanton projects

- [Synanton Platform](https://github.com/synanton/platform)
- [Content Extractor](https://github.com/synanton/content_extractor)

### GPU-3 Checklist

#### Artifact lifecycle
- [x] `ArtifactResolver` interface + `SecureRegistryFetcher` implementation
- [x] SHA-256 artifact digest verification
- [x] Shared model cache (`/model-cache/<model-id>/<digest>`)
- [x] Distributed PostgreSQL advisory locking (exactly one downloader per `(model_id, digest)`)
- [x] Second cache check after acquiring lock
- [x] Atomic artifact publication (staging → rename)

#### Runtime lifecycle
- [x] `ExecutionRuntime` port interface with `RuntimeResult` sealed type
- [x] `VllmRuntime` implementation (HTTP → vLLM `/v1/completions` + `/v1/chat/completions`)
- [x] `StubExecutionRuntime` for local/CI testing without vLLM
- [x] `RetryDisposition` domain enum
- [x] Runtime acceptance semantics explicitly detected (HTTP status, not `IOException`)
- [x] Automatic retry only for `NOT_ACCEPTED`
- [x] RUNNING executions receive heartbeat lease refresh
- [x] `HeartbeatManager` implementation
- [x] Heartbeat stops on terminal state

#### Persistence
- [x] `ExecutionRepository` port interface
- [x] `JdbcExecutionRepository` (advisory lock, predicated UPDATEs, JSONB usage/error)
- [x] `ConfigModelRepository` (capabilities from `gpu-gateway.models` config)

------
#### Model manager
- [x] `ModelManager` port interface
- [x] `DefaultModelManager` implementation
- [x] `MODEL_LOADING` execution state explicitly tracked
- [x] Load failures cascade to QUEUED executions (`failAllQueuedForModel`)

## Contract mirroring

`java/gpu-contract` is a **byte-identical** copy of `platform/java/gpu-contract`:

- protobuf package `synanton.gpu.v1`
- Java package `org.synanton.gpu.v1`
- service `GPUExecutionService` (`Execute`, `Cancel`, `GetStatus`, `GetCapacity`, `GetModels`)
- `GetStatusRequest` / `ExecutionStatus`
- `ErrorReason` catalogue (`ErrorInfo`)

```bash
./scripts/verify-gpu-contract-mirror.sh
GPU_PEER_REPO=/path/to/platform ./scripts/verify-gpu-contract-mirror.sh
```

Wired into `./gradlew check`. Internal domain states (`ACCEPTED`, `MODEL_LOADING`, `SUCCEEDED`) still exist in PostgreSQL; the gRPC mapper emits the platform wire enum (`QUEUED`, `RUNNING`, `SUCCESS`, …).

---

## Getting Started (Development)
#### Reconciliation
- [x] `GetStatusService` lazy reconciliation logic
- [x] Expired RUNNING leases trigger runtime ping
- [x] Live runtime refreshes lease; dead runtime → `RUNTIME_UNAVAILABLE`
- [x] Terminal executions protected by predicated UPDATE

#### Tests
- [x] 39 unit tests passing (domain, adapters, scheduler, model manager)
- [ ] Integration tests (JdbcExecutionRepository, GpuExecutionIntegrationTest, ConcurrencyAdmissionTest) — written, require Docker to run

---

## Core Invariants

These are enforced by the architecture and encoded into `.cursor/rules/gpu-execution-rules.mdc`:

1. **PostgreSQL is the only source of truth** for execution state. No Redis, Cassandra, or in-memory shadow.
2. **Fail-closed idempotency**: `Execute()` never dispatches an unrecorded request; PostgreSQL failure → `Execute()` failure.
3. **Transactional admission**: concurrency slots are checked and consumed inside a single PostgreSQL advisory-locked transaction.
4. **No background controllers**: reconciliation is lazy, triggered by `GetStatus()` lease expiry.
5. **Retry semantics from protocol, not exceptions**: `RetryDisposition` is derived from HTTP status codes, not `IOException`.
6. **Exactly one artifact download** per `(model_id, digest)` across all Gateway replicas.
7. **Artifact integrity before availability**: SHA-256 verified, staged, then atomically published.
8. **CPU fallback is prohibited here**: belongs entirely to the Synanton Platform.
9. **No prompt logging**: prompts, tokens, and authorization assertions are never written to logs or traces.
10. **Equalix is optional**: `ExecutionScheduler` interface allows swapping `DirectScheduler` → `EqualixScheduler` without touching domain or use-case code.

---

## Getting Started

- Java 21 (Eclipse Temurin or OpenJDK)
- Docker (for Testcontainers — used by integration tests)
- `./gradlew` wrapper (included)

### Build & Test

```bash
# Compile all modules
./gradlew compileJava
```

### Clone the repository
```bash
git clone https://github.com/synanton/gpu-runtime.git
cd gpu-runtime
```

### Build the contract module
```bash
./gradlew :java:gpu-contract:build
```

### Run unit tests (no infrastructure required)
```bash
./gradlew :java:gpu-gateway:test
```

### Run integration tests (Testcontainers PostgreSQL — requires Docker)
Remove @Disabled from JdbcExecutionRepositoryTest, GpuExecutionIntegrationTest,ConcurrencyAdmissionTest, then run with DOCKER_HOST set to your daemon socket:
```bash
DOCKER_HOST=unix:///path/to/docker.sock ./gradlew :java:gpu-gateway:test
```

### Full build + check (includes GPU proto mirror vs sibling platform/)
```bash
./gradlew check
```

> Integration tests spin up a real PostgreSQL 16 container. They are `@Disabled` by default to allow clean CI without Docker. Enable them manually when Docker is available.

### Configuration

Key properties in `application.yml` (all overridable via environment variables):

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `gpu-gateway.grpc-port` | `GPU_GATEWAY_GRPC_PORT` | `9090` | gRPC listen port |
| `gpu-gateway.dispatch.strategy` | `GPU_GATEWAY_DISPATCH_STRATEGY` | `stub` | `stub`, `direct` (GPU-5 vLLM) or `external` (GPU-7 providers); anything else fails startup |
| `gpu-gateway.dispatch.vllm-endpoint` | `VLLM_ENDPOINT` | `http://vllm-service:8000` | vLLM base URL |
| `gpu-gateway.execution.lease-timeout-seconds` | `EXECUTION_LEASE_TIMEOUT_SECONDS` | `300` | Heartbeat lease window |
| `gpu-gateway.execution.heartbeat-interval-seconds` | `EXECUTION_HEARTBEAT_INTERVAL_SECONDS` | `60` | Heartbeat fire interval |
| `gpu-gateway.artifacts.cache-dir` | `MODEL_CACHE_DIR` | `/model-cache` | Shared artifact cache root |
| `gpu-gateway.models.<id>.concurrency-limit` | — | — | Max parallel executions per model |

Example model configuration:

```yaml
gpu-gateway:
  models:
    llama-3-8b:
      concurrency-limit: 8
      max-input-tokens: 32768
      runtime-class: vllm-a100
      digest: sha256:abc123...
```

---

## License

Apache 2.0 License – see [LICENSE](LICENSE).

------

## References

- [Synanton Design v1.21 (current)](https://github.com/Synanton/platform/blob/main/docs/architecture/synanton-design-1.21.md)
- [Synanton Design v1.20 (GPU Part VIII)](https://github.com/Synanton/platform/blob/main/docs/architecture/synanton-design-1.20.md)
- [Synanton Design v1.19 (baseline)](https://github.com/Synanton/platform/blob/main/docs/architecture/synanton-design-1.19.md)
- [Synanton Core README](https://github.com/Synanton/platform/blob/main/README.md)
