# B1 — Read-only GPU hardware and scheduling inventory

**Issue:** synanton/gpu-runtime#32 · **Program:** platform#892
**Collected:** 2026-11-25T05:32Z (after Kubernetes maintenance/restart)
**Mode:** read-only. No pods, workloads, device plugins, or GPU state were created, changed, restarted, scaled, evicted, or benchmarked.

## Physical hardware

One physical GPU per worker; no MIG devices observed.

| Node | GPU | UUID | VRAM total | VRAM used | Free | Compute capability |
|---|---|---|---|---:|---:|---:|---|
| `node1` | NVIDIA GeForce GTX 1650 | `GPU-220d6936-c4de-75d7-463d-b5f64958f3b0` | 4096 MiB | 180 MiB | 3916 MiB | 7.5 |
| `node2` | NVIDIA GeForce RTX 4060 Ti | `GPU-a004ce09-4df3-3098-a6dc-1d11175ecf30` | 16380 MiB | 15 MiB | 16365 MiB | 8.9 |
| `node3` | NVIDIA GeForce RTX 5060 Ti | `GPU-2270a494-7456-1a7a-a974-eb76d94b9b1c` | 16311 MiB | 12973 MiB | 3338 MiB | 12.0 |

Commands:

```bash
for n in node1 node2 node3; do
  ssh "$n" "nvidia-smi --query-gpu=index,name,uuid,memory.total,memory.used,compute_cap,driver_version --format=csv,noheader"
done
```

## Software

- Host NVIDIA driver: **615.71.09** on all three workers.
- Host CUDA (as reported by `nvidia-smi`): **13.4**.
- Historical container/runtime stack recorded in #770: vLLM `0.29.0`, PyTorch `2.13.0+cu130`, CUDA `13.0`. The container CUDA runtime was **not independently re-verified** in this read-only pass; treat it as historical/uncertain.
- `nvidia-smi` warns that the Driver/CUDA display fields are deprecated in favor of KMD/CUDA UMD fields.

```bash
nvidia-smi --query | grep -E 'CUDA Version|Driver Version'
```

## Kubernetes GPU scheduling state

- `nvidia-device-plugin-daemonset`: **3/3 ready** in `kube-system`; pods Running/Ready on `node1`, `node2`, `node3`.
- Restart counts observed after maintenance: node1 `38`, node2 `37`, node3 `38`; all currently Ready.
- `nvidia.com/gpu` **allocatable = 2 per worker**. This is time-sliced capacity, not two physical GPUs.
- `node3` also advertises `nvidia.com/gpu-driver-upgrade-state=upgrade-required`; node1 reports `pod-restart-required`. These are observed labels, not acted on.

```bash
kubectl get ds -n kube-system nvidia-device-plugin-daemonset
kubectl get pods -n kube-system -l name=nvidia-device-plugin-ds -o wide
for n in node1 node2 node3; do
  kubectl get node "$n" -o jsonpath='{.metadata.name}{" nvidia.com/gpu="}{.status.allocatable.nvidia\.com/gpu}{"\n"}'
done
kubectl describe node node3 | grep -A8 'Allocatable:'
```

## GPU workload occupancy and attribution

Only one Running GPU-consuming pod was observed:

| Namespace | Pod | Node | Container | GPU request | GPU limit | Actual GPU memory |
|---|---|---|---|---|---:|---:|
| `gpu-plane` | `vllm-extraction-84fb9d65b7-hztkd` | `node3` | `vllm` | 1 | 1 | 12952 MiB (`VLLM::EngineCore`, pid 11901) |

`node1` and `node2` have no Running GPU-requesting pods. Their small used-memory readings (180 MiB / 15 MiB) are not attributable to Kubernetes GPU compute workloads.

```bash
kubectl get pods -n gpu-plane --field-selector=status.phase=Running -o wide
ssh node3 "nvidia-smi --query-compute-apps=pid,process_name,used_memory,gpu_uuid --format=csv"
kubectl get pod -n gpu-plane vllm-extraction-84fb9d65b7-hztkd \
  -o jsonpath='{.spec.nodeName}{"\n"}{range .spec.containers[*]}{.name}{" requests="}{.resources.requests}{" limits="}{.resources.limits}{"\n"}{end}'
```

## Availability assessment

These are separate conclusions:

1. **Physical GPU availability**
   - `node2` (RTX 4060 Ti, 16 GB) is physically idle: ~16,365 MiB free, no GPU pod.
   - `node1` (GTX 1650, 4 GB) is physically idle but too small for an 8B FP8 model.
   - `node3` (RTX 5060 Ti, 16 GB) is occupied by production vLLM: ~12,973 MiB used, ~3,338 MiB free.

2. **Kubernetes scheduling availability**
   - All workers advertise 2 `nvidia.com/gpu` slots due to time-slicing.
   - `node3`'s second slot is **not** free physical memory; the physical device is already occupied by vLLM.
   - `node2` has allocatable slots and no GPU pod, but a schedulable slot alone is not proof of a safe isolated GPU.

3. **Safe isolated test window**
   - Not established by B1 and not authorized here.
   - `node2` is the only plausible physical candidate; any B2 probe requires explicit authorization and a documented isolated window.

## B2 recommendation

- Device plugin is healthy: **verify, do not reinstall**.
- `nvidia.com/gpu` is allocatable on all workers; record, but do not infer physical free memory from it.
- Production vLLM still occupies `node3`; `node2` appears physically free. B2 should remain **verification-only** until an authorized isolated window is approved.
- No pod creation/reservation is permitted merely because a slot is allocatable.

## B2 carry-forward notes

B2 must explicitly record or resolve these known-unknowns instead of assuming them:
- **Container/runtime CUDA version:** not independently verified in B1. Host reports CUDA 13.4; #770 records the historical vLLM/PyTorch stack separately. B2/B3 should verify inside the actual runtime before model compatibility claims.
- **Node1/node2 small GPU memory use:** ~180 MiB / ~15 MiB is not attributed to Kubernetes compute pods. It is likely driver/context overhead but remains unconfirmed.
- **Time-sliced allocatable slots:** `nvidia.com/gpu=2` per node is logical capacity; it does not imply two physical devices or two independent 16 GB partitions.

## Limitations

- Container CUDA runtime version was not independently re-verified.
- Small non-compute GPU memory usage on node1/node2 is not attributed to Kubernetes pods.
- Time-sliced slots are logical scheduling capacity, not physical GPUs.
- No benchmarking, model loading, or probe was performed.
