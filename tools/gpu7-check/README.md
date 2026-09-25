# gpu7-check — GPU-7 live validation against a real external provider arm

Validates the GPU-7 external-provider profile end-to-end with a **real provider key**,
over the same transport the Platform uses: gRPC `synanton.gpu.v1` over **mTLS**.

Two real arms are configured. `--provider` picks one (default `GPU7_EXTERNAL_PROVIDER` from `.env`):

| Arm | Provider id | Models (overridable in `.env`) | Spend | Status (2026-09-25) |
|---|---|---|---|---|
| `opencode` (**current**) | `opencode`, https://opencode.ai/zen/v1 | chat `qwen3.8-flash` (fallback `glm-5.3-flash`), Responses `gpt-6-luna`; no embeddings/rerank | paid, cheap; estimated spend capped | 15/15, estimated $0.000063 (gateway cost ledger agrees) |
| `openrouter` | `openai`, https://openrouter.ai/api/v1 | free chat, Responses, three free EMBED arms | zero (free models only) | unreachable from this network: Cloudflare 403 "Access denied by security policy" |

Why these opencode.ai models:
- **Free tier refused:** `*-free` and `big-pickle` are refused outside the OpenCode app (403 `FreeTierError`).
- **`jev-1.13` down:** cheapest on paper ($0.04/$0.00), but its backend returned 503.
- **`gpt-6-luna` Responses-only:** $0.10/$0.50, served only on `/responses`; chat completions returns 503.
- **Chat pick:** `qwen3.8-flash` ($0.15/$0.47) is the cheapest model that answered chat completions. `glm-5.3-flash` ($0.15/$0.50) is its fallback.
- **No embeddings/rerank:** opencode.ai has neither endpoint (404), so the EMBED and benchmark-principal checks are skipped on this arm.

It checks model discovery, chat, streaming (`include_usage`), the Responses API, the rerank
capability gap, tenant authorization, logical-ID restoration (provider model IDs are
never exposed), usage capture and `upstream_request_id`.

It also checks embeddings on **every** real-arm EMBED model: the vector length must equal
the catalog's `embedding-dim`. That covers `synanton-free-embedding` and the retrieval-benchmark
arms `synanton-free-embedding-nemotron-vl` and `synanton-free-embedding-lfm` (platform benchmark
§6 Phase B1-G, G3).

When `certs/synanton-benchmark.crt` exists (`gen-certs.sh` creates it by default), it checks
the benchmark principal too. That principal may embed for its `rb-*` tenants and is denied
(`tenant_not_allowed`) for any other tenant. Last run: 20 passed, spend unchanged.

## Safety for a spend-capped key

**opencode arm (paid):**
- **Cheap-models guard.** Every catalog model of `providers.opencode` must be listed live by
  opencode.ai, match its `allowed-model-pattern` (`OPENCODE_ALLOWED_MODEL_PATTERN`), be priced
  in the catalog, and cost at most `--max-usd-per-million` (default $1/M). The tool also refuses
  free-tier models, which opencode.ai rejects outside its app.
- **Spend estimate.** opencode.ai has no usage API. Spend is estimated as the token usage
  reported through the gateway × catalog prices, and the run fails above `--max-spend-usd`
  (default $0.01).
- **Gateway budget.** The gateway's cost ledger enforces a budget too: `smoke-tenant` is capped
  at `GPU7_SMOKE_DAILY_USD` ($0.05/day).

**openrouter arm (free):**
- **Free-models guard.** Before any call, every catalog model of the real provider
  (`providers.openai` in `deployments/external/config/gateway-external.yaml`) must be
  priced **0/0** on OpenRouter's live pricing API and end in `:free`. If not, the tool
  refuses to run. (The Gateway has its own guard, `allowed-model-pattern: ".*:free"`,
  and refuses to start with a paid mapping.)
- The key's remaining budget is checked (the tool refuses within 10% of the limit).
  Spend before and after the run must be equal.
- **Keys** are read from `OPENCODE_API_KEY` / `OPENAI_API_KEY` (environment, else
  `tools/gpu7-check/.env`, else `deployments/external/.env`, all git-ignored). They are never
  printed; the tool shows only the prefix and length.

## Setup — uv venv (Python 3.12)

```bash
cd tools/gpu7-check
uv venv --python 3.12 --seed .venv        # .venv is git-ignored
source .venv/bin/activate
uv pip install -r requirements.txt
```

Put the key in `deployments/external/.env` (also used by compose), or in
`tools/gpu7-check/.env`. `--compose` also needs the dev secrets `POSTGRES_PASSWORD` and
`MOCK_PROVIDER_API_KEY`. Put them in `.env`, or export throwaway values for the run only, e.g.
`export POSTGRES_PASSWORD=$(openssl rand -hex 16) MOCK_PROVIDER_API_KEY=$(openssl rand -hex 16)`.
The packaged smoke inside `gpu7-package-check.py --live` also needs `GRPCURL` (path to grpcurl).

