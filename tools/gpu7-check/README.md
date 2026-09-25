# gpu7-check — GPU-7 live validation (OpenRouter free models only)

Validates the GPU-7 external-provider profile end-to-end with a **real provider key**,
over the same transport the Platform uses: gRPC `synanton.gpu.v1` over **mTLS**.

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

- **Free-models guard.** Before any call, every catalog model of the real provider
  (`providers.openai` in `deployments/external/config/gateway-external.yaml`) must be
  priced **0/0** on OpenRouter's live pricing API and end in `:free`. If not, the tool
  refuses to run. (The Gateway has its own guard, `allowed-model-pattern: ".*:free"`,
  and refuses to start with a paid mapping.)
- The key's remaining budget is checked (the tool refuses within 10% of the limit).
  Spend before and after the run must be equal.
- The key is read from `OPENAI_API_KEY`, else `tools/gpu7-check/.env`, else
  `deployments/external/.env`. All of these are git-ignored. The key is never printed
  (only its prefix and length).

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
OPENAI_API_KEY=sk-or-v1-…          # OpenRouter key (free models only)
OPENAI_PROVIDER_ENABLED=true       # compose: enable the real arm in the Gateway
```

## Run

```bash
source tools/gpu7-check/.venv/bin/activate

python tools/gpu7-check/gpu7_check.py --guard-only   # pricing guard + key budget only
python tools/gpu7-check/gpu7_check.py --compose      # generate PKI if missing, start compose, check
python tools/gpu7-check/gpu7_check.py                # against an already running gateway
```

Options: `--gateway host:port` (default `localhost:9090`), `--cert-dir`
(default `deployments/external/certs`), `--client` (principal CN, default
`gpu7-smoke`), `--tenant` (default `smoke-tenant`), `--plaintext` (only for a gateway
in `insecure-plaintext` mode). The exit code is non-zero on any failure.

mTLS material comes from `deployments/external/scripts/gen-certs.sh`. See
`doc/GPU Plane mTLS Setup.md`.

## Spend / quota line for the platform harness

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

## Changing the free models

OpenRouter's free catalog changes over time. List the current free models:

```bash
curl -s https://openrouter.ai/api/v1/models | python3 -c "import sys,json;[print(m['id']) for m in json.load(sys.stdin)['data'] if m['id'].endswith(':free')]"
curl -s https://openrouter.ai/api/v1/embeddings/models | python3 -c "import sys,json;[print(m['id']) for m in json.load(sys.stdin)['data'] if m['id'].endswith(':free')]"
```

Update the `provider-model-id` of `synanton-free-chat` / `synanton-free-embedding` in
`gateway-external.yaml`, restart the Gateway, and re-run `--guard-only` first.
