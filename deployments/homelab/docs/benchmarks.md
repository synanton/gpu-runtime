# GPU-5 Benchmarks

Purpose: record the homelab baseline the GPU-6 capacity work (spec §27
T-K8S-37) and the platform retrieval benchmark
(`platform/docs/research/retrieval-evaluation-benchmark-plan.md`) will compare
against. Fill in during bring-up phases 3–8; every row needs VRAM **and** at
least one latency number.

Method:

- VRAM per pod: `./scripts/record-vram.sh <pod> [container]` (container matters
  for the colocated pod: `embedding` / `reranker`).
- Functional check per row: `./scripts/smoke-test.sh <synthesis|embed|rerank>`.
- Latency: `time` the smoke-test calls; for streaming TTFT use
  `curl -N ... "stream":true` and time-to-first-`data:` event.

## Matrix

| Row | Pod / container | Node | Model | VRAM used (MiB) | Latency | Notes |
|-----|-----------------|------|-------|-----------------|---------|-------|
| Synthesis alone | vllm-synthesis | node2 | qwen3-4b-instruct-2507 (bf16) | _ / 16380 | chat _ ms / TTFT _ ms | util 0.90, max-model-len 32768 |
| Embedding alone* | vllm-embed-rerank / embedding | node3 | qwen3-embedding-0.6b | _ / 16311 (shared) | embed _ ms | util 0.20 |
| Reranker alone* | vllm-embed-rerank / reranker | node3 | qwen3-reranker-0.6b | (same GPU) | rerank _ ms | util 0.20 + hf-overrides |
| Embedding+reranker concurrent | both containers | node3 | both | _ combined | _ | the D4 coexistence proof — run both smoke tests at once |
| Through Envoy | all three routes | via node1 | — | n/a | +_ ms overhead per route | measures the JWT perimeter's added latency |
| Through Gateway (phase 6+) | full path | — | — | n/a | _ ms end-to-end | the number platform consumers see |

\* "alone" = the only one under load; both containers stay running (that's the
deployment shape — there is no solo-embedding Deployment).

## Decision record

_Record after the concurrent row runs:_ whether 0.20/0.20 holds under parallel
load (watch for KV-cache pressure or CUDA-graph capture OOM on the second
engine), and whether embedding/reranker latency through Envoy stays small
enough relative to platform-side p95 budgets (the retrieval benchmark's §5
taxonomy) that the GPU plane isn't the bottleneck.
