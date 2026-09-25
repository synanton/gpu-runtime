# GPU-5 Homelab Implementation Plan

**Status:** Contract defined. Implementation complete, including T-K8S-6a (§13). **Cluster phases 0–5 and 7 pass:**
- **Local end-to-end** test with the real Envoy config: 17/17.
- **On the cluster**, through Gateway → Envoy (JWT) → GPUs: EMBED, SYNTHESIZE, stream and RERANK succeed.
- **Negative checks:** unsigned requests get 401, and direct backend access is denied.

**Next:** load baselines (§11, §9), then platform integration (Gateway exposure to the workstation, benchmark principal). One inference workload per physical GPU.
**Revision date:** 2026-09-25
**Canonical spec:** `../../doc/GPU-5  GPU-6  GPU-7 Deployment Plan.md`
**Model setup doc:** `../../doc/GPU-5 Local Models Setup.md` (placement superseded — see D2/D4 below)
**Cluster reference:** `./claster-as-build.md`

---

## 1. Decisions and deviations from the spec

| # | Decision | Spec reference | Rationale |
|---|----------|----------------|-----------|
| D1 | vLLM pinned at **v0.29.0** (`vllm/vllm-openai:v0.29.0`, default `cu130` variant) — used on node2/node3 only. | §9 baseline says `vLLM 0.6.x` | Qwen3-Reranker support exists only in vLLM ≥ 0.9.2 (PR vllm-project/vllm#19260, merged 2025-06-11). §9 explicitly permits runtime upgrades as validated compatibility changes. node2 is Ada (sm_8.9), node3 is Blackwell (sm_12.0); the v0.29.0 cu130 image covers both. Validation per §9 is part of the acceptance phase. |
| D2 | **One inference workload per physical GPU** (PR #15 review §2): node1 → embedding, node2 → reranker, node3 → synthesis. The newest/largest GPU (RTX 5060 Ti) serves the workload with the greatest memory pressure (synthesis: KV cache, concurrency, long context). | §41 "one GPU per inference workload" | The 2026-09-23 revision colocated embedding+reranker in one pod on node3 with a single `nvidia.com/gpu: 1` request. That design is **invalid**: device-plugin extended resources are allocated per *container*, not per Pod; there is no Pod-level GPU visibility inheritance; and one GPU request creates no VRAM partition. PR #15 review blocked on it (P0.1). The colocated manifest is removed, not kept as an option; any future GPU sharing goes through documented mechanisms (MIG/time-slicing) as a separate experiment, never the default PoC. |
| D3 | Model copies as verified **2026-09-24**: node2 holds `qwen3-reranker-0.6b` (normalized into `models/` today) plus `qwen3-4b-instruct-2507` and flat `qwen3-embedding-0.6b`; node3 holds `qwen3-4b-instruct-2507` (normalized today) plus embedding/reranker copies. The node2↔node3 role swap therefore needed **no inter-node copy** — only the flat→`models/` moves in §4.4. Operator practice: **all models are mirrored on all nodes** — `bge-base-en-v1.5` is complete everywhere (§4.5); `bge-small-en-v1.5` (fallback) is complete everywhere too (verified 2026-09-25). Extra copies are harmless (hostPath reads only the local copy, D6). | Local Models Setup §5 | §4.4–4.6 |
| D4 | Embedding serving stack on node1: **TEI `turing-1.9` + `BAAI/bge-base-en-v1.5` (fp16)**; `BAAI/bge-small-en-v1.5` is the documented fallback model (PR #15 review §2.2 "TEI / equivalent encoder server", §3.1). | §41, Local Models Setup | The GTX 1650 is consumer Turing (sm_7.5, 4 GB, no bf16, no tensor cores): the pinned vLLM cu130 image ships no sm_75 kernels, so vLLM is not an option on node1. TEI publishes a `turing` variant for sm_7.5 (flash-attention auto-disabled on Turing per TEI's precision warning). BGE-base fp16 is ~0.5 GB — comfortable in 4 GB. Fallback ladder if the GPU path misbehaves at bring-up: bge-small → TEI CPU build on node1's 16C/64Gi. |
| D5 | Namespace **`gpu-plane`**; registry secret name **`local-registry-cred`**; images pulled from **`local-registry:5000`** on node0. | §22 T-K8S-0b | Reuses the existing registry and conventions from the speech-to-speech project. |
| D6 | Model weights via `hostPath` from `/mnt/local-fast/models/...`, read-only, container path `/models`. | §41, Local Models Setup §9 | Longhorn is installed but not used for model weights (matches spec: "Longhorn is not required for model weights"). |
| D7 | Gateway/Envoy/PostgreSQL **stay on node1** (CPU-only) rather than moving PostgreSQL to node0 as sketched in the PR #15 review node table. | §22 | node0 is tainted `node-role.kubernetes.io/control-plane:NoSchedule` and is **not** a Longhorn storage node (only node1–3 are), so node0 PostgreSQL would need a taint toleration plus non-replicated storage — a worse trade for a PoC than co-locating CPU-only services next to node1's GPU workload (they request no GPU, so no contention). The review's "no inference on node0" intent is honored. |

## 2. Target layout

| Node | Hardware | GPU-5 workloads | Model (hostPath) |
|------|----------|-----------------|------------------|
| node0 | control-plane, no GPU | container registry (`local-registry:5000`) — already running | — |
| node1 | 16C/64Gi, GTX 1650 4GB | Gateway, Envoy, PostgreSQL (CPU-only) + **TEI embedding** (GPU) | `/mnt/local-fast/models/bge-base-en-v1.5` |
| node2 | 20C/64Gi, RTX 4060 Ti 16GB | vLLM reranker | `/mnt/local-fast/models/qwen3-reranker-0.6b` |
| node3 | 20C/64Gi, RTX 5060 Ti 16GB | vLLM synthesis | `/mnt/local-fast/models/qwen3-4b-instruct-2507` |

```text
Synanton Platform
  | gRPC synanton.gpu.v1 over mTLS (Deployment Plan §4, §13; doc/GPU Plane mTLS Setup.md)
  v
Gateway (node1, gRPC :9090, metrics :8091)
  | ES256 execution JWT (spec §12)
  v
Envoy (node1, :8080)  jwt_authn ← JWKS from Gateway /internal/.well-known/jwks.json
  | 300 s upstream timeout (spec §31)
  +-- /v1/chat/completions --> vllm-synthesis:8000  (node3 pod, RTX 5060 Ti)
  +-- /v1/embeddings       --> tei-embedding:8000   (node1 pod, GTX 1650)
  +-- /v1/rerank           --> vllm-reranker:8000   (node2 pod, RTX 4060 Ti)
Gateway --> PostgreSQL (node1, :5432, Longhorn PVC)
```

Clients see only the logical Synanton model IDs below — never node addresses or runtimes (PR #15 review §2.4).

| Synanton model ID | Backend |
|---|---|
| `synanton-bge-base-embedding` | node1 TEI → `/models` (BAAI/bge-base-en-v1.5) |
| `synanton-qwen3-reranker-0.6b` | node2 vLLM → `/models` (Qwen3-Reranker-0.6B) |
| `synanton-qwen3-4b-synthesis` | node3 vLLM → `/models` (Qwen3-4B-Instruct-2507) |

Notes:

- The embedding public ID **changed** from `synanton-qwen3-embedding-0.6b` — the
  backend model changed (D4). Recorded here as a deliberate PoC contract change.
- `synanton-bge-small-embedding` (BAAI/bge-small-en-v1.5) is the documented
  embedding fallback (review §3.1): downloaded alongside (§4.5), not deployed
  by default.

## 3. Repository layout created by this plan

```text
deployments/homelab/
├── claster-as-build.md              # existing as-built record
├── gpu-5-implementation-plan.md     # this document
├── blueprints/                      # plain manifests for phased kubectl bring-up
│   ├── namespace.yaml
│   ├── tei/embedding.yaml           # node1 — TEI embedding, GTX 1650 (D4)
│   ├── vllm/reranker.yaml           # node2 — vLLM reranker, RTX 4060 Ti
│   ├── vllm/synthesis.yaml          # node3 — vLLM synthesis, RTX 5060 Ti
│   ├── postgres/postgres.yaml
│   ├── envoy/envoy-config.yaml
│   ├── envoy/envoy.yaml
│   ├── gateway/gateway.yaml
│   └── network-policy/network-policies.yaml
├── docker/
│   └── gateway.Dockerfile           # T-K8S-1 image blueprint
├── helm/gpu-plane/                  # packaged install path (post bring-up)
│   ├── Chart.yaml
│   ├── values.yaml
│   └── templates/
└── scripts/
    ├── mirror-images.sh             # copy public images into local-registry:5000 (MANUAL, §5)
    ├── create-registry-secret.sh    # local-registry-cred in gpu-plane
    ├── deploy.sh                    # phased blueprint apply
    ├── cluster-stop.sh              # quiesce: scale gpu-plane to 0, cordon+drain, optional poweroff
    └── cluster-start.sh             # restore: wait Ready, uncordon, GPU-aware rollout restore
```

Bring-up is done with **blueprints + scripts** (phased, debuggable). The **Helm chart** packages the same resources for repeatable reinstall once bring-up is validated; both paths must produce equivalent resources.

> **Manual operations:** model downloads (§4.5) and image mirroring (§5) are
> manual steps, executed by the operator — never by automation or agents.
> `deploy.sh` applies only Kubernetes manifests.

## 4. Prerequisites (Phase 0, T-K8S-0 / T-K8S-0b)

```bash
# 4.1 Uncordon workers (cluster is currently cordoned from the speech project)
kubectl uncordon node1 node2 node3
kubectl get nodes   # all Ready, no SchedulingDisabled

# 4.2 GPU operator sanity
kubectl get pods -n kube-system -l app=nvidia-device-plugin-daemonset
kubectl get runtimeclass nvidia

# 4.3 Verify models per node (verified 2026-09-24: qwen3 intact, Qwen3ForCausalLM bf16;
#     bge-base and bge-small (fallback) COMPLETE incl. tokenizer files on all nodes — 4.5)
ssh node1 'ls /mnt/local-fast/models/bge-base-en-v1.5'                                  # BGE-base: complete (4.5)
ssh node2 'test -f /mnt/local-fast/models/qwen3-reranker-0.6b/config.json && du -sh /mnt/local-fast/models/qwen3-reranker-0.6b'   # 1.2G
ssh node3 'test -f /mnt/local-fast/models/qwen3-4b-instruct-2507/config.json && du -sh /mnt/local-fast/models/qwen3-4b-instruct-2507'   # 7.6G

# 4.4 Path normalization to the canonical /mnt/local-fast/models/<model> layout
# DONE 2026-09-23 — first pass under the since-retracted layout (historical log only).
# DONE 2026-09-24 — swap pass for the D2 topology (executed and verified):
ssh node2 'mv /mnt/local-fast/qwen3-reranker-0.6b /mnt/local-fast/models/'   # reranker moves with its role
ssh node3 'mv /mnt/local-fast/qwen3-4b-instruct-2507 /mnt/local-fast/models/' # synthesis moves to node3

# 4.5 MANUAL model download — BGE models (operator-run, like §5 mirroring)
# Toolchain: a uv venv per node (avoids PEP 668 / system-Python issues):
#   uv venv --python 3.12 --seed ~/k8s/.venv && source ~/k8s/.venv/bin/activate
#   uv pip install "huggingface_hub[cli]"
# Known issue (observed 2026-09-24): SSL EOF against huggingface.co
# ([SSL: UNEXPECTED_EOF_WHILE_READING]) → export HF_ENDPOINT=https://hf-mirror.com
# and re-run; `hf download` resumes and only fetches missing files.
#
# State 2026-09-25: bge-base and bge-small (fallback, §2) COMPLETE on node1/2/3
# (operator mirrors models everywhere; weights + tokenizer files verified).
# To (re)create the fallback on a node:
ssh node1 'source ~/k8s/.venv/bin/activate && hf download BAAI/bge-small-en-v1.5 \
  --local-dir /mnt/local-fast/models/bge-small-en-v1.5'
# verify — all must exist before phase 3 (bge-small: same check on its directory):
ssh node1 'test -f /mnt/local-fast/models/bge-base-en-v1.5/tokenizer.json \
  -a -f /mnt/local-fast/models/bge-base-en-v1.5/tokenizer_config.json \
  -a -f /mnt/local-fast/models/bge-base-en-v1.5/vocab.txt && echo bge-base complete'

# 4.6 Model copies — the operator mirrors all models on all nodes (extra copies
# are harmless: hostPath mounts only ever read the local node's copy, D6).
# Purely-unused leftover: node2's flat /mnt/local-fast/qwen3-embedding-0.6b
# (pre-normalization duplicate; deletion is an operator decision).

# 4.7 Namespace + registry secret (reuses speech-project pattern)
./scripts/create-registry-secret.sh        # defaults: NAMESPACE=gpu-plane, SECRET_NAME=local-registry-cred

# 4.8 Registry login on the machine that will mirror/build images
docker login local-registry:5000
```

> **Contention warning:** the `speech` namespace still exists. Do not run speech GPU
> workloads (`speech-llm` on node2, `speech-stt-tts` on node3) concurrently with
> GPU-5 — each node has exactly one physical GPU (`nvidia.com/gpu: 1`).

## 5. Image mirroring to the local registry (T-K8S-2)

**Manual operation.** Run from a machine with internet access and `docker login local-registry:5000`:

```bash
./scripts/mirror-images.sh                  # mirrors the pinned set below
```

| Image | Source tag | Local tag |
|---|---|---|
| vLLM (D1) | `vllm/vllm-openai:v0.29.0` | `local-registry:5000/vllm-openai:v0.29.0` |
| TEI (D4) | `ghcr.io/huggingface/text-embeddings-inference:turing-1.9` | `local-registry:5000/text-embeddings-inference:turing-1.9` |
| Envoy | `envoyproxy/envoy:v1.31.0` | `local-registry:5000/envoy:v1.31.0` |
| PostgreSQL | `postgres:16.4` | `local-registry:5000/postgres:16.4` |
| Gateway base | `eclipse-temurin:21-jre` | `local-registry:5000/eclipse-temurin:21-jre` |
| Smoke-test client | `curlimages/curl:8.10.1` | `local-registry:5000/curlimages-curl:8.10.1` |

`turing-1.9` is TEI's sm_7.5 build (verified 2026-09-24: tag resolves on GHCR;
flash-attention is disabled by default on Turing per TEI's precision warning).

Gateway image (T-K8S-1, blueprint `docker/gateway.Dockerfile`):

```bash
./gradlew bootJar
docker build -f deployments/homelab/docker/gateway.Dockerfile \
  -t local-registry:5000/gpu-gateway:0.1.0 .
docker push local-registry:5000/gpu-gateway:0.1.0
```

**Digest pinning (spec §8):** before freeze, record digests into `helm/gpu-plane/values.yaml`:

```bash
for img in vllm-openai:v0.29.0 text-embeddings-inference:turing-1.9 envoy:v1.31.0 postgres:16.4 gpu-gateway:0.1.0; do
  docker buildx imagetools inspect "local-registry:5000/${img}" --format '{{.Manifest.Digest}}'
done
```

Floating tags are prohibited in execution manifests; blueprints use tags for bring-up and must be switched to `repo@sha256:...` in the Helm values at freeze.

## 6. Serving parameters

> **VRAM discipline (PR #15 review P1.5):** `--gpu-memory-utilization` values
> below are *envelopes*, not allocation guarantees. Do not record computed
> budgets as facts. Acceptance records **empirical observations**: the engine
> starts, loads, survives representative max-context/concurrent requests
> without CUDA OOM, and the observed VRAM/free-VRAM floor goes into §11.

### node3 — synthesis (`vllm-synthesis`)

```text
vllm serve /models
  --served-model-name synanton-qwen3-4b-synthesis
  --port 8000 --host 0.0.0.0
  --gpu-memory-utilization 0.90
  --max-model-len 32768
  --max-num-seqs 8
```

Weights: 7.6 GB bf16 on disk (measured, §4.3). KV cache takes the rest of the 0.90 × 16 GB envelope — an envelope, not a guarantee; the observed footprint is what §9 accepts. If KV pressure appears, drop `--max-model-len` to 16384.

### node2 — reranker (`vllm-reranker`)

```text
vllm serve /models
  --runner pooling
  --served-model-name synanton-qwen3-reranker-0.6b
  --port 8000 --host 0.0.0.0
  --gpu-memory-utilization 0.40
  --max-model-len 8192
  --hf-overrides '{"architectures": ["Qwen3ForSequenceClassification"], "classifier_from_token": ["no", "yes"], "is_original_qwen3_reranker": true}'
```

Dedicated GPU — the retracted colocation's port-8001/network-namespace hack is
gone; every inference Service is uniformly `:8000`. 0.40 is a generous envelope
for a 0.6 B pooling model (1.2 GB weights on disk, measured); raise only if the observed footprint says so.

The `--hf-overrides` block is required for the **official** `Qwen/Qwen3-Reranker-0.6B`
checkpoint (it is a `Qwen3ForCausalLM` checkpoint; the override routes it to the
sequence-classification path with yes/no logit scoring). Rerank is served at `/rerank`
(and `/v1/rerank`).

### node1 — embedding (`tei-embedding`, TEI turing build)

```text
text-embeddings-inference
  --model-id /models
  --served-model-name synanton-bge-base-embedding
  --hostname 0.0.0.0 --port 8000
  --dtype float16
```

Notes:

- `/models` is the node-local `bge-base-en-v1.5` dir (hostPath, read-only);
  TEI loads local paths directly — no Hub access (`HF_HUB_OFFLINE=1` set anyway).
- `--dtype float16`: Turing has no bf16. The weights are a 438 MB (418 MiB) fp32 safetensors file on disk (measured), loaded as fp16; the observed VRAM is recorded in §11.
- TEI serves the OpenAI-compatible `POST /v1/embeddings` route
  (`--served-model-name` applies to it). Verify at bring-up; if the mirrored
  build lacks the OpenAI route, remap in Envoy (`/v1/embeddings` → TEI `/embed`)
  rather than changing the public contract.
- Expected to be the lightest workload in the plane; if the Turing build
  misbehaves, fall back per D4 (bge-small → TEI CPU).

## 7. Phased bring-up (maps to spec §22 ticket sequence)

Each phase's exit criteria must pass before moving on.

| Phase | Tickets | Action | Exit criteria |
|-------|---------|--------|---------------|
| 0 | T-K8S-0, T-K8S-0b | §4 above | Nodes Ready/uncordoned; models verified per node (§4.3); `local-registry-cred` exists in `gpu-plane` |
| 1 | T-K8S-1, T-K8S-2 | §5 above: mirror images; build+push gateway | All 6 images in `local-registry:5000` |
| 2 | T-K8S-3 | `./scripts/deploy.sh postgres` | Pod Running on node1; `psql` connects via ClusterIP; V1/V2 Flyway migrations applied by first Gateway boot |
| 3 | T-K8S-5, T-K8S-10 | `./scripts/deploy.sh inference` | All 3 inference pods Running (tei-embedding on node1, vllm-reranker on node2, vllm-synthesis on node3); smoke tests pass (§8.1–8.3) |
| 4 | T-K8S-4, T-K8S-1a | `./scripts/deploy.sh gateway` — mode `local-only`, model registry → 3 inference services | Gateway ready; startup validation fails closed on bad config; `GetModels` lists the 3 Synanton IDs |
| 5 | T-K8S-6a, 6b, 6 | Keys → Secret `gpu-gateway-jwt-keys` (§7); `./scripts/deploy.sh gateway envoy` | Unsigned request → 401; Gateway-signed request reaches the backends; direct backend Service access still works only from Envoy (enforced in phase 7) |
| 6 | T-K8S-7, 8 | mTLS + tenant authorization: create the `gpu-gateway-tls` Secret from a self-signed PKI (`doc/GPU Plane mTLS Setup.md` §5); principal `synanton-platform` | Plaintext and foreign-CA clients refused; `tenant_not_allowed` for unauthorized tenants |
| 7 | T-K8S-11, 12 | `./scripts/deploy.sh policies` | Direct client→backend denied; logs contain no prompts/completions |
| 8 | T-K8S-9, 9a | Prometheus scrape of Gateway :8091 (`/actuator/prometheus`) | `gpu_gateway_*` metrics visible |
| 9 | T-K8S-13, 14, 15a | Idempotency retention 24 h; run acceptance suite (§9); proto contract mirror (`scripts/verify-gpu-contract-mirror.sh`) | §24 suite green against the packaged deployment |

Secrets are created imperatively and never committed (spec §12/§13):

```bash
# Phase 5 — execution JWT signing keys (T-K8S-6a, Plan §12.1): ES256 / P-256, PKCS#8,
# current + previous pairs in git-ignored/gpu5-jwt; creates/replaces Secret gpu-gateway-jwt-keys
deployments/homelab/scripts/generate-key.sh --apply
# rotation later (T-K8S-25): previous := current, new current; then restart the Gateway
#   deployments/homelab/scripts/generate-key.sh --rotate --apply
#   kubectl -n gpu-plane rollout restart deploy/gpu-gateway

# Phase 6 — gRPC mTLS: self-signed PKI (doc/GPU Plane mTLS Setup.md); keep ca.key offline
deployments/external/scripts/gen-certs.sh git-ignored/gpu5-pki synanton-platform
kubectl -n gpu-plane create secret generic gpu-gateway-tls \
  --from-file=server.crt=git-ignored/gpu5-pki/server.crt --from-file=server.key=git-ignored/gpu5-pki/server.key \
  --from-file=ca.crt=git-ignored/gpu5-pki/ca.crt

# Phase 2 — PostgreSQL credentials
kubectl -n gpu-plane create secret generic gpu-postgres-cred \
  --from-literal=POSTGRES_USER=gpu --from-literal=POSTGRES_PASSWORD="$(openssl rand -base64 24)" \
  --from-literal=POSTGRES_DB=gpu_plane
```

## 8. Smoke tests

Two levels, complementary:

**A. End-to-end via the Gateway** — over the platform transport, gRPC
`synanton.gpu.v1` on :9090 (Deployment Plan v3.0.0 §4; requires `grpcurl`):

```bash
kubectl -n gpu-plane port-forward svc/gpu-gateway 9090:9090
GPU_GRPC_CERT_DIR=git-ignored/gpu5-pki GPU_GRPC_CLIENT=synanton-platform \
  tools/gpu-grpc-call.sh localhost:9090 GetModels '{"operation":"SYNTHESIZE"}'
./scripts/smoke-test.sh gateway          # same call via port-forward
```

**B. Per-service, pre-Gateway (phases 3/5)** — in-cluster curl pod (image already
mirrored), or `./scripts/smoke-test.sh <synthesis|embed|rerank|envoy>`
(port-forward based, no API key needed):

```bash
CURL='kubectl run smoke --rm -it --restart=Never -n gpu-plane \
  --image=local-registry:5000/curlimages-curl:8.10.1 \
  --overrides={"spec":{"imagePullSecrets":[{"name":"local-registry-cred"}]}} --'
```

```bash
# 8.1 synthesis (phase 3)
$CURL -- curl -s vllm-synthesis:8000/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"synanton-qwen3-4b-synthesis","messages":[{"role":"user","content":"Say OK"}],"max_tokens":8}'

# 8.2 embedding (phase 3) — TEI on node1
$CURL -- curl -s tei-embedding:8000/v1/embeddings -H 'Content-Type: application/json' \
  -d '{"model":"synanton-bge-base-embedding","input":"hello"}'

# 8.3 rerank (phase 3) — vLLM on node2, uniform :8000
$CURL -- curl -s vllm-reranker:8000/v1/rerank -H 'Content-Type: application/json' \
  -d '{"model":"synanton-qwen3-reranker-0.6b","query":"ping","documents":["pong"," unrelated"]}'

# 8.4 negative: unsigned request to Envoy must 401 (phase 5)
$CURL -- curl -s -o /dev/null -w '%{http_code}' -X POST envoy:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -d '{}'
```

Record VRAM per pod with `ssh node1/2/3 nvidia-smi` (or `scripts/record-vram.sh`)
and append to §11.

## 9. Acceptance (spec §24 + PR #15 review §3, homelab-executable subset)

Executable after phase 9 against the packaged deployment:

- §24.1 cases 1–10 over gRPC (model discovery, Execute chat/embed/rerank, ExecuteStream framing, usage, request IDs, idempotent replay, GetStatus, Envoy JWT acceptance).
- §24.2 cases 1–8 (negative matrix incl. direct-backend denial and fail-closed JWKS).
- §24.3 admission cases (concurrency; nothing unenforced is advertised).
- §24.4 contract cases incl. proto mirror equality (`scripts/verify-gpu-contract-mirror.sh`, also in CI).
- §10 streaming: `chat.completion.chunk` data messages, exactly one terminal message, usage unset when authoritative usage is unavailable.
- D1 validation per §9: upgrade checklist (JWT, streaming, model load, GPU memory, graceful shutdown) run once against vLLM v0.29.0 and recorded here.

**Per-workload hardware acceptance (PR #15 review §3):**

- **Embedding — node1 (GTX 1650):** the GPU is allocated to the embedding
  workload (`nvidia.com/gpu: 1` held by the tei-embedding pod); BGE-base loads;
  512-token inputs complete; representative batch/concurrency completes without
  CUDA OOM; GPU memory consumption, p50/p95 latency, and embeddings/sec are
  recorded (§11 / `docs/benchmarks.md`); BGE-small remains the documented
  fallback.
- **Reranking — node2 (RTX 4060 Ti 16 GB):** the reranker loads; a
  representative candidate set completes without CUDA OOM; latency and
  throughput are recorded.
- **Synthesis — node3 (RTX 5060 Ti 16 GB):** Qwen3-4B loads; maximum tested
  context and maximum tested concurrency are documented; GPU memory consumption
  is recorded; **streaming works through Envoy and the Gateway**.
- **End-to-end:** `embedding → retrieval → reranking → synthesis` executes
  through the single GPU Gateway with clients using only the logical model IDs
  (no physical node addressing).

**Empirical GPU acceptance (review P1.5)** — replaces any computed "VRAM budget";
`--gpu-memory-utilization` values are envelopes, never evidence:

1. All three engines start **concurrently** (one per node, `deploy.sh inference`)
   and each reaches Ready and loads its model.
2. Each survives a representative **max-context** request (synthesis: the largest
   tested `--max-model-len`; embedding: 512-token inputs; reranker: a
   representative candidate set) plus the documented concurrency, without CUDA OOM.
3. Each GPU keeps an **observed free-VRAM floor ≥ 512 MiB** during (2)
   (`scripts/record-vram.sh` / `nvidia-smi` sampled during the load).
4. The observed values — used/total, free-VRAM floor, max tested context and
   concurrency, p50/p95 latency — are recorded in §11 from the actual run.

## 10. Helm packaging path

After blueprints pass acceptance:

```bash
helm upgrade --install gpu-plane deployments/homelab/helm/gpu-plane \
  -n gpu-plane --create-namespace
```

The chart reproduces the blueprints parameterized by `values.yaml` (registry, tags+digests,
node placement, model hostPaths, GPU memory envelopes). Blueprint-vs-chart equivalence is
checked by diffing `helm template` output against `blueprints/` in CI (follow-up to T-K8S-32,
which is formally a GPU-6 ticket — homelab treats the chart as convenience, not the source of truth).

## 11. Observed baselines (fill in during bring-up)

**Status (2026-09-25): phase 5 passed on the cluster; load baselines pending.**

**End to end through the Gateway (mTLS `synanton-platform`)** → Envoy (execution JWT verified) → real GPU backends, single requests:

| Operation | Model | Result | Latency |
|---|---|---|---|
| EMBED | bge-base | 2×768 | 166 ms |
| SYNTHESIZE | Qwen3-4B | "OK" | 91 ms |
| ExecuteStream | Qwen3-4B | 12 data chunks + 1 terminal | 282 ms |
| RERANK | Qwen3-Reranker | correct order | 74 ms |

**Perimeter checks:**
- unsigned request to Envoy → 401;
- from a non-Envoy pod, TEI, both vLLM backends and the JWKS port are unreachable (NetworkPolicy).

The "used" VRAM below is **idle after model load**, from `nvidia-smi`. The load columns (§9 criteria 1–4) stay blank until a load test is run; they are never estimated.

| Pod | Node | VRAM used / total | Free-VRAM floor under load | Max tested context / concurrency | p50 / p95 | Notes |
|-----|------|-------------------|----------------------------|----------------------------------|-----------|-------|
| tei-embedding | node1 | 471 / 4096 MiB (idle) | _ | 512 tokens / _ | single req 166 ms (2 inputs) | GTX 1650, sm_7.5, fp16 |
| vllm-reranker | node2 | 6229 / 16380 MiB (idle) | _ | _ / _ | single req 74 ms (2 docs) | RTX 4060 Ti; cold start ≈ 1 min |
| vllm-synthesis | node3 | 13689 / 16311 MiB (idle; `--gpu-memory-utilization 0.90` preallocates KV cache) | _ | 32768 / 8 (configured) | single req 91 ms (2 output tokens) | RTX 5060 Ti; cold start ≈ 4.5 min |

## 11a. Cluster lifecycle (power off / on)

Use `scripts/cluster-stop.sh` / `scripts/cluster-start.sh` (adapted from the
speech-to-speech project, retargeted to the `gpu-plane` namespace) for planned
downtime. Stop scales every gpu-plane Deployment to 0 (replica counts saved as
`gpu.cluster/prev-replicas` annotations) and waits for termination *before*
cordon+drain — this ordering is what prevents the zombie-pod storm seen on this
cluster when GPU pods outlive the node shutdown. Start waits for all nodes
Ready, uncordons, restores non-GPU Deployments (postgres/gateway/envoy)
immediately, holds GPU Deployments (vllm-*, tei-embedding) at 0 until the
NVIDIA device plugin reports healthy per worker, then restores and waits out
rollouts. Cold-start rollout timeout is generous (600 s) because of vLLM kernel
autotune / TEI model load.
`cluster-stop.sh --poweroff` also powers the nodes off over SSH (workers first,
control-plane last; sudo password prompted per node); `--skip-snapshot` skips
the best-effort etcd backup on node0.

## 12. Known risks / follow-ups

1. **vLLM 0.29.x on consumer GPUs** (D1): first-boot kernel autotune can take minutes; readiness probes use `initialDelaySeconds: 120`, `failureThreshold: 10`.
2. **Resolved (T-K8S-6a, §13): verified on the cluster 2026-09-25.** *Originally:* Gateway → Envoy is blocked until T-K8S-6a (execution-JWT signing + JWKS on
   :8090) lands: the gateway does not sign JWTs or serve JWKS yet, so Envoy's
   `jwt_authn` fails closed and every local execution is rejected. Phases 0–4 and
   §8.1–8.3 (direct backend smoke) are executable today; end-to-end GPU-5 execution
   through the Gateway is not. The gateway config is complete (strategy `direct`,
   `vllm-endpoint: http://envoy:8080`, 3 LOCAL catalog entries — verified to boot
   and advertise exactly the 3 IDs). **Planned in §13**, which also covers a second
   blocker: the `/v1/models` readiness poll can't pass through Envoy (§13.2).
3. **Envoy body-hash enforcement** (spec §12): `jwt_authn` validates signature/iss/aud/exp; SHA-256 body-hash claim verification may need a Lua/ext filter — tracked under T-K8S-6b acceptance. Decided (§13.3 J2): 6a emits the `body_sha256` claim now, and enforcement is a 6b follow-up.
4. **TEI turing build on GTX 1650** (D4): sm_7.5 consumer card without tensor cores, TEI's turing variant is marked experimental upstream. Phase 3 validates it; fallback ladder is bge-small → TEI CPU on node1. If neither works, embedding moves to node2 (vLLM, Qwen3-Embedding-0.6B — already on disk) and the reranker returns to node1 only if a CPU reranker path is validated — i.e. reopen D2/D4 rather than silently re-colocating.
5. **Mirrored model copies** (D3/§4.6): models are deliberately mirrored on all nodes (operator practice); the only pure leftover is node2's flat `qwen3-embedding-0.6b`. Harmless either way — hostPath reads only the local copy.
6. **GPU-7 external profile**: the PR #15 review's remaining blockers (Compose↔Spring datasource env mismatch, mock-provider dispatch, external routing strategy, model-ID rewriting, streaming, expanded acceptance tests) are gateway-code items in `deployments/external/` and the platform `gpu-gateway` module — tracked in PR #15, out of scope for this GPU-5 local-only plan. GPU-5 is unaffected: it runs `local-only` mode with no external dispatch.

7. **Fixed during the first cluster bring-up (2026-09-25).** Phases 0–4 plus the phase 5 negative check run on the cluster. Every direct-backend smoke test passes (§8.1–8.3), Envoy rejects unsigned requests (401), and Gateway `GetModels` over mTLS lists the three IDs. Three manifest bugs were fixed (blueprint and Helm):
   - **vLLM args:** the `vllm-openai` image ENTRYPOINT is already `["vllm","serve"]`, so the leading `serve` arg made vLLM exit with "unrecognized arguments: /models". Removed.
   - **Envoy probes:** the admin interface is bound to `127.0.0.1:9901`, so kubelet probes got "connection refused" and liveness restarted Envoy in a loop. A new probe listener on `:9902` proxies only `GET /ready` to the admin interface, which stays private.
   - **vLLM probes:** cold start (weights + torch.compile + CUDA graphs) measured about 1 min for the reranker (RTX 4060 Ti) and about 4.5 min for synthesis (RTX 5060 Ti). The old liveness window (180 s + 3×30 s) killed synthesis just before it served, and the compile cache sat in the container filesystem, so every restart recompiled and the pod never converged. The fix is a `startupProbe` allowing up to 15 min, plus an `emptyDir` at `/root/.cache/vllm`.

## 13. T-K8S-6a plan — execution-JWT signing + JWKS (planned 2026-09-25)

**Goal:** unblock GPU-5 end-to-end execution (Gateway → Envoy → TEI/vLLM) per spec §12 and
§17. **Scope:** T-K8S-6a (Gateway signing + JWKS), the Envoy changes it depends on (6b), and
a readiness blocker found while planning (§13.2). GPU-7 is unaffected: spec §12 applies only
to GPU-5, so `OpenAiProviderRuntime` is not touched.

### 13.1 Current state

| Part | State |
|---|---|
| Spec §12 | ES256; `iss=synanton-gpu-gateway`; `aud=gpu-plane-execution`; SHA-256 of the exact forwarded body bytes in the token; exactly two keys (current + previous); keys file-mounted, never passed via env or logged; JWKS at `/internal/.well-known/jwks.json`, cached 5 min; Envoy fails closed; Gateway ready before Envoy |
| Envoy (6b) | `jwt_authn` with `remote_jwks` → `gpu-gateway:8090`; checks signature, `iss`, `aud` and `exp`; the `/` prefix requires a valid token (`blueprints/envoy/envoy-config.yaml`, Helm `templates/envoy.yaml`) |
| Manifests | Secret `gpu-gateway-jwt-keys` mounted **optionally** at `/etc/gpu-gateway/keys`; NetworkPolicy reserves ingress :8090 for Envoy |
| **Gateway** | **Signs nothing and serves nothing on :8090.** `VllmRuntime` (execute, stream, ping) and `VllmModelManager` send plain HTTP to Envoy |

### 13.2 Blocker found while planning: model-readiness poll through Envoy

Before every local dispatch, `ExecuteService.loadAndDispatchLocal` calls
`VllmModelManager.getStatus()`, i.e. `GET {vllm-endpoint}/v1/models`, and expects the model ID
to be listed. With `vllm-endpoint: http://envoy:8080` that can never succeed:
- Envoy has no `/v1/models` route; it routes only `/v1/chat/completions`, `/v1/embeddings`, `/v1/rerank` and `/v1/score`.
- One endpoint fronts three backends, so the question is ambiguous.
- The call carries no JWT.

Every local execution would sit in `MODEL_LOADING` for `model-load-timeout-ms` (600 s) and then
fail with `MODEL_LOAD_FAILED`. The `GetStatus` liveness ping (`GET {envoy}/health`) fails the
same way. **T-K8S-6a alone would not make GPU-5 work end to end, so this fix is part of the plan.**

### 13.3 Decisions (user-confirmed 2026-09-25)

| # | Decision | Chosen | Rejected |
|---|---|---|---|
| J1 | JWKS listener | Dedicated **JDK `HttpServer` on :8090** (Gateway lifecycle), one path only; actuator stays on :8091 | Second Tomcat connector (more framework coupling; the controller would also be reachable on :8091 unless filtered) |
| J2 | Body-hash enforcement | **Claim now, enforce later.** 6a emits `body_sha256`; Envoy verifies signature, `iss`, `aud` and `exp`; enforcement is a tracked 6b follow-up. Stock `jwt_authn` can't compare a claim with the body, and Envoy's Lua API has no SHA-256 | Pure-Lua SHA-256 filter now (unmeasured latency on bodies up to 4 MB); `ext_authz` sidecar (most moving parts) |
| J3 | Model readiness (§13.2) | **Static readiness** for Envoy-fronted LOCAL models. Each GPU-5 model is an always-on pod and Kubernetes readiness is authoritative, so skip the `/v1/models` poll. A down backend surfaces as Envoy 503, which the Gateway treats as NOT_ACCEPTED (retryable). The ping goes to a signed Envoy `/healthz` route, or is disabled | Per-backend Envoy `/models/<backend>` routes + per-model readiness paths in the catalog |

### 13.4 Design

1. **Key material** (`gpu-gateway.execution-jwt.key-dir`, default `/etc/gpu-gateway/keys`, from Secret `gpu-gateway-jwt-keys`):
   - Files: `current.key` (PKCS#8, P-256), `current.pub` and `previous.pub` (SPKI PEM). The previous key's private half is not needed, because only the current key signs.
   - Startup checks, all fail closed (§5.5):
     - every key is P-256;
     - `current.key`/`current.pub` are a pair (sign-then-verify);
     - there are exactly two public keys, and they differ;
     - files are read from disk only, never from the environment.
   - Logs name only each key's `kid` (the RFC 7638 JWK thumbprint).
   - The Phase 5 command in §7 changes: `openssl ecparam -genkey` writes SEC1, which the JDK can't read. Instead, two pairs are generated with `openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256` plus `openssl pkey -pubout`. The previous private key is discarded.
   - Rotation (T-K8S-25, out of scope): previous := current, current := new, then restart the Gateway. Envoy picks up the new JWKS within its 5-minute cache.
2. **Signer** — JDK only (`SHA256withECDSAinP1363Format` yields the raw JWS `r‖s`), no new dependency.
   - Header: `{"alg":"ES256","typ":"JWT","kid":…}`.
   - Claims:
     - `iss`, `aud`: the spec values;
     - `iat`, `nbf`, and `exp` = iat + `ttl-seconds` (default 60, max 300; Envoy only checks at arrival);
     - `jti` = execution ID;
     - `sub` = caller mTLS principal, `tenant_id`, `op`, `model` (logical ID);
     - `body_sha256` = base64url(SHA-256(exact body bytes)).
3. **Attaching the token.** `Authorization: Bearer <jwt>` goes on every Gateway→Envoy request: `VllmRuntime.execute`, `executeStreaming` and `ping`.
   - `VllmRuntime` builds the body bytes once, hashes them, and publishes that same array. This is required because streaming re-serializes the payload after adding `stream`/`include_usage`.
   - A GET signs the hash of an empty body.
   - It is only active when `execution-jwt.enabled`.
4. **JWKS listener (J1).** `GET /internal/.well-known/jwks.json` on `jwks-port` (8090).
   - It returns both public keys as JWK (`kty EC`, `crv P-256`, `x`, `y`, `kid`, `use sig`, `alg ES256`) with `Cache-Control: max-age=300`.
   - Every other path/method gets 404/405.
   - No authentication: the NetworkPolicy admits Envoy only.
5. **Readiness.** A new `executionJwt` health indicator joins the readiness group: keys loaded and the listener bound. So the Gateway is ready before Envoy, whose readiness waits for its JWKS fetch (spec §12).
6. **Configuration.** `gpu-gateway.execution-jwt.{enabled, key-dir, issuer, audience, ttl-seconds, jwks-port}`, defaulting to the spec §12 values.
   - `enabled: true` is set only in `gateway-local.yaml` (blueprint + Helm).
   - `GatewayStartupValidator`: enabled without valid keys fails startup.
7. **Static readiness (J3).**
   - `dispatch.model-readiness: static` for the Envoy-fronted local profile: no `/v1/models` polling.
   - The existing polling behaviour stays the default for other local setups.
   - The `GetStatus` ping uses a signed `GET /healthz`, served by an Envoy direct response.
8. **Envoy (6b changes needed here):**
   - add a `/healthz` route (direct response 200, still behind `jwt_authn`);
   - set `forward: false`, so the token isn't passed on to TEI/vLLM;
   - keep `remote_jwks` fail-closed;
   - `body_sha256` enforcement stays a 6b follow-up (J2).
9. **Manifests and docs:**
   - `gateway.yaml` + Helm: the Secret becomes **required**; `containerPort` 8090 and Service port 8090; config keys;
   - the NetworkPolicy comment ("reserved") becomes active;
   - §7 key commands; `docs/troubleshooting.md` (401 / JWKS diagnosis);
   - this §12 items 2 and 3; spec §12 addendum (claim names, key files, listener).

### 13.5 Tests

- **Unit (gpu-gateway):**
  - the signer, verified through the published JWKS key: claims, `exp`, and that `body_sha256` equals the SHA-256 of the bytes actually sent;
  - the `kid` against the RFC 7638 §3.1 test vector;
  - the key loader rejects: missing file, SEC1 key, wrong curve, mismatched pair, identical current/previous, a third key;
  - the JWKS listener: both keys, cache header, 404 elsewhere;
  - `VllmRuntime` attaches the token on unary, stream and ping (local `HttpServer`, as in `OpenAiProviderRuntimeHeadersTest`);
  - static readiness skips the poll;
  - the readiness indicator.
- **Local end-to-end without GPUs:** `deployments/homelab/scripts/envoy-jwt-local-test.sh`. It uses docker compose with the real Gateway (strategy `direct`, `vllm-endpoint` = Envoy), the **pinned Envoy image and the real envoy config** (hostnames adapted), and mock TEI/vLLM backends. Spec cases covered:
  - §24.1.10: Gateway-signed EMBED/SYNTHESIZE/RERANK and a stream reach the mocks;
  - §24.2.6: unsigned, wrong key, expired or wrong `aud` → 401;
  - §24.2.7: JWKS unavailable → fail closed;
  - rotation: a token signed with the previous key verifies while its key is still in the JWKS.
- **Cluster:** Phase 5 exit criteria (§7), then phase 9 and the §24 suite.

### 13.6 Steps (one commit each)

| # | Item | Needs |
|---|---|---|
| 1 | ✅ Spec §12 addendum (claim names, key files, :8090 listener, TTL) + this plan | — |
| 2 | ✅ Key loader + ES256 signer + unit tests | — |
| 3 | ✅ JWKS listener + readiness indicator + startup validation + tests | — |
| 4 | ✅ Token on every Gateway→Envoy call (`VllmRuntime` body-bytes refactor) + tests | — |
| 5 | ✅ Static readiness + signed `/healthz` ping (J3) + tests | — |
| 6 | ✅ Manifests, Helm, NetworkPolicy, Envoy `forward: false` + `/healthz` (+ JWKS `async_fetch`), §7 commands, troubleshooting | — |
| 7 | ✅ Local Envoy end-to-end script + run: **17/17** (`scripts/envoy-jwt-local-test.sh`) | Docker pull of the pinned Envoy image |
| 8 | ✅ Cluster Phase 5 run (2026-09-25: Gateway image digest-pinned `sha256:8a747eb…`, Secret via `scripts/generate-key.sh --apply`; EMBED/SYNTHESIZE/stream/RERANK through Envoy on the GPUs; unsigned→401; direct backend access denied) → §11, status docs | **Operator:** create `gpu-gateway-jwt-keys` (§7), push the Gateway image, run `deploy.sh gateway envoy` |

Steps 1–7 need no GPU and no external provider. After step 8, the platform retrieval
benchmark's bge-base T02/T03 rows and the reranker rows (T10/T11, `synanton-qwen3-reranker-0.6b`)
become runnable on GPU-5.

**Follow-ups (not in 6a):**
- `body_sha256` enforcement in Envoy (6b; J2 options, measured).
- JWT/JWKS rotation automation (T-K8S-25).
