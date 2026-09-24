#!/usr/bin/env python3
"""Manual test CLI for Synanton GPU plane deployments (GPU-5 and GPU-7).

Both profiles expose the same OpenAI-compatible Gateway surface (spec §4.2), so
one tool covers both; --profile only picks default logical model IDs:

  GPU-5 (homelab k8s, local vLLM):
    kubectl -n gpu-plane port-forward svc/gpu-gateway 8080:8080
    ./tools/gpu-plane-check.py --profile gpu5 --api-key "$GPU_DEV_API_KEY" all

  GPU-5 via Traefik (phase 8+, self-signed cert):
    ./tools/gpu-plane-check.py --profile gpu5 --base-url https://192.168.10.31:30443 \
        --insecure --api-key "$GPU_DEV_API_KEY" all

  GPU-7 (external profile, docker compose):
    cd deployments/external && docker compose up -d
    ./tools/gpu-plane-check.py --profile gpu7 --api-key "$GPU_DEV_API_KEY" all

Exit code 0 = every check passed. Stdlib-only; Python 3.11+.
"""
from __future__ import annotations

import argparse
import json
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid

PROFILES = {
    "gpu5": {  # deployments/homelab — logical IDs from the GPU-5 plan §2
        "chat": "synanton-qwen3-4b-synthesis",
        "embed": "synanton-bge-base-embedding",
        "rerank": "synanton-qwen3-reranker-0.6b",
        "rerank_supported": True,   # §14: GPU-5 baseline always has a reranker
    },
    "gpu7": {  # deployments/external — logical IDs from config/gateway-external.yaml
        "chat": "mock-chat-1",
        "embed": "mock-embedding-1",
        "rerank": "mock-reranker-1",
        "rerank_supported": True,   # mock supports it; pass --rerank-unsupported
                                    # when testing a real provider without rerank
    },
}

PASS, FAIL = 0, 0


def report(ok: bool, name: str, detail: str = "") -> bool:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"PASS  {name}" + (f"  ({detail})" if detail else ""))
    else:
        FAIL += 1
        print(f"FAIL  {name}" + (f"  ({detail})" if detail else ""))
    return ok


