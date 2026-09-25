# GPU-5 Benchmarks

Purpose: record the homelab baseline the GPU-6 capacity work (spec §27
T-K8S-37) and the platform retrieval benchmark
(`platform/docs/research/retrieval-evaluation-benchmark-plan.md`) will compare
against. Fill in during bring-up phases 3–8; every row needs VRAM **and** at
least one latency number. All VRAM figures are empirical observations, never
arithmetic derived from `--gpu-memory-utilization` (PR #15 review P1.5).

Method:

- VRAM per pod: `./scripts/record-vram.sh <pod>` (all inference pods are
  single-container since the colocated pod was retracted — plan D2).
- Functional check per row: `./scripts/smoke-test.sh <synthesis|embed|rerank>`.
- Latency: `time` the smoke-test calls; for streaming TTFT use
  `curl -N ... "stream":true` and time-to-first-`data:` event.

## Matrix

| Row | Pod | Node | Model | VRAM used (MiB) | Latency | Notes |
|-----|-----|------|-------|-----------------|---------|-------|
| Synthesis | vllm-synthesis | node3 | qwen3-4b-instruct-2507 (bf16) | _ / 16311 | chat _ ms / TTFT _ ms | util 0.90, max-model-len 32768 |
| Embedding | tei-embedding | node1 | bge-base-en-v1.5 (fp16) | _ / 4096 | embed _ ms, _ embeddings/s | TEI turing, flash-attn off |
| Reranker | vllm-reranker | node2 | qwen3-reranker-0.6b | _ / 16380 | rerank _ ms | util 0.40 + hf-overrides |
| All three concurrent | all pods | node1+2+3 | all | _ per pod | _ | one workload per GPU — concurrency is cross-node, not in-GPU |
| Through Envoy | all three routes | via node1 | — | n/a | +_ ms overhead per route | measures the JWT perimeter's added latency |
| Through Gateway (phase 6+) | full path | — | — | n/a | _ ms end-to-end | the number platform consumers see |

Embedding row acceptance detail (PR #15 review §3.1): use 512-token inputs and
a representative batch/concurrency level; record p50/p95 latency and
embeddings/sec in addition to the single-call number.

## Decision record

_Record after bring-up:_ per-workload numbers above, plus the end-to-end
embedding → retrieval → reranking → synthesis pipeline latency through the
Gateway (review §3.4) — the figure that shows whether the GPU plane stays
small enough relative to platform-side p95 budgets (the retrieval benchmark's
§5 taxonomy) that it isn't the bottleneck.
