# GPU-5 Homelab Implementation Plan

**Status:** Execution-ready baseline — revised per PR #15 review (one inference workload per physical GPU; the colocated two-container/one-GPU design was retracted as invalid)
**Revision date:** 2026-09-24
**Canonical spec:** `../../doc/GPU-5  GPU-6  GPU-7 Deployment Plan.md`
**Model setup doc:** `../../doc/GPU-5 Local Models Setup.md` (placement superseded — see D2/D4 below)
**Cluster reference:** `./claster-as-build.md`

---

## 1. Decisions and deviations from the spec

| # | Decision | Spec reference | Rationale |
|---|----------|----------------|-----------|
| D1 | vLLM pinned at **v0.29.0** (`vllm/vllm-openai:v0.29.0`, default `cu130` variant) — used on node2/node3 only. | §9 baseline says `vLLM 0.6.x` | Qwen3-Reranker support exists only in vLLM ≥ 0.9.2 (PR vllm-project/vllm#19260, merged 2025-06-11). §9 explicitly permits runtime upgrades as validated compatibility changes. node2 is Ada (sm_8.9), node3 is Blackwell (sm_12.0); the v0.29.0 cu130 image covers both. Validation per §9 is part of the acceptance phase. |
| D2 | **One inference workload per physical GPU** (PR #15 review §2): node1 → embedding, node2 → reranker, node3 → synthesis. The newest/largest GPU (RTX 5060 Ti) serves the workload with the greatest memory pressure (synthesis: KV cache, concurrency, long context). | §41 "one GPU per inference workload" | The 2026-09-23 revision colocated embedding+reranker in one pod on node3 with a single `nvidia.com/gpu: 1` request. That design is **invalid**: device-plugin extended resources are allocated per *container*, not per Pod; there is no Pod-level GPU visibility inheritance; and one GPU request creates no VRAM partition. PR #15 review blocked on it (P0.1). The colocated manifest is removed, not kept as an option; any future GPU sharing goes through documented mechanisms (MIG/time-slicing) as a separate experiment, never the default PoC. |
| D3 | Model copies as verified **2026-09-24**: node2 holds `qwen3-reranker-0.6b` (normalized into `models/` today) plus `qwen3-4b-instruct-2507` and flat `qwen3-embedding-0.6b`; node3 holds `qwen3-4b-instruct-2507` (normalized today) plus embedding/reranker copies. The node2↔node3 role swap therefore needed **no inter-node copy** — only the flat→`models/` moves in §4.4. Operator practice: **all models are mirrored on all nodes** — `bge-base-en-v1.5` is complete everywhere (§4.5); `bge-small` (fallback) is partial pending one re-run. Extra copies are harmless (hostPath reads only the local copy, D6). | Local Models Setup §5 | §4.4–4.6 |
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
  | gRPC synanton.gpu.v1 (Deployment Plan v3.0.0 §4; mTLS = T-K8S-7/8, pending)
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
#     bge-base COMPLETE incl. tokenizer files; bge-small fallback partial — 4.5)
ssh node1 'ls /mnt/local-fast/models/bge-base-en-v1.5'                                  # BGE-base: complete (4.5)
ssh node2 'test -f /mnt/local-fast/models/qwen3-reranker-0.6b/config.json && du -sh /mnt/local-fast/models/qwen3-reranker-0.6b'   # 1.2G
ssh node3 'test -f /mnt/local-fast/models/qwen3-4b-instruct-2507/config.json && du -sh /mnt/local-fast/models/qwen3-4b-instruct-2507'   # 7.6G

# 4.4 Path normalization to the canonical /mnt/local-fast/models/<model> layout
# DONE 2026-09-23 — first pass (node2 qwen3-4b; node3 embedding+reranker).
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
# State 2026-09-24: bge-base COMPLETE on all nodes (operator mirrors models
# everywhere). bge-small (fallback, §2) still PARTIAL — tokenizer.json/vocab.txt
# missing; re-run its download to complete:
ssh node1 'source ~/k8s/.venv/bin/activate && hf download BAAI/bge-small-en-v1.5 \
  --local-dir /mnt/local-fast/models/bge-small-en-v1.5'
# verify — all must exist before phase 3:
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

Envelope: ~8.2 GB weights (bf16) + KV cache inside 0.90 × 16 GB. If KV pressure appears, drop `--max-model-len` to 16384.

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
for a 0.6 B pooling model (~1.2 GB weights); raise only if profiling says so.

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
- `--dtype float16`: Turing has no bf16; fp16 bge-base is ~0.5 GB in 4 GB VRAM.
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
| 5 | T-K8S-6a, 6b, 6 | ES256 keypair → Secret; `./scripts/deploy.sh envoy` | Unsigned request → 401; Gateway-signed request reaches the backends; direct backend Service access still works only from Envoy (enforced in phase 7) |
| 6 | T-K8S-8 | Caller principal validation (mTLS) — **pending ticket**; API keys were removed from the contract (Plan v3.0.0 §13) | Until then the gRPC port is reachable only via NetworkPolicy (phase 7) |
| 7 | T-K8S-11, 12 | `./scripts/deploy.sh policies` | Direct client→backend denied; logs contain no prompts/completions |
| 8 | T-K8S-7, 9, 9a | gRPC transport security (mTLS — pending ticket); Prometheus scrape of Gateway :8091 | `gpu_gateway_*` metrics visible |
| 9 | T-K8S-13, 14, 15a | Idempotency retention 24 h; run acceptance suite (§9); proto contract mirror (`scripts/verify-gpu-contract-mirror.sh`) | §24 suite green against the packaged deployment |

Secrets are created imperatively and never committed (spec §12/§13):

```bash
# Phase 5 — execution JWT signing keys (ES256, current + previous slot)
openssl ecparam -name prime256v1 -genkey -noout -out es256-current.pem
kubectl -n gpu-plane create secret generic gpu-gateway-jwt-keys \
  --from-file=current=es256-current.pem

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

**Empirical VRAM rule (review P1.5):** all VRAM figures in §11 are observed
values, never arithmetic derived from `--gpu-memory-utilization`.

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

| Pod | Node | VRAM used / total | Notes |
|-----|------|-------------------|-------|
| tei-embedding | node1 | _ / 4096 MiB | dedicated GPU (GTX 1650, sm_7.5, fp16) |
| vllm-reranker | node2 | _ / 16380 MiB | dedicated GPU (RTX 4060 Ti) |
| vllm-synthesis | node3 | _ / 16311 MiB | dedicated GPU (RTX 5060 Ti) |

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
2. **Gateway → Envoy is blocked until T-K8S-6a** (execution-JWT signing + JWKS on
   :8090) lands: the gateway does not sign JWTs or serve JWKS yet, so Envoy's
   `jwt_authn` fails closed and every local execution is rejected. Phases 0–4 and
   §8.1–8.3 (direct backend smoke) are executable today; end-to-end GPU-5 execution
   through the Gateway is not. The gateway config is complete (strategy `direct`,
   `vllm-endpoint: http://envoy:8080`, 3 LOCAL catalog entries — verified to boot
   and advertise exactly the 3 IDs).
3. **Envoy body-hash enforcement** (spec §12): `jwt_authn` validates signature/iss/aud/exp; SHA-256 body-hash claim verification may need a Lua/ext filter — tracked under T-K8S-6b acceptance.
4. **TEI turing build on GTX 1650** (D4): sm_7.5 consumer card without tensor cores, TEI's turing variant is marked experimental upstream. Phase 3 validates it; fallback ladder is bge-small → TEI CPU on node1. If neither works, embedding moves to node2 (vLLM, Qwen3-Embedding-0.6B — already on disk) and the reranker returns to node1 only if a CPU reranker path is validated — i.e. reopen D2/D4 rather than silently re-colocating.
5. **Mirrored model copies** (D3/§4.6): models are deliberately mirrored on all nodes (operator practice); the only pure leftover is node2's flat `qwen3-embedding-0.6b`. Harmless either way — hostPath reads only the local copy.
6. **GPU-7 external profile**: the PR #15 review's remaining blockers (Compose↔Spring datasource env mismatch, mock-provider dispatch, external routing strategy, model-ID rewriting, streaming, expanded acceptance tests) are gateway-code items in `deployments/external/` and the platform `gpu-gateway` module — tracked in PR #15, out of scope for this GPU-5 local-only plan. GPU-5 is unaffected: it runs `local-only` mode with no external dispatch.