class Client:
    def __init__(self, base_url: str, api_key: str, scheme: str, insecure: bool):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.scheme = scheme
        self.ctx = ssl.create_default_context()
        if insecure:
            self.ctx.check_hostname = False
            self.ctx.verify_mode = ssl.CERT_NONE

    def request(self, method, path, body=None, extra_headers=None, stream=False):
        """Returns (status, headers, parsed_json_or_None, raw_bytes, latency_ms)."""
        headers = {"Authorization": f"{self.scheme} {self.api_key}"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        headers.update(extra_headers or {})
        req = urllib.request.Request(
            self.base_url + path,
            method=method,
            headers=headers,
            data=json.dumps(body).encode() if body is not None else None,
        )
        t0 = time.monotonic()
        try:
            resp = urllib.request.urlopen(req, context=self.ctx, timeout=300)
            raw = b"" if stream else resp.read()
            ms = (time.monotonic() - t0) * 1000
            if stream:
                return resp.status, resp.headers, None, resp, ms  # caller reads
            parsed = json.loads(raw) if raw else None
            return resp.status, resp.headers, parsed, raw, ms
        except urllib.error.HTTPError as e:
            raw = e.read()
            ms = (time.monotonic() - t0) * 1000
            try:
                parsed = json.loads(raw)
            except Exception:
                parsed = None
            return e.code, e.headers, parsed, raw, ms


def check_envelope(parsed, want_code, name):
    """§16 canonical error envelope."""
    if not isinstance(parsed, dict) or "error" not in parsed:
        return report(False, name, f"no error envelope: {str(parsed)[:120]}")
    err = parsed["error"]
    keys = {"message", "type", "param", "code"}
    if not keys <= set(err):
        return report(False, name, f"envelope missing keys: {sorted(err)}")
    return report(err.get("code") == want_code, name,
                  f"code={err.get('code')} type={err.get('type')}")


def cmd_models(c, args):
    status, hdrs, body, _, ms = c.request("GET", "/v1/models")
    if not report(status == 200, "GET /v1/models -> 200", f"{ms:.0f} ms"):
        return
    ids = [m.get("id") for m in body.get("data", [])]
    report(isinstance(body, dict) and body.get("object") == "list" and ids,
           "models payload shape", f"models={ids}")
    for m in (args.chat_model, args.embed_model, args.rerank_model):
        report(m in ids, f"model advertised: {m}")


def cmd_chat(c, args):
    body = {"model": args.chat_model,
            "messages": [{"role": "user", "content": "Say OK"}],
            "max_tokens": 8}
    status, _, parsed, _, ms = c.request("POST", "/v1/chat/completions", body)
    ok = status == 200 and isinstance(parsed, dict)
    if ok:
        ch = (parsed.get("choices") or [{}])[0]
        ok = bool(ch.get("message", {}).get("content"))
    report(ok, "chat completion", f"{ms:.0f} ms")
    if isinstance(parsed, dict) and "usage" in parsed:
        u = parsed["usage"]
        report(u is None or u.get("total_tokens", 0) > 0,
               "chat usage authoritative-or-null (§10.2)", f"usage={u}")


def cmd_stream(c, args):
    body = {"model": args.chat_model,
            "messages": [{"role": "user", "content": "Say OK"}],
            "stream": True,
            "stream_options": {"include_usage": True}}
    status, _, _, resp, _ = c.request("POST", "/v1/chat/completions", body, stream=True)
    if not report(status == 200, "chat stream -> 200"):
        resp.close()
        return
    done = chunks = 0
    usage_seen = bad = False
    for line in resp:
        line = line.decode().strip()
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if data == "[DONE]":
            done += 1
            continue
        try:
            evt = json.loads(data)
        except Exception:
            bad = True
            continue
        if evt.get("object") == "chat.completion.chunk":
            chunks += 1
        if evt.get("usage"):
            usage_seen = True
    resp.close()
    report(chunks > 0 and not bad, "stream chunks valid chat.completion.chunk (§10.1)",
           f"chunks={chunks}")
    report(done == 1, "[DONE] emitted exactly once (§10.1)", f"count={done}")
    report(usage_seen, "terminal usage present when requested (§10.1)")


def cmd_embed(c, args):
    body = {"model": args.embed_model, "input": "hello gpu plane"}
    status, _, parsed, _, ms = c.request("POST", "/v1/embeddings", body)
    ok = status == 200 and isinstance(parsed, dict)
    dim = 0
    if ok:
        data = parsed.get("data") or []
        dim = len((data[0] or {}).get("embedding") or []) if data else 0
        ok = dim > 0
    report(ok, "embeddings", f"{ms:.0f} ms, dim={dim}")


def cmd_rerank(c, args):
    body = {"model": args.rerank_model, "query": "ping",
            "documents": ["pong", "an unrelated paragraph about cooking"]}
    status, _, parsed, _, ms = c.request("POST", "/v1/rerank", body)
    if args.rerank_supported:
        ok = status == 200 and isinstance(parsed, dict) and parsed.get("results")
        if ok:
            scores = [r.get("relevance_score") for r in parsed["results"]]
            ok = all(s is not None for s in scores)
        report(ok, "rerank", f"{ms:.0f} ms")
    else:
        # §40: capability gap must surface deterministically, never silently convert
        report(status == 400, "rerank unsupported -> HTTP 400 (§40)", f"got {status}")
        check_envelope(parsed, "capability_not_supported", "rerank gap envelope")


def cmd_negative(c, args):
    # invalid API key -> 401 invalid_api_key (§16)
    bad = Client(c.base_url, "sk-syn-definitely-wrong", c.scheme, False)
    bad.ctx = c.ctx
    status, _, parsed, _, _ = bad.request("GET", "/v1/models")
    report(status == 401, "invalid API key -> 401")
    check_envelope(parsed, "invalid_api_key", "invalid key envelope (§16)")

    # unknown model -> 404 model_not_found (§16)
    status, _, parsed, _, _ = c.request(
        "POST", "/v1/embeddings", {"model": "no-such-model", "input": "x"})
    report(status == 404, "unknown model -> 404")
    check_envelope(parsed, "model_not_found", "unknown model envelope (§16)")

    # request-ID preservation (§21)
    rid = f"gpu-check-{uuid.uuid4()}"
    status, hdrs, _, _, _ = c.request("GET", "/v1/models", extra_headers={"x-request-id": rid})
    echoed = hdrs.get("x-request-id")
    report(status == 200 and echoed == rid, "x-request-id preserved (§21)", f"id={rid}")

    # invalid request-ID rejected at the boundary (§21)
    status, _, parsed, _, _ = c.request("GET", "/v1/models",
                                        extra_headers={"x-request-id": "bad id with spaces\t"})
    report(status == 400, "invalid x-request-id -> 400")
    check_envelope(parsed, "invalid_request_id", "invalid request-id envelope (§21)")


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["models", "chat", "stream", "embed",
                                        "rerank", "negative", "all"])
    ap.add_argument("--profile", choices=sorted(PROFILES), default="gpu5")
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--api-key", required=True)
    ap.add_argument("--auth-scheme", choices=["Bearer", "Api-Key"], default="Bearer",
                    help="§13.1: both schemes must work — run 'negative' twice, once per scheme")
    ap.add_argument("--insecure", action="store_true", help="skip TLS verify (self-signed)")
    ap.add_argument("--rerank-unsupported", action="store_true",
                    help="provider has no rerank: expect §40 capability error instead of 200")
    for name in ("chat", "embed", "rerank"):
        ap.add_argument(f"--{name}-model", help=f"override the profile's {name} model ID")
    args = ap.parse_args()

    prof = PROFILES[args.profile]
    args.chat_model = args.chat_model or prof["chat"]
    args.embed_model = args.embed_model or prof["embed"]
    args.rerank_model = args.rerank_model or prof["rerank"]
    args.rerank_supported = prof["rerank_supported"] and not args.rerank_unsupported

    c = Client(args.base_url, args.api_key, args.auth_scheme, args.insecure)
    cmds = {"models": cmd_models, "chat": cmd_chat, "stream": cmd_stream,
            "embed": cmd_embed, "rerank": cmd_rerank, "negative": cmd_negative}
    todo = list(cmds) if args.command == "all" else [args.command]

    print(f"== gpu-plane-check profile={args.profile} base={args.base_url} "
          f"scheme={args.auth_scheme}")
    for name in todo:
        print(f"-- {name}")
        try:
            cmds[name](c, args)
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            report(False, f"{name} reachable", str(e))
            break  # nothing else can pass if the gateway is unreachable

    print(f"== {PASS} passed, {FAIL} failed")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
