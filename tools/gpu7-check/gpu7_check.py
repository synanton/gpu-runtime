#!/usr/bin/env python3
"""GPU-7 live validation against a real external provider arm.

Arms (deployments/external/config/gateway-external.yaml; switched in .env):
  openrouter  provider id `openai`   — OpenRouter FREE models only (zero spend)
  opencode    provider id `opencode` — opencode.ai Zen, cheapest PAID models (bounded spend)
The arm checked is --provider, default GPU7_EXTERNAL_PROVIDER from the environment or .env.

Talks to the GPU Gateway exactly like the Platform does: gRPC synanton.gpu.v1 over mTLS
(Deployment Plan §4, §13). Python stubs are generated from the canonical proto at start-up
(nothing generated is committed).

Safety:
  * openrouter: FREE-MODELS GUARD. Before any call, every catalog model of the arm must be
    an OpenRouter model priced 0/0 (live pricing API), or the tool refuses to run. The key's
    budget is checked, and spend must be unchanged after the run.
  * opencode: CHEAP-MODELS GUARD. Every catalog model of the arm must be listed live by
    opencode.ai, match the arm's allowed-model-pattern, and be priced in the catalog at most
    --max-usd-per-million (default $1/M). opencode.ai has no usage API, so spend is
    estimated from the gateway-reported token usage × catalog prices and must stay below
    --max-spend-usd (default $0.01). The gateway budget caps smoke-tenant as well.
  * keys come from the environment or git-ignored .env files and are never printed.

Usage (inside the uv venv — see README.md):
  python gpu7_check.py                    # gateway at localhost:9090, compose stack already up
  python gpu7_check.py --compose          # also generate the dev PKI and start the compose stack
  python gpu7_check.py --guard-only       # only run the free-models guard + key budget check
  python gpu7_check.py --usage            # openrouter: one JSON line, spend + free-model quota (no key)
  python gpu7_check.py --provider opencode   # validate the opencode.ai arm (default: GPU7_EXTERNAL_PROVIDER)
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
REAL_PROVIDER = "openai"          # provider id of the checked arm (set from --provider in main)
ARMS = {  # --provider → gateway provider id, key variable, live model listing
    "openrouter": {"id": "openai", "key_env": "OPENAI_API_KEY"},
    "opencode": {"id": "opencode", "key_env": "OPENCODE_API_KEY",
                 "base_env": "OPENCODE_BASE_URL", "base": "https://opencode.ai/zen/v1",
                 "ua_env": "OPENCODE_USER_AGENT", "ua": "synanton/1.0 (Synthesis & Semantics App)"},
}
SMOKE_TENANT = "smoke-tenant"     # the gpu7-smoke principal may act for this tenant
CONFIG_PATH = str(CONFIG)         # overridable with --config


# ─── key handling (never printed) ─────────────────────────────────────────────

def env_files() -> dict[str, str]:
    """tools/gpu7-check/.env then deployments/external/.env (first definition wins)."""
    out: dict[str, str] = {}
    for env_file in (Path(__file__).with_name(".env"), EXTERNAL / ".env"):
        if env_file.is_file():
            for line in env_file.read_text().splitlines():
                if "=" in line and not line.lstrip().startswith("#"):
                    k, v = line.split("=", 1)
                    out.setdefault(k.strip(), v.strip())
    return out


def setting(name: str, default: str | None = None) -> str | None:
    if os.environ.get(name):
        return os.environ[name].strip()
    return env_files().get(name) or default


def resolve(value):
    """Resolve Spring-style ${VAR:default} placeholders the way the gateway does (env, .env)."""
    import re
    if not isinstance(value, str):
        return value
    def sub(m):
        name, _, default = m.group(1).partition(":")
        return setting(name, default) or ""
    return re.sub(r"\$\{([^}]*)\}", sub, value)


def load_key(key_env: str = "OPENAI_API_KEY") -> str:
    """The arm's key from the environment, else tools/gpu7-check/.env, else deployments/external/.env."""
    key = setting(key_env)
    if not key:
        sys.exit(f"{key_env} not found (env, tools/gpu7-check/.env or deployments/external/.env)")
    return key


def masked(key: str) -> str:
    return key[:6] + "…" + f"({len(key)} chars)"


def openrouter_get(path: str, key: str | None = None) -> dict:
    headers = {"Authorization": "Bearer " + key} if key else {}
    with urllib.request.urlopen(urllib.request.Request(OPENROUTER + path, headers=headers), timeout=30) as r:
        return json.load(r)


# ─── free-models guard ────────────────────────────────────────────────────────

def catalog() -> dict:
    import yaml
    return yaml.safe_load(Path(CONFIG_PATH).read_text())["gpu-gateway"]


def catalog_prices() -> dict[str, tuple[float, float]]:
    """logical model → (input, output) USD per million, for the checked arm."""
    out = {}
    for ops in catalog()["model-catalog"]["operations"].values():
        for logical, info in (ops.get("models") or {}).items():
            if str(info.get("provider", "")).lower() == REAL_PROVIDER:
                out[logical] = (float(resolve(str(info.get("input-usd-per-million", "0")))),
                                float(resolve(str(info.get("output-usd-per-million", "0")))))
    return out


