#!/usr/bin/env bash
# Safely quiesce the cluster before powering the nodes off (planned reboot,
# maintenance, etc). Adapted from speech-to-speech-k8s/scripts/cluster-stop.sh
# for the GPU-5 'gpu-plane' namespace.
#
# The important part: every Deployment in the '${NAMESPACE}' namespace is
# scaled to 0 FIRST, and we wait for those pods to actually terminate,
# before touching cordon/drain at all. This is what prevents zombie-pod
# churn: if no pod object exists for vllm-synthesis/vllm-reranker/
# tei-embedding while
# the nodes are down, there is nothing for the scheduler to keep re-placing
# against a not-yet-healthy GPU device plugin when the nodes come back.
# Cordon + drain afterwards is just a backstop (everything else on these
# nodes is a DaemonSet, which drain leaves alone anyway).
#
# Each Deployment's pre-shutdown replica count is recorded as an annotation
# (gpu.cluster/prev-replicas) so cluster-start.sh can restore exactly what
# was running, rather than assuming everything is always 1 replica.
#
# Note: PostgreSQL (RWO Longhorn PVC) scales to 0 cleanly with everything
# else; its data is unaffected.
#
# By default this only quiesces + drains - it does NOT power anything off.
# Pass --poweroff to also run `sudo shutdown -h now` over SSH on every node
# (workers first, control-plane last). Nodes have passwordless SSH but NOT
# passwordless sudo, so you'll be prompted for the sudo password per node.
#
# Usage: ./scripts/cluster-stop.sh [--poweroff] [--yes] [--skip-snapshot]
#   --poweroff       also power off every node via SSH once quiesced
#   --yes            skip the confirmation prompt (only relevant with --poweroff)
#   --skip-snapshot  skip the best-effort etcd snapshot on the control-plane node
set -euo pipefail

NAMESPACE="${NAMESPACE:-gpu-plane}"
SYNANTON_NAMESPACE="${SYNANTON_NAMESPACE:-synanton}"
CONTROL_PLANE="${CONTROL_PLANE:-node0}"
WORKERS=(node1 node2 node3)
REPLICAS_ANNOTATION="gpu.cluster/prev-replicas"
SCALE_DOWN_TIMEOUT=180 # vLLM pods can take longer to drain than speech pods

POWEROFF=false
CONFIRM=false
SKIP_SNAPSHOT=false

for arg in "$@"; do
  case "$arg" in
    --poweroff) POWEROFF=true ;;
    --yes) CONFIRM=true ;;
    --skip-snapshot) SKIP_SNAPSHOT=true ;;
    *) echo "unknown arg: $arg" >&2; exit 1 ;;
  esac
done

if ! $SKIP_SNAPSHOT; then
  echo "== best-effort etcd snapshot on ${CONTROL_PLANE} (you may be prompted for the sudo password)"
  ts="$(date +%F-%H%M%S)"
  ssh -t "${CONTROL_PLANE}" "sudo ETCDCTL_API=3 etcdctl snapshot save /var/backups/etcd-snapshot-${ts}.db \
    --endpoints=https://127.0.0.1:2379 \
    --cacert=/etc/kubernetes/pki/etcd/ca.crt \
    --cert=/etc/kubernetes/pki/etcd/server.crt \
    --key=/etc/kubernetes/pki/etcd/server.key" \
    || echo "warning: etcd snapshot failed or was skipped - continuing anyway" >&2
fi

