# GPU-5 Observability

Normative requirements: spec §20 (zero-prompt), §20a (rate-limit metrics),
§21 (request IDs). This file is the homelab how-to.

## Request IDs (spec §21)

Every Gateway response carries `x-request-id`; a valid client-supplied value is
preserved, missing ones are UUIDv4. Verify:

```bash
curl -s -D - -o /dev/null -H "Authorization: Bearer $KEY" \
  -H "x-request-id: gpu5-check-1" http://<gateway>/v1/models | grep -i x-request-id
```

Envoy forwards the header to vLLM (jwt_authn `forward: true` covers the JWT
payload; headers pass by default), so one ID correlates Gateway → Envoy →
vLLM log lines. vLLM logs its own request lines with the served model name —
join on ID + timestamp.

## Zero-prompt logging (spec §20, T-K8S-12)

Logs, metrics, traces and error bodies must never contain prompts,
completions, API keys, provider secrets, or token contents. Concretely here:

- vLLM logs request *metadata* by default, not payloads — do not enable
  `--enable-log-requests` / debug logging on the inference containers.
- Envoy `info` level (as shipped) logs connection/route metadata only.
- Gateway: prompts must not appear at any level; T-K8S-12's verification greps
  active + rotated logs against canary prompt strings as part of acceptance.

## Metrics

Gateway exposes Prometheus on :8091 (pod annotations
`prometheus.io/scrape: "true"` are already in the blueprint). Required series
(§20a): `gpu_gateway_rate_limit_total`, `gpu_gateway_rate_limit_remaining_*`,
`gpu_gateway_429_total`. No tenant label until cardinality is validated
(T-K8S-9a).

vLLM exposes its own `/metrics` per container (port 8000/8001) with GPU
utilization, KV-cache usage, queue depth — scrape directly from the pods for
the §11 baseline table instead of relying on one-off `nvidia-smi` reads.

## What is deliberately absent

No tracing pipeline and no log aggregation in GPU-5 (single namespace, four
nodes — `kubectl logs` is sufficient). Dashboards/alerting are GPU-6
(T-K8S-29/35).
