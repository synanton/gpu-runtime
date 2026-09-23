# GPU-5 Troubleshooting

Cluster-generic issues (nodes NotReady, pod storms after node flapping,
registry pull failures, device-plugin health) are documented in the speech
project's `docs/troubleshooting.md` and apply unchanged — same cluster, same
registry, same device plugin. This file covers only GPU-5-specific failure
modes. Namespace is `gpu-plane` throughout.

## vLLM pod stuck in `Pending` — `Insufficient nvidia.com/gpu`

Another GPU pod already holds the node's single `nvidia.com/gpu: 1`. Check:

```bash
kubectl -n gpu-plane describe pod <pod> | grep -A5 Events
kubectl get pods -A -o wide --field-selector spec.nodeName=node2   # who owns the GPU?
```

Usual suspect: a `speech` namespace workload left running (`speech-llm` on
node2, `speech-stt-tts` on node3) — the two projects cannot share a node.
Scale the speech Deployment to 0 rather than deleting GPU-5 pods.

## vLLM pod CrashLoopBackOff / OOM at startup

1. **Colocated pod VRAM overcommit (node3).** `--gpu-memory-utilization` is a
   fraction of *total* GPU memory per container; if the two values don't sum
   well below 1.0 the second engine to initialize OOMs. Blueprints ship
   0.20 + 0.20. Check `kubectl -n gpu-plane logs deploy/vllm-embed-rerank -c reranker --previous`
   for `CUDA out of memory` and lower further or add `--enforce-eager`.
2. **Model path wrong.** `hostPath` must point at the *normalized* layout
   `/mnt/local-fast/models/<model>` (plan §4.4). A missing dir yields
   `Directory` type-check failures or vLLM "not a valid model directory" errors.
   Verify on the node: `ssh node2 ls /mnt/local-fast/models/`.
3. **Reranker loads but `/v1/rerank` 400s** with `The model does not support
   Rerank (Score) API`: the `--hf-overrides` block (sequence-classification
   routing, yes/no logit scoring) is missing or malformed. Compare against
   `blueprints/vllm/embedding-reranker.yaml`.

## vLLM slow to become Ready after cold boot

First start after a node reboot runs CUDA kernel autotune/compile — minutes,
not seconds. The blueprints allow for it (`initialDelaySeconds: 120`,
`failureThreshold: 10`); `scripts/cluster-start.sh` waits 600 s for rollouts.
If a pod is `Running` but not `Ready` with logs showing graph capture progress,
it's normal — watch, don't restart.

## Envoy returns 401 for everything

Envoy fails closed (spec §12): if it can't fetch JWKS from the Gateway, **all**
requests are rejected, including valid ones.

```bash
kubectl -n gpu-plane logs deploy/envoy | grep -i jwks
kubectl -n gpu-plane get pods -l app=gpu-gateway    # Gateway must be Ready FIRST
```

Causes, in order of likelihood: Gateway not ready yet (deploy gateway before
envoy — `deploy.sh` phases do this); Gateway's JWT keys Secret missing
(`gpu-gateway-jwt-keys`, created imperatively per plan §7); JWKS endpoint
served on the wrong Gateway port (Envoy config targets :8090 `internal`).

## Envoy up but vLLM unreachable (503/UF,upstream_reset)

Route-to-cluster mapping in `envoy-config` points at Service DNS names
(`vllm-synthesis`, `vllm-embedding`, `vllm-reranker`). Confirm all three
Services have endpoints:

```bash
kubectl -n gpu-plane get endpoints vllm-synthesis vllm-embedding vllm-reranker
```

Empty endpoints = pods not Ready (see vLLM sections above). The reranker
Service maps `port: 8000 → targetPort: 8001` — if you edited container ports,
keep that mapping in sync (both containers share the pod network namespace;
two :8000 listeners would collide).

## Gateway not Ready / fails startup validation

Spec §5.5 is deliberately fail-closed. Check `kubectl -n gpu-plane logs
deploy/gpu-gateway` for: unsupported `GPU_DEPLOYMENT_MODE` (GPU-5 must be
`local-only`), missing API-key pepper Secret, missing JWT key files,
unreachable PostgreSQL, model registry entries pointing at Services that don't
exist. Config errors must prevent readiness — don't paper over them by
disabling validation.

## PostgreSQL PVC Pending

`storageClassName: longhorn` requires the Longhorn stack healthy
(`kubectl -n longhorn-system get pods`) and at least one schedulable node with
free disk. The Deployment is pinned to node1 but the *volume* is
Longhorn-replicated, so a node1 outage shows up as mount/attach errors
(`kubectl -n gpu-plane describe pod <postgres>`), not as a lost PVC.

## Zombie pods after unplanned node outage

Same incident class as the speech project's 2026-09-09 pod storm (device
plugin flaps → `UnexpectedAdmissionError` loop → hundreds of dead pods).
Prevention is `scripts/cluster-stop.sh` before planned downtime; cure after an
unplanned one:

```bash
kubectl -n gpu-plane get pods --no-headers \
  | awk '$3!="Running" && $3!="Completed" {print $1}' \
  | xargs -r -n 50 kubectl -n gpu-plane delete pod --force --grace-period=0
```

Confirm the storm has stopped (no new admission-error events) before deleting,
and check every machine on 192.168.10.0/24 has a static IP (see the speech
troubleshooting doc for the full story).

## Speech-project GPU conflict (both stacks needed)

They can't coexist on one GPU node. If both must be installed, keep only one
stack's GPU Deployments scaled up at a time; CPU-side components (gateways,
demos, Traefik) coexist fine.
