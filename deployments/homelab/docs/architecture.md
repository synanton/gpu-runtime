# GPU-5 Homelab Architecture

The normative authority is `doc/GPU-5  GPU-6  GPU-7 Deployment Plan.md` (spec);
this document describes only the **homelab realization** of it — what runs
where, and why. Numbered decisions (D1–D7) live in
`../gpu-5-implementation-plan.md` §1 and are not repeated here.

## Components and request path

```
Synanton Platform
   │  gRPC synanton.gpu.v1 (Deployment Plan v3.0.0 §4; mTLS = T-K8S-7/8, pending)
   ▼
gpu-gateway  (node1, CPU only — :9090 gRPC API, :8091 actuator; :8090 JWKS for Envoy, T-K8S-6a)
   │  ES256 execution JWT, signed per request (spec §12)
   ▼
envoy        (node1, CPU only — :8080, jwt_authn verifies the Gateway's ES256 execution JWT
              against its JWKS; fails closed; :9902 probe listener)
   │  300 s upstream timeout (spec §31)
   ├─ /v1/chat/completions ─► vllm-synthesis  :8000 (node3, RTX 5060 Ti)
   ├─ /v1/embeddings       ─► tei-embedding   :8000 (node1, GTX 1650 — TEI, fp16)
   └─ /v1/rerank           ─► vllm-reranker   :8000 (node2, RTX 4060 Ti)

gpu-gateway ─► postgres (node1, Longhorn PVC) — control state only, never on
the inference request path (spec §2.2)
```

**One inference workload per physical GPU** (plan D2, PR #15 review P0.1). The
earlier two-container/one-GPU colocation was invalid — device-plugin extended
resources are per-container, with no Pod-level GPU inheritance — and has been
removed. Clients see only logical model IDs, never node addresses.

## GPU scheduling constraint

Identical mechanics to the speech project (`k8s-test-hardware.md` / speech
`docs/architecture.md`): the scheduler sees GPU **count** (`nvidia.com/gpu: 1`
per node), not VRAM. Consequences:

- Synthesis gets node3's GPU alone (RTX 5060 Ti — the newest GPU serves the
  highest memory-pressure workload: KV cache, concurrency, long context) with
  the whole 16 GB envelope (`--gpu-memory-utilization 0.90`).
- Reranker gets node2's GPU alone (RTX 4060 Ti) — a 0.6 B cross-encoder needs
  only a modest envelope (0.40).
- Embedding gets node1's GTX 1650 4 GB via TEI's sm_7.5 "turing" build in fp16
  (plan D4) — vLLM ships no sm_75 kernels. Gateway/Envoy/PostgreSQL cohabit
  node1 as CPU-only workloads; they request no GPU, so there is no contention.
- `--gpu-memory-utilization` values are envelopes, not guarantees — acceptance
  records observed VRAM, not arithmetic (review P1.5).
- Never run the `speech` namespace's GPU workloads concurrently with GPU-5 —
  one physical GPU per node.

## Storage

Model weights: `hostPath` from `/mnt/local-fast/models/<model>` (node-local
NVMe), mounted read-only at `/models`. Deliberately not Longhorn and not the
HF cache (spec §41, Local Models Setup §14): deterministic inventory, no
startup downloads (`HF_HUB_OFFLINE=1`), no multi-GB copies into volumes.
PostgreSQL state: Longhorn PVC (the default StorageClass — control-plane
data, small, benefits from replication; models don't).

## Security boundary (spec §43)

```text
Platform → Gateway (gRPC) → (signed JWT) → Envoy → inference backends (vLLM / TEI)
```

- Direct client → backend and client → Envoy execution endpoint are denied by
  NetworkPolicy (`blueprints/network-policy/`), default-deny base + explicit
  Gateway→Envoy→backends→PostgreSQL flows (spec §19).
- The backend pods themselves are unauthenticated (vLLM and TEI have no auth);
  the NetworkPolicy is what makes that acceptable — treat it as load-bearing,
  not optional.
- JWT signing keys are file-mounted Secrets (never env vars, never logged);
  JWKS served from the Gateway's internal port to Envoy only.

## Failure model — no HA by design

`replicas: 1` everywhere, `strategy: Recreate` for GPU pods. Each workload is
pinned to the node holding its model (hostPath coupling), so a crashed pod has
nowhere else to go anyway. GPU-5 optimizes for a correct, observable reference
deployment; HA is GPU-6 scope (spec §25, deferred). Planned power cycles go
through `scripts/cluster-stop.sh` / `cluster-start.sh` — see
`troubleshooting.md` for the pod-storm incident those scripts exist to prevent.
