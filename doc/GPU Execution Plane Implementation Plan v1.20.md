# GPU Execution Plane — Implementation Plan v1.20

## 1. Technology Decision

### Use the SynAnton Core stack

| Layer              | Decision                                              |
| ------------------ | ----------------------------------------------------- |
| Language           | **Java 21**                                           |
| Framework          | **Spring Boot 3.x**                                   |
| Build              | **Gradle Kotlin DSL**                                 |
| RPC                | **gRPC + Protobuf**                                   |
| Validation         | **Protovalidate / PGV**                               |
| Database           | **PostgreSQL**                                        |
| Migrations         | **Flyway**                                            |
| DB access          | JDBC initially; jOOQ only if already standard in Core |
| Logging            | SLF4J / Lombok `@Slf4j`                               |
| Metrics            | Micrometer + Prometheus                               |
| Tracing            | OpenTelemetry                                         |
| Tests              | JUnit 5 + AssertJ + Mockito                           |
| Integration tests  | Spring Boot + Testcontainers                          |
| Deployment         | Kubernetes + Helm                                     |
| GPU runtime        | vLLM                                                  |
| Artifact storage   | Internal model registry + shared model cache          |
| TLS                | mTLS                                                  |
| Scheduler          | DirectScheduler initially                             |
| Advanced scheduler | EqualixScheduler later                                |

The Core rules specifically require hexagonal architecture, Gradle Kotlin DSL, Java 21, Spring Boot, Protobuf/gRPC, PostgreSQL/Flyway, and the stated testing conventions. 

### One deliberate exception

Do **not** introduce Cassandra, Kafka, or Redis into the GPU Execution Plane merely because they exist in the Core platform stack.

The GPU plane has different requirements:

```
PostgreSQL = execution truth
Kafka       = unnecessary for v1.20
Redis       = unnecessary for v1.20
Cassandra   = unnecessary
```

This keeps the isolated plane small.

------

# 2. Repository Structure

I would change the previous proposed structure to follow the **actual SynAnton Java architecture** rather than inventing a GPU-specific package convention.

```
gpu-execution-plane/
│
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
│
├── .cursor/
│   └── rules/
│       ├── java-rules.mdc
│       └── gpu-execution-rules.mdc
│
├── java/
│   ├── gpu-contract/
│   │   ├── build.gradle.kts
│   │   └── src/main/proto/
│   │       └── synanton/gpu/v1/
│   │           ├── execution.proto
│   │           ├── capacity.proto
│   │           ├── common.proto
│   │           └── error.proto
│   │
│   └── gpu-gateway/
│       ├── build.gradle.kts
│       │
│       └── src/
│           ├── main/
│           │   ├── java/com/synanton/gpu/
│           │   │
│           │   │   ├── adapter/
│           │   │   │   ├── in/
│           │   │   │   │   ├── grpc/
│           │   │   │   │   └── schedule/
│           │   │   │   │
│           │   │   │   └── out/
│           │   │   │       ├── database/
│           │   │   │       ├── runtime/
│           │   │   │       ├── registry/
│           │   │   │       └── security/
│           │   │   │
│           │   │   ├── domain/
│           │   │   │   ├── model/
│           │   │   │   ├── service/
│           │   │   │   ├── ExecuteUseCase.java
│           │   │   │   ├── CancelUseCase.java
│           │   │   │   ├── GetStatusUseCase.java
│           │   │   │   └── GetCapacityUseCase.java
│           │   │   │
│           │   │   └── config/
│           │   │
│           │   └── resources/
│           │       ├── application.yml
│           │       └── application-test.yml
│           │
│           └── test/
│               ├── java/com/synanton/gpu/
│               │   ├── domain/
│               │   └── integration/
│               └── resources/
│
├── migrations/
│   └── V1__create_executions.sql
│
├── helm/
│   └── gpu-execution-plane/
│
└── deploy/
```

This directly aligns with the Core rules: incoming adapters under `adapter/in`, outgoing infrastructure under `adapter/out`, and business logic/domain models in `domain`. 

------

# 3. Dependency Architecture

The most important dependency rule:

```
                ┌────────────────────┐
                │      adapters      │
                └─────────┬──────────┘
                          │
                          ▼
                ┌────────────────────┐
                │      domain        │
                │                    │
                │ use cases           │
                │ domain services     │
                │ domain models       │
                └────────────────────┘
```

