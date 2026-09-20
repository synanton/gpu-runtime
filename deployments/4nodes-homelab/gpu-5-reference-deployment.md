# GPU-5 — Four-Node Homelab Deployment & Platform Benchmark Environment

**Status:** Planned  
**Purpose:** Provide a real external GPU Runtime for Synanton Platform benchmark experiments involving embeddings and reranking, while validating the same runtime plane for LLM hosting.

## 1. Purpose and scope

GPU-5 is a **reference/benchmark deployment**, not the production deployment.

It provides a reproducible deployment of GPU Runtime on the four-node homelab and validates the complete external execution path:

```text
Synanton Platform
        |
        | synanton.gpu.v1 / gRPC
        | long-lived external API
        v
+---------------------------+
| GPU Runtime — homelab     |
|                           |
|  embedding runtime        |
|  reranking runtime        |
|  LLM runtime              |
+-------------+-------------+
              |
              v
       NVIDIA GPU workers
```

The immediate Synanton Platform benchmark requirement is:

1. embedding inference;
2. reranking inference.

GPU-5 also includes **LLM hosting as a first-class workload**. This validates that GPU Runtime is a general AI inference plane rather than an embedding/reranking-specific service.

The homelab deployment may use hard-coded/internal configuration for:

- Kubernetes node names;
- GPU placement;
- model identifiers;
- local model-cache paths;
- local registry;
- service ports;
- the externally reachable GPU Runtime endpoint;
- benchmark-specific resource limits;
- model-server endpoints.

These are deployment-profile details and must not become dependencies of the `synanton.gpu.v1` Platform API.

---

## 2. Why GPU-5 exists

Synanton Platform benchmark work needs real GPU execution for embeddings and reranking.

At the same time, GPU Runtime should prove that the same execution plane can host general-purpose LLM inference.

GPU-5 therefore validates:

```text
                         Benchmark
                             |
                             v
                    Synanton Platform
                             |
                             | gRPC
                             v
                       GPU Runtime
                       /    |    \
                      /     |     \
                     v      v      v
                embedding rerank  LLM
                  runtime runtime runtime
                     |      |       |
                     +------+-------+
                            |
                            v
                       NVIDIA GPUs
```

The benchmark result is not considered representative if the platform silently uses a CPU fallback or bypasses GPU Runtime and calls an inference backend directly.

---

## 3. Deployment location

GPU-5 belongs under:

```text
deployments/
└── 4nodes-homelab/
    └── gpu-5-reference-deployment.md
```

The directory represents one **deployment profile**, not a property of the runtime implementation.

Additional environments can later be added independently:

```text
deployments/
├── 4nodes-homelab/
│   ├── README.md
│   └── gpu-5-reference-deployment.md
│
├── team-member-homelab/
│   └── ...
│
└── production/
    └── gpu-6-production-deployment.md
```

The runtime implementation and public gRPC contract remain shared.

---

## 4. Reference hardware

| Node | Role | GPU | VRAM |
|---|---|---|---:|
| `node0` | Kubernetes control plane / registry | — | — |
| `node1` | GPU worker | GTX 1650 | 4 GiB |
| `node2` | GPU worker | RTX 4060 Ti | 16 GiB |
| `node3` | GPU worker | RTX 5060 Ti | 16 GiB |

The homelab is a dedicated GPU Runtime environment. It does not need production-grade HA.

The heterogeneous GPUs are intentional: GPU-5 should demonstrate that the runtime can operate across different GPU capabilities while keeping physical placement behind the deployment profile.

---

## 5. External API boundary

### Required

Synanton Platform communicates with GPU Runtime through the stable external API:

```text
synanton.gpu.v1.GPUExecutionService
```

The Platform must not depend on:

- Kubernetes Service DNS names;
- Kubernetes Pod IPs;
- GPU node names;
- vLLM endpoints;
- model-server-specific HTTP APIs;
- model-cache paths;
- a particular GPU model.

The homelab deployment may hard-code these internally.

### Example

Platform configuration can contain a deployment-specific endpoint:

```text
GPU_RUNTIME_ENDPOINT=<homelab-gpu-runtime-endpoint>:9090
```

The exact endpoint is a property of this deployment profile.

It should not be embedded into Java code or the protobuf contract.

For the reference homelab, a static external endpoint is acceptable. Production will use a separately managed endpoint/discovery mechanism.

---

## 6. Workloads

GPU-5 provides GPU hosting for three workload classes:

1. **embedding models**;
2. **reranker models**;
3. **LLM models**.

The immediate Synanton Platform benchmark milestone requires real embedding and reranking execution.

LLM hosting is included to validate the broader role of GPU Runtime as a general AI inference plane.

### Recommended initial placement

```text
node1 / GTX 1650, 4 GiB
    |
    +-- small LLM / development model
    |
    +-- lightweight GPU experiments

node2 / RTX 4060 Ti, 16 GiB
    |
    +-- embedding model
    |
    +-- embedding gateway/runtime

node3 / RTX 5060 Ti, 16 GiB
    |
    +-- reranker model
    |
    +-- reranking gateway/runtime
    |
    +-- LLM hosting where capacity permits
```

The exact model/GPU assignment is a benchmark and deployment configuration decision.

The important architectural distinction is:

```text
workload capability
       !=
physical GPU placement
```

GPU-5 may hard-code physical placement because the hardware is known.

The public API must remain independent of that placement.

### Workload priority

| Workload | GPU-5 purpose | Priority |
|---|---|---|
| Embeddings | Synanton Platform benchmark | Required |
| Reranking | Synanton Platform benchmark | Required |
| LLM inference | GPU Runtime hosting validation | Required for reference deployment |

---

## 7. LLM hosting

LLM hosting is a first-class GPU-5 workload.

The intended architecture is:

```text
Synanton Platform
       |
       | synanton.gpu.v1
       v
GPU Runtime
       |
       +--> embedding runtime
       |
       +--> reranker runtime
       |
       +--> LLM runtime
                |
                v
              vLLM
                |
                v
             NVIDIA GPU
```

vLLM is the initial inference backend for LLM hosting.

The vLLM-specific HTTP API must remain behind the GPU Runtime runtime adapter.

The Platform must never call vLLM directly.

The initial homelab deployment may use an independent gateway/runtime instance for each vLLM endpoint because the current gateway configuration uses a single `VLLM_ENDPOINT` per instance.

Conceptually:

```text
                    GPU Runtime
                    logical API
                         |
              +----------+----------+
              |                     |
              v                     v
        gateway/runtime       gateway/runtime
             LLM                  other
              |                     |
              v                     v
           vLLM/LLM           vLLM/embed/rerank
              |                     |
            GPU                   GPU
```

This is an internal deployment topology.

It must not leak into the `synanton.gpu.v1` contract.

### LLM acceptance

The LLM hosting path must demonstrate:

- model can be loaded on an allocated GPU;
- runtime reports model readiness;
- inference reaches the physical GPU;
- GPU Runtime exposes the execution through the public gRPC API;
- the Platform does not call vLLM directly;
- no CPU fallback is silently used.

The initial model should be selected according to the available VRAM and benchmark purpose rather than assuming that every homelab GPU can host the same LLM.

---

## 8. Embedding and reranking hosting

Embedding and reranking are the immediate reason for connecting Synanton Platform to the external GPU Runtime.

The deployment must provide real GPU-backed execution for both:

```text
Platform
   |
   | synanton.gpu.v1
   v
GPU Runtime
   |
   +--> embedding model
   |
   +--> reranker model
```

The selected inference backend must support the actual workload interfaces required by the models.

If the current `VllmRuntime` only supports completion/chat-completion operations, GPU-5 must add the smallest backend adaptation necessary to support embedding and reranking through the existing `ExecutionRuntime` abstraction.

The public `synanton.gpu.v1` contract should not be redesigned merely because vLLM exposes different internal HTTP endpoints.

---

## 9. Kubernetes design

Use the existing four-node Kubernetes architecture:

- Calico CNI;
- NVIDIA GPU Operator;
- one `nvidia.com/gpu` allocation per GPU worker;
- `runtimeClassName: nvidia`;
- no MIG;
- no GPU time-slicing;
- plain Kubernetes manifests;
- node-pinned GPU workloads;
- `Recreate` deployment strategy for exclusive GPU workloads;
- node-local model storage;
- local registry on `node0`.

GPU placement is intentionally hard-coded for this deployment profile.

This should not be generalized into the runtime API.

---

## 10. GPU scheduling and placement

GPU-5 uses explicit placement because it is a known, fixed homelab.

The deployment can use:

```text
nodeName
```

or equivalent hard placement for the reference workloads.

This deliberately expresses:

```text
"run this benchmark workload on this known GPU"
```

rather than attempting to solve general production GPU scheduling.

GPU-6 will address capability-based placement, where workloads can express requirements such as:

```text
minimum VRAM
GPU architecture
compute capability
backend compatibility
```

The difference is intentional:

```text
GPU-5
known hardware
    |
    v
explicit placement

GPU-6
heterogeneous production fleet
    |
    v
capability-based scheduling
```

---

## 11. Model storage

Use node-local storage for model weights/cache:

```text
<node local SSD>
        |
        +-- model cache
```

The cache is coupled to the pinned GPU node.

This is acceptable for the homelab because the deployment is deliberately hardware-specific.

It is not the production storage architecture.

The production deployment should distinguish:

```text
authoritative model artifact
          !=
node-local performance cache
```

---

## 12. Container registry

Use the existing local registry on `node0`.

Images must be built once and mirrored/imported to the registry used by the GPU nodes.

Avoid pulling the same large runtime image independently from external registries on every GPU node.

The deployment should document the exact image tags/digests used for benchmark runs.

---

## 13. PostgreSQL

Use a single PostgreSQL instance for the homelab.

It is persistent but not HA.

Flyway remains responsible for schema initialization.

The benchmark environment does not require CloudNativePG or database failover.

PostgreSQL remains the authoritative source of execution state.

---

## 14. Security

The external gRPC API is a real service boundary even though the server is hosted in a home lab.

GPU-5 should therefore use TLS/mTLS for the long-lived Platform connection before the environment is treated as the stable benchmark endpoint.

Deployment-specific certificate/key material belongs in Kubernetes Secrets or another deployment-local secret mechanism.

Do not commit credentials or private keys.

The homelab may use a manually managed certificate authority and static endpoint configuration. Production certificate lifecycle belongs to GPU-6.

---

## 15. Readiness and health

Kubernetes `Running` must not be treated as equivalent to model readiness.

The deployment should distinguish:

```text
Pod started
    |
    v
runtime initialized
    |
    v
model loaded
    |
    v
GPU runtime ready
    |
    v
execution admitted
```

Readiness should fail when the runtime cannot execute the configured workload.

This is particularly important for LLM hosting because model loading may take substantially longer than process startup.

---

## 16. Observability

At minimum expose:

### Runtime

- gateway health;
- readiness;
- execution state;
- execution latency;
- model/runtime errors;
- GPU allocation failures;
- inference failures;
- model loading state.

### GPU

- utilization;
- memory usage;
- temperature;
- allocation state;
- GPU health where available.

### Benchmark

Record enough metadata to distinguish:

```text
Platform latency
    +
gRPC/network latency
    +
gateway admission/queue time
    +
model loading time
    +
GPU inference time
```

Cold-cache and warm-cache runs should be distinguishable.

Do not log prompts, authorization assertions, or sensitive document content.

---

## 17. Failure scenarios

GPU-5 should define deterministic behavior for the main failure modes.

### GPU unavailable

```text
GPU allocation failure
        |
        v
runtime unavailable
        |
        v
execution fails explicitly
```

No silent CPU fallback.

### Model loading failure

```text
Pod Running
     !=
Model Ready
```

The runtime must remain unready if the configured model cannot be loaded.

### GPU OOM

The deployment must record and expose the inference failure.

The runtime must not report successful execution after a failed GPU invocation.

