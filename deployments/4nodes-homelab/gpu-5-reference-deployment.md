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

# GPU-5 — Kubernetes Reference Deployment Plan

**Status:** Ticket backlog — not started. No Helm charts, manifests, or Dockerfile exist yet; this document breaks GPU-5 ("Kubernetes Reference Deployment" in the README's roadmap table) into ticket-sized items. 

**Context:** GPU-1 through GPU-3 are complete. GPU-4 (Main Platform Integration) has the `synanton.gpu.v1` contract unified and mirrored with `platform`, but routing is still optional. GPU-5 is a confirmed blank slate — `helm/`, `deploy/`, and `Dockerfile` do not exist anywhere in this repo, despite the README's own "Repository Structure" tree describing them. That tree is aspirational/stale (verified directly against the filesystem): it also shows conflicting package names (`com.Synanton.gpu` / `com.synanton.gpu` vs. the real `org.synanton.gpu`) and references files that don't exist (`scripts/deploy-onprem.sh`, `scripts/smoke-test.sh`). Treat this document, not that tree, as the source of truth for what GPU-5 actually requires.

**Target hardware:** the existing homelab k8s cluster is being **recreated as a cluster dedicated to Synanton's GPU plane** (not shared with any other workload). Same 4-node hardware as before:

| Node | Role | CPU | RAM | GPU |
|---|---|---|---|---|
| `node0` | control-plane | 4 | 16Gi | none |
| `node1` | worker | 16 | 64Gi | GTX 1650, 4GB |
| `node2` | worker | 20 | 64Gi | RTX 4060 Ti, 16GB |
| `node3` | worker | 20 | 64Gi | RTX 5060 Ti, 16GB |

**Reference sources for every ticket below:**
- Cluster bootstrap shape: `cluster-as-built.md` (Calico CNI, NVIDIA GPU Operator, local registry, Longhorn/`local-ssd`).
- Workload manifest shape: `https://github.com/andreminin/speech-to-speech-k8s/k8s/` (real, currently-running GPU Deployments on this exact hardware) and `docs/deployment.md` (the actual `kubectl apply` sequence and gotchas hit deploying to it).

---

## Cluster bootstrap (new — dedicated cluster, not a shared one)

### T-K8S-0 — Recreate the cluster on the same 4-node hardware

Reproduce the proven shape rather than redesigning it:
- Calico CNI (`calico-system` namespace: `calico-node` DaemonSet on all 4 nodes, `calico-apiserver`, `calico-kube-controllers`, `calico-typha`).
- NVIDIA GPU Operator's device plugin DaemonSet, targeting `node1`-`node3` only (`node0` has no GPU). `RuntimeClass nvidia` (handler `nvidia`) is what GPU-requesting Pods set via `runtimeClassName: nvidia`. Each GPU node exposes exactly `nvidia.com/gpu: 1` — no MIG, no time-slicing.
- Longhorn (`longhorn-system` namespace) + a `local-ssd` `StorageClass` (`kubernetes.io/no-provisioner`, `WaitForFirstConsumer`) for latency-sensitive local data (model caches use this, not Longhorn — see T-K8S-5).
- A plain `registry:2` container run via `docker compose` directly on `node0` (not inside Kubernetes), reachable as `local-registry:5000` via `/etc/hosts` mapping on client machines, basic-auth protected.

**Decision:** keep the same node-role assignment (`node0` control-plane + registry host, `node1`-`node3` workers) — no reason to change a layout that's already proven to work on this hardware.

**Definition of done:** `kubectl get nodes` shows all 4 nodes `Ready`; `kubectl get pods -n kube-system -l name=nvidia-device-plugin-ds` shows 3 running pods (one per GPU worker); `docker push` to `local-registry:5000/test:latest` succeeds from an admin workstation.

### T-K8S-0b — Namespace + registry secret for the new cluster

No coexistence with a `speech` namespace this time — pick a namespace name (suggest `gpu-runtime`) and set it up fresh.

Reuse `https://github.com/andreminin/speech-to-speech-k8s/scripts/create-registry-secret.sh`'s exact pattern — it's already namespace-parameterized:
```bash
NAMESPACE=gpu-runtime REGISTRY_USER=... REGISTRY_PASSWORD=... ./create-registry-secret.sh
```
Either run it from that sibling repo directly, or copy the ~30-line script into this repo (`scripts/create-registry-secret.sh`) so GPU-5 doesn't depend on a sibling checkout being present. It creates the namespace (idempotent `kubectl apply` of a `Namespace` object) and a `docker-registry`-type `Secret` (default name `local-registry-cred`) that every Deployment's `imagePullSecrets` will reference.

**Definition of done:** `kubectl get ns gpu-runtime` exists; `kubectl get secret local-registry-cred -n gpu-runtime` exists; a test pod in that namespace referencing that secret can pull an image from `local-registry:5000`.

---

## Tickets proposal

### T-K8S-1 — Containerize `gpu-gateway`

New `Dockerfile` (repo root or `deployment/docker/`, matching the naming convention `content_extractor`/`platform` already use): multi-stage, `eclipse-temurin:21-jdk` build stage → `eclipse-temurin:21-jre` runtime stage.
21-jdk-alpine shouldn't be used because native grpc tools libs are missing in alpine image.

Build command: `./gradlew :java:gpu-gateway:bootJar`. Output jar lands at `java/gpu-gateway/build/libs/gpu-gateway-<version>.jar` (Gradle project name `gpu-gateway`, version from root `build.gradle.kts`'s `version = "0.1.0-SNAPSHOT"`). Spring Boot's Gradle plugin sets `Main-Class` in the jar manifest automatically — no explicit override needed, but the real class is `org.synanton.gpu.GpuGatewayApplication` if one is ever needed for documentation. `java:gpu-contract` needs no separate packaging or COPY step — it's a compile-time dependency already bundled into `gpu-gateway`'s fat jar.

**Use `--mount=type=cache,target=/root/.gradle` on the build `RUN` step from the start.** This session had to add that fix reactively to `content_extractor`'s Dockerfile after it caused real pain: without it, every image rebuild redownloads the full Gradle distribution + all Maven dependencies from scratch, and this development environment's network proved unreliable for that specific kind of large, sustained download (repeated SSL/connection drops on `services.gradle.org` and Maven Central, each attempt failing at a different point). Building this in from the first version avoids repeating that debugging cycle.

**Definition of done:** `docker build -f Dockerfile -t gpu-gateway .` succeeds from a clean Gradle cache; `docker run gpu-gateway` starts and logs `Started GpuGatewayApplication` (with `GPU_GATEWAY_DISPATCH_STRATEGY=stub`, the default — no vLLM/Postgres needed to just prove the image boots).

### T-K8S-2 — Build-and-push tooling

Reuse `speech-to-speech-k8s/scripts/build-and-push.sh`'s pattern: build this repo's own Dockerfile, tag and push to `local-registry:5000/gpu-gateway:<tag>`.

If `node1`-`node3` on the new cluster also lack direct internet egress (true on the current cluster per `cluster-as-built.md`), also port `mirror-images.sh`'s pattern for third-party base images this repo depends on (`eclipse-temurin:21-jdk`/`21-jre-alpine`, `postgres:16-alpine` if used for T-K8S-3, a vLLM base image for T-K8S-4).

**Known bug to avoid if copying `mirror-images.sh` verbatim:** its `IMAGES` associative array has two entries both keyed `[node]` (meant for `node:24-slim` and `python:3.11-slim`) — the second silently overwrites the first in Bash, so one of those two images never actually gets mirrored. Use distinct keys for each image if porting this script.

**Definition of done:** `local-registry:5000/gpu-gateway:<tag>` is pullable from any of `node0`-`node3`.

### T-K8S-3 — Postgres

`gpu-gateway` needs (real env vars, confirmed from `application.yml`, not the README's aspirational names):
- `GPU_GATEWAY_DB_URL` (default `jdbc:postgresql://localhost:5432/gpu_gateway` — must be overridden)
- `GPU_GATEWAY_DB_USER` (default `synanton`)
- `GPU_GATEWAY_DB_PASSWORD` (default `synanton`)

Flyway migrations already exist at `java/gpu-gateway/src/main/resources/db/migration/` (`V1__create_executions.sql`, `V2__add_artifact_cache.sql`) and run automatically on startup (`baseline-on-migrate: false`) — no manual schema setup needed beyond having a reachable, empty database.

**Recommend a plain single-instance Postgres Deployment** (`postgres:16-alpine`, one replica, a `local-ssd` `hostPath` or small Longhorn volume for `/var/lib/postgresql/data`) — not the README's aspirational CloudNativePG HA setup. HA Postgres is disproportionate for a reference/PoC deployment, and no CloudNativePG operator is part of the T-K8S-0 bootstrap plan.

**Definition of done:** `gpu-gateway` starts against this Postgres instance and Flyway reports both migrations applied (`SELECT * FROM flyway_schema_history;` shows 2 rows, both `success=true`).

### T-K8S-4 — vLLM Deployment(s) + Service, model/node placement plan

**Confirmed constraint (from code, not just config):** `gpu-gateway` only supports one flat `VLLM_ENDPOINT` (default `http://vllm-service:8000`, a same-namespace Service DNS name) — `VllmRuntime`/`VllmModelManager` have no per-model host/port map, just this single URL plus per-model metadata (`concurrency-limit`, `max-input-tokens`, `runtime-class`, `digest`) under `gpu-gateway.models.<id>`.

The design needs both an LLM and an embedding model (mirroring `platform`'s own Docker Compose Phase 2 profile: separate `vllm-llm` + `vllm-embed` services). Two options — **decide before writing any manifest**:

- **(a) Two independent `gpu-gateway` + vLLM pairs, one per model, each its own Service and own GPU node.** E.g. LLM pair on `node2` (16GB RTX 4060 Ti), embedding pair on `node3` (16GB RTX 5060 Ti) or `node1` (4GB GTX 1650, if the embedding model is small enough). Matches `gpu-gateway`'s single-endpoint assumption exactly — **no code change needed**.
- **(b) Extend `gpu-gateway` to a per-model endpoint map, then one shared gateway in front of two vLLM Services.** More flexible long-term, but real Java code changes to `VllmRuntime`/`DomainConfig` before any manifest can be written.

**Recommend (a).** The dedicated cluster (per this turn's direction) has full node/GPU freedom now — no reason to take on the code-change risk of (b) just to save running two gateway pairs instead of one.

No vLLM-specific reference manifest exists in this hardware's own history — `speech-to-speech-k8s` runs `llama.cpp` instead of vLLM (a deliberate choice documented in that repo, not a gap to fill here). Adapt its `k8s/llm/deployment.yaml` **shape**, not its content: single-GPU (`resources.requests/limits: {nvidia.com/gpu: 1, cpu, memory}`), `runtimeClassName: nvidia`, `nodeName: <pinned>` (hard pin, not `nodeSelector` — this cluster's established convention), `strategy: {type: Recreate}` (a rolling-update surge pod can never schedule a second copy of a single un-partitioned GPU, so `RollingUpdate` just hangs), `imagePullSecrets: [{name: local-registry-cred}]`, a `hostPath` volume for model weights, and HTTP readiness/liveness probes against vLLM's own health endpoint.

**Definition of done:** two vLLM instances (LLM + embedding) running on two different GPU nodes, each backed by its own `gpu-gateway` instance; a real `Execute()` call against each returns a real inference result (see T-K8S-10).

### T-K8S-5 — Storage for model artifacts

`MODEL_CACHE_DIR` (default `/model-cache`) and vLLM's own model weight downloads: follow `speech-to-speech-k8s`'s precedent — `hostPath` under `local-ssd` on the node the model actually runs on, not Longhorn replicated storage. That repo's own stated rationale (latency/simplicity for large model files; Longhorn reserved for smaller data that actually benefits from replication) applies identically here.

**Definition of done:** model weights persist across a pod restart without re-downloading (confirm via a deliberate pod delete + recreate, checking startup time drops after the first successful pull).

### T-K8S-6 — mTLS (currently completely absent from the codebase)

Confirmed this session: a repo-wide, case-insensitive grep for `tls|mtls|ssl` across every `.java`/`.yml` file in this repo returned **zero hits**. gRPC currently runs plaintext (`grpc-netty-shaded`, no TLS config anywhere). Both `platform`'s README and this repo's own docs describe mTLS as the intended boundary control between the Main Platform and the isolated GPU plane — this is a real prerequisite for anything beyond a fully-trusted internal network, not an afterthought.

**Decide:** gRPC TLS/mTLS configured directly in `gpu-gateway` (cert/key mounted via a `Secret`, Spring gRPC server TLS config added), vs. terminating TLS at a service-mesh or ingress layer in front of it. Either is legitimate; **do not defer this decision past whatever milestone first exposes the gateway beyond the cluster's own internal network.**

**Definition of done:** a plaintext gRPC call from outside the trust boundary is rejected; an authenticated mTLS call succeeds.

### T-K8S-7 — Health probes + Prometheus scrape

`management.endpoints.web.exposure.include: health,prometheus,info` already exists in `application.yml`, on `server.port` (default `8091`, HTTP — management/metrics only). Wire k8s `livenessProbe`/`readinessProbe` (`httpGet: {path: /actuator/health, port: 8091}`) and a Prometheus scrape annotation or `ServiceMonitor` against that same port. `9090` is the gRPC traffic port and isn't probe-able the same way — don't point probes at it.

**Definition of done:** `kubectl describe pod` shows both probes passing; a Prometheus instance (if/when one exists on the cluster) shows `gpu_gateway_*` metrics.

### T-K8S-8 — Plain manifests, not Helm

This repo's README aspires to Helm (`helm/gpu-runtime/Chart.yaml`, etc.). The reference cluster's own established convention — every component in `speech-to-speech-k8s`, **including Traefik despite it having an upstream Helm chart** (that repo deliberately chose plain manifests for Traefik too, specifically to reuse the existing `imagePullSecrets` pattern) — is plain `kubectl apply -f` YAML, organized one directory per component (`k8s/<component>/{deployment,service}.yaml`), applied via a small phase-dispatch `deploy.sh` script.

**Recommend following that convention on the new cluster too.** Don't let the README's aspiration default this decision — introducing this environment's first Helm-packaged application is a real, deliberate choice to make explicitly, not something to back into.

**Definition of done:** a decision is recorded (even if it's "use Helm anyway, here's why") before T-K8S-1 through T-K8S-7's artifacts are actually written as manifests.

### T-K8S-9 (optional, non-blocking) — NetworkPolicy

`speech-to-speech-k8s` has exactly one `NetworkPolicy` (`internal-services-from-gateway-only`): ingress to its inference Pods restricted to only its own gateway Pod, via `podSelector.matchExpressions` with `operator: In` covering multiple Deployment labels in one policy. That repo's own docs explicitly mark this "Phase F+ hardening, not required for the first milestone" — same call here. The equivalent for this service would restrict ingress to vLLM's Pods to only `gpu-gateway`'s Pods.

No `ResourceQuota`/`LimitRange` precedent exists anywhere on this hardware's history to copy — if wanted, that's a fresh design exercise, not a port from the reference repo.

**Definition of done (if picked up):** a NetworkPolicy exists restricting vLLM ingress to `gpu-gateway` only; explicitly tracked as deferred otherwise, not silently skipped.

### T-K8S-10 — Smoke test

Once T-K8S-1 through T-K8S-4 are deployed: a real `Execute()` gRPC call end-to-end (`gpu-gateway` → vLLM → real inference response), matching the pattern `content_extractor`'s `scripts/docker-smoke-test.sh` established this session — build the image, stand up the minimal dependency set, make one real call through the whole stack, assert success, tear down. Adapt that script's structure for `kubectl apply`/`kubectl port-forward` (or a `kubectl run` one-off client pod) against the live cluster instead of `docker compose`.

**Definition of done:** a scripted, repeatable smoke test exists and passes against a freshly deployed cluster.

### T-K8S-11 — `verify-gpu-contract-mirror.sh` stays green

Already exists (`scripts/verify-gpu-contract-mirror.sh`) and already runs in CI (`.github/workflows/gpu-contract-mirror.yml`, checks out this repo + `Synanton/platform` side by side, asserts the `synanton.gpu.v1` proto is byte-identical). No new ticket needed — just confirm none of T-K8S-1 through T-K8S-10 touches `java/gpu-contract/src/main/proto/**` without re-running this check.