Never:

```
domain → PostgreSQL
domain → gRPC generated classes
domain → Kubernetes
domain → vLLM
domain → Spring
```

Instead:

```
domain
  │
  ├── ExecutionRepository
  ├── Runtime
  ├── ArtifactResolver
  ├── ModelRepository
  └── ExecutionScheduler
          ▲
          │
       adapters
```

This is particularly important here because it lets us test scheduling/admission/state transitions without Kubernetes or vLLM.

------

# 4. GPU-1 — Contract

## Goal

Freeze the external API before implementing runtime behavior.

### Protobuf

Create:

```
java/gpu-contract/src/main/proto/
```

with:

```
execution.proto
capacity.proto
common.proto
error.proto
```

Core API:

```
service GpuExecutionService {


    rpc Execute(ExecutionRequest)
        returns (ExecutionResponse);


    rpc Cancel(CancelRequest)
        returns (CancelResponse);


    rpc GetStatus(StatusRequest)
        returns (StatusResponse);


    rpc GetCapacity(CapacityRequest)
        returns (CapacityResponse);
}
```

------

# 5. ExecutionRequest

Minimum immutable execution payload:

```
model_id
input
options
```

Plus:

```
request_id
tenant_id
authorization assertion
trace context
```

The request hash must only cover immutable execution semantics.

```
hash(
    model_id
    input
    immutable options
)
```

Never:

```
hash(request_id)
hash(trace_id)
hash(priority metadata)
```

unless those fields are explicitly part of execution identity.

------

# 6. Usage

Freeze the usage contract now:

```
message Usage {
    int64 input_tokens = 1;
    int64 output_tokens = 2;
    double gpu_duration_seconds = 3;
    string runtime_class = 4;
}
```

This belongs to the **execution result**, not merely an observability endpoint.

------

# 7. Error taxonomy

Create a stable enum:

```
INVALID_ARGUMENT
UNAUTHENTICATED
PERMISSION_DENIED


MODEL_NOT_FOUND
MODEL_NOT_READY
MODEL_LOAD_FAILED
MODEL_ARTIFACT_INTEGRITY_FAILURE


CONCURRENCY_LIMIT
GPU_QUOTA_EXCEEDED
CAPACITY_EXCEEDED
ADMISSION_DENIED


EXECUTION_NOT_FOUND
EXECUTION_CANCELLED


RUNTIME_UNAVAILABLE
RUNTIME_TIMEOUT
RUNTIME_FAILED


DEPENDENCY_UNAVAILABLE
INTERNAL
```

Do not expose Java exception names over gRPC.

------

# 8. GPU-1 Test Requirements

Before GPU-2:

### Contract tests

```
Execute valid request
Execute invalid request
Cancel valid execution
GetStatus valid execution
GetCapacity valid model
```

### Idempotency tests

```
same request_id + same hash
    → same execution_id


same request_id + different hash
    → REQUEST_ID_REUSE
```

### Validation

PGV validation occurs at the gRPC boundary, consistent with the Core rules. 

------

# 9. GPU-2 — Domain Core

Implement the four use cases:

```
ExecuteUseCase
CancelUseCase
GetStatusUseCase
GetCapacityUseCase
```

They should operate entirely on domain objects.

For example:

```
ExecuteUseCase
    │
    ├── AuthorizationPolicy
    ├── IdempotencyService
    ├── AdmissionService
    ├── ModelManager
    └── ExecutionScheduler
```

No protobuf objects should enter the domain.

The SynAnton rules explicitly require repositories/use cases to work with domain objects, with protobuf conversion occurring at the gRPC adapter layer. 

------

# 10. PostgreSQL Execution Model

Use:

```
executions
```

with at least:

```
execution_id
request_id
request_hash
tenant_id
model_id
state
runtime_class
created_at
updated_at
expires_at
usage
error
result
```

I would **not** store the complete protobuf response as the only representation.

Instead, persist structured execution state and reconstruct the response.

This gives better:

- querying
- reconciliation
- metrics
- migration flexibility
- capacity calculation

------

# 11. Transactional Idempotency

`ExecuteUseCase`:

```
BEGIN
   │
   ├── acquire request/model admission lock
   │
   ├── lookup request_id
   │
   ├── validate hash
   │
   ├── evaluate admission
   │
   ├── insert execution
   │
   └── COMMIT
```