def catalog_real_models() -> list[tuple[str, str, str]]:
    """(operation, logical id, provider model id) for every catalog model on the real provider."""
    import yaml
    cfg = yaml.safe_load(Path(CONFIG_PATH).read_text())["gpu-gateway"]
    out = []
    for op, ops in cfg["model-catalog"]["operations"].items():
        for logical, info in (ops.get("models") or {}).items():
            if str(info.get("provider", "")).lower() == REAL_PROVIDER:
                out.append((op, logical, resolve(info.get("provider-model-id", logical))))
            for fb in info.get("fallbacks") or []:           # T-K8S-52 fallbacks are guarded too
                if str(fb.get("provider", "")).lower() == REAL_PROVIDER:
                    out.append((op + "*", logical + " (fallback)", resolve(fb.get("provider-model-id", logical))))
    return out


def catalog_embed_dims() -> dict[str, int]:
    """logical EMBED model → catalog embedding-dim (real provider only)."""
    import yaml
    cfg = yaml.safe_load(Path(CONFIG_PATH).read_text())["gpu-gateway"]
    models = cfg["model-catalog"]["operations"].get("EMBED", {}).get("models") or {}
    return {k: int(v["embedding-dim"]) for k, v in models.items()
            if str(v.get("provider", "")).lower() == REAL_PROVIDER and "embedding-dim" in v}


def opencode_get(path: str, key: str) -> dict:
    arm = ARMS["opencode"]
    base = setting(arm["base_env"], arm["base"])
    req = urllib.request.Request(base + path, headers={"Authorization": "Bearer " + key,
                                                       "User-Agent": setting(arm["ua_env"], arm["ua"])})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def cheap_models_guard(key: str, max_usd_per_million: float) -> list[tuple[str, str, str]]:
    """opencode arm: listed live, allowed by the arm's pattern, priced and cheap in the catalog."""
    import re
    models = catalog_real_models()
    if not models:
        sys.exit(f"guard: no {REAL_PROVIDER} models in the catalog — nothing to validate")
    pattern = resolve(str(catalog()["providers"][REAL_PROVIDER].get("allowed-model-pattern") or ".*"))
    live = {m["id"] for m in opencode_get("/models", key).get("data", [])}
    prices = catalog_prices()
    bad = []
    for op, logical, pid in models:
        base_logical = logical.replace(" (fallback)", "")
        pin, pout = prices.get(base_logical, (None, None))
        problems = []
        if pid not in live:
            problems.append("not listed by opencode.ai")
        if not re.fullmatch(pattern, pid):
            problems.append("violates allowed-model-pattern")
        if pin is None or pin <= 0 or pout <= 0:
            problems.append("unpriced (budget/ledger need a price)")
        elif max(pin, pout) > max_usd_per_million:
            problems.append(f"price > ${max_usd_per_million}/M")
        if pid.endswith("-free") or pid == "big-pickle":
            problems.append("free tier is refused outside the OpenCode app (403 FreeTierError)")
        print(f"  guard  {op:<10} {logical:<34} → {pid:<22} ${pin}/${pout} per M  "
              + ("OK" if not problems else "; ".join(problems)))
        if problems:
            bad.append(pid)
    if bad:
        sys.exit(f"guard: refusing to run — {bad}")
    print(f"  guard  key {masked(key)} (opencode.ai has no usage API: spend is estimated from token usage)")
    return models


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


USAGE: list[tuple[str, int, int]] = []   # (logical model, input tokens, output tokens) of real-arm calls


def track(logical: str, usage) -> None:
    if usage is not None:
        USAGE.append((logical, int(usage.input_tokens), int(usage.output_tokens)))