### Node failure

Because GPU-5 uses hard placement:

```text
node2 fails
   |
   v
embedding workload unavailable
```

Automatic migration is not required for GPU-5.

This is an intentional limitation of the reference deployment.

### Gateway restart

Execution state must remain recoverable from PostgreSQL.

---

## 18. Benchmark integration

The end-to-end benchmark path is:

```text
Benchmark
    |
    v
Synanton Platform
    |
    | external gRPC
    v
GPU Runtime
    |
    +---- embedding
    |
    +---- reranking
    |
    +---- LLM where benchmarked
    |
    v
NVIDIA GPU
```

The Platform benchmark must not bypass the GPU Runtime.

The benchmark configuration should identify:

- Platform version;
- GPU Runtime version;
- deployment profile;
- GPU model;
- model ID;
- model digest;
- inference backend/version;
- dataset version;
- workload configuration;
- warm/cold cache state.

This makes benchmark results reproducible and comparable across deployment profiles.

---

## 19. Acceptance criteria

GPU-5 is complete when all of the following are true.

### Infrastructure

- [ ] Four-node Kubernetes cluster is reproducibly documented.
- [ ] NVIDIA GPU Operator exposes the three GPU workers.
- [ ] Local registry works from all GPU nodes.
- [ ] Model caches persist across pod restarts.
- [ ] Embedding, reranking and LLM workloads can be assigned to known GPUs.

### Runtime

- [ ] `gpu-gateway` image builds reproducibly.
- [ ] PostgreSQL migrations succeed.
- [ ] Gateway readiness/liveness probes pass.
- [ ] Embedding execution reaches a real GPU.
- [ ] Reranking execution reaches a real GPU.
- [ ] LLM execution reaches a real GPU.
- [ ] Model readiness is distinct from Pod readiness.
- [ ] No CPU fallback is silently used.
- [ ] Runtime failures are reported through existing execution semantics.

### Platform integration

- [ ] Synanton Platform calls the homelab through `synanton.gpu.v1` over gRPC.
- [ ] The Platform does not call vLLM directly.
- [ ] The Platform does not depend on Kubernetes-internal DNS.
- [ ] Contract mirror verification remains green.
- [ ] Platform benchmark runs use the GPU Runtime path.
- [ ] Embedding and reranking benchmark calls reach real GPUs.

### LLM hosting

- [ ] At least one LLM is successfully hosted on a homelab GPU.
- [ ] The model reaches a ready state.
- [ ] An inference request reaches the GPU Runtime.
- [ ] GPU utilization can be observed during inference.
- [ ] The LLM backend remains behind the runtime abstraction.

### Benchmark

- [ ] Embedding benchmark completes against real GPU Runtime.
- [ ] Reranking benchmark completes against real GPU Runtime.
- [ ] LLM smoke test completes against real GPU Runtime.
- [ ] Warm-cache and cold-cache behavior are distinguishable.
- [ ] Latency/throughput metrics are captured.
- [ ] Results are reproducible from documented deployment configuration.

---

## 20. Known limitations

GPU-5 intentionally does not provide:

- multi-zone HA;
- automatic GPU fleet scheduling;
- portable production storage;
- automatic failover between heterogeneous GPUs;
- production secret management;
- automated certificate lifecycle;
- automatic model placement;
- production service discovery;
- guaranteed failover after GPU-node loss.

These belong to GPU-6.

The fact that these capabilities are absent from GPU-5 is intentional and should not be interpreted as a limitation of the `synanton.gpu.v1` API.

---

## 21. Relationship to GPU-6

GPU-5 proves that the stable external GPU execution boundary works against real heterogeneous hardware and can host:

```text
embeddings
reranking
LLMs
```

GPU-6 defines how the same boundary is operated in production:

```text
GPU-5
four-node homelab
benchmark/reference
        |
        | same synanton.gpu.v1 contract
        v
GPU-6
production GPU infrastructure
```

The key architectural invariant is:

> Deployment topology may change; `synanton.gpu.v1` remains the Platform-facing GPU execution contract.
