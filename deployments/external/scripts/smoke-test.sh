#!/usr/bin/env bash
# GPU-7 smoke test — positive and negative paths against the Gateway (spec §37 subset).
#
# STATUS (PR #15 review): this targets the public OpenAI-compatible HTTP face
# (:8080 /v1/...), which the current gateway build does NOT serve yet — the live
# surface today is gRPC :9090 (Execute/Cancel/GetStatus/GetCapacity/GetModels).
# The routing/runtime layer it exercises (provider registry, mock dispatch,
# model-ID rewriting, SSE normalization, circuit breaker, kill switch) is
# implemented and unit-tested in java/gpu-gateway. Run this script once the
# HTTP-face ticket lands; until then it is the phase-4 acceptance target, not a
# runnable check.
#
# Model IDs below match config/gateway-external.yaml (catalog) — keep in sync.
#
# Run from deployments/external/ with the compose stack up:
#   docker compose up -d && ./scripts/smoke-test.sh
# Requires: .env sourced (GPU_DEV_API_KEY), curl, python3.
set -euo pipefail

GW="${GPU_GATEWAY_URL:-http://localhost:8080}"
KEY="${GPU_DEV_API_KEY:?source .env first}"
PASS=0; FAIL=0

check() { # name expected_code actual_code
  if [[ "$2" == "$3" ]]; then echo "PASS  $1"; PASS=$((PASS+1));
  else echo "FAIL  $1 (want $2, got $3)"; FAIL=$((FAIL+1)); fi
}

code() { # method path json-body [extra curl args...]
  local method="$1" path="$2" body="${3:-}"; shift 3 || true
  if [[ -n "$body" ]]; then
    curl -s -o /tmp/gpu7-smoke.json -w '%{http_code}' -X "$method" "$GW$path" \
      -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' "$@" -d "$body"
  else
    curl -s -o /tmp/gpu7-smoke.json -w '%{http_code}' -X "$method" "$GW$path" \
      -H "Authorization: Bearer $KEY" "$@"
  fi
}

echo "== positive paths (mock provider)"
check "models list"            200 "$(code GET  /v1/models)"
check "chat completions"       200 "$(code POST /v1/chat/completions '{"model":"mock-chat-1","messages":[{"role":"user","content":"hi"}]}')"
check "embeddings"             200 "$(code POST /v1/embeddings '{"model":"mock-embedding-1","input":"hello"}')"
check "rerank (configured)"    200 "$(code POST /v1/rerank '{"model":"mock-reranker-1","query":"q","documents":["a","b"]}')"

echo "== streaming: [DONE] exactly once, terminal usage present (§10)"
curl -s -N -X POST "$GW/v1/chat/completions" -H "Authorization: Bearer $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"model":"mock-chat-1","messages":[{"role":"user","content":"hi"}],"stream":true,"stream_options":{"include_usage":true}}' \
  > /tmp/gpu7-sse.txt
[[ "$(grep -c '^data: \[DONE\]$' /tmp/gpu7-sse.txt)" == "1" ]] \
  && { echo "PASS  [DONE] once"; PASS=$((PASS+1)); } || { echo "FAIL  [DONE] count"; FAIL=$((FAIL+1)); }
grep -q '"usage"' /tmp/gpu7-sse.txt \
  && { echo "PASS  terminal usage"; PASS=$((PASS+1)); } || { echo "FAIL  terminal usage"; FAIL=$((FAIL+1)); }

echo "== negative paths (§16 envelope)"
check "invalid api key"        401 "$(curl -s -o /tmp/gpu7-smoke.json -w '%{http_code}' -H 'Authorization: Bearer wrong' $GW/v1/models)"
python3 -c "import json;e=json.load(open('/tmp/gpu7-smoke.json'))['error'];assert e['code']=='invalid_api_key'" \
  && { echo "PASS  error code invalid_api_key"; PASS=$((PASS+1)); } || { echo "FAIL  error code"; FAIL=$((FAIL+1)); }
check "unknown model"          404 "$(code POST /v1/embeddings '{"model":"nope","input":"x"}')"
check "request-id echo"        200 "$(code GET /v1/models '' -H 'x-request-id: gpu7-smoke-1')"
grep -q gpu7-smoke-1 <(curl -s -D - -o /dev/null -H "Authorization: Bearer $KEY" -H 'x-request-id: gpu7-smoke-1' "$GW/v1/models") \
  && { echo "PASS  x-request-id preserved"; PASS=$((PASS+1)); } || { echo "FAIL  x-request-id"; FAIL=$((FAIL+1)); }

echo "== $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