def estimated_spend_usd() -> float:
    prices = catalog_prices()
    return sum(i * prices.get(m, (0, 0))[0] / 1e6 + o * prices.get(m, (0, 0))[1] / 1e6 for m, i, o in USAGE)


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
        # 64 tokens: reasoning models (e.g. qwen3.8-flash) spend output tokens on hidden reasoning
        r = stub.Execute(req(chat[1], "SYNTHESIZE", {"model": chat[1], "max_tokens": 64,
                                                     "messages": [{"role": "user", "content": "Reply with: OK"}]}),
                         timeout=120)
        track(chat[1], r.usage)
        body = r.result.decode() if r.result else ""
        c.check(f"Execute {chat[1]} → SUCCESS", r.state == pb.SUCCESS, pb.ExecutionState.Name(r.state)
                + (f" code={r.error.code}" if r.error.code else ""))
        c.check("chat result carries the logical model ID; provider ID never exposed",
                body != "" and json.loads(body).get("model") == chat[1] and chat[2] not in body)
        c.check("provider usage captured", r.usage.input_tokens > 0, str(r.usage))
        c.check("provider request ID preserved (upstream_request_id)", r.upstream_request_id != "")

        chunks = list(stub.ExecuteStream(req(chat[1], "SYNTHESIZE", {
            "model": chat[1], "max_tokens": 64, "stream_options": {"include_usage": True},
            "messages": [{"role": "user", "content": "Count from 1 to 3"}]}), timeout=120))
        data = [json.loads(ch.data) for ch in chunks if ch.WhichOneof("event") == "data"]
        terms = [ch for ch in chunks if ch.WhichOneof("event") == "terminal"]
        for t in terms:
            track(chat[1], t.terminal.usage)
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
        track(respond[1], r.usage)
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
        for ch in chunks:
            if ch.WhichOneof("event") == "terminal":
                track(respond[1], ch.terminal.usage)
        c.check("Responses stream: typed events, one terminal, no [DONE]",
                len(types) >= 1 and chunks[-1].WhichOneof("event") == "terminal"
                and not any(b"[DONE]" in ch.data for ch in chunks))

    # every real-arm EMBED model (incl. the retrieval-benchmark arms, G3): vector length must
    # equal the catalog's embedding-dim, and the provider model ID must never appear downstream
    dims = catalog_embed_dims()
    embeds = [m for m in models if m[0] == "EMBED"]
    if not embeds:
        print(f"SKIP  EMBED checks — the {REAL_PROVIDER} arm maps no embedding models")
    for _, logical, pid in embeds:
        r = stub.Execute(req(logical, "EMBED", {"model": logical, "input": "hello"}), timeout=120)
        track(logical, r.usage)
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
        print(f"SKIP  synanton-benchmark principal checks — the {REAL_PROVIDER} arm maps no embedding models")
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
    global CONFIG_PATH, REAL_PROVIDER
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--provider", choices=sorted(ARMS), default=None,
                    help="external arm to validate (default: GPU7_EXTERNAL_PROVIDER, else openrouter)")
    ap.add_argument("--gateway", default="localhost:9090")
    ap.add_argument("--cert-dir", default=str(EXTERNAL / "certs"))
    ap.add_argument("--client", default="gpu7-smoke", help="client certificate / principal CN")
    ap.add_argument("--tenant", default=SMOKE_TENANT)
    ap.add_argument("--plaintext", action="store_true", help="gateway in security.mode=insecure-plaintext")
    ap.add_argument("--compose", action="store_true", help="generate PKI if missing and start the compose stack")
    ap.add_argument("--guard-only", action="store_true")
    ap.add_argument("--usage", action="store_true",
                    help="openrouter: print the key's spend + free-model quota as one JSON line (no key) and exit; "
                         "used as the platform retrieval-eval --spend-cmd")
    ap.add_argument("--max-usd-per-million", type=float, default=1.0,
                    help="opencode: refuse catalog models priced above this (input or output, USD / M tokens)")
    ap.add_argument("--max-spend-usd", type=float, default=0.01,
                    help="opencode: fail if the run's estimated spend exceeds this")
    ap.add_argument("--config", default=str(CONFIG), help="gateway config to guard (default: the compose config)")
    args = ap.parse_args()
    CONFIG_PATH = args.config
    arm_name = args.provider or setting("GPU7_EXTERNAL_PROVIDER", "openrouter")
    if arm_name not in ARMS:
        sys.exit(f"unknown external provider arm '{arm_name}' (choose from {sorted(ARMS)})")
    arm = ARMS[arm_name]
    REAL_PROVIDER = arm["id"]
    paid = arm_name == "opencode"
    print(f"== external arm: {arm_name} (provider id {REAL_PROVIDER})")

    key = load_key(arm["key_env"])
    if args.usage:
        if paid:
            print(json.dumps({"usage": None, "provider": arm_name,
                              "note": "opencode.ai exposes no usage API; see the gateway cost ledger"}))
            return 3
        d = openrouter_get("/key", key)["data"]
        print(json.dumps({k: d.get(k) for k in ("usage", "usage_daily", "limit", "limit_remaining",
                                                 "limit_reset", "is_free_tier", "free_model_daily_requests")}))
        return 0
    if paid:
        print(f"== cheap-models guard (opencode.ai live model list, catalog prices <= ${args.max_usd_per_million}/M)")
        models = cheap_models_guard(key, args.max_usd_per_million)
    else:
        print("== free-models guard (OpenRouter live pricing)")
        models = free_models_guard(key)
    if args.guard_only:
        print("== guard passed")
        return 0
    spent_before = None if paid else openrouter_get("/key", key)["data"].get("usage", 0)

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

    if paid:
        tokens_in = sum(i for _, i, _ in USAGE)
        tokens_out = sum(o for _, _, o in USAGE)
        spend = estimated_spend_usd()
        c.check(f"estimated spend ${spend:.6f} <= ${args.max_spend_usd} "
                f"({len(USAGE)} metered calls, {tokens_in} in / {tokens_out} out tokens × catalog prices)",
                spend <= args.max_spend_usd)
    else:
        spent_after = openrouter_get("/key", key)["data"].get("usage", 0)
        c.check(f"no spend on the capped key (usage ${spent_before} → ${spent_after})",
                float(spent_after) <= float(spent_before) + 1e-9)
    print(f"== {c.passed} passed, {c.failed} failed")
    return 0 if c.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
