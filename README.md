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
│  │                  VllmRuntime                              │  │
│  │  (Execution, Cancellation, Heartbeat, Status)             │  │
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
gpu-runtime/
├── build.gradle.kts                     # Root build
├── settings.gradle.kts
├── gradle.properties
│
├── .cursor/
│   └── rules/                           # AI-assisted development rules
│       ├── java-rules.mdc               # Synanton Core Java conventions
│       └── gpu-execution-rules.mdc      # GPU-specific invariants
│
├── doc/
│   ├── GPU-5  GPU-6  GPU-7 Deployment Plan.md   # Canonical deployment spec (v2.1.9)
│   ├── GPU-5 Local Models Setup.md              # Node-local model download/verify (BGE + Qwen3)
│   ├── TEST_ENVIRONMENT_SETUP.md
│   ├── GPU Execution Plane Implementation Plan v1.20.md
│   └── GPU Execution Plane Implementation Plan v1.21.md
│
├── deployments/
│   ├── homelab/                         # [GPU-5] reference local-inference deployment
│   │   ├── gpu-5-implementation-plan.md #   phased plan, decisions D1-D7, acceptance
│   │   ├── claster-as-build.md          #   observed 4-node cluster inventory
│   │   ├── blueprints/                  #   plain K8s manifests (phased bring-up)
│   │   │   ├── tei/                     #   embedding (node1, GTX 1650, TEI turing build)
│   │   │   ├── vllm/                    #   reranker (node2), synthesis (node3) — one
│   │   │   │                            #   workload per physical GPU (PR #15 review)
│   │   │   ├── postgres/                #   PostgreSQL 16 + Longhorn PVC (node1)
│   │   │   ├── envoy/                   #   execution perimeter, ES256 JWT via JWKS
│   │   │   ├── gateway/                 #   Gateway Deployment/Service
│   │   │   └── network-policy/          #   default-deny + spec §19 flows
│   │   ├── helm/gpu-plane/              #   packaged chart (post bring-up path)
│   │   ├── docker/gateway.Dockerfile    #   T-K8S-1 image blueprint
│   │   └── scripts/                     #   mirror-images / create-registry-secret / deploy /
│   │                                    #   cluster-stop / cluster-start (safe power cycle)
│   ├── external/                        # [GPU-7] external-provider profile (no GPU/Envoy/vLLM)
│   │   ├── gpu-7-implementation-plan.md #   phased plan, T-K8S-38..53 mapping
│   │   ├── compose.yaml                 #   single-node: gateway + postgres + mock-provider
│   │   ├── config/gateway-external.yaml #   provider registry, mappings, kill switch, budget
│   │   ├── mock-provider/               #   stdlib-only OpenAI-compatible mock (T-K8S-51)
│   │   └── scripts/smoke-test.sh        #   positive/negative path checks (§37 subset)
│   └── production/                      # [GPU-6] deferred — not a GPU-5/GPU-7 gate
│
├── java/
│   ├── gpu-contract/                    # Shared gRPC protobuf contracts
│   │   └── src/main/proto/synanton/gpu/v1/
│   │       └── gpu_execution_service.proto  # Execute/Cancel/GetStatus/GetCapacity/GetModels
│   │
│   └── gpu-gateway/                     # Main Spring Boot service
│       └── src/
│           ├── main/java/org/synanton/gpu/
│           │   ├── adapter/             # Hexagonal Architecture
│           │   │   ├── in/              # gRPC, Schedules
│           │   │   └── out/             # Database, Runtime, Registry, Security
│           │   ├── domain/              # Use Cases, Models, Services
│           │   └── config/
│           └── resources/
│               ├── application.yml
│               ├── application-test.yml
│               └── db/migration/        # Flyway V1 (executions), V2 (artifact cache)
│
├── scripts/
│   └── verify-gpu-contract-mirror.sh    # proto mirror check vs platform repo
│
├── tools/
│   └── gpu-plane-check.py               # manual test CLI for GPU-5/GPU-7 Gateways
│                                        # (models/chat/stream/embed/rerank/negative;
│                                        #  validates §10 SSE, §16 envelopes, §21 IDs)
│
└── README.md
```

------

## Roadmap

| Milestone | Purpose | Status |
|---|---|---|
| GPU-1 | Contract & deterministic semantics | Complete |
| GPU-2 | Domain core & persistence | Complete |
| GPU-3 | Runtime & model lifecycle | Complete |
| GPU-4 | Main Platform integration / contract mirror | Contract complete; runtime routing being validated |
| **GPU-5** | **Homelab local inference (Qwen3-4B synthesis + BGE-base embedding via TEI + Qwen3-Reranker 0.6B — one workload per GPU)** | **In progress — deployment package ready, cluster bring-up next** |
| **GPU-7** | **External-provider profile (OpenAI-compatible adapter, no local GPU)** | **Routing/runtime layer implemented (PR #15 fixes); HTTP face pending — acceptance blocked** |
| **GPU-6** | **Production deployment and operational hardening** | **Deferred — not a GPU-5/GPU-7 gate** |

### GPU-5 status (2026-09-24, revised per PR #15 review)

Deployment content lives in [`deployments/homelab/`](deployments/homelab/gpu-5-implementation-plan.md):
blueprint manifests (kubectl bring-up), `helm/gpu-plane` chart (packaged path), registry/scripts tooling.

- [x] Node placement: **one inference workload per physical GPU** — node1 = TEI embedding (GTX 1650, BGE-base fp16) + CPU-only Gateway/Envoy/PostgreSQL; node2 = vLLM reranker (RTX 4060 Ti); node3 = vLLM synthesis (RTX 5060 Ti). The two-container/one-GPU colocation was retracted (invalid device-plugin design, PR #15 P0.1).
- [x] Model files verified on nodes (`/mnt/local-fast/models/...`): reranker on node2, synthesis on node3 (Qwen3ForCausalLM, bf16); `bge-base-en-v1.5` **complete incl. tokenizer files on all nodes** (operator mirrors models everywhere); `bge-small-en-v1.5` fallback partial — one re-run completes it. Manual downloads use a `uv` venv (`uv venv --python 3.12 --seed`) + `HF_ENDPOINT=https://hf-mirror.com` workaround for the huggingface.co SSL EOF failure (plan §4.5, Local Models Setup §4.1)
- [x] vLLM pinned to `v0.29.0` (CUDA 13.0 image; Ada sm_8.9 + Blackwell sm_12.0); TEI pinned to `turing-1.9` (sm_7.5)
- [x] GPU-5 manifests fixed per PR #15 review: invalid colocated pod removed; gateway datasource env names aligned to the app's actual `GPU_GATEWAY_DB_*` binding
- [ ] Cluster bring-up phases 0–9 (uncordon → registry mirror → postgres → inference → gateway → envoy JWT → policies → TLS → acceptance)

### GPU-7 status (2026-09-24, PR #15 review fixes landed)

Deployment package lives in [`deployments/external/`](deployments/external/gpu-7-implementation-plan.md):
Docker Compose (gateway + PostgreSQL + mock provider), external-mode config contract
(`config/gateway-external.yaml`), smoke test (phase-4 acceptance target).

**Implemented (routing/runtime layer, unit-tested):** provider registry
(`gpu-gateway.providers.<id>`) + generic `OpenAiProviderRuntime` serving every
configured provider incl. the mock; authoritative `ProviderRouter` with fail-closed
startup (§5.5), no-local-fallback in external mode, kill switch, catalog-driven
provider selection; logical→provider model-ID rewriting with logical ID restored on
every downstream body **including SSE chunks**; streaming execution in the runtime
abstraction; per-provider circuit breaker (denies without a provider call);
Compose↔Spring datasource env fix.

**Declared, not yet enforced:** provider health scheduler (T-K8S-46), budget
(T-K8S-48b), sensitivity (T-K8S-49), cost-ledger reporting (T-K8S-48), persisted
routing control state (T-K8S-38).

**Blocked:** §37 acceptance runs against the public HTTP face (`:8080`), which this
build does not serve yet — the live surface is gRPC :9090. Acceptance remains
blocked until the HTTP-face ticket lands (see `gpu-7-implementation-plan.md` §0).

Unblocks the platform retrieval benchmark's T02/T03 rows (`platform/docs/research/gpu-plane-integration-tickets.md`)
once the §37 acceptance paths pass against the mock provider.

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
| `gpu-gateway.dispatch.strategy` | `GPU_GATEWAY_DISPATCH_STRATEGY` | `stub` | `stub` or `direct` (vLLM) |
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