```bash
GPU7_EXTERNAL_PROVIDER=opencode    # arm this tool validates: opencode | openrouter
OPENCODE_PROVIDER_ENABLED=true     # compose: enable the opencode.ai arm in the Gateway
OPENCODE_API_KEY=oc_sk_…           # opencode.ai key
OPENAI_PROVIDER_ENABLED=false      # OpenRouter arm (kept configured, switched off)
OPENAI_API_KEY=sk-or-v1-…          # OpenRouter key (free models only)
```

The full list (model IDs, prices, allowlist, budgets) is in
`deployments/external/.env.example`. To switch arms, flip the `*_PROVIDER_ENABLED` flags and
`GPU7_EXTERNAL_PROVIDER`, then recreate the gateway (`docker compose up -d gateway`). The
gateway reads `.env` via `env_file`, and only variables that are set override the config
defaults. Comment out unused optional lines rather than leaving them empty.

## Run

```bash
source tools/gpu7-check/.venv/bin/activate

python tools/gpu7-check/gpu7_check.py --guard-only   # pricing guard (+ key budget on openrouter) only
python tools/gpu7-check/gpu7_check.py --provider openrouter   # validate the other arm explicitly
python tools/gpu7-check/gpu7_check.py --compose      # generate PKI if missing, start compose, check
python tools/gpu7-check/gpu7_check.py                # against an already running gateway
```

Options: `--gateway host:port` (default `localhost:9090`), `--cert-dir`
(default `deployments/external/certs`), `--client` (principal CN, default
`gpu7-smoke`), `--tenant` (default `smoke-tenant`), `--plaintext` (only for a gateway
in `insecure-plaintext` mode). The exit code is non-zero on any failure.

mTLS material comes from `deployments/external/scripts/gen-certs.sh`. See
`doc/GPU Plane mTLS Setup.md`.

## Spend / quota line for the platform harness (openrouter arm)

```bash
python tools/gpu7-check/gpu7_check.py --usage
# {"usage": 0.00052275, "usage_daily": 0, "limit": 1, ..., "free_model_daily_requests": {"used": 62, "limit": 1000, "remaining": 938}}
```

One JSON line with the key's spend and free-model quota; the key itself is never printed.
The platform retrieval harness (`retrieval-eval evaluate --gpu-plane gpu-7`) runs this before
and after each run as its `--spend-cmd`, and rejects the run if spend rose. The provider key
therefore stays in this repo's tooling and never enters the platform.

## Embedding probe (retrieval benchmark G0)

`embed_probe.py` characterises the free OpenRouter embedding models for the platform
retrieval benchmark (platform `docs/research/retrieval-evaluation-benchmark-plan.md`,
§6 Phase B1-G, step G0). It reports:

- the key's free-model daily quota and spend;
- each model's native dimension;
- whether the provider honours the `dimensions` field;
- a Matryoshka check: retrieval quality and neighbour order on the platform demo corpus
  when vectors are cut to 1024/768/512/384 dims and L2-renormalised. Lucene 9.11 caps
  KNN vectors at 1024 dims.

The probe calls OpenRouter directly, since it measures the provider models themselves.
It uses the same free-models guard and key handling as `gpu7_check.py`, and fails if
spend changes. Each model costs 2 free requests.

```bash
python tools/gpu7-check/embed_probe.py \
  --out ../platform/demo-data/eval/retrieval-benchmark/results/G0-embed-probe.json
```

## Changing models

**opencode.ai:** list the live models with
`curl -s -H "Authorization: Bearer $OPENCODE_API_KEY" -H "User-Agent: synanton/1.0" https://opencode.ai/zen/v1/models`.
Then:
1. Set `OPENCODE_CHAT_MODEL` / `OPENCODE_CHAT_FALLBACK_MODEL` / `OPENCODE_RESPONSES_MODEL`, plus their `*_USD_PER_M` prices (they feed the cost ledger and budget), in `.env`.
2. Extend `OPENCODE_ALLOWED_MODEL_PATTERN` if needed.
3. Run `--guard-only`, then recreate the gateway.

**OpenRouter free models:**

OpenRouter's free catalog changes over time. List the current free models:

```bash
curl -s https://openrouter.ai/api/v1/models | python3 -c "import sys,json;[print(m['id']) for m in json.load(sys.stdin)['data'] if m['id'].endswith(':free')]"
curl -s https://openrouter.ai/api/v1/embeddings/models | python3 -c "import sys,json;[print(m['id']) for m in json.load(sys.stdin)['data'] if m['id'].endswith(':free')]"
```

Set `OPENROUTER_*_MODEL` in `.env` (defaults are in `gateway-external.yaml`), re-run
`--guard-only`, and recreate the gateway.
