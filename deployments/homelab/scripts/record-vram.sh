#!/usr/bin/env bash
# Record VRAM for a running GPU-5 component, for the baseline table in
# gpu-5-implementation-plan.md §11. Adapted from speech-to-speech-k8s.
# All figures are empirical observations, never arithmetic derived from
# --gpu-memory-utilization (PR #15 review P1.5).
#
# Usage: ./scripts/record-vram.sh <pod-name> [container-name]
#   container-name is optional (single-container pods since the colocated
#   two-container pod was retracted — plan D2).
set -euo pipefail

NAMESPACE="${NAMESPACE:-gpu-plane}"
POD="${1:?usage: record-vram.sh <pod-name> [container-name]}"
CONTAINER="${2:-}"

ARGS=(-n "${NAMESPACE}" exec "${POD}")
if [[ -n "${CONTAINER}" ]]; then
  ARGS+=(-c "${CONTAINER}")
fi
ARGS+=(-- nvidia-smi --query-gpu=name,memory.used,memory.total --format=csv)

kubectl "${ARGS[@]}"
