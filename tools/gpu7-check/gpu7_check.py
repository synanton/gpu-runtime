#!/usr/bin/env python3
"""GPU-7 live validation against a real external provider (OpenRouter FREE models only).

Talks to the GPU Gateway exactly like the Platform does: gRPC synanton.gpu.v1 over mTLS
(Deployment Plan §4, §13). Python stubs are generated from the canonical proto at start-up
(nothing generated is committed).

Safety (the provider key is spend-capped):
  * FREE-MODELS GUARD — before any call, every catalog model of the real provider in
    deployments/external/config/gateway-external.yaml must be an OpenRouter model priced
    0/0 (checked against OpenRouter's live pricing API). Otherwise the tool refuses to run.
  * the key's remaining budget is checked, and spend is compared before/after the run
    (a non-zero delta fails the run).
  * the key is read from the environment or a git-ignored .env file and never printed.

Usage (inside the uv venv — see README.md):
  python gpu7_check.py                    # gateway at localhost:9090, compose stack already up
  python gpu7_check.py --compose          # also generate the dev PKI and start the compose stack
  python gpu7_check.py --guard-only       # only run the free-models guard + key budget check
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXTERNAL = ROOT / "deployments" / "external"
PROTO_DIR = ROOT / "java" / "gpu-contract" / "src" / "main" / "proto"
PROTO = "synanton/gpu/v1/gpu_execution_service.proto"
CONFIG = EXTERNAL / "config" / "gateway-external.yaml"
OPENROUTER = "https://openrouter.ai/api/v1"
REAL_PROVIDER = "openai"          # provider id of the real arm in gateway-external.yaml
SMOKE_TENANT = "smoke-tenant"     # the gpu7-smoke principal may act for this tenant
CONFIG_PATH = str(CONFIG)         # overridable with --config


# ─── key handling (never printed) ─────────────────────────────────────────────

def load_key() -> str:
    """OPENAI_API_KEY from the environment, else tools/gpu7-check/.env, else deployments/external/.env."""
    if os.environ.get("OPENAI_API_KEY"):
        return os.environ["OPENAI_API_KEY"].strip()
    for env_file in (Path(__file__).with_name(".env"), EXTERNAL / ".env"):
        if env_file.is_file():
            for line in env_file.read_text().splitlines():
                if line.startswith("OPENAI_API_KEY=") and line.split("=", 1)[1].strip():
                    return line.split("=", 1)[1].strip()
    sys.exit("OPENAI_API_KEY not found (env, tools/gpu7-check/.env or deployments/external/.env)")


def masked(key: str) -> str:
    return key[:6] + "…" + f"({len(key)} chars)"


def openrouter_get(path: str, key: str | None = None) -> dict:
    headers = {"Authorization": "Bearer " + key} if key else {}
    with urllib.request.urlopen(urllib.request.Request(OPENROUTER + path, headers=headers), timeout=30) as r:
        return json.load(r)


# ─── free-models guard ────────────────────────────────────────────────────────

def catalog_real_models() -> list[tuple[str, str, str]]:
    """(operation, logical id, provider model id) for every catalog model on the real provider."""
    import yaml
    cfg = yaml.safe_load(Path(CONFIG_PATH).read_text())["gpu-gateway"]
    out = []
    for op, ops in cfg["model-catalog"]["operations"].items():
        for logical, info in (ops.get("models") or {}).items():
            if str(info.get("provider", "")).lower() == REAL_PROVIDER:
                out.append((op, logical, info.get("provider-model-id", logical)))
            for fb in info.get("fallbacks") or []:           # T-K8S-52 fallbacks are guarded too
                if str(fb.get("provider", "")).lower() == REAL_PROVIDER:
                    out.append((op + "*", logical + " (fallback)", fb.get("provider-model-id", logical)))
    return out


def catalog_embed_dims() -> dict[str, int]:
    """logical EMBED model → catalog embedding-dim (real provider only)."""
    import yaml
    cfg = yaml.safe_load(Path(CONFIG_PATH).read_text())["gpu-gateway"]
    models = cfg["model-catalog"]["operations"].get("EMBED", {}).get("models") or {}
    return {k: int(v["embedding-dim"]) for k, v in models.items()
            if str(v.get("provider", "")).lower() == REAL_PROVIDER and "embedding-dim" in v}


def free_models_guard(key: str) -> list[tuple[str, str, str]]:
    models = catalog_real_models()
    if not models:
        sys.exit("guard: no real-provider models in the catalog — nothing to validate")
    priced = {}
    for listing in ("/models", "/embeddings/models"):
        for m in openrouter_get(listing).get("data", []):
            priced[m["id"]] = m.get("pricing", {})
    bad = []
    for op, logical, pid in models:
        p = priced.get(pid)
        free = p is not None and pid.endswith(":free") and all(
            str(p.get(k, "0")) in ("0", "0.0") for k in ("prompt", "completion"))
        print(f"  guard  {op:<10} {logical:<26} → {pid:<42} {'FREE' if free else 'NOT FREE / UNKNOWN'}")
        if not free:
            bad.append(pid)
    if bad:
        sys.exit(f"guard: refusing to run — not free on OpenRouter: {bad}")
    info = openrouter_get("/key", key)["data"]
    limit, usage = info.get("limit"), info.get("usage", 0)
    print(f"  guard  key {masked(key)} limit=${limit} usage=${usage}")
    if limit is not None and usage >= 0.9 * float(limit):
        sys.exit("guard: refusing to run — key is within 10% of its spend limit")
    return models


# ─── gRPC client (stubs generated from the canonical proto) ───────────────────

def load_stubs():
    from grpc_tools import protoc
    out = Path(tempfile.mkdtemp(prefix="gpu7-stubs-"))
    if protoc.main(["protoc", f"-I{PROTO_DIR}", f"--python_out={out}", f"--grpc_python_out={out}",
                    str(PROTO_DIR / PROTO)]) != 0:
        sys.exit("protoc failed")
    sys.path.insert(0, str(out))
    from synanton.gpu.v1 import gpu_execution_service_pb2 as pb, gpu_execution_service_pb2_grpc as rpc
    return pb, rpc


def channel(target: str, cert_dir: Path, client: str, plaintext: bool):
    import grpc
    if plaintext:
        return grpc.insecure_channel(target)
    read = lambda n: (cert_dir / n).read_bytes()
    creds = grpc.ssl_channel_credentials(root_certificates=read("ca.crt"),
                                         private_key=read(f"{client}.key"),
                                         certificate_chain=read(f"{client}.crt"))
    return grpc.secure_channel(target, creds)


# ─── checks ───────────────────────────────────────────────────────────────────

class Checks:
    def __init__(self):
        self.passed, self.failed = 0, 0

    def check(self, name: str, ok: bool, detail: str = ""):
        if ok:
            self.passed += 1
            print(f"PASS  {name}")
        else:
            self.failed += 1
            print(f"FAIL  {name}  {detail}")


def trailer_code(err) -> str | None:
    for k, v in (err.trailing_metadata() or ()):
        if k == "x-synanton-error-code":
            return v
    return None


def run_live(pb, rpc, stub, models, c: Checks, tenant: str):
    import grpc

    def req(model, op, payload, **kw):
        return pb.ExecutionRequest(request_id=f"gpu7check-{uuid.uuid4()}", tenant_id=kw.get("tenant", tenant),
                                   model=model, model_version="1", operation=getattr(pb, op),
                                   payload=json.dumps(payload).encode(), data_tags=kw.get("tags", []))

    chat = next((m for m in models if m[0] == "SYNTHESIZE"), None)
    embed = next((m for m in models if m[0] == "EMBED"), None)  # "*"-suffixed ops are fallbacks
    provider_ids = [pid for _, _, pid in models]

    listed = stub.GetModels(pb.GetModelsRequest(operation=pb.SYNTHESIZE), timeout=30)
    c.check("GetModels advertises the real-provider chat model (logical ID)",
            chat is not None and any(m.model_id == chat[1] for m in listed.models))
    c.check("GetModels never exposes provider model IDs",
            not any(pid in str(listed) for pid in provider_ids))

    if chat:
        r = stub.Execute(req(chat[1], "SYNTHESIZE", {"model": chat[1], "max_tokens": 16,
                                                     "messages": [{"role": "user", "content": "Reply with: OK"}]}),
                         timeout=120)
        body = r.result.decode() if r.result else ""
        c.check(f"Execute {chat[1]} → SUCCESS", r.state == pb.SUCCESS, pb.ExecutionState.Name(r.state)
                + (f" code={r.error.code}" if r.error.code else ""))
        c.check("chat result carries the logical model ID; provider ID never exposed",
                body != "" and json.loads(body).get("model") == chat[1] and chat[2] not in body)
        c.check("provider usage captured", r.usage.input_tokens > 0, str(r.usage))
        c.check("provider request ID preserved (upstream_request_id)", r.upstream_request_id != "")

        chunks = list(stub.ExecuteStream(req(chat[1], "SYNTHESIZE", {
            "model": chat[1], "max_tokens": 24, "stream_options": {"include_usage": True},
            "messages": [{"role": "user", "content": "Count from 1 to 3"}]}), timeout=120))
        data = [json.loads(ch.data) for ch in chunks if ch.WhichOneof("event") == "data"]
        terms = [ch for ch in chunks if ch.WhichOneof("event") == "terminal"]
        c.check("ExecuteStream: data chunks then exactly one terminal (SUCCESS)",
                len(data) >= 1 and len(terms) == 1 and chunks[-1].WhichOneof("event") == "terminal"
                and terms[0].terminal.state == pb.SUCCESS)
        c.check("stream chunks carry the logical ID; include_usage → usage chunk",
                all(d.get("model", chat[1]) == chat[1] for d in data) and any(d.get("usage") for d in data)
                and not any(chat[2] in json.dumps(d) for d in data))

        try:
            stub.Execute(req(chat[1], "RERANK", {"query": "q", "documents": ["a"]}), timeout=30)
            c.check("RERANK on the real arm → capability_not_supported", False, "no denial")
        except grpc.RpcError as e:
            c.check("RERANK on the real arm → capability_not_supported", trailer_code(e) == "capability_not_supported",
                    str(e.code()))
        try:
            stub.Execute(req(chat[1], "SYNTHESIZE", {"messages": []}, tenant="not-my-tenant"), timeout=30)
            c.check("asserting another tenant → tenant_not_allowed", False, "no denial")
        except grpc.RpcError as e:
            c.check("asserting another tenant → tenant_not_allowed", trailer_code(e) == "tenant_not_allowed",
                    str(e.code()))

    respond = next((m for m in models if m[0] == "RESPOND"), None)
    if respond:
        r = stub.Execute(req(respond[1], "RESPOND", {"model": respond[1], "input": "Reply with: OK",
                                                     "max_output_tokens": 16}), timeout=120)
        body = r.result.decode() if r.result else ""
        obj = json.loads(body) if body else {}
        c.check(f"Responses API create {respond[1]} → SUCCESS, Gateway response ID, logical model",
                r.state == pb.SUCCESS and str(obj.get("id", "")).startswith("resp_")
                and obj.get("model") == respond[1] and respond[2] not in body,
                pb.ExecutionState.Name(r.state) + (f" code={r.error.code}" if r.error.code else ""))
        if obj.get("id"):
            got = stub.GetResponse(pb.GetResponseRequest(response_id=obj["id"]), timeout=30)
            c.check("GetResponse returns the stored response", got.response_id == obj["id"])
            c.check("DeleteResponse", stub.DeleteResponse(pb.DeleteResponseRequest(response_id=obj["id"]), timeout=30).deleted)
        chunks = list(stub.ExecuteStream(req(respond[1], "RESPOND", {"model": respond[1], "input": "Count 1 to 3",
                                                                     "max_output_tokens": 24}), timeout=120))
        types = [json.loads(ch.data).get("type") for ch in chunks if ch.WhichOneof("event") == "data"]
        c.check("Responses stream: typed events, one terminal, no [DONE]",
                len(types) >= 1 and chunks[-1].WhichOneof("event") == "terminal"
                and not any(b"[DONE]" in ch.data for ch in chunks))

    # every real-arm EMBED model (incl. the retrieval-benchmark arms, G3): vector length must
    # equal the catalog's embedding-dim, and the provider model ID must never appear downstream
    dims = catalog_embed_dims()
    for _, logical, pid in [m for m in models if m[0] == "EMBED"]:
        r = stub.Execute(req(logical, "EMBED", {"model": logical, "input": "hello"}), timeout=120)
        body = r.result.decode() if r.result else ""
        n = len(json.loads(body)["data"][0]["embedding"]) if r.state == pb.SUCCESS and body else 0
        c.check(f"Execute EMBED {logical} → SUCCESS, {dims.get(logical)}-dim vector (catalog embedding-dim)",
                r.state == pb.SUCCESS and n == dims.get(logical) and pid not in body,
                pb.ExecutionState.Name(r.state) + (f" code={r.error.code}" if r.error.code else "") + f" dim={n}")


def run_benchmark_principal(pb, stub, models, c: Checks):
    """synanton-benchmark (platform retrieval benchmark, B1-G G3) may act only for its rb-* tenants."""
    import grpc
    embed = next((m for m in models if m[0] == "EMBED"), None)
    if embed is None:
        return

    def req(tenant):
        return pb.ExecutionRequest(request_id=f"gpu7check-{uuid.uuid4()}", tenant_id=tenant, model=embed[1],
                                   model_version="1", operation=pb.EMBED,
                                   payload=json.dumps({"model": embed[1], "input": "hello"}).encode())
    r = stub.Execute(req("rb-fixed-g"), timeout=120)
    c.check(f"synanton-benchmark: EMBED {embed[1]} for rb-fixed-g → SUCCESS", r.state == pb.SUCCESS,
            pb.ExecutionState.Name(r.state) + (f" code={r.error.code}" if r.error.code else ""))
    try:
        stub.Execute(req(SMOKE_TENANT), timeout=30)
        c.check("synanton-benchmark: non-benchmark tenant → tenant_not_allowed", False, "no denial")
    except grpc.RpcError as e:
        c.check("synanton-benchmark: non-benchmark tenant → tenant_not_allowed",
                trailer_code(e) == "tenant_not_allowed", str(e.code()))


def compose_up(key_present: bool):
    certs = EXTERNAL / "certs"
    if not (certs / "gpu7-smoke.crt").is_file():
        subprocess.run([str(EXTERNAL / "scripts" / "gen-certs.sh")], check=True)
    subprocess.run(["docker", "compose", "up", "-d", "--build"], cwd=EXTERNAL, check=True)
    time.sleep(25)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--gateway", default="localhost:9090")
    ap.add_argument("--cert-dir", default=str(EXTERNAL / "certs"))
    ap.add_argument("--client", default="gpu7-smoke", help="client certificate / principal CN")
    ap.add_argument("--tenant", default=SMOKE_TENANT)
    ap.add_argument("--plaintext", action="store_true", help="gateway in security.mode=insecure-plaintext")
    ap.add_argument("--compose", action="store_true", help="generate PKI if missing and start the compose stack")
    ap.add_argument("--guard-only", action="store_true")
    ap.add_argument("--config", default=str(CONFIG), help="gateway config to guard (default: the compose config)")
    args = ap.parse_args()
    global CONFIG_PATH
    CONFIG_PATH = args.config

    key = load_key()
    print("== free-models guard (OpenRouter live pricing)")
    models = free_models_guard(key)
    if args.guard_only:
        print("== guard passed")
        return 0
    spent_before = openrouter_get("/key", key)["data"].get("usage", 0)

    if args.compose:
        compose_up(True)
    pb, rpc = load_stubs()
    stub = rpc.GPUExecutionServiceStub(channel(args.gateway, Path(args.cert_dir), args.client, args.plaintext))

    print(f"== live checks via {args.gateway} (principal {args.client}, tenant {args.tenant})")
    c = Checks()
    try:
        run_live(pb, rpc, stub, models, c, args.tenant)
    except Exception as e:  # connection/handshake problems are failures, not crashes
        c.check("gateway reachable over mTLS", False, f"{type(e).__name__}: {e}")

    bench_cert = Path(args.cert_dir) / "synanton-benchmark.crt"
    if args.plaintext or not bench_cert.is_file():
        print("SKIP  synanton-benchmark principal checks (plaintext, or no synanton-benchmark cert — re-run gen-certs.sh)")
    else:
        print("== benchmark principal (synanton-benchmark)")
        try:
            bstub = rpc.GPUExecutionServiceStub(channel(args.gateway, Path(args.cert_dir), "synanton-benchmark", False))
            run_benchmark_principal(pb, bstub, models, c)
        except Exception as e:
            c.check("synanton-benchmark reachable over mTLS", False, f"{type(e).__name__}: {e}")

    spent_after = openrouter_get("/key", key)["data"].get("usage", 0)
    c.check(f"no spend on the capped key (usage ${spent_before} → ${spent_after})",
            float(spent_after) <= float(spent_before) + 1e-9)
    print(f"== {c.passed} passed, {c.failed} failed")
    return 0 if c.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
