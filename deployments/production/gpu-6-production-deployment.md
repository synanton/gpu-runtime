# GPU-6 — Production GPU Runtime Deployment

**Status:** Planned  
**Purpose:** Define the production deployment profile for GPU Runtime while preserving the stable `synanton.gpu.v1` external API validated by GPU-5.

## 1. Scope

GPU-6 is the production deployment track.

It is deliberately separate from the four-node homelab deployment:

```text
deployments/
├── 4nodes-homelab/
│   └── gpu-5-reference-deployment.md
│
└── production/
    └── gpu-6-production-deployment.md
```

GPU-6 must not assume that production hardware, Kubernetes topology, storage, networking, GPU models, or model-server implementation are identical to the homelab.

## 2. Architectural invariant

The Synanton Platform communicates with GPU Runtime through the long-lived external API:

```text
Synanton Platform
        |
        | gRPC / synanton.gpu.v1
        v
GPU Runtime
        |
        +-- execution admission
        +-- model lifecycle
        +-- runtime selection
        +-- GPU scheduling
        +-- inference backend
```

Kubernetes is an implementation/deployment substrate.

vLLM is an implementation backend.

Neither should become part of the platform-facing API.

This permits the production runtime to evolve independently from the Platform.

## 3. Production requirements

Production deployment must address the limitations intentionally accepted by GPU-5.

### Required

- highly available control plane appropriate to the chosen infrastructure;
- production-grade GPU node lifecycle;
- capability-based GPU scheduling;
- production service discovery;
- TLS/mTLS with managed certificate rotation;
- secret management;
- durable PostgreSQL with backup and recovery;
- durable model/artifact storage;
- observability and alerting;
- resource quotas and workload isolation;
- controlled model rollout;
- runtime health and failure recovery;
- documented upgrade and rollback procedures.

## 4. External networking

The production Platform must not know:

- Kubernetes Pod IPs;
- Kubernetes Service names;
- GPU node names;
- vLLM endpoints;
- model-server ports.

Use a stable production endpoint:

```text
Platform
   |
   | mTLS gRPC
   v
stable GPU Runtime endpoint
   |
   v
production gateway/service layer
```

The endpoint may be implemented using:

- an internal load balancer;
- service discovery;
- an ingress/gateway;
- a service mesh;
- another organization-standard mechanism.

The choice is deployment-specific.

The protobuf/gRPC contract does not change.

## 5. Authentication and authorization

Production gRPC communication must use mutual authentication.

At minimum:

```text
Platform client certificate
        |
        v
GPU Runtime verifies client identity

GPU Runtime certificate
        |
        v
Platform verifies server identity
```

Certificate lifecycle must include:

- issuance;
- rotation;
- expiration monitoring;
- revocation/replacement;
- secure private-key storage.

Do not bake certificates into container images.

## 6. GPU scheduling

Production should replace homelab `nodeName` pinning with capability-based scheduling where practical.

Possible node attributes include:

```text
GPU vendor
GPU model
VRAM capacity
compute capability
architecture
runtime/backend compatibility
```

A workload should express a capability requirement rather than an absolute physical node identity.

Conceptually:

```text
embedding model
      |
      v
requires >= X GiB VRAM
requires backend Y
      |
      v
scheduler chooses eligible GPU
```

Hard placement remains valid for exceptional workloads that require dedicated hardware.

## 7. Runtime backends

GPU Runtime should keep the inference backend behind the runtime abstraction.

Possible implementations include:

```text
ExecutionRuntime
    |
    +-- vLLM
    +-- another inference server
    +-- specialized embedding runtime
    +-- specialized reranking runtime
```

The Platform must never need to know which backend executes a model.

The backend may expose different HTTP/gRPC APIs internally; adapters translate them into the GPU Runtime execution model.

## 8. Model lifecycle

Production model deployment should distinguish:

```text
artifact available
        ↓
artifact integrity verified
        ↓
model downloaded
        ↓
model loaded
        ↓
runtime ready
        ↓
execution admitted
```

A Kubernetes Pod being `Running` must not automatically mean the model is ready.

Model readiness should be represented explicitly.

Artifact integrity remains mandatory.

The existing GPU Runtime invariant is retained:

> exactly one artifact download for a `(model_id, digest)` across concurrent Gateway replicas.

## 9. Storage

Production should not rely on node-local `hostPath` as the only durable model-storage mechanism.

Use a storage architecture appropriate to the environment, for example:

```text
artifact registry/object storage
          |
          v
node-local cache
          |
          v
GPU runtime
```

The node-local cache can still be used for inference performance.

The important distinction is:

```text
source of truth
      ≠
local performance cache
```

A GPU node can therefore be replaced without losing the authoritative model artifact.

## 10. PostgreSQL

Production PostgreSQL must provide:

- durable storage;
- backup;
- restore testing;
- monitoring;
- controlled upgrades;
- failover appropriate to the required availability.

GPU Runtime execution state remains PostgreSQL-backed.

The production architecture must preserve the existing invariant that PostgreSQL is the authoritative source of execution state.

Do not introduce Redis or another database as a shadow execution-state store merely for availability.

## 11. Observability

Production monitoring should cover at least:

### Runtime

- request rate;
- success/error rate;
- queue depth;
- admission rejection;
- execution duration;
- model-loading duration;
- runtime failures;
- retry disposition.

### GPU

- GPU utilization;
- memory utilization;
- temperature;
- power;
- ECC/error state where supported;
- GPU allocation failures;
- node health.

### Model

- model readiness;
- model version/digest;
- artifact download failures;
- cache hit/miss;
- load failures.

### Infrastructure

- Kubernetes node health;
- Pod restarts;
- PostgreSQL health;
- storage health;
- certificate expiration;
- network failures.

Sensitive prompts and document content must not be written to logs or traces.

## 12. Failure and recovery

Production must define deterministic behavior for:

### GPU failure

```text
GPU failure
    ↓
runtime becomes unavailable
    ↓
execution reports failure
    ↓
scheduler/service layer determines whether another eligible GPU can accept work
```

### Model load failure

Queued executions for the failed model must receive deterministic failure semantics.

### Node failure

If another compatible GPU exists, production scheduling may place the workload elsewhere.

If no compatible GPU exists, the API should report runtime unavailability rather than silently falling back to CPU execution.

### Gateway failure

A new gateway instance must recover execution state from PostgreSQL rather than an in-memory shadow.

## 13. Deployment strategy

Production deployment should support:

- immutable container images;
- versioned manifests/Helm or equivalent deployment packaging;
- controlled rollout;
- rollback;
- configuration separation from secrets;
- environment-specific values;
- automated validation.

Unlike GPU-5, production packaging should be selected according to the operational environment rather than copied mechanically from the homelab.

## 14. Network isolation

The GPU plane should be isolated from unrelated workloads.

At minimum:

```text
Platform
   |
   | allowed gRPC
   v
GPU Runtime gateway
   |
   | allowed inference traffic
   v
model servers
```

Model servers should not be generally reachable by arbitrary workloads.

NetworkPolicy or an equivalent network-control layer should enforce this boundary.

## 15. Capacity and scheduling

Production capacity management should distinguish:

```text
available GPU capacity
        ↓
model compatibility
        ↓
admission control
        ↓
queueing
        ↓
execution
```

GPU Runtime's existing `ExecutionScheduler` abstraction remains the correct extension point for future scheduling policies.

A more advanced scheduler should only be introduced when benchmark/production measurements demonstrate a need.

GPU-5 benchmark results should therefore be treated as input evidence rather than assuming a particular scheduler implementation in advance.

## 16. Security boundary

The production GPU plane is a security boundary.

Required controls include:

- mTLS;
- least-privilege Kubernetes service accounts;
- secret isolation;
- restricted network access;
- signed/verified images where available;
- artifact digest verification;
- controlled registry access;
- audit logging without sensitive payloads;
- regular dependency/image updates.

## 17. Benchmark continuity

Production deployment must remain benchmark-compatible.

A benchmark run should be able to identify:

```text
GPU Runtime deployment
GPU model
model ID
model digest
runtime/backend version
GPU Runtime version
Platform version
dataset version
benchmark configuration
```

This allows a result from the four-node homelab to be compared with a production deployment without conflating software/model changes with hardware changes.

## 18. GPU-6 acceptance criteria

GPU-6 is complete when:

- [ ] stable production gRPC endpoint exists;
- [ ] Platform communicates exclusively through `synanton.gpu.v1`;
- [ ] mTLS is enforced;
- [ ] production secrets are managed outside images/manifests;
- [ ] GPU scheduling is capability-aware where required;
- [ ] model artifacts have an authoritative durable source;
- [ ] node-local model caches are treated as caches, not source of truth;
- [ ] PostgreSQL has backup/recovery procedures;
- [ ] runtime and GPU metrics are monitored;
- [ ] alerts exist for critical runtime failures;
- [ ] GPU/model/node failure behavior is tested;
- [ ] deployment rollback is documented and tested;
- [ ] network policies isolate model servers;
- [ ] production benchmark metadata is reproducible;
- [ ] the GPU-5 contract/integration tests remain valid unchanged.

## 19. What GPU-6 must not change

GPU-6 must not redesign the Platform/GPU boundary merely because the deployment becomes more sophisticated.

The following remain stable:

```text
synanton.gpu.v1
    |
    +-- Execute
    +-- Cancel
    +-- GetStatus
    +-- GetCapacity
```

Production complexity belongs behind the contract.

This is the central architectural purpose of the separate deployment tracks.
