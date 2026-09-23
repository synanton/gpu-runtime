# GPU-5 Homelab Implementation Plan

**Status:** Execution-ready baseline
**Revision date:** 2026-09-23
**Canonical spec:** `../../doc/GPU-5  GPU-6  GPU-7 Deployment Plan.md`
**Model setup doc:** `../../doc/GPU-5 Local Models Setup.md` (placement superseded — see D2 below)
**Cluster reference:** `./claster-as-build.md`

---

## 1. Decisions and deviations from the spec

| # | Decision | Spec reference | Rationale |
|---|----------|----------------|-----------|
| D1 | vLLM pinned at **v0.29.0** (`vllm/vllm-openai:v0.29.0`, default `cu130` variant) | §9 baseline says `vLLM 0.6.x` | Qwen3-Embedding / Qwen3-Reranker support only exists in vLLM ≥ 0.9.2 (PR vllm-project/vllm#19260, merged 2025-06-11). §9 explicitly permits runtime upgrades as validated compatibility changes. Nodes 1–3 run CUDA 13 host drivers; the v0.29.0 default image is the CUDA 13.0 build, matching the hosts. Validation per §9 is part of the acceptance phase. |
| D2 | **node1 runs no GPU inference.** Embedding + reranker are colocated in one pod on **node3**; synthesis alone on **node2**. | `GPU-5 Local Models Setup.md` placed reranker on node1 | node1's GTX 1650 (Turing, 4 GB) has no bf16 support and insufficient VRAM headroom for vLLM; it is reserved for CPU-only control-plane workloads (Gateway, Envoy, PostgreSQL), mirroring the speech-to-speech project layout. |
| D3 | Models were downloaded redundantly: node2 and node3 **each already hold all three** models; node1 holds embedding+reranker. **No inter-node copy is needed** — only path normalization (flat `qwen3-*` dirs → `models/` subdir, §4.4). Verified intact 2026-09-23 (configs/tokenizers/safetensors; all `Qwen3ForCausalLM`, bf16). | Local Models Setup §5 | §4.4
| D4 | One pod, two vLLM containers, **one** `nvidia.com/gpu: 1` request on node3. | §41 "one GPU per inference workload" | Documented homelab deviation: two 0.6B pooling models (~1.2 GB weights each) share one 16 GB GPU with capped `--gpu-memory-utilization`. Same pattern proven in production on this cluster by `speech-stt-tts`. Full §41 isolation remains the GPU-6 target. |
| D5 | Namespace **`gpu-plane`**; registry secret name **`local-registry-cred`**; images pulled from **`local-registry:5000`** on node0. | §22 T-K8S-0b | Reuses the existing registry and conventions from the speech-to-speech project. |
| D6 | Model weights via `hostPath` from `/mnt/local-fast/models/...`, read-only, container path `/models`. | §41, Local Models Setup §9 | Longhorn is installed but not used for model weights (matches spec: "Longhorn is not required for model weights"). |

## 2. Target layout

| Node | Hardware | GPU-5 workloads | Model (hostPath) |
|------|----------|-----------------|------------------|
| node0 | control-plane, no GPU | container registry (`local-registry:5000`) — already running | — |
| node1 | 16C/64Gi, GTX 1650 (unused) | Gateway, Envoy, PostgreSQL | — |
| node2 | 20C/64Gi, RTX 4060 Ti 16GB | vLLM synthesis | `/mnt/local-fast/models/qwen3-4b-instruct-2507` |
| node3 | 20C/64Gi, RTX 5060 Ti 16GB | vLLM embedding + reranker (colocated pod) | `/mnt/local-fast/models/qwen3-embedding-0.6b`, `/mnt/local-fast/models/qwen3-reranker-0.6b` |

```text
Client
  | HTTPS (TLS via Traefik ingressclass, phase 8)
  v
Gateway (node1, :8080, metrics :8091)
  | ES256 execution JWT (spec §12)
  v
Envoy (node1, :8080)  jwt_authn ← JWKS from Gateway /internal/.well-known/jwks.json
  | 300 s upstream timeout (spec §31)
  +-- /v1/chat/completions --> vllm-synthesis:8000  (node2 pod)
  +-- /v1/embeddings       --> vllm-embedding:8000  (node3 pod, containerPort 8000)
  +-- /v1/rerank           --> vllm-reranker:8000   (node3 pod, targetPort 8001)
Gateway --> PostgreSQL (node1, :5432, Longhorn PVC)
```

Public Synanton model IDs (stable API contract, spec §12 of Local Models doc):

| Synanton model ID | Backend |
|---|---|
| `synanton-qwen3-4b-synthesis` | node2 vLLM → `/models` (Qwen3-4B-Instruct-2507) |
| `synanton-qwen3-embedding-0.6b` | node3 vLLM → `/models` (Qwen3-Embedding-0.6B) |
| `synanton-qwen3-reranker-0.6b` | node3 vLLM → `/models` (Qwen3-Reranker-0.6B) |

## 3. Repository layout created by this plan

```text
deployments/homelab/
├── claster-as-build.md              # existing as-built record
├── gpu-5-implementation-plan.md     # this document
├── blueprints/                      # plain manifests for phased kubectl bring-up
│   ├── namespace.yaml
│   ├── vllm/synthesis.yaml
│   ├── vllm/embedding-reranker.yaml
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
    ├── mirror-images.sh             # copy public images into local-registry:5000
    ├── create-registry-secret.sh    # local-registry-cred in gpu-plane
    ├── deploy.sh                    # phased blueprint apply
    ├── cluster-stop.sh              # quiesce: scale gpu-plane to 0, cordon+drain, optional poweroff
    └── cluster-start.sh             # restore: wait Ready, uncordon, GPU-aware rollout restore
```

Bring-up is done with **blueprints + scripts** (phased, debuggable). The **Helm chart** packages the same resources for repeatable reinstall once bring-up is validated; both paths must produce equivalent resources.

## 4. Prerequisites (Phase 0, T-K8S-0 / T-K8S-0b)

```bash
# 4.1 Uncordon workers (cluster is currently cordoned from the speech project)
kubectl uncordon node1 node2 node3
kubectl get nodes   # all Ready, no SchedulingDisabled

# 4.2 GPU operator sanity
kubectl get pods -n kube-system -l app=nvidia-device-plugin-daemonset
kubectl get runtimeclass nvidia

# 4.3 Verify pre-downloaded models (verified 2026-09-23: all present and intact)
ssh node2 'test -f /mnt/local-fast/qwen3-4b-instruct-2507/config.json && du -sh /mnt/local-fast/qwen3-4b-instruct-2507'   # 7.6G
ssh node3 'test -f /mnt/local-fast/qwen3-embedding-0.6b/config.json && test -f /mnt/local-fast/qwen3-reranker-0.6b/config.json && du -sh /mnt/local-fast/qwen3-*'   # 1.2G each

# 4.4 Normalize paths to the canonical /mnt/local-fast/models/<model> layout
# DONE 2026-09-23 — executed and verified (configs readable on both nodes).
# (downloads landed flat at /mnt/local-fast/qwen3-*; Local Models Setup §14 mandates
# the models/ subdir). mv on the same filesystem = instant, no data copy.
ssh node2 'mkdir -p /mnt/local-fast/models && mv /mnt/local-fast/qwen3-4b-instruct-2507 /mnt/local-fast/models/'
ssh node3 'mkdir -p /mnt/local-fast/models && mv /mnt/local-fast/qwen3-embedding-0.6b /mnt/local-fast/qwen3-reranker-0.6b /mnt/local-fast/models/'
ssh node2 'chmod -R a+rX /mnt/local-fast/models' ; ssh node3 'chmod -R a+rX /mnt/local-fast/models'

# 4.5 Optional dedup (redundant downloads, ~11 GB reclaimable):
#   node1: qwen3-embedding-0.6b, qwen3-reranker-0.6b   (node1 runs no GPU inference, D2)
#   node2: qwen3-embedding-0.6b, qwen3-reranker-0.6b   (node2 runs synthesis only)
#   node3: qwen3-4b-instruct-2507                      (node3 runs embedding+reranker only)

# 4.5 Namespace + registry secret (reuses speech-project pattern)
./scripts/create-registry-secret.sh        # defaults: NAMESPACE=gpu-plane, SECRET_NAME=local-registry-cred

# 4.6 Registry login on the machine that will mirror/build images
docker login local-registry:5000
```

> **Contention warning:** the `speech` namespace still exists. Do not run speech GPU
> workloads (`speech-llm` on node2, `speech-stt-tts` on node3) concurrently with
> GPU-5 — each node has exactly one physical GPU (`nvidia.com/gpu: 1`).

## 5. Image mirroring to the local registry (T-K8S-2)

Run from a machine with internet access and `docker login local-registry:5000`:

```bash
./scripts/mirror-images.sh                  # mirrors the pinned set below
```

| Image | Source tag | Local tag |
|---|---|---|
| vLLM (D1) | `vllm/vllm-openai:v0.29.0` | `local-registry:5000/vllm-openai:v0.29.0` |
| Envoy | `envoyproxy/envoy:v1.31.0` | `local-registry:5000/envoy:v1.31.0` |
| PostgreSQL | `postgres:16.4` | `local-registry:5000/postgres:16.4` |
| Gateway base | `eclipse-temurin:21-jre` | `local-registry:5000/eclipse-temurin:21-jre` |
| Smoke-test client | `curlimages/curl:8.10.1` | `local-registry:5000/curlimages-curl:8.10.1` |

Gateway image (T-K8S-1, blueprint `docker/gateway.Dockerfile`):

```bash
./gradlew bootJar
docker build -f deployments/homelab/docker/gateway.Dockerfile \
  -t local-registry:5000/gpu-gateway:0.1.0 .
docker push local-registry:5000/gpu-gateway:0.1.0
```

**Digest pinning (spec §8):** before freeze, record digests into `helm/gpu-plane/values.yaml`:

```bash
for img in vllm-openai:v0.29.0 envoy:v1.31.0 postgres:16.4 gpu-gateway:0.1.0; do
  docker buildx imagetools inspect "local-registry:5000/${img}" --format '{{.Manifest.Digest}}'
done
```

Floating tags are prohibited in execution manifests; blueprints use tags for bring-up and must be switched to `repo@sha256:...` in the Helm values at freeze.

## 6. vLLM serving parameters

### node2 — synthesis (`vllm-synthesis`)

```text
vllm serve /models
  --served-model-name synanton-qwen3-4b-synthesis
  --port 8000 --host 0.0.0.0
  --gpu-memory-utilization 0.90
  --max-model-len 32768
  --max-num-seqs 8
```

VRAM budget: ~8.2 GB weights (bf16) + KV cache inside a 0.90 × 16 GB envelope. If KV pressure appears, drop `--max-model-len` to 16384.

### node3 — colocated pod (`vllm-embed-rerank`), two containers

Container `embedding` (declares `nvidia.com/gpu: 1`, port 8000):

```text
vllm serve /models
  --runner pooling
  --served-model-name synanton-qwen3-embedding-0.6b
  --port 8000 --host 0.0.0.0
  --gpu-memory-utilization 0.20
  --max-model-len 8192
```

Container `reranker` (no gpu request — inherits pod-level GPU visibility; port **8001** to
avoid a pod-network-namespace port conflict):

```text
vllm serve /models
  --runner pooling
  --served-model-name synanton-qwen3-reranker-0.6b
  --port 8001 --host 0.0.0.0
  --gpu-memory-utilization 0.20
  --max-model-len 8192
  --hf-overrides '{"architectures": ["Qwen3ForSequenceClassification"], "classifier_from_token": ["no", "yes"], "is_original_qwen3_reranker": true}'
```

The `--hf-overrides` block is required for the **official** `Qwen/Qwen3-Reranker-0.6B`
checkpoint (it is a `Qwen3ForCausalLM` checkpoint; the override routes it to the
sequence-classification path with yes/no logit scoring). Rerank is served at `/rerank`
(and `/v1/rerank`).

VRAM budget node3: 0.20 + 0.20 = ~6.6 GB of 16 GB used; ~9 GB headroom.
Gotchas: both `--gpu-memory-utilization` values are fractions of **total** GPU memory and
**must sum well below 1.0**; if either engine OOMs during CUDA-graph capture, add
`--enforce-eager` to that container.

## 7. Phased bring-up (maps to spec §22 ticket sequence)

Each phase's exit criteria must pass before moving on.

| Phase | Tickets | Action | Exit criteria |
|-------|---------|--------|---------------|
| 0 | T-K8S-0, T-K8S-0b | §4 above | Nodes Ready/uncordoned; models verified on node2+node3; `local-registry-cred` exists in `gpu-plane` |
| 1 | T-K8S-1, T-K8S-2 | §5 above: mirror images; build+push gateway | All 5 images in `local-registry:5000` |
| 2 | T-K8S-3 | `./scripts/deploy.sh postgres` | Pod Running on node1; `psql` connects via ClusterIP; V1/V2 Flyway migrations applied by first Gateway boot |
| 3 | T-K8S-5, T-K8S-10 | `./scripts/deploy.sh vllm` | All 3 vLLM containers Running; smoke tests pass (§8.1–8.3) |
| 4 | T-K8S-4, T-K8S-1a | `./scripts/deploy.sh gateway` — mode `local-only`, model registry → 3 vLLM services | Gateway ready; startup validation fails closed on bad config; `/v1/models` lists the 3 Synanton IDs |
| 5 | T-K8S-6a, 6b, 6 | ES256 keypair → Secret; `./scripts/deploy.sh envoy` | Unsigned request → 401; Gateway-signed request reaches vLLM; direct vLLM Service access still works only from Envoy (enforced in phase 7) |
| 6 | T-K8S-8, 8a, 8b | API-key pepper Secret; issue first `sk-syn-...` key | `Bearer` + `Api-Key` auth accepted; wrong key → `invalid_api_key` envelope per §16 |
| 7 | T-K8S-11, 12 | `./scripts/deploy.sh policies` | Direct client→vLLM denied; logs contain no prompts/completions |
| 8 | T-K8S-7, 9, 9a | TLS exposure (reuse cluster Traefik ingressclass, self-signed cert per speech pattern); Prometheus scrape of Gateway :8091 | `https://` API reachable; `gpu_gateway_*` metrics visible |
| 9 | T-K8S-13, 14, 15a, 15b | Idempotency retention 24 h; run acceptance suite (§9); OpenAPI canonical/package equality | §24 suite green against the packaged deployment |

Secrets are created imperatively and never committed (spec §12/§13):

```bash
# Phase 5 — execution JWT signing keys (ES256, current + previous slot)
openssl ecparam -name prime256v1 -genkey -noout -out es256-current.pem
kubectl -n gpu-plane create secret generic gpu-gateway-jwt-keys \
  --from-file=current=es256-current.pem

# Phase 6 — API-key pepper
kubectl -n gpu-plane create secret generic gpu-gateway-pepper --from-literal=pepper="$(openssl rand -hex 32)"

# Phase 2 — PostgreSQL credentials
kubectl -n gpu-plane create secret generic gpu-postgres-cred \
  --from-literal=POSTGRES_USER=gpu --from-literal=POSTGRES_PASSWORD="$(openssl rand -base64 24)" \
  --from-literal=POSTGRES_DB=gpu_plane
```

## 8. Smoke tests

Two levels, complementary:

**A. Manual end-to-end via the Gateway** — `tools/gpu-plane-check.py` at the repo
root (stdlib-only Python CLI, works for GPU-5 and GPU-7; validates §10 streaming,
§16 error envelopes, §21 request IDs):

```bash
kubectl -n gpu-plane port-forward svc/gpu-gateway 8080:8080   # or Traefik URL in phase 8
tools/gpu-plane-check.py --profile gpu5 --api-key "$GPU_DEV_API_KEY" all
# phase 8 TLS: --base-url https://<node-ip>:30443 --insecure
# §13.1: also run  ... negative --auth-scheme Api-Key
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

# 8.2 embedding (phase 3)
$CURL -- curl -s vllm-embedding:8000/v1/embeddings -H 'Content-Type: application/json' \
  -d '{"model":"synanton-qwen3-embedding-0.6b","input":"hello"}'

# 8.3 rerank (phase 3)
$CURL -- curl -s vllm-reranker:8000/v1/rerank -H 'Content-Type: application/json' \
  -d '{"model":"synanton-qwen3-reranker-0.6b","query":"ping","documents":["pong"," unrelated"]}'

# 8.4 negative: unsigned request to Envoy must 401 (phase 5)
$CURL -- curl -s -o /dev/null -w '%{http_code}' -X POST envoy:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -d '{}'
```

Record VRAM per pod with `ssh node2/3 nvidia-smi` and append to this file's §11 table.

## 9. Acceptance (spec §24, homelab-executable subset)

Executable after phase 9 against the packaged deployment:

- §24.1 cases 1–15 (auth schemes, model discovery, chat/embed/rerank, SSE `[DONE]`, usage, request IDs, project/org headers, idempotent replay, Envoy JWT acceptance).
- §24.2 cases 1–14 (full negative matrix incl. direct-vLLM denial and fail-closed JWKS).
- §24.3 rate-limit cases where policy configured; unlimited dimensions must emit `0`.
- §24.5 cases 1–10 incl. packaged OpenAPI == canonical artifact (CI also enforces via `scripts/verify-gpu-contract-mirror.sh`).
- §10 streaming: valid `chat.completion.chunk` framing, `[DONE]` exactly once, `usage: null` when authoritative usage unavailable.
- D1 validation per §9: upgrade checklist (JWT, streaming, model load, GPU memory, graceful shutdown) run once against vLLM v0.29.0 and recorded here.

## 10. Helm packaging path

After blueprints pass acceptance:

```bash
helm upgrade --install gpu-plane deployments/homelab/helm/gpu-plane \
  -n gpu-plane --create-namespace
```

The chart reproduces the blueprints parameterized by `values.yaml` (registry, tags+digests,
node placement, model hostPaths, GPU memory fractions). Blueprint-vs-chart equivalence is
checked by diffing `helm template` output against `blueprints/` in CI (follow-up to T-K8S-32,
which is formally a GPU-6 ticket — homelab treats the chart as convenience, not the source of truth).

## 11. Observed baselines (fill in during bring-up)

| Pod | Node | VRAM used / total | Notes |
|-----|------|-------------------|-------|
| vllm-synthesis | node2 | _ / 16380 MiB | |
| vllm-embed-rerank (embedding) | node3 | _ / 16311 MiB | shared GPU |
| vllm-embed-rerank (reranker) | node3 | (same GPU) | |

## 11a. Cluster lifecycle (power off / on)

Use `scripts/cluster-stop.sh` / `scripts/cluster-start.sh` (adapted from the
speech-to-speech project, retargeted to the `gpu-plane` namespace) for planned
downtime. Stop scales every gpu-plane Deployment to 0 (replica counts saved as
`gpu.cluster/prev-replicas` annotations) and waits for termination *before*
cordon+drain — this ordering is what prevents the zombie-pod storm seen on this
cluster when GPU pods outlive the node shutdown. Start waits for all nodes
Ready, uncordons, restores non-GPU Deployments (postgres/gateway/envoy)
immediately, holds GPU Deployments (vllm-*) at 0 until the NVIDIA device plugin
reports healthy per worker, then restores and waits out rollouts. Cold-start
rollout timeout is generous (600 s) because of vLLM kernel autotune.
`cluster-stop.sh --poweroff` also powers the nodes off over SSH (workers first,
control-plane last; sudo password prompted per node); `--skip-snapshot` skips
the best-effort etcd backup on node0.

## 12. Known risks / follow-ups

1. **vLLM 0.29.x on consumer GPUs** (D1): first-boot kernel autotune can take minutes; readiness probes use `initialDelaySeconds: 120`, `failureThreshold: 10`.
2. **Envoy body-hash enforcement** (spec §12): `jwt_authn` validates signature/iss/aud/exp; SHA-256 body-hash claim verification may need a Lua/ext filter — tracked under T-K8S-6b acceptance.
3. **Colocation** (D4): if embedding+reranker ever need independent scaling, split into two Deployments on node3+node2 or revisit time-slicing — same decision tree as the speech project documented.
4. **Redundant model copies** (D3): ~11 GB reclaimable across node1/node2/node3 (§4.5); harmless to keep.
