#!/usr/bin/env bash
# Bring the cluster back into service after the nodes have rebooted (e.g.
# after ./scripts/cluster-stop.sh --poweroff, or any power cycle). Adapted
# from speech-to-speech-k8s/scripts/cluster-start.sh for the GPU-5
# 'gpu-plane' namespace.
#
# kubelet and containerd are systemd-enabled on every node, so the control
# plane and kubelet come back on their own - this script waits for that,
# then uncordons workers, restores every Deployment that cluster-stop.sh
# scaled to 0 (reading back the replica count it saved as an annotation),
# and waits for rollouts.
#
# GPU-requesting Deployments (anything with a nvidia.com/gpu resource
# request, detected automatically - currently vllm-synthesis, vllm-reranker
# and tei-embedding) are held at 0 until the nvidia device plugin reports
# actually healthy on every worker node. This is the other half of what
# prevents zombie-pod churn: cluster-stop.sh ensures no pod object exists
# while the nodes are down, and this script makes sure we don't recreate GPU
# pods before the device plugin has re-registered healthy devices post-boot.
# Non-GPU Deployments (postgres, gpu-gateway, envoy) don't need to wait for
# this and are restored right after uncordon.
#
# Fail-closed rule: if a required GPU workload cannot be made ready, this
# script prints that the GPU runtime must be started in Kubernetes and exits
# before restoring Synanton. It never deploys Synanton against a broken GPU
# dependency and never restores GPU Deployments on plugin timeout.
#
# Note: vLLM first-boot/kernel-autotune after a cold start can take minutes;
# the rollout wait below uses a generous timeout and only warns on expiry.
#
# Usage: ./scripts/cluster-start.sh
#   Run this once all node machines are powered back on and reachable.
set -euo pipefail

NAMESPACE="${NAMESPACE:-gpu-plane}"
SYNANTON_NAMESPACE="${SYNANTON_NAMESPACE:-synanton}"
CONTROL_PLANE="${CONTROL_PLANE:-node0}"
WORKERS=(node1 node2 node3)
ALL_NODES=("${CONTROL_PLANE}" "${WORKERS[@]}")
REPLICAS_ANNOTATION="gpu.cluster/prev-replicas"
READY_TIMEOUT=600
GPU_PLUGIN_TIMEOUT=180
GPU_PLUGIN_LABEL="name=nvidia-device-plugin-ds"
GPU_PLUGIN_NAMESPACE="kube-system"
ROLLOUT_TIMEOUT=600 # vLLM model load + kernel autotune on cold start

echo "== waiting up to ${READY_TIMEOUT}s for all nodes to report Ready"
deadline=$(( $(date +%s) + READY_TIMEOUT ))
for n in "${ALL_NODES[@]}"; do
  until kubectl get node "$n" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null | grep -q True; do
    if (( $(date +%s) > deadline )); then
      echo "error: node $n did not become Ready within ${READY_TIMEOUT}s" >&2
      exit 1
    fi
    echo "-- waiting on $n ..."
    sleep 5
  done
  echo "-- $n Ready"
done

echo "== uncordoning workers"
for n in "${WORKERS[@]}"; do
  kubectl uncordon "$n"
done

restore_namespace() {
  local namespace="$1"
  local kind="$2"
  mapfile -t objects < <(kubectl -n "${namespace}" get "${kind}" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true)
  local object want current
  for object in "${objects[@]}"; do
    want="$(kubectl -n "${namespace}" get "${kind}" "$object" \
      -o jsonpath="{.metadata.annotations['${REPLICAS_ANNOTATION//./\\.}']}" 2>/dev/null || true)"
    want="${want:-1}"
    current="$(kubectl -n "${namespace}" get "${kind}" "$object" -o jsonpath='{.spec.replicas}' 2>/dev/null || true)"
    if [[ "${want}" == "0" && -n "${current}" && "${current}" != "0" ]]; then
      echo "-- ${namespace}/${kind}/${object}: saved state is 0 but current is ${current}; leaving it running"
      continue
    fi
    echo "-- ${namespace}/${kind}/${object}: restoring to ${want} replica(s)"
    kubectl -n "${namespace}" scale "${kind}" "$object" --replicas="${want}" >/dev/null
  done
}

annotation_replicas() {
  local namespace="$1"
  local kind="$2"
  local object="$3"
  local want
  want="$(kubectl -n "${namespace}" get "${kind}" "$object"     -o jsonpath="{.metadata.annotations['${REPLICAS_ANNOTATION//./\\.}']}" 2>/dev/null || true)"
  printf '%s' "${want:-1}"
}

