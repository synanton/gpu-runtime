#!/usr/bin/env bash
# Apply the GPU-5 blueprints in the order gpu-5-implementation-plan.md §7 describes.
# Idempotent (kubectl apply) — safe to re-run after edits.
#
# Usage: ./scripts/deploy.sh [phase]
#   phase: all | scaffolding | postgres | vllm | gateway | envoy | policies
# Secrets (registry cred, postgres cred, JWT keys, pepper) are created
# imperatively beforehand — see plan §4/§7.
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

vllm() {
  echo "== vllm-synthesis (node2) + vllm-embed-rerank (node3)"
  kubectl apply -f "${BP}/vllm/synthesis.yaml"
  kubectl apply -f "${BP}/vllm/embedding-reranker.yaml"
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
  all) scaffolding; postgres; vllm; gateway; envoy; policies ;;
  scaffolding) scaffolding ;;
  postgres) postgres ;;
  vllm) vllm ;;
  gateway) gateway ;;
  envoy) envoy ;;
  policies) policies ;;
  *) echo "unknown phase: ${PHASE}" >&2; exit 1 ;;
esac