The critical invariant:

> Two concurrent `Execute()` calls cannot both consume the same remaining concurrency slot.

This should be tested with actual concurrent integration tests against PostgreSQL.

------

# 12. GPU-2 — Admission

Create:

```
domain/service/AdmissionService.java
```

Responsibilities:

```
validate request
validate model capabilities
validate token limits
validate quota
validate concurrency
```

The concurrency calculation is based on PostgreSQL execution state.

Do **not** introduce Redis.

------

# 13. GPU-2 — State Machine

Create a strongly typed Java model:

```
ExecutionState
```

with transitions validated by the domain.

For example:

```
ACCEPTED → QUEUED
ACCEPTED → RUNNING


QUEUED → MODEL_LOADING
QUEUED → FAILED
QUEUED → CANCELLED


MODEL_LOADING → RUNNING
MODEL_LOADING → FAILED


RUNNING → SUCCEEDED
RUNNING → FAILED
RUNNING → CANCELLED
```

Invalid transitions should be impossible through the domain API.

------

# 14. GPU-2 — DirectScheduler

Implement:

```
interface ExecutionScheduler {


    RuntimeTarget schedule(
        ExecutionRequest request,
        ModelCapabilities capabilities
    );
}
```

Initial:

```
DirectScheduler
```

No Equalix yet.

The scheduler should not know about:

```
Pod names
Pod IPs
GPU IDs
Kubernetes node names
```

------

# 15. GPU-2 — ModelManager

Implement the interface first:

```
interface ModelManager {


    ModelCapabilities getCapabilities(String modelId);


    ModelStatus getStatus(String modelId);


    void ensureReady(String modelId);
}
```

This creates the boundary for GPU-3.

------

# 16. GPU-3 — ArtifactResolver

Now implement:

```
interface ArtifactResolver {


    Artifact resolve(ModelDescriptor descriptor);
}
```

Implementation:

```
SecureRegistryFetcher
```

Responsibilities:

1. authenticate
2. fetch artifact
3. verify digest
4. publish atomically
5. return artifact location

No Kubernetes logic in the domain.

------

# 17. GPU-3 — Shared Model Cache

Deployment:

```
Internal Registry
       │
       ▼
ArtifactResolver
       │
       ▼
Shared artifact volume
       │
       ▼
vLLM pods
```

The vLLM container receives:

```
/model-cache/<model-id>/<digest>
```

read-only.

The digest becomes part of the path.

This avoids ambiguous mutable model names.

------

# 18. GPU-3 — VllmRuntime

Implement:

```
interface ExecutionRuntime {


    ExecutionHandle execute(
        ExecutionRequest request,
        RuntimeTarget target
    );


    CancellationResult cancel(
        ExecutionHandle execution
    );


    RuntimeStatus status(
        ExecutionHandle execution
    );
}
```

Then:

```
VllmRuntime
```

is the first implementation.

The rest of the system should depend only on `ExecutionRuntime`.

------

# 19. Runtime Retry Safety

Implement:

```
RetryDisposition
```

as a domain concept:

```
NOT_ACCEPTED
ACCEPTED_UNKNOWN
COMPLETED_UNKNOWN
DEFINITELY_FAILED
```

Only retry `NOT_ACCEPTED` automatically.

Do not put generic:

```
retryOn(IOException.class)
```

around vLLM execution.

That would violate execution idempotency.

------

# 20. GPU-3 — Model Loading

Implement:

```
ModelManager
      │
      ▼
ArtifactResolver
      │
      ▼
Model Cache
      │
      ▼
vLLM
```

State:

```
UNAVAILABLE
    ↓
LOADING
    ↓
AVAILABLE
```

Failure:

```
LOADING
    ↓
LOAD_FAILED
    ↓
retry
```

Queued executions associated with permanent/current load failure are atomically transitioned:

```
QUEUED
   ↓
FAILED / MODEL_LOAD_FAILED
```

------

# 21. GPU-3 — Graceful Runtime Lifecycle

vLLM Kubernetes configuration:

```
readinessProbe
livenessProbe
preStop
terminationGracePeriodSeconds
```

Shutdown sequence:

```
unready
  ↓
remove from Service
  ↓
drain
  ↓
terminate
```

