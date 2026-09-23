# GPU-5 Homelab Architecture

The normative authority is `doc/GPU-5  GPU-6  GPU-7 Deployment Plan.md` (spec);
this document describes only the **homelab realization** of it — what runs
where, and why. Numbered decisions (D1–D6) live in
`../gpu-5-implementation-plan.md` §1 and are not repeated here.

## Components and request path

```
Client
   │  HTTPS (Traefik ingressclass, self-signed cert — phase 8)
   ▼
gpu-gateway  (node1, CPU only — :8080 public API, :8090 internal JWKS, :8091 metrics)
   │  ES256 execution JWT, signed per request (spec §12)
   ▼
envoy        (node1, CPU only — :8080, jwt_authn verifies against Gateway JWKS)
   │  300 s upstream timeout (spec §31)
   ├─ /v1/chat/completions ─► vllm-synthesis  :8000 (node2, RTX 4060 Ti)
   ├─ /v1/embeddings       ─► vllm-embedding  :8000 ─┐ same pod, node3
   └─ /v1/rerank           ─► vllm-reranker   :8000 → targetPort 8001 ─┘ (RTX 5060 Ti)

gpu-gateway ─► postgres (node1, Longhorn PVC) — control state only, never on
the inference request path (spec §2.2)
```

node1's GTX 1650 4 GB is deliberately unused (plan D2): no bf16 support, no
VRAM headroom for vLLM. It carries the CPU control plane instead — the same
split the speech project converged on.

## GPU scheduling constraint

Identical mechanics to the speech project (`k8s-test-hardware.md` / speech
`docs/architecture.md`): the scheduler sees GPU **count** (`nvidia.com/gpu: 1`
per node), not VRAM. Consequences:

- Synthesis gets node2's GPU alone — the 4B model's KV cache grows under real
  usage, so it keeps the whole 16 GB envelope (`--gpu-memory-utilization 0.90`).
- Embedding + reranker share node3's GPU via the **one-pod-two-containers**
  pattern (plan D4): only the `embedding` container requests
  `nvidia.com/gpu: 1`; `reranker` inherits pod-level GPU visibility. Both cap
  `--gpu-memory-utilization` (0.20 + 0.20 ≪ 1.0 — these are fractions of
  *total* VRAM). They listen on different ports (8000/8001) because containers
  in one pod share the network namespace.
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
Public → Gateway → (signed JWT) → Envoy → vLLM
```

- Direct client → vLLM and client → Envoy execution endpoint are denied by
  NetworkPolicy (`blueprints/network-policy/`), default-deny base + explicit
  Gateway→Envoy→vLLM→PostgreSQL flows (spec §19).
- vLLM pods themselves are unauthenticated (vLLM has no auth); the NetworkPolicy
  is what makes that acceptable — treat it as load-bearing, not optional.
- JWT signing keys are file-mounted Secrets (never env vars, never logged);
  JWKS served from the Gateway's internal port to Envoy only.

## Failure model — no HA by design

`replicas: 1` everywhere, `strategy: Recreate` for GPU pods. Each workload is
pinned to the node holding its model (hostPath coupling), so a crashed pod has
nowhere else to go anyway. GPU-5 optimizes for a correct, observable reference
deployment; HA is GPU-6 scope (spec §25, deferred). Planned power cycles go
through `scripts/cluster-stop.sh` / `cluster-start.sh` — see
`troubleshooting.md` for the pod-storm incident those scripts exist to prevent.
