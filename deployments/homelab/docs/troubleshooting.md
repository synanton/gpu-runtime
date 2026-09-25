# GPU-5 Troubleshooting

Cluster-generic issues (nodes NotReady, pod storms after node flapping,
registry pull failures, device-plugin health) are documented in the speech
project's `docs/troubleshooting.md` and apply unchanged — same cluster, same
registry, same device plugin. This file covers only GPU-5-specific failure
modes. Namespace is `gpu-plane` throughout.

## vLLM/TEI pod stuck in `Pending` — `Insufficient nvidia.com/gpu`

Another GPU pod already holds the node's single `nvidia.com/gpu: 1`. Check:

```bash
kubectl -n gpu-plane describe pod <pod> | grep -A5 Events
kubectl get pods -A -o wide --field-selector spec.nodeName=node2   # who owns the GPU?
```

Usual suspect: a `speech` namespace workload left running (`speech-llm` on
node2, `speech-stt-tts` on node3) — the two projects cannot share a node.
Scale the speech Deployment to 0 rather than deleting GPU-5 pods. (Within
gpu-plane there is exactly one GPU pod per node by design — plan D2.)

## vLLM pod CrashLoopBackOff / OOM at startup

1. **Model path wrong.** `hostPath` must point at the *normalized* layout
   `/mnt/local-fast/models/<model>` (plan §4.4). A missing dir yields
   `Directory` type-check failures or vLLM "not a valid model directory" errors.
   Verify on the node: `ssh node3 ls /mnt/local-fast/models/`.
2. **Reranker loads but `/v1/rerank` 400s** with `The model does not support
   Rerank (Score) API`: the `--hf-overrides` block (sequence-classification
   routing, yes/no logit scoring) is missing or malformed. Compare against
   `blueprints/vllm/reranker.yaml`.

## TEI embedding pod fails on node1

The GTX 1650 is sm_7.5 consumer Turing (no bf16, no tensor cores). Checks:

- Image must be the **turing** variant (`.../text-embeddings-inference:turing-1.9`)
  — the default TEI image targets Ampere+ and will not run on sm_7.5.
- Flash attention is auto-disabled on the turing build (upstream precision
  warning) — do not force-enable it.
- Model dir must contain tokenizer files, not just weights:
  `ssh node1 ls /mnt/local-fast/models/bge-base-en-v1.5` (plan §4.5 — a
  partial download is the known failure mode).
- If the turing build still misbehaves, fall back per plan D4: bge-small →
  TEI CPU image on node1.

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
and — until T-K8S-6a lands — the Gateway does not serve JWKS at all, so this
401 is the **expected** fail-closed state (plan §12.2), not a misconfiguration.

## Envoy up but backend unreachable (503/UF,upstream_reset)

Route-to-cluster mapping in `envoy-config` points at Service DNS names
(`vllm-synthesis`, `tei-embedding`, `vllm-reranker`). Confirm all three
Services have endpoints:

```bash
kubectl -n gpu-plane get endpoints vllm-synthesis tei-embedding vllm-reranker
```

Empty endpoints = pods not Ready (see the vLLM/TEI sections above). All three
inference Services listen on uniform `:8000` (the retracted colocation's
8000→8001 port mapping is gone).

## Gateway not Ready / fails startup validation

Spec §5.5 is deliberately fail-closed. Check `kubectl -n gpu-plane logs
deploy/gpu-gateway` for: unsupported `gpu-gateway.dispatch.strategy` (GPU-5 uses
`direct`), a missing `gpu-gateway-config` ConfigMap, unreachable PostgreSQL
(`GPU_GATEWAY_DB_*`), or GatewayStartupValidator messages. Readiness is
`/actuator/health/readiness` on :8091. Config errors must prevent readiness — don't paper over them by
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