Do not rely on `sleep 30` as the primary mechanism.

------

# 22. GPU-3 — Lazy Reconciliation

Implement inside:

```
GetStatusUseCase
```

not as a separate controller.

```
RUNNING
   │
   ▼
stale?
   │
   ├── no → return
   │
   └── yes
        │
        ▼
   runtime check
        │
        ├── alive → refresh
        │
        └── unavailable
                ↓
          RUNTIME_UNAVAILABLE
```

No Kubernetes operator.

No controller-runtime dependency.

No background reconciliation framework.

------

# 23. GPU-4 — Main Platform Integration

Implement in SynAnton Core:

```
GpuExecutionClient
GpuSynthesisAdapter
```

The Core calls:

```
Execute
GetStatus
Cancel
GetCapacity
```

and knows nothing about:

```
Kubernetes
vLLM
GPU node
GPU ID
Pod
```

------

# 24. CPU Fallback

The Main Platform owns:

```
GPU failure
     │
     ▼
GpuExecutionClient
     │
     ▼
fallback policy
     │
     ▼
CPU synthesis
```

Never implement CPU fallback inside `gpu-execution-plane`.

------

# 25. GPU-4 — Observability

Use the same Spring/Micrometer/Prometheus conventions as Core.

Metrics:

```
gpu_execute_total
gpu_execute_duration_seconds


gpu_execution_active
gpu_execution_queue_depth
gpu_execution_queue_wait_seconds


gpu_admission_rejected_total
gpu_concurrency_rejected_total


gpu_model_load_duration_seconds
gpu_model_load_failure_total


gpu_idempotency_hit_total
gpu_idempotency_conflict_total


gpu_postgres_transaction_duration_seconds


gpu_runtime_failure_total
gpu_runtime_retry_total
```

Tracing:

```
synanton.gpu.execute
synanton.gpu.admission
synanton.gpu.runtime
```

Never add:

```
prompt
token contents
authorization assertion
```

to spans or logs.

------

# 26. Testing Strategy

This should follow Core's testing model closely.

The rules specify unit tests without Spring context and integration tests as black-box Spring-context tests. 

## Unit tests

```
RequestCanonicalizerTest
AdmissionServiceTest
ExecutionStateTest
IdempotencyServiceTest
DirectSchedulerTest
ModelCapabilityValidatorTest
RetryDispositionTest
```

No Spring.

Use Mockito where dependencies are required.

------

## Integration tests

Use:

```
Spring Boot
PostgreSQL Testcontainer
gRPC client
mock runtime
```

Test:

```
Execute → persisted execution
Execute duplicate → same execution
conflicting request → rejection


concurrency limit
parallel Execute race


Cancel
GetStatus
GetCapacity


model load failure cascade
runtime disappearance
```

The concurrency race test is especially important.

Example:

```
limit = 8


100 concurrent Execute requests


expected:
8 accepted
92 CONCURRENCY_LIMIT
```

------

# 27. GPU-5 — Production Infrastructure

Only after the Java execution semantics work.

Implement Helm:

```
gpu-gateway
vllm
postgresql
shared-model-cache
network-policy
service-monitor
idempotency-cleaner
```

Production PostgreSQL:

```
CloudNativePG
```

rather than the development StatefulSet.

------

# 28. GPU-5 — Failure Injection

Required scenarios:

### PostgreSQL

```
DB unavailable
DB timeout
DB failover
```

Expected:

```
Execute → fail closed
```

### vLLM

```
pod killed
connection reset
pod drained
runtime timeout
```

### Registry

```
registry unavailable
artifact corrupted
digest mismatch
```

### Kubernetes

```
GPU node drain
vLLM rolling deployment
GPU pod eviction
```

------

# 29. GPU-6 — Equalix

Do not implement Equalix until the measurements exist.

The interface remains:

```
ExecutionScheduler
```

So:

```
scheduler.type=direct
```

becomes:

```
scheduler.type=equalix
```

without changing:

```
ExecuteUseCase
AdmissionService
ModelManager
ExecutionRuntime
gRPC contract
```

This is exactly the architectural payoff of the separation.

------

# 30. Recommended Gradle Modules

I would **not create a dozen Gradle modules** initially.

Start with exactly two:

```
java/gpu-contract
java/gpu-gateway
```

Dependency graph:

