# Synanton GPU Execution Plane

[![Status](https://img.shields.io/badge/Status-Development-blue)](https://github.com/Synanton/gpu-runtime)
[![Java](https://img.shields.io/badge/Java-21-red)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![gRPC](https://img.shields.io/badge/gRPC-Protobuf-purple)](https://grpc.io/)

## Overview

The **GPU Execution Plane** is a physically isolated, on-premise optimized service responsible for executing GPU-intensive inference workloads (LLMs, Embeddings, Reranking) on behalf of the **Synanton Platform**.

It acts as the exclusive bridge between the business logic of Synanton and the physical GPU infrastructure, ensuring a strict separation of concerns.

### Architectural Invariant
- **Synanton Main Platform** determines **WHAT** should execute (business intent, model selection, tenant identity).
- **GPU Execution Plane** determines **HOW** it executes (scheduling, admission, runtime lifecycle).
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
- **Cost Attribution**: Reports `input_tokens`, `output_tokens`, and `gpu_duration_seconds` back to the Main Platform for tenant chargeback, without exposing infrastructure details.
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
│                      GPU EXECUTION PLANE                        │
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

text

```
gpu-runtime/
├── build.gradle.kts                     # Root build
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
│
├── .cursor/
│   └── rules/                           # AI-assisted development rules
│       ├── java-rules.mdc               # Synanton Core Java conventions
│       └── gpu-execution-rules.mdc      # GPU-specific invariants
├── doc/
│   ├── GPU Execution Plane Implementation Plan v1.20.md
│   └── GPU Execution Plane Implementation Plan v1.21.md
│
├── java/
│   ├── gpu-contract/                    # Shared gRPC protobuf contracts
│   │   ├── build.gradle.kts
│   │   └── src/main/proto/Synanton/gpu/v1/
│   │       ├── execution.proto
│   │       ├── capacity.proto
│   │       ├── common.proto
│   │       └── error.proto
│   ├── gpu-contract/                    # Protobuf contracts (generated gRPC stubs)
│   │   └── src/main/proto/synanton/gpu/v1/
│   │       ├── execution.proto          # Execute, Cancel, GetStatus RPCs
│   │       ├── capacity.proto           # GetCapacity RPC
│   │       ├── common.proto             # Shared types
│   │       └── error.proto              # Error taxonomy enum
│   │
│   └── gpu-gateway/                     # Main Spring Boot service
│       ├── build.gradle.kts
│       └── src/
│           ├── main/java/com/Synanton/gpu/
│           │   ├── adapter/             # Hexagonal Architecture
│           │   │   ├── in/              # gRPC, Schedules
│           │   │   └── out/             # Database, Runtime, Registry, Security
│           │   ├── domain/              # Use Cases, Models, Services
│           │   │   ├── model/
│           │   │   └── service/
│           │   └── config/
│           └── resources/
│               ├── application.yml
│               └── application-test.yml
│
├── migrations/                          # Flyway database migrations
│   └── V1__create_executions.sql
│
├── helm/                                # Kubernetes Helm charts
│   └── gpu-runtime/
│       ├── Chart.yaml
│       ├── values.yaml
│       └── templates/
│           ├── gateway-deployment.yaml
│           ├── gateway-service.yaml
│           ├── vllm-deployment.yaml
│           ├── postgres-cluster.yaml    # HA (CloudNativePG)
│           ├── idempotency-cleaner.yaml
│           └── network-policies.yaml
│
├── deploy/                              # Additional K8s manifests
│   └── k8s/
│       ├── namespace.yaml
│       ├── mtls/
│       └── monitoring/
│       └── src/main/java/com/synanton/gpu/
│           ├── adapter/
│           │   ├── in/grpc/             # GpuExecutionGrpcAdapter, GpuCapacityGrpcAdapter
│           │   └── out/                 # [GPU-3] database/, runtime/, registry/, schedule/, model/
│           ├── domain/
│           │   ├── model/               # Execution, ExecutionState, ModelCapabilities, …
│           │   ├── port/
│           │   │   ├── in/              # ExecuteUseCase, CancelUseCase, GetStatusUseCase, GetCapacityUseCase
│           │   │   └── out/             # ExecutionRepository, ExecutionRuntime, ModelManager, …
│           │   └── service/             # ExecuteService, AdmissionService, HeartbeatManager, …
│           └── config/                  # GpuGatewayProperties, DomainConfig, GrpcServerLifecycle
│
├── scripts/
│   ├── deploy-onprem.sh
│   └── smoke-test.sh
│
└── README.md
├── helm/                                # [GPU-5] Kubernetes Helm charts
└── deploy/                              # [GPU-5] Additional K8s manifests
```

------

## Implementation Roadmap

---------|------------------------------------|------------------------------------------------------------------------------------------------------|--------------------|
| **GPU-1** | Contract & Deterministic Semantics | Protobuf definitions, error taxonomy, PGV validation, request canonicalization, idempotency contract | ✅ Complete         |
| **GPU-2** | Domain Core & Persistence          | Use cases, AdmissionService, PostgreSQL schema (Flyway), advisory-lock admission, concurrency tests  | ✅ Complete         |
| **GPU-3** | Runtime & Model Lifecycle          | Outbound port interfaces, JdbcExecutionRepository, VllmRuntime, ModelManager, ArtifactResolver, heartbeat, lazy reconciliation | ✅ Complete     |
| **GPU-4** | Main Platform Integration          | `GpuExecutionClient` in Synanton Core, W3C tracing, long-polling, CPU fallback in Core              | ⬜ Not Started      |
| **GPU-5** | Production Hardening               | HA PostgreSQL (CloudNativePG), Helm charts, NetworkPolicy, fault injection, load tests, Grafana      | ⬜ Not Started      |
| **GPU-6** | Equalix Evaluation                 | Measure queue fairness and utilization; implement `EqualixScheduler` only if data justifies it       | ⬜ Future           |

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
# Clone the repository
git clone https://github.com/synanton/gpu-runtime.git
cd gpu-runtime

# Build the contract module
./gradlew :java:gpu-contract:build

# Run unit tests (no infrastructure required)
./gradlew :java:gpu-gateway:test

# Run integration tests (Testcontainers PostgreSQL — requires Docker)
# Remove @Disabled from JdbcExecutionRepositoryTest, GpuExecutionIntegrationTest,
# ConcurrencyAdmissionTest, then run with DOCKER_HOST set to your daemon socket:
DOCKER_HOST=unix:///path/to/docker.sock ./gradlew :java:gpu-gateway:test

# Full build + check
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

- [Synanton Design v1.20 (GPU Execution Plane)](https://github.com/Synanton/platform/blob/main/docs/architecture/Synanton-design-1.20.md)
- [Synanton Design v1.19](https://github.com/Synanton/platform/blob/main/docs/architecture/Synanton-design-1.19.md)
- [Synanton Core README](https://github.com/Synanton/platform/blob/main/README.md)