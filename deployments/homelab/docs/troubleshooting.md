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

## GPU pod stuck Pending/AdmissionError with nothing Running (orphaned container)

Symptom: node Capacity shows `nvidia.com/gpu: 1`, kubectl shows no Running
pod, yet every new pod fails with `Allocate failed ... Available: 0` (or an
admitted pod's container dies instantly with `nvidia-smi not found` — a
phantom allocation with no device injected). Root cause (observed 2026-10-10):
a containerd container holding VRAM whose pod object is already deleted —
invisible to kubectl, self-sustaining the lockout. Deleting pods cannot fix
it; the ReplicaSet churn only adds zombies.

Diagnose from any host with ssh (no sudo needed):

```bash
ssh node1 "nvidia-smi --query-compute-apps=pid --format=csv,noheader"
# any pid listed while no pod runs in gpu-plane == the orphan
ssh node1 "ps -o pid,ppid,cmd -p <pid>"   # parent is containerd-shim, not kubelet
```

Fix on the node (sudo):

```bash
sudo ctr -n k8s.io tasks kill -s SIGKILL <container-id>  # id from the shim cmdline
sudo ctr -n k8s.io containers rm <container-id>
nvidia-smi --query-compute-apps=pid --format=csv   # expect header only
```

Then delete the failed pods once and let a single fresh pod schedule. If the
fault recurs after a driver change, also clear the kubelet device-plugin
checkpoint (`sudo rm /var/lib/kubelet/device-plugins/kubelet_internal_checkpoint`
+ `sudo systemctl restart kubelet`) — stale checkpoints produce the same
Available: 0 signature with a different cause.

Prevention: never shuffle GPU replicas without the pre-flight in
`platform/scripts/bench-tei-swap.sh` (VRAM-empty + allocatable checks), and
keep the orphan watchdog (`platform/scripts/bench-tei-orphan-watch.sh`, cron
on node0 every 5 min) installed — it alerts exactly on held-VRAM-with-no-Running-pod.

## K8s GPU injection absent cluster-wide (2026-10-11)

Symptom: pods admit (get IPs) but containers start with no NVIDIA devices
(`libcuda.so.1` missing; TEI entrypoint dies on `nvidia-smi not found`),
on all workers. Plugin advertises capacity, checkpoint empty, no holders.

Cause chain: the plugin runs `DEVICE_LIST_STRATEGY=envvar`, but containerd
has only `runc` + `enable_cdi` (no nvidia runtime, no hooks) — nothing
consumes the env injection path. The local v0.20.0 plugin build crashes
instantly on `cdi-annotations` (all nodes), so the working pairing is
unrepresentable with the current image. Independent fault found en route:
node driver↔libs skew (node2: module 595.84 vs libs 595.99.02 — even host
`nvidia-smi` failed); converged all nodes to 595.99.02.

Workaround: serve TEI from manual docker (`--gpus all` path is independent
of K8s injection) until a CDI-capable plugin build lands. Do not "fix" by
flipping the strategy flag — it crash-loops the DaemonSet.

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

Causes, in order of likelihood:
1. **Gateway not Ready yet.** Deploy the gateway before Envoy; the `deploy.sh` phases do this. Envoy fetches the JWKS at startup and re-fetches 5 s after a failure, so it recovers without a restart once the Gateway is up.
2. **Gateway fails to start: JWT keys Secret missing or invalid.** Check `kubectl -n gpu-plane logs deploy/gpu-gateway | grep -i "execution-jwt\|key"`. The Secret `gpu-gateway-jwt-keys` needs `current.key` (PKCS#8, P-256; SEC1 is rejected), `current.pub` and `previous.pub` (plan §7). Only the kids are logged, never key material.
3. **JWKS not reachable from Envoy:**
   ```bash
   kubectl -n gpu-plane exec deploy/envoy -- wget -qO- http://gpu-gateway:8090/internal/.well-known/jwks.json
   ```
   Expect two keys. Also check the NetworkPolicy (Envoy → gateway :8090) and the gateway Service port `jwks`.
4. **Wrong audience/issuer** gets **403**, not 401 ("Audiences in Jwt are not allowed"). Check `execution-jwt.audience` against the Envoy provider `audiences`.
5. **Clock skew between node1 and the Gateway pod.** Tokens live 60 s (`execution-jwt.ttl-seconds`), and Envoy allows 60 s of skew.

The Gateway reports these as `execution_jwt_rejected` (non-retryable) in `ErrorInfo.code`.

Unsigned requests (e.g. `smoke-test.sh envoy`) must get 401. That is the expected
fail-closed behaviour, not a misconfiguration.

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
