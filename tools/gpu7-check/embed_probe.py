#!/usr/bin/env python3
"""G0 embedding probe: characterises OpenRouter FREE embedding models for the retrieval benchmark.

Platform retrieval benchmark plan, §6 Phase B1-G, step G0
(platform/docs/research/retrieval-evaluation-benchmark-plan.md). It answers:

  1. Key limits: free-model daily request quota and spend (the key is never printed).
  2. Native embedding dimension and per-model context behaviour.
  3. Whether the provider honours the OpenAI `dimensions` request field.
  4. Matryoshka check: when a native vector is cut to 1024/768/512/384 dims and
     L2-renormalised, does retrieval over the demo corpus keep its quality and its
     neighbour order? Lucene 9.11 caps KNN vectors at 1024 dims, and the free models
     return 2048.

The probe calls OpenRouter directly rather than through the Gateway. It measures the
provider models themselves, and only one of them has a catalog entry yet (that is G3).
The Gateway passes EMBED payloads through except for `model`
(OpenAiProviderRuntime.rewritePayloadForProvider), so a `dimensions` field behaves the
same way through the GPU plane.

Safety: only `*:free` models priced 0 on OpenRouter's live listing are called. Spend must
be unchanged afterwards. Cost is 2 requests per model: one batched corpus+query call and
one `dimensions` call.

Usage (inside the tools/gpu7-check uv venv):
  python embed_probe.py --out ../../../platform/demo-data/eval/retrieval-benchmark/results/G0-embed-probe.json
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from gpu7_check import OPENROUTER, load_key, masked, openrouter_get

ROOT = Path(__file__).resolve().parents[2]
PLATFORM = ROOT.parent / "platform"
DEFAULT_MODELS = [
    "nvidia/nemotron-3-embed-1b:free",
    "nvidia/llama-nemotron-embed-vl-1b-v2:free",
    "liquid/lfm-2.5-embedding-350m:free",
]
DIMS = [2048, 1024, 768, 512, 384]

# Hand-mapped gold markers: a passage is relevant to a query if it contains any marker.
# These come from each query's gold_answer, matched against the demo corpus text.
# rb009 (negative query, no answer in the corpus) has no markers, so it is excluded from
# recall and MRR.
GOLD_MARKERS = {
    "rb001": ["$870 million", "$870M"],
    "rb002": ["$112M"],
    "rb003": ["1971"],
    "rb004": ["62%"],
    "rb005": ["AS9100D"],
    "rb006": ["500 units"],
    "rb007": ["Rotterdam hub handles"],
    "rb008": ["95%"],
    "rb010": ["IATF 16949"],
}


# ─── corpus → passages (heading sections for .md, blank-line paragraphs for .txt) ─

def passages(doc_dir: Path) -> list[dict]:
    out = []
    for f in sorted(doc_dir.iterdir()):
        if f.suffix not in (".md", ".txt") or not f.is_file():
            continue
        text = f.read_text()
        if f.suffix == ".md":
            parts = re.split(r"\n(?=#{1,6} )", text)
        else:
            parts = re.split(r"\n\s*\n", text)
        for i, p in enumerate(x.strip() for x in parts):
            if len(p) >= 20:
                out.append({"id": f"{f.name}#{i}", "text": p})
    return out


def queries(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


# ─── vector maths (pure Python; the sizes are small) ─────────────────────────

def norm(v: list[float]) -> list[float]:
    n = math.sqrt(sum(x * x for x in v))
    return [x / n for x in v] if n > 1e-12 else v


def cut(v: list[float], d: int) -> list[float]:
    return norm(v[:d])


def dot(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b))


def ranking(q: list[float], ps: list[list[float]]) -> list[int]:
    return sorted(range(len(ps)), key=lambda i: -dot(q, ps[i]))


# ─── provider calls ──────────────────────────────────────────────────────────

def embed(key: str, model: str, inputs: list[str], dimensions: int | None = None) -> tuple[dict, float]:
    body = {"model": model, "input": inputs}
    if dimensions:
        body["dimensions"] = dimensions
    req = urllib.request.Request(OPENROUTER + "/embeddings", data=json.dumps(body).encode(), method="POST",
                                 headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            data = json.load(r)
    except urllib.error.HTTPError as e:
        data = {"error": {"status": e.code, "body": e.read().decode(errors="replace")[:500]}}
    ms = (time.monotonic() - t0) * 1000
    if data.get("error"):          # "error": null is not an error
        return {"error": data["error"]}, ms
    return data, ms


def guard(models: list[str]) -> None:
    priced = {m["id"]: m for m in openrouter_get("/embeddings/models").get("data", [])}
    bad = [m for m in models if not m.endswith(":free") or m not in priced
           or str(priced[m].get("pricing", {}).get("prompt", "0")) not in ("0", "0.0")]
    for m in models:
        ctx = priced.get(m, {}).get("context_length")
        print(f"  guard  {m:<46} {'FREE' if m not in bad else 'NOT FREE / UNKNOWN'}  context={ctx}")
    if bad:
        sys.exit(f"guard: refusing to run — not free on OpenRouter: {bad}")


def key_limits(key: str) -> dict:
    d = openrouter_get("/key", key)["data"]
    keep = ("is_free_tier", "limit", "limit_reset", "limit_remaining", "usage", "usage_daily",
            "free_model_daily_requests")
    return {k: d.get(k) for k in keep}


# ─── evaluation ──────────────────────────────────────────────────────────────

def evaluate(pvec: list[list[float]], qvec: list[list[float]], ps: list[dict], qs: list[dict], d: int,
             ref_rank: dict[str, list[int]] | None) -> tuple[dict, dict[str, list[int]]]:
    P = [cut(v, d) for v in pvec]
    rec1 = rec5 = mrr = 0.0
    scored = 0
    ranks: dict[str, list[int]] = {}
    for q, qv in zip(qs, qvec):
        r = ranking(cut(qv, d), P)
        ranks[q["query_id"]] = r
        marks = GOLD_MARKERS.get(q["query_id"])
        if not marks:
            continue
        rel = {i for i, p in enumerate(ps) if any(m in p["text"] for m in marks)}
        if not rel:
            continue
        scored += 1
        rec1 += 1.0 if r[0] in rel else 0.0
        rec5 += 1.0 if rel & set(r[:5]) else 0.0
        first = next((k for k, i in enumerate(r[:10]) if i in rel), None)
        mrr += 1.0 / (first + 1) if first is not None else 0.0
    out = {"dim": d, "queries_scored": scored,
           "hit_at_1": round(rec1 / scored, 3), "hit_at_5": round(rec5 / scored, 3),
           "mrr_at_10": round(mrr / scored, 3)}
    if ref_rank:
        ov = [len(set(ranks[k][:10]) & set(ref_rank[k][:10])) / 10 for k in ranks]
        t1 = [ranks[k][0] == ref_rank[k][0] for k in ranks]
        out["top10_overlap_vs_native"] = round(sum(ov) / len(ov), 3)
        out["top1_agreement_vs_native"] = round(sum(t1) / len(t1), 3)
    return out, ranks


def probe_model(key: str, model: str, ps: list[dict], qs: list[dict]) -> dict:
    texts = [p["text"] for p in ps] + [q["question"] for q in qs]
    data, ms = embed(key, model, texts)
    res: dict = {"model": model, "batch_inputs": len(texts), "batch_latency_ms": round(ms)}
    if "error" in data:
        res["error"] = data["error"]
        return res
    vecs = [row["embedding"] for row in sorted(data["data"], key=lambda r: r["index"])]
    native = len(vecs[0])
    raw_norm = math.sqrt(sum(x * x for x in vecs[0]))
    res.update({"native_dim": native, "returned_l2_norm": round(raw_norm, 4), "usage": data.get("usage")})
    pvec, qvec = vecs[:len(ps)], vecs[len(ps):]

    rows, ref = [], None
    for d in [native] + [x for x in DIMS if x < native]:
        row, ranks = evaluate(pvec, qvec, ps, qs, d, ref)
        if ref is None:
            ref = ranks
        rows.append(row)
    res["truncation"] = rows

    # Does the provider honour `dimensions`? If so, does it match a client-side cut?
    target = 768 if native > 768 else None
    if target:
        d2, ms2 = embed(key, model, [ps[0]["text"]], dimensions=target)
        if "error" in d2:
            res["dimensions_param"] = {"requested": target, "error": d2["error"]}
        else:
            got = d2["data"][0]["embedding"]
            same = len(got) == target
            res["dimensions_param"] = {
                "requested": target, "returned_dim": len(got), "honoured": same,
                "cosine_vs_client_cut": round(dot(norm(got), cut(pvec[0], target)), 4) if same else None,
                "latency_ms": round(ms2)}
    return res


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models", nargs="+", default=DEFAULT_MODELS)
    ap.add_argument("--documents", default=str(PLATFORM / "demo-data" / "documents"))
    ap.add_argument("--queries", default=str(PLATFORM / "demo-data" / "eval" / "retrieval-benchmark" / "queries.jsonl"))
    ap.add_argument("--out", help="write the JSON result here (no secrets in it)")
    args = ap.parse_args()

    key = load_key()
    print(f"== key {masked(key)}")
    print("== free-models guard (OpenRouter live embedding listing)")
    guard(args.models)
    before = key_limits(key)
    print(f"  limits before: {json.dumps(before)}")

    ps, qs = passages(Path(args.documents)), queries(Path(args.queries))
    print(f"== corpus: {len(ps)} passages, {len(qs)} queries")
    results = []
    for m in args.models:
        print(f"== probing {m}")
        r = probe_model(key, m, ps, qs)
        results.append(r)
        print(json.dumps(r, indent=1))

    after = key_limits(key)
    spend_ok = float(after["usage"] or 0) <= float(before["usage"] or 0) + 1e-9
    print(f"  limits after: {json.dumps(after)}")
    print(f"== spend unchanged: {spend_ok}")

    record = {"probe": "G0-embed-probe", "recorded_at": datetime.now(timezone.utc).isoformat(),
              "corpus": {"documents": "demo-data/documents (*.md, *.txt)", "passages": len(ps),
                         "passage_unit": "markdown heading section / text blank-line paragraph",
                         "queries": "demo-data/eval/retrieval-benchmark/queries.jsonl", "query_count": len(qs),
                         "gold": "hand-mapped answer markers (embed_probe.GOLD_MARKERS); rb009 negative excluded"},
              "key_limits_before": before, "key_limits_after": after, "spend_unchanged": spend_ok,
              "models": results}
    if args.out:
        Path(args.out).write_text(json.dumps(record, indent=2) + "\n")
        print(f"== wrote {args.out}")
    return 0 if spend_ok and all("error" not in r for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