```
gpu-gateway
     │
     ▼
gpu-contract
```

Within `gpu-gateway`, maintain the hexagonal package separation.

Only split additional modules when there is an actual independent build/dependency boundary.

------

# 31. Build Conventions

Copy the Core's Gradle conventions where possible.

The Core rules explicitly use:

```
build.gradle.kts
settings.gradle.kts
./gradlew
```

and provide the standard compile/test/build workflow. 

Development loop:

```
./gradlew compileJava
./gradlew test
./gradlew build
```

For the gateway:

```
./gradlew :java:gpu-gateway:test
```

------

# 32. Cursor Rules

I would **copy the Core Java rules into the new repository**, then add a small GPU-specific rule file.

```
.cursor/
└── rules/
    ├── java-rules.mdc
    └── gpu-execution-rules.mdc
```

`java-rules.mdc` governs:

- Java style
- architecture
- Gradle
- testing
- logging
- nullability
- Spring conventions

`gpu-execution-rules.mdc` governs:

- execution state machine
- idempotency invariant
- PostgreSQL source-of-truth invariant
- no Kubernetes leakage
- runtime retry semantics
- no prompt/token logging
- CPU fallback prohibition
- lazy reconciliation

This is better than duplicating all the Java rules and allowing them to drift.

------

# 33. GPU-Specific Rules

The additional Cursor rule should explicitly say:

```
1. Domain code MUST NOT depend on Kubernetes, vLLM, protobuf,
   PostgreSQL, Spring adapters, or infrastructure clients.


2. PostgreSQL is the authoritative execution-state store.


3. Redis MUST NOT be introduced for execution state or idempotency.


4. Main Platform MUST NOT receive Kubernetes/vLLM/GPU topology.


5. Runtime retries MUST respect RetryDisposition.


6. CPU fallback MUST NOT be implemented in this repository.


7. Equalix is optional and MUST implement ExecutionScheduler.


8. Prompts, token contents, credentials, and authorization assertions
   MUST NOT be logged.


9. Execution state transitions MUST be validated by the domain.


10. All concurrency admission decisions MUST be transactionally safe.
```

------

# 34. Implementation Order

I would execute the work in this exact order:

```
                 ┌────────────────────┐
                 │ GPU-1              │
                 │ Contract            │
                 │ + invariants        │
                 └─────────┬──────────┘
                           │
                           ▼
                 ┌────────────────────┐
                 │ GPU-2              │
                 │ Domain + PostgreSQL │
                 │ + admission         │
                 └─────────┬──────────┘
                           │
                           ▼
                 ┌────────────────────┐
                 │ GPU-3              │
                 │ Model + vLLM       │
                 │ + artifacts        │
                 └─────────┬──────────┘
                           │
                           ▼
                 ┌────────────────────┐
                 │ GPU-4              │
                 │ Core integration   │
                 └─────────┬──────────┘
                           │
                           ▼
                 ┌────────────────────┐
                 │ GPU-5              │
                 │ Production         │
                 └─────────┬──────────┘
                           │
                           ▼
                 ┌────────────────────┐
                 │ GPU-6              │
                 │ Equalix evaluation │
                 └────────────────────┘
```

## Immediate first PR

I would make the **first PR deliberately boring**:

```
gpu-execution-plane/
├── Gradle Kotlin DSL
├── Java 21
├── Spring Boot 3.x
├── gpu-contract
├── gpu-gateway
├── copied SynAnton Java rules
├── GPU-specific Cursor rules
├── protobuf definitions
├── generated gRPC classes
├── PGV validation
└── contract tests
```

**No Kubernetes. No vLLM. No PostgreSQL yet.**

Then PR #2 introduces the domain and PostgreSQL execution model.

That gives you a clean progression where every subsequent layer is built on a tested contract rather than discovering architecture while integrating GPUs.

### Bottom line

**Yes: use the same Java/Spring/Gradle stack as SynAnton Core.** The repository rules already establish exactly the conventions we need: Java 21, Spring Boot 3.x, Gradle Kotlin DSL, gRPC/Protobuf, PostgreSQL/Flyway, hexagonal architecture, and the same testing discipline. 

The GPU plane should be **a sibling Java service with a deliberately smaller dependency footprint**, not a separate technology stack. That gives SynAnton one engineering model while preserving the very important **runtime/physical isolation boundary**.