#!/usr/bin/env bash
# GPU-7 smoke test over the platform transport — gRPC synanton.gpu.v1
# (Deployment Plan §4) — against the in-compose mock provider (§37 subset).
#
# Run from deployments/external/ with the compose stack up:
#   docker compose up -d && ./scripts/smoke-test.sh
# Requires: grpcurl (https://github.com/fullstorydev/grpcurl/releases), python3.
# Model IDs are the LOGICAL catalog IDs from config/gateway-external.yaml; the mock
# provider only knows the provider IDs (mock-*-1), so every PASS below also proves
# the logical → provider rewrite and that provider IDs never leak downstream.
set -euo pipefail

GW="${GPU_GATEWAY_GRPC:-localhost:9090}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CALL="$ROOT/tools/gpu-grpc-call.sh"
# shellcheck source=../../../tools/gpu-grpc-call.sh
source "$CALL"
PASS=0; FAIL=0
RUN="smoke-$(date +%s)-$$"

ok()  { echo "PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "FAIL  $1${2:+ — $2}"; FAIL=$((FAIL+1)); }

# request <request_id> <logical model> <OPERATION> <payload-json>
request() {
  printf '{"request_id":"%s","tenant_id":"smoke-tenant","model":"%s","model_version":"1","operation":"%s","payload":"%s"}' \
    "$1" "$2" "$3" "$(payload_b64 "$4")"
}
call() { "$CALL" "$GW" "$@" 2>&1 || true; }

# json-field <expr> — evaluate a python expression over the Execute response `r`
# (result bytes decoded as `res`) and print it.
field() {
  python3 -c '
import sys, json, base64
raw = sys.stdin.read()
try:
    r = json.loads(raw)
except Exception:
    print("<non-json:" + raw.strip()[:200] + ">"); sys.exit(0)
res = base64.b64decode(r.get("result", "")).decode() if r.get("result") else ""
print(eval(sys.argv[1]))' "$1"
}

echo "== model discovery (GetModels)"
out="$(call GetModels '{"operation":"SYNTHESIZE"}')"
[[ "$out" == *synanton-mock-chat* ]] && ok "GetModels lists logical synanton-mock-chat" || bad "GetModels" "$out"
[[ "$out" != *mock-chat-1* ]] && ok "GetModels never exposes provider model ID" || bad "provider ID leaked in GetModels"

echo "== positive paths (Execute → ProviderRouter → mock provider)"
out="$(call Execute "$(request "$RUN-chat" synanton-mock-chat SYNTHESIZE \
  '{"model":"synanton-mock-chat","messages":[{"role":"user","content":"hi"}]}')")"
[[ "$(field 'r.get("state")' <<<"$out")" == "SUCCESS" ]] && ok "chat → SUCCESS" || bad "chat" "$out"
[[ "$(field 'json.loads(res)["model"] if res else ""' <<<"$out")" == "synanton-mock-chat" ]] \
  && ok "chat result carries logical model ID" || bad "chat logical model ID" "$out"
[[ "$(field '"mock-chat-1" in res' <<<"$out")" == "False" ]] \
  && ok "chat result never contains provider model ID" || bad "provider ID leaked in chat result"
[[ "$(field 'int(r.get("usage",{}).get("inputTokens","0"))>0' <<<"$out")" == "True" ]] \
  && ok "provider usage captured" || bad "usage" "$out"

out="$(call Execute "$(request "$RUN-embed" synanton-mock-embedding EMBED \
  '{"model":"synanton-mock-embedding","input":"hello"}')")"
[[ "$(field 'r.get("state")' <<<"$out")" == "SUCCESS" ]] && ok "embeddings → SUCCESS" || bad "embeddings" "$out"

out="$(call Execute "$(request "$RUN-rerank" synanton-mock-reranker RERANK \
  '{"model":"synanton-mock-reranker","query":"q","documents":["a","b"]}')")"
[[ "$(field 'r.get("state")' <<<"$out")" == "SUCCESS" ]] && ok "rerank (configured) → SUCCESS" || bad "rerank" "$out"

echo "== negative paths (canonical denials, fail closed)"
out="$(GRPCURL_FLAGS="-plaintext -v" call Execute "$(request "$RUN-nope" no-such-model SYNTHESIZE '{"model":"no-such-model"}')")"
[[ "$out" == *NotFound* && "$out" == *model_not_found* ]] && ok "unknown model → NOT_FOUND model_not_found" || bad "unknown model" "$out"

out="$(GRPCURL_FLAGS="-plaintext -v" call Execute "$(request "$RUN-cap" synanton-mock-chat RERANK '{"query":"q","documents":["a"]}')")"
[[ "$out" == *capability_not_supported* ]] && ok "chat model on RERANK → capability_not_supported" || bad "capability gap" "$out"

out="$(GRPCURL_FLAGS="-plaintext -v" call Execute "$(request "$RUN-local" synanton-mock-chat SYNTHESIZE '{"model":"x"}' | sed 's/}$/,"provider":"LOCAL"}/')")"
[[ "$out" == *PermissionDenied* && "$out" == *no_local_fallback* ]] && ok "LOCAL request in external mode → no_local_fallback" || bad "no local fallback" "$out"

req="$(request "$RUN-idem" synanton-mock-chat SYNTHESIZE '{"model":"synanton-mock-chat","messages":[{"role":"user","content":"a"}]}')"
call Execute "$req" >/dev/null
out="$(call Execute "$(request "$RUN-idem" synanton-mock-chat SYNTHESIZE '{"model":"synanton-mock-chat","messages":[{"role":"user","content":"DIFFERENT"}]}')")"
[[ "$out" == *InvalidArgument* ]] && ok "request_id reuse with different payload → INVALID_ARGUMENT" || bad "idempotency conflict" "$out"

echo "== $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