gpu_runtime_error() {
  local reason="$1"
  cat >&2 <<EOF

error: GPU runtime must be started in Kubernetes before Synanton is deployed.
       ${reason}
       Namespace: ${NAMESPACE}
       Synanton will NOT be started against broken GPU dependencies.

       Verify/fix the GPU runtime in Kubernetes:
         kubectl -n ${NAMESPACE} get deploy,pods
         kubectl -n ${GPU_PLUGIN_NAMESPACE} get pods -l ${GPU_PLUGIN_LABEL} -o wide
         kubectl get nodes -o custom-columns=NAME:.metadata.name,GPU:.status.allocatable.nvidia\.com/gpu

       After the GPU runtime is healthy, re-run:
         ./scripts/cluster-start.sh
EOF
  exit 1
}

cleanup_terminal_pods() {
  local namespace="$1"
  local name phase
  local -a stale=()
  while read -r name phase; do
    [[ -n "${name}" ]] || continue
    case "${phase}" in
      Failed|Unknown|Succeeded) stale+=("${name}") ;;
    esac
  done < <(kubectl -n "${namespace}" get pods     -o custom-columns='NAME:.metadata.name,PHASE:.status.phase' --no-headers 2>/dev/null || true)

  if [[ ${#stale[@]} -eq 0 ]]; then
    echo "  ${namespace}: no terminal pod objects"
    return
  fi
  echo "  ${namespace}: deleting ${#stale[@]} terminal pod object(s)"
  printf '%s\n' "${stale[@]}"     | xargs -r -n 50 kubectl -n "${namespace}" delete pod --force --grace-period=0         --ignore-not-found=true --wait=false >/dev/null
}

if ! kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1; then
  gpu_runtime_error "namespace '${NAMESPACE}' does not exist."
fi

echo "== restoring Deployments"
mapfile -t DEPLOYMENTS < <(kubectl -n "${NAMESPACE}" get deployments -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
if [[ ${#DEPLOYMENTS[@]} -eq 0 ]]; then
  gpu_runtime_error "no Deployments exist in '${NAMESPACE}'."
fi
GPU_DEPLOYMENTS=()
OTHER_DEPLOYMENTS=()
for d in "${DEPLOYMENTS[@]}"; do
  gpu_req="$(kubectl -n "${NAMESPACE}" get deployment "$d" -o jsonpath='{.spec.template.spec.containers[*].resources.requests.nvidia\.com/gpu}')"
  if [[ -n "${gpu_req// }" ]]; then
    GPU_DEPLOYMENTS+=("$d")
  else
    OTHER_DEPLOYMENTS+=("$d")
  fi
done

GPU_REQUIRED=()
for d in "${GPU_DEPLOYMENTS[@]}"; do
  want="$(annotation_replicas "${NAMESPACE}" deployment "$d")"
  if [[ "${want}" != "0" ]]; then
    GPU_REQUIRED+=("$d")
  fi
done

restore() {
  local d="$1"
  local want
  want="$(annotation_replicas "${NAMESPACE}" deployment "$d")"
  current="$(kubectl -n "${NAMESPACE}" get deployment "$d" -o jsonpath='{.spec.replicas}' 2>/dev/null || true)"
  if [[ "${want}" == "0" && -n "${current}" && "${current}" != "0" ]]; then
    echo "-- ${d}: saved state is 0 but current is ${current}; leaving it running"
    return
  fi
  echo "-- ${d}: restoring to ${want} replica(s)"
  kubectl -n "${NAMESPACE}" scale deployment "$d" --replicas="${want}" >/dev/null
}

echo "== holding GPU Deployments at 0 while the runtime is prepared"
for d in "${GPU_DEPLOYMENTS[@]}"; do
  current="$(kubectl -n "${NAMESPACE}" get deployment "$d" -o jsonpath='{.spec.replicas}' 2>/dev/null || true)"
  [[ -n "${current}" ]] || continue
  saved="$(kubectl -n "${NAMESPACE}" get deployment "$d"     -o jsonpath="{.metadata.annotations['${REPLICAS_ANNOTATION//./\\.}']}" 2>/dev/null || true)"
  if [[ -z "${saved}" ]]; then
    kubectl -n "${NAMESPACE}" annotate deployment "$d" "${REPLICAS_ANNOTATION}=${current}" --overwrite >/dev/null
  fi
  if [[ "${current}" != "0" ]]; then
    echo "-- ${d}: ${current} -> 0 until GPU readiness is proven"
    kubectl -n "${NAMESPACE}" scale deployment "$d" --replicas=0 >/dev/null
  fi
done
cleanup_terminal_pods "${NAMESPACE}"

restore_non_gpu() {
  if [[ ${#OTHER_DEPLOYMENTS[@]} -gt 0 ]]; then
    echo "-- non-GPU Deployments (restoring now): ${OTHER_DEPLOYMENTS[*]}"
    for d in "${OTHER_DEPLOYMENTS[@]}"; do restore "$d"; done
  fi
}

if [[ ${#GPU_REQUIRED[@]} -gt 0 ]]; then
  echo "-- GPU Deployments required by saved state: ${GPU_REQUIRED[*]}"
  echo "== waiting up to ${GPU_PLUGIN_TIMEOUT}s for the nvidia device plugin to be Ready on every worker"
  deadline=$(( $(date +%s) + GPU_PLUGIN_TIMEOUT ))
  for n in "${WORKERS[@]}"; do
    while true; do
      ready="$(kubectl -n "${GPU_PLUGIN_NAMESPACE}" get pods -l "${GPU_PLUGIN_LABEL}"         --field-selector "spec.nodeName=${n}"         -o jsonpath='{.items[0].status.containerStatuses[0].ready}' 2>/dev/null || true)"
      gpu_alloc="$(kubectl get node "$n" -o jsonpath='{.status.allocatable.nvidia\.com/gpu}' 2>/dev/null || true)"
      if [[ "${ready}" == "true" && -n "${gpu_alloc}" && "${gpu_alloc}" != "0" ]]; then
        echo "-- ${n}: device plugin Ready, nvidia.com/gpu=${gpu_alloc}"
        break
      fi
      if (( $(date +%s) > deadline )); then
        gpu_runtime_error "nvidia device plugin on ${n} is not healthy (ready=${ready:-?} gpu=${gpu_alloc:-?}) after ${GPU_PLUGIN_TIMEOUT}s."
      fi
      sleep 3
    done
  done

  echo "== checking for VRAM holders with no live pods (orphaned containers lock out admission)"
  for n in "${WORKERS[@]}"; do
    holders="$(ssh -o ConnectTimeout=10 "$n" \
      "nvidia-smi --query-compute-apps=pid --format=csv,noheader 2>/dev/null" | grep -vc '^$' || true)"
    if [[ "${holders}" != "0" ]]; then
      cat >&2 <<EOF

error: GPU runtime must be started in Kubernetes before Synanton is deployed.
       ${holders} compute process(es) hold the GPU on ${n}, but no live GPU pod owns them.
       These orphaned containerd containers recreate the known admission lockout.
       Clear them before re-running the start:
         ssh ${n} "ps -o pid,ppid,cmd -p \$(nvidia-smi --query-compute-apps=pid --format=csv,noheader | tr '\n' ',')"
         (node sudo) ctr -n k8s.io tasks kill -s SIGKILL <cid>
         (node sudo) ctr -n k8s.io containers rm <cid>
       See troubleshooting.md, orphaned-GPU-container entry.
       Synanton has NOT been started.
EOF
      exit 1
    fi
    echo "-- ${n}: VRAM free"
  done
  restore_non_gpu
  for d in "${GPU_REQUIRED[@]}"; do restore "$d"; done
else
  echo "-- no GPU Deployments are enabled in the saved state; skipping GPU restore"
  restore_non_gpu
fi

echo "== verifying GPU runtime is ready before starting dependent Synanton workloads"
for d in "${DEPLOYMENTS[@]}"; do
  want="$(annotation_replicas "${NAMESPACE}" deployment "$d")"
  if [[ "${want}" == "0" ]]; then
    continue
  fi
  if ! kubectl -n "${NAMESPACE}" rollout status "deployment/${d}" --timeout="${ROLLOUT_TIMEOUT}s"; then
    gpu_runtime_error "deployment/${d} did not become ready within ${ROLLOUT_TIMEOUT}s."
  fi
done

echo "== restoring ${SYNANTON_NAMESPACE} Deployments and StatefulSets"
restore_namespace "${SYNANTON_NAMESPACE}" deployment
restore_namespace "${SYNANTON_NAMESPACE}" statefulset

echo "== waiting for ${SYNANTON_NAMESPACE} Deployments and StatefulSets to roll out"
for d in $(kubectl -n "${SYNANTON_NAMESPACE}" get deployments -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true); do
  kubectl -n "${SYNANTON_NAMESPACE}" rollout status "deployment/${d}" --timeout="${ROLLOUT_TIMEOUT}s" \
    || echo "warning: ${SYNANTON_NAMESPACE}/deployment/${d} did not become ready in time - check it manually" >&2
done
for s in $(kubectl -n "${SYNANTON_NAMESPACE}" get statefulsets -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true); do
  kubectl -n "${SYNANTON_NAMESPACE}" rollout status "statefulset/${s}" --timeout="${ROLLOUT_TIMEOUT}s" \
    || echo "warning: ${SYNANTON_NAMESPACE}/statefulset/${s} did not become ready in time - check it manually" >&2
done

echo "== checking for terminal pods after startup"
cleanup_terminal_pods "${NAMESPACE}"

echo
echo "== final state"
kubectl -n "${NAMESPACE}" get pods -o wide
echo
echo "Done."
echo "  Verify vLLM:   run the smoke tests in gpu-5-implementation-plan.md §8"
echo "  API (phase 8+): https://<any-node-ip>:30443/ via Traefik (self-signed cert)"
