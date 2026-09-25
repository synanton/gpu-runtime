#!/usr/bin/env bash
# GPU-7 smoke test over the platform transport — gRPC synanton.gpu.v1
# (Deployment Plan §4) — against the in-compose mock provider (§37 subset).
#
# Run from deployments/external/ with the compose stack up:
#   docker compose up -d && ./scripts/smoke-test.sh
# Requires: grpcurl (https://github.com/fullstorydev/grpcurl/releases), python3, and the
# self-signed dev PKI (./scripts/gen-certs.sh) — the gateway only speaks mTLS.
# Model IDs are the LOGICAL catalog IDs from config/gateway-external.yaml. The mock
# runs with MOCK_STRICT_MODELS=1: it 404s any model except the provider IDs
# (mock-*-1), so each SUCCESS proves the logical → provider rewrite upstream, and the
# result checks prove provider IDs never leak downstream (PR #15 P1.1).
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

# request <request_id> <logical model> <OPERATION> <payload-json> [tenant] [data_tags-json-array]
request() {
  printf '{"request_id":"%s","tenant_id":"%s","model":"%s","model_version":"1","operation":"%s","payload":"%s","data_tags":%s}' \
    "$1" "${5:-smoke-tenant}" "$2" "$3" "$(payload_b64 "$4")" "${6:-[]}"
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
[[ "$(field 'r.get("upstreamRequestId","")' <<<"$out")" == "$RUN-chat" ]] \
  && ok "provider request ID preserved (upstream_request_id)" || bad "upstream_request_id" "$out"

out="$(call Execute "$(request "$RUN-embed" synanton-mock-embedding EMBED \
  '{"model":"synanton-mock-embedding","input":"hello"}')")"
[[ "$(field 'r.get("state")' <<<"$out")" == "SUCCESS" ]] && ok "embeddings → SUCCESS" || bad "embeddings" "$out"

out="$(call Execute "$(request "$RUN-rerank" synanton-mock-reranker RERANK \
  '{"model":"synanton-mock-reranker","query":"q","documents":["a","b"]}')")"
[[ "$(field 'r.get("state")' <<<"$out")" == "SUCCESS" ]] && ok "rerank (configured) → SUCCESS" || bad "rerank" "$out"

echo "== streaming (ExecuteStream, Plan §10): data chunks, exactly one terminal, include_usage"
out="$(call ExecuteStream "$(request "$RUN-stream" synanton-mock-chat SYNTHESIZE \
  '{"model":"synanton-mock-chat","messages":[{"role":"user","content":"hi"}],"stream_options":{"include_usage":true}}')")"
verdict="$(python3 -c '
import sys, json, base64
dec = json.JSONDecoder(); raw = sys.stdin.read().strip(); msgs = []; i = 0
while i < len(raw):
    while i < len(raw) and raw[i].isspace(): i += 1
    if i >= len(raw): break
    obj, i = dec.raw_decode(raw, i); msgs.append(obj)
data = [json.loads(base64.b64decode(m["data"])) for m in msgs if "data" in m]
terms = [m["terminal"] for m in msgs if "terminal" in m]
ok = (len(data) >= 1 and len(terms) == 1 and "terminal" in msgs[-1]
      and all(d.get("model") == "synanton-mock-chat" for d in data if "model" in d)
      and not any("mock-chat-1" in json.dumps(d) for d in data)
      and any(d.get("usage") for d in data)
      and terms[0].get("state") == "SUCCESS" and int(terms[0].get("usage", {}).get("inputTokens", "0")) > 0)
print("OK" if ok else "BAD data=%d terms=%d" % (len(data), len(terms)))' <<<"$out")"
[[ "$verdict" == OK ]] && ok "stream: chunks carry logical ID, usage chunk, exactly one terminal with usage" || bad "stream" "$verdict $out"
out="$(GRPCURL_EXTRA="-v" call ExecuteStream "$(request "$RUN-stream-embed" synanton-mock-embedding EMBED '{"input":"x"}')")"
[[ "$out" == *capability_not_supported* ]] && ok "EMBED on ExecuteStream → capability_not_supported" || bad "non-streamable op" "$out"

echo "== negative paths (canonical denials, fail closed)"
out="$(GRPCURL_EXTRA="-v" call Execute "$(request "$RUN-nope" no-such-model SYNTHESIZE '{"model":"no-such-model"}')")"
[[ "$out" == *NotFound* && "$out" == *model_not_found* ]] && ok "unknown model → NOT_FOUND model_not_found" || bad "unknown model" "$out"

out="$(GRPCURL_EXTRA="-v" call Execute "$(request "$RUN-cap" synanton-mock-chat RERANK '{"query":"q","documents":["a"]}')")"
[[ "$out" == *capability_not_supported* ]] && ok "chat model on RERANK → capability_not_supported" || bad "capability gap" "$out"

out="$(GRPCURL_EXTRA="-v" call Execute "$(request "$RUN-local" synanton-mock-chat SYNTHESIZE '{"model":"x"}' | sed 's/}$/,"provider":"LOCAL"}/')")"
[[ "$out" == *PermissionDenied* && "$out" == *no_local_fallback* ]] && ok "LOCAL request in external mode → no_local_fallback" || bad "no local fallback" "$out"

req="$(request "$RUN-idem" synanton-mock-chat SYNTHESIZE '{"model":"synanton-mock-chat","messages":[{"role":"user","content":"a"}]}')"
call Execute "$req" >/dev/null
out="$(call Execute "$(request "$RUN-idem" synanton-mock-chat SYNTHESIZE '{"model":"synanton-mock-chat","messages":[{"role":"user","content":"DIFFERENT"}]}')")"
[[ "$out" == *InvalidArgument* ]] && ok "request_id reuse with different payload → INVALID_ARGUMENT" || bad "idempotency conflict" "$out"

if [[ "${SMOKE_REAL_PROVIDER:-0}" == "1" ]]; then
  # Opt-in real external provider arm (OpenRouter FREE models only — the gateway refuses
  # to start otherwise: providers.openai.allowed-model-pattern). Needs OPENAI_API_KEY and
  # OPENAI_PROVIDER_ENABLED=true in .env. Four calls; free models are rate-limited.
  echo "== real provider arm (OpenRouter free models)"
  out="$(call Execute "$(request "$RUN-real-chat" synanton-free-chat SYNTHESIZE \
    '{"model":"synanton-free-chat","messages":[{"role":"user","content":"Reply with exactly: OK"}],"max_tokens":16}')")"
  [[ "$(field 'r.get("state")' <<<"$out")" == "SUCCESS" ]] && ok "real chat → SUCCESS" || bad "real chat" "$out"
  [[ "$(field 'json.loads(res)["model"] if res else ""' <<<"$out")" == "synanton-free-chat" \
     && "$(field '":free" in res' <<<"$out")" == "False" ]] \
    && ok "real chat: logical ID restored, provider ID never exposed" || bad "real chat model ID" "$out"
  out="$(call ExecuteStream "$(request "$RUN-real-stream" synanton-free-chat SYNTHESIZE \
    '{"model":"synanton-free-chat","messages":[{"role":"user","content":"Count 1 to 3"}],"max_tokens":24}')")"
  [[ "$out" == *'"terminal"'* && "$out" == *'"state": "SUCCESS"'* ]] && ok "real stream → terminal SUCCESS" || bad "real stream" "$out"
  out="$(call Execute "$(request "$RUN-real-embed" synanton-free-embedding EMBED \
    '{"model":"synanton-free-embedding","input":"hello"}')")"
  [[ "$(field 'len(json.loads(res)["data"][0]["embedding"]) if res else 0' <<<"$out")" == "2048" ]] \
    && ok "real embeddings → 2048-dim vector" || bad "real embeddings" "$out"
  out="$(GRPCURL_EXTRA="-v" call Execute "$(request "$RUN-real-rerank" synanton-free-chat RERANK '{"query":"q","documents":["a"]}')")"
  [[ "$out" == *capability_not_supported* ]] && ok "real arm has no rerank → capability_not_supported" || bad "real rerank gap" "$out"
fi

echo "== caller authentication and tenant authorization (mTLS, Plan §13)"
out="$(GRPCURL_EXTRA="-v" call Execute "$(request "$RUN-tenant" synanton-mock-chat SYNTHESIZE '{"messages":[]}' someone-elses-tenant)")"
[[ "$out" == *PermissionDenied* && "$out" == *tenant_not_allowed* ]] \
  && ok "principal asserting another tenant → tenant_not_allowed" || bad "tenant authorization" "$out"
out="$(GPU_GRPC_PLAINTEXT=1 call GetModels '{"operation":"SYNTHESIZE"}')"
[[ "$out" != *synanton-mock-chat* ]] && ok "plaintext (no client certificate) is refused" || bad "plaintext accepted" "$out"

echo "== GPU-7 controls (sensitivity, budget; fail closed)"
out="$(GRPCURL_EXTRA="-v" call Execute "$(request "$RUN-sens" synanton-mock-chat-sensitive SYNTHESIZE '{"messages":[]}')")"
[[ "$out" == *PermissionDenied* && "$out" == *sensitive_model_external_blocked* ]] \
  && ok "sensitive-tagged model → sensitive_model_external_blocked" || bad "sensitive model" "$out"
out="$(GRPCURL_EXTRA="-v" call Execute "$(request "$RUN-pii" synanton-mock-chat SYNTHESIZE '{"messages":[]}' smoke-tenant '["pii"]')")"
[[ "$out" == *sensitive_model_external_blocked* ]] && ok "request data_tags [pii] → external routing denied" || bad "pii data tag" "$out"
# smoke-budget-tenant has a 0.000001 USD/day budget: exhausted by at most one mock call
denied=""
for n in 1 2; do
  out="$(GRPCURL_EXTRA="-v" call Execute "$(request "$RUN-budget-$n" synanton-mock-chat SYNTHESIZE \
    '{"model":"synanton-mock-chat","messages":[{"role":"user","content":"hi"}]}' smoke-budget-tenant)")"
  [[ "$out" == *ResourceExhausted* && "$out" == *budget_exceeded* ]] && { denied=yes; break; }
done
[[ -n "$denied" ]] && ok "tenant budget exhausted → RESOURCE_EXHAUSTED budget_exceeded" || bad "budget" "$out"

echo "== $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
