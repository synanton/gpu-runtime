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

> **Critical Constraint**: The Synaton Platform is strictly prohibited from directly discovering or accessing Kubernetes Pods, GPU nodes, physical GPUs, or vLLM endpoints. The GPU Gateway is the **sole network entry point**.

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
│                     Synanton PLATFORM                           │
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

text

```
ACCEPTED → (Model Hot) → RUNNING → SUCCEEDED / FAILED / CANCELLED
ACCEPTED → QUEUED → MODEL_LOADING → RUNNING → SUCCEEDED / FAILED / CANCELLED
```



------

## Technology Stack

| Layer                | Technology                                              |
| -------------------- | ------------------------------------------------------- |
| **Language**         | Java 21                                                 |
| **Framework**        | Spring Boot 3.x                                         |
| **Build**            | Gradle (Kotlin DSL)                                     |
| **RPC / API**        | gRPC + Protobuf + Protovalidate (PGV)                   |
| **Persistence**      | PostgreSQL + Flyway (JDBC `JdbcTemplate`)               |
| **Observability**    | Micrometer + Prometheus, OpenTelemetry                  |
| **Testing**          | JUnit 5, AssertJ, Mockito, Testcontainers               |
| **Orchestration**    | Kubernetes + Helm                                       |
| **GPU Runtime**      | vLLM (initial), TGI (future)                            |
| **Artifact Storage** | Internal Registry + Shared Read-Only Cache (NFS/CephFS) |

> **Important**: The GPU Execution Plane deliberately **does not** use Redis, Kafka, or Cassandra. PostgreSQL remains the sole,  authoritative source of execution state to keep the system small and  deterministic.

------

## Repository Structure

text

```
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
├── java/
│   ├── gpu-contract/                    # Shared gRPC protobuf contracts
│   │   ├── build.gradle.kts
│   │   └── src/main/proto/Synanton/gpu/v1/
│   │       ├── execution.proto
│   │       ├── capacity.proto
│   │       ├── common.proto
│   │       └── error.proto
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
│           │   │   ├── service/
│           │   │   ├── ExecuteUseCase.java
│           │   │   ├── CancelUseCase.java
│           │   │   ├── GetStatusUseCase.java
│           │   │   └── GetCapacityUseCase.java
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
│
├── scripts/
│   ├── deploy-onprem.sh
│   └── smoke-test.sh
│
└── README.md
```



------

## Implementation Roadmap

| Stage     | Title                              | Deliverables                                                 | Status        |
| --------- | ---------------------------------- | ------------------------------------------------------------ | ------------- |
| **GPU-1** | Contract & Deterministic Semantics | Protobuf, state machine, error taxonomy, PGV validation, request canonicalization, idempotency contract tests. | ![Status](https://img.shields.io/badge/Status-Experimental-purple) |
| **GPU-2** | Domain Core & Persistence          | `ExecuteUseCase`, `AdmissionService`, PostgreSQL repository, Flyway, `DirectScheduler`, fail-closed logic, concurrent admission tests. | ![Status](https://img.shields.io/badge/Status-Experimental-purple) |
| **GPU-3** | Runtime & Model Lifecycle          | `ModelManager`, `ArtifactResolver`, `VllmRuntime`, shared model cache, graceful draining, lazy reconciliation, runtime retry safety. | ⬜ Not Started |
| **GPU-4** | Main Platform Integration          | `GpuExecutionClient` in Synanton Core, W3C tracing, `GetStatus` long-polling, CPU fallback handling. | ⬜ Not Started |
| **GPU-5** | Production Hardening               | HA PostgreSQL (CloudNativePG), NetworkPolicy, PodSecurity, fault injection tests, load testing, Grafana dashboards. | ⬜ Not Started |
| **GPU-6** | Equalix Evaluation                 | Measure queue fairness, tenant distribution, utilization; implement `EqualixScheduler` only if warranted by data. | ⬜ Future      |

------

## Getting Started (Development)

### Prerequisites

- Java 21 (Eclipse Temurin or OpenJDK)
- Docker (for Testcontainers and local PostgreSQL)
- `./gradlew` wrapper

### Build & Test (PR #1)

The first PR establishes the project skeleton and contract without external dependencies.

bash

```
# Clone the repository
git clone https://github.com/synanton/gpu-runtime.git
cd gpu-runtime

# Build the contract module
./gradlew :java:gpu-contract:build

# Run unit tests (no infrastructure required)
./gradlew :java:gpu-gateway:test

# Run all checks
./gradlew check
```



### Local Integration Testing (GPU-2+)

For testing against a real PostgreSQL instance (via Testcontainers):

bash

```
./gradlew :java:gpu-gateway:integrationTest
```



> **Note**: GPU performance testing requires a dedicated on-prem cluster with  NVIDIA GPUs and vLLM. Local Kind clusters are suitable for GPU-1/GPU-2  only.

------

## Deployment (On-Prem)

1. **Prepare the Kubernetes cluster**: Ensure GPU nodes are tainted/labeled and have NVIDIA drivers + Device Plugin installed.

2. **Configure Secrets**: Create Kubernetes secrets for mTLS (`tls.crt`, `tls.key`), Platform CA (`ca.crt`), and PostgreSQL credentials.

3. **Install via Helm**:

   bash

   ```
   helm install gpu-runtime ./helm/gpu-runtime \
     --namespace gpu-system \
     --create-namespace \
     --set tls.existingSecret=gpu-gateway-tls \
     --set postgresql.ha.enabled=true
   ```

   

Refer to the [Helm README](https://./helm/gpu-runtime/README.md) for detailed configuration options (runtime classes, model registry endpoints, cache paths).

------

## Core Invariants (The "Rules")

These are encoded into the repository's Cursor rules and are enforced by the architecture:

1. **Physical Isolation**: The Main Platform has no network path to vLLM, K8s API, or PostgreSQL.
2. **Logical Capacity**: `GetCapacity()` returns `available_concurrency`, never GPU IDs or node names.
3. **Fail-Closed Idempotency**: If PostgreSQL is unavailable, `Execute()` fails. It never dispatches an unrecorded request.
4. **Transactional Admission**: Concurrency limits are enforced via PostgreSQL advisory locks.
5. **No Background Controllers**: Reconciliation is lazy (inside `GetStatus`); no Kubernetes operator in v1.20.
6. **CPU Fallback Prohibition**: This repository contains zero logic for CPU inference fallback—that belongs to the Main Platform.
7. **Artifact Integrity**: Every model digest is verified before the cache makes it available to vLLM.
8. **No Prompt Logging**: Prompts, tokens, and authorization assertions are strictly omitted from logs and traces.

------

## Contributing

Please ensure your IDE/editor honors the rules in [`.cursor/rules/`](https://./.cursor/rules/) before submitting PRs.

- All domain logic must be unit-testable without Spring context.
- Integration tests must use Testcontainers.
- Follow the Synanton Core Gradle conventions (Kotlin DSL, `compileJava`, `test` tasks).

------

## License

Apache 2.0 License – see [LICENSE](LICENSE).

------

## Status ![Status](https://img.shields.io/badge/Status-Experimental-purple)

## References

- [Synanton Design v1.20 (GPU Execution Plane)](https://github.com/synanton/platform/blob/main/docs/architecture/Synanton-design-1.20.md)
- [Synanton Design v1.19](https://github.com/synanton/platform/blob/main/docs/architecture/Synanton-design-1.19.md)
- [Synanton Core README](https://github.com/synanton/platform/blob/main/README.md)