record_and_scale() {
  local namespace="$1"
  local kind="$2"
  mapfile -t objects < <(kubectl -n "${namespace}" get "${kind}" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true)
  if [[ ${#objects[@]} -eq 0 ]]; then
    echo "  (no ${kind}s found in '${namespace}')"
    return
  fi
  local object current
  for object in "${objects[@]}"; do
    current="$(kubectl -n "${namespace}" get "${kind}" "$object" -o jsonpath='{.spec.replicas}')"
    echo "-- ${namespace}/${kind}/${object}: ${current} -> 0 (saved as ${REPLICAS_ANNOTATION})"
    kubectl -n "${namespace}" annotate "${kind}" "$object" "${REPLICAS_ANNOTATION}=${current}" --overwrite >/dev/null
    kubectl -n "${namespace}" scale "${kind}" "$object" --replicas=0 >/dev/null
  done
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
  done < <(kubectl -n "${namespace}" get pods \
    -o custom-columns='NAME:.metadata.name,PHASE:.status.phase' --no-headers 2>/dev/null || true)

  if [[ ${#stale[@]} -eq 0 ]]; then
    echo "  ${namespace}: no terminal pod objects"
    return
  fi
  echo "  ${namespace}: deleting ${#stale[@]} terminal pod object(s)"
  printf '%s\n' "${stale[@]}" \
    | xargs -r -n 50 kubectl -n "${namespace}" delete pod --force --grace-period=0 \
        --ignore-not-found=true --wait=false >/dev/null
}

wait_for_namespace_empty() {
  local namespace="$1"
  local deadline=$(( $(date +%s) + SCALE_DOWN_TIMEOUT ))
  while true; do
    local remaining
    remaining="$(kubectl -n "${namespace}" get pods --no-headers 2>/dev/null | grep -vc '^$' || true)"
    if [[ "${remaining}" -eq 0 ]]; then
      echo "  ${namespace}: all pods terminated"
      return
    fi
    if (( $(date +%s) > deadline )); then
      echo "warning: ${namespace}: ${remaining} pod(s) still present after ${SCALE_DOWN_TIMEOUT}s - continuing anyway (drain below will force the issue)" >&2
      kubectl -n "${namespace}" get pods
      return
    fi
    sleep 3
  done
}

echo "== recording current replica counts and scaling ${NAMESPACE} + ${SYNANTON_NAMESPACE} to 0"
record_and_scale "${NAMESPACE}" deployment
record_and_scale "${SYNANTON_NAMESPACE}" deployment
record_and_scale "${SYNANTON_NAMESPACE}" statefulset

echo "== deleting terminal/stale pods left by failed GPU admission"
cleanup_terminal_pods "${NAMESPACE}"

echo "== waiting up to ${SCALE_DOWN_TIMEOUT}s for pods to terminate"
wait_for_namespace_empty "${NAMESPACE}"
wait_for_namespace_empty "${SYNANTON_NAMESPACE}"

echo "== cordoning workers"
for n in "${WORKERS[@]}"; do
  kubectl cordon "$n"
done

echo "== draining workers (backstop - everything app-level should already be gone)"
for n in "${WORKERS[@]}"; do
  echo "-- draining $n"
  kubectl drain "$n" --ignore-daemonsets --delete-emptydir-data --force --timeout=180s
done

echo
echo "== quiesced. Current namespace state:"
kubectl -n "${NAMESPACE}" get pods -o wide
kubectl -n "${SYNANTON_NAMESPACE}" get pods -o wide || true

echo
echo "== verifying no VRAM holders survive on workers (orphaned containers have"
echo "   no pod objects and persist past drain - they lock out the next start)"
for n in "${WORKERS[@]}"; do
  holders="$(ssh -o ConnectTimeout=10 "$n" \
    "nvidia-smi --query-compute-apps=pid --format=csv,noheader 2>/dev/null" | grep -vc '^$' || true)"
  if [[ "${holders}" != "0" ]]; then
    echo "warning: ${holders} compute process(es) still hold the GPU on ${n} with no pods left - orphaned container(s)." >&2
    echo "  Clear before the next start (node sudo): ctr -n k8s.io tasks kill -s SIGKILL <cid>; ctr -n k8s.io containers rm <cid>" >&2
  else
    echo "  ${n}: VRAM free"
  fi
done

if ! $POWEROFF; then
  cat <<EOF

Nodes are cordoned + drained, and every Deployment is scaled to 0. Power the
machines off yourself now, or re-run this script with --poweroff to do it
from here.
EOF
  exit 0
fi

if ! $CONFIRM; then
  cat <<EOF

This will run 'sudo shutdown -h now' over SSH on: ${WORKERS[*]}, then ${CONTROL_PLANE}.
You'll be prompted for the sudo password on each node.
EOF
  read -rp "Type 'yes' to continue: " reply
  [[ "${reply}" == "yes" ]] || { echo "Aborted (nodes remain quiesced, not powered off)."; exit 1; }
fi

echo "== powering off workers"
for n in "${WORKERS[@]}"; do
  echo "-- $n"
  ssh -t "$n" 'sudo shutdown -h now' || echo "warning: could not confirm shutdown on $n" >&2
done

echo "== powering off control-plane (${CONTROL_PLANE}) last"
ssh -t "${CONTROL_PLANE}" 'sudo shutdown -h now' || echo "warning: could not confirm shutdown on ${CONTROL_PLANE}" >&2

echo "Done. All nodes told to power off. Use ./scripts/cluster-start.sh once they're back up."
