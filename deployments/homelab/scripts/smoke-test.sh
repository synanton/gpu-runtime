#!/usr/bin/env bash
# Per-service smoke tests for GPU-5 bring-up phases. Run via kubectl
# port-forward so it works from outside the cluster (no in-cluster curl pod
# needed). Adapted from speech-to-speech-k8s/scripts/smoke-test.sh.
#
# Usage: ./scripts/smoke-test.sh <synthesis|embed|rerank|envoy|gateway>
#
#   synthesis  phase 3 - chat completion against vllm-synthesis (node3)
#   embed      phase 3 - embeddings against tei-embedding (node1)
#   rerank     phase 3 - rerank against vllm-reranker (node2)
#   envoy      phase 5 - NEGATIVE: unsigned request must be rejected (401)
#   gateway    phase 4+ - GetModels over the platform transport (gRPC :9090, needs grpcurl)
set -euo pipefail

NAMESPACE="${NAMESPACE:-gpu-plane}"
CMD="${1:-}"
shift || true

pf() {
  local svc="$1" local_port="$2" remote_port="$3"
  # Redirect stdout/stderr so this long-running background process doesn't
  # inherit the pipe backing `pid=$(pf ...)` — otherwise that command
  # substitution blocks forever waiting for EOF that never comes.
  kubectl -n "${NAMESPACE}" port-forward "svc/${svc}" "${local_port}:${remote_port}" >/dev/null 2>&1 &
  local pid=$!
  sleep 2
  echo "${pid}"
}

case "${CMD}" in
  synthesis)
    pid=$(pf vllm-synthesis 18000 8000)
    trap 'kill "${pid}" 2>/dev/null || true' EXIT
    echo "-- /health"
    curl -sf http://localhost:18000/health
    echo
    echo "-- chat completion (synanton-qwen3-4b-synthesis)"
    curl -sf http://localhost:18000/v1/chat/completions \
      -H 'Content-Type: application/json' \
      -d '{"model":"synanton-qwen3-4b-synthesis","messages":[{"role":"user","content":"Say OK"}],"max_tokens":8}'
    echo
    ;;
  embed)
    pid=$(pf tei-embedding 18001 8000)
    trap 'kill "${pid}" 2>/dev/null || true' EXIT
    echo "-- embeddings (synanton-bge-base-embedding, TEI on node1)"
    curl -sf http://localhost:18001/v1/embeddings \
      -H 'Content-Type: application/json' \
      -d '{"model":"synanton-bge-base-embedding","input":"hello"}' | head -c 400
    echo
    ;;
  rerank)
    pid=$(pf vllm-reranker 18002 8000)
    trap 'kill "${pid}" 2>/dev/null || true' EXIT
    echo "-- rerank (synanton-qwen3-reranker-0.6b)"
    curl -sf http://localhost:18002/v1/rerank \
      -H 'Content-Type: application/json' \
      -d '{"model":"synanton-qwen3-reranker-0.6b","query":"ping","documents":["pong","unrelated document"]}'
    echo
    ;;
  envoy)
    # spec §6: Envoy MUST NOT accept unauthenticated execution traffic
    pid=$(pf envoy 18080 8080)
    trap 'kill "${pid}" 2>/dev/null || true' EXIT
    code="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:18080/v1/chat/completions \
      -H 'Content-Type: application/json' -d '{}')"
    if [[ "${code}" == "401" ]]; then
      echo "-- unsigned request rejected with 401 OK"
    else
      echo "!! expected 401 for unsigned request, got ${code}" >&2
      exit 1
    fi
    ;;
  gateway)
    # platform transport is gRPC synanton.gpu.v1 (Deployment Plan §4)
    pid=$(pf gpu-gateway 19090 9090)
    trap 'kill "${pid}" 2>/dev/null || true' EXIT
    echo "-- GetModels (SYNTHESIZE) through the Gateway"
    "$(dirname "${BASH_SOURCE[0]}")/../../../tools/gpu-grpc-call.sh" localhost:19090 GetModels \
      '{"operation":"SYNTHESIZE"}'
    ;;
  *)
    echo "usage: $0 <synthesis|embed|rerank|envoy|gateway>" >&2
    exit 1
    ;;
esac
