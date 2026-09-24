#!/usr/bin/env bash
# Apply the GPU-5 blueprints in the order gpu-5-implementation-plan.md §7 describes.
# Idempotent (kubectl apply) — safe to re-run after edits.
#
# Usage: ./scripts/deploy.sh [phase]
#   phase: all | scaffolding | postgres | inference | gateway | envoy | policies
#   (the phase formerly named "vllm" is now "inference" — it also deploys TEI)
# Secrets (registry cred, postgres cred, JWT keys, pepper) are created
# imperatively beforehand — see plan §4/§7. Model downloads (plan §4.5) and
# image mirroring (plan §5) are manual operations, not part of this script.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BP="${ROOT_DIR}/blueprints"
PHASE="${1:-all}"

scaffolding() {
  echo "== namespace"
  kubectl apply -f "${BP}/namespace.yaml"
  echo "NOTE: run scripts/create-registry-secret.sh separately if you haven't yet."
}

postgres() {
  echo "== postgres (node1)"
  kubectl apply -f "${BP}/postgres/postgres.yaml"
}

inference() {
  # one inference workload per physical GPU (plan D2, PR #15 review P0.1)
  echo "== tei-embedding (node1) + vllm-reranker (node2) + vllm-synthesis (node3)"
  kubectl apply -f "${BP}/tei/embedding.yaml"
  kubectl apply -f "${BP}/vllm/reranker.yaml"
  kubectl apply -f "${BP}/vllm/synthesis.yaml"
}

gateway() {
  echo "== gpu-gateway (node1)"
  kubectl apply -f "${BP}/gateway/gateway.yaml"
}

envoy() {
  echo "== envoy execution perimeter (node1)"
  kubectl apply -f "${BP}/envoy/envoy-config.yaml"
  kubectl apply -f "${BP}/envoy/envoy.yaml"
}

policies() {
  echo "== network policies"
  kubectl apply -f "${BP}/network-policy/network-policies.yaml"
}

case "${PHASE}" in
  all) scaffolding; postgres; inference; gateway; envoy; policies ;;
  scaffolding) scaffolding ;;
  postgres) postgres ;;
  inference) inference ;;
  vllm) echo "phase 'vllm' was renamed to 'inference'" >&2; inference ;;
  gateway) gateway ;;
  envoy) envoy ;;
  policies) policies ;;
  *) echo "unknown phase: ${PHASE}" >&2; exit 1 ;;
esac
