#!/usr/bin/env python3
"""T-K8S-6a local end-to-end test: real Gateway + real Envoy config + mock backends, no GPUs.

GPU-5 plan §13.5 / Deployment Plan §24.1.10, §24.2.6–7. Run it via
deployments/homelab/scripts/envoy-jwt-local-test.sh.

Checks:
  * Gateway: JWKS (2 keys) on :8090; readiness UP; no key material in its logs
  * §24.1.10  Gateway-signed EMBED / SYNTHESIZE / stream / RERANK reach the backends through
              Envoy, and the JWT is NOT forwarded to backends (forward: false)
  * §24.2.6   Envoy rejects (401): unsigned, foreign key, expired, wrong audience
  * rotation  a token signed with the previous key still verifies
  * §24.2.7   JWKS unavailable → fail closed; recovers after the Gateway returns
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
HOMELAB = HERE.parents[1]
ROOT = HOMELAB.parents[1]
sys.path.insert(0, str(ROOT / "tools" / "gpu7-check"))
import gpu7_check  # noqa: E402  (stub generation + mTLS channel helpers)

GATEWAY_IMAGE = "gpu-gateway:t6a-local"
PASS, FAIL = [], []


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    print(("PASS  " if ok else "FAIL  ") + name + ("" if ok else f"  {detail}"))


def sh(*cmd, cwd=None, check_rc=True, env=None, capture=True):
    p = subprocess.run(cmd, cwd=cwd, env=env, capture_output=capture, text=True)
    if check_rc and p.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} failed: {(p.stderr or '')[-800:]}")
    return p


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


# ─── work dir: PKI, execution-JWT keys, blueprint configs (verbatim) ─────────

def prepare(work: Path):
    import yaml
    sh(str(ROOT / "deployments/external/scripts/gen-certs.sh"), str(work / "pki"), "synanton-platform")
    keys = work / "keys"
    keys.mkdir()
    for k in ("current", "previous", "foreign"):
        sh("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", str(keys / f"{k}.key"))
        sh("openssl", "pkey", "-in", str(keys / f"{k}.key"), "-pubout", "-out", str(keys / f"{k}.pub"))
    secret = work / "jwt-secret"  # what `kubectl create secret generic gpu-gateway-jwt-keys` holds
    secret.mkdir()
    shutil.copy(keys / "current.key", secret / "current.key")
    shutil.copy(keys / "current.pub", secret / "current.pub")
    shutil.copy(keys / "previous.pub", secret / "previous.pub")
    for f in list((work / "pki").iterdir()) + list(secret.iterdir()):
        f.chmod(0o644)  # dev-only material, readable by the container user

    def configmap(path, name):
        for doc in yaml.safe_load_all((HOMELAB / path).read_text()):
            if doc and doc.get("kind") == "ConfigMap" and doc["metadata"]["name"] == name:
                return doc["data"]
        raise RuntimeError(f"ConfigMap {name} not found in {path}")

    (work / "gateway-config").mkdir()
    (work / "gateway-config" / "gateway-local.yaml").write_text(
        configmap("blueprints/gateway/gateway.yaml", "gpu-gateway-config")["gateway-local.yaml"])
    (work / "envoy-config").mkdir()
    (work / "envoy-config" / "envoy.yaml").write_text(
        configmap("blueprints/envoy/envoy-config.yaml", "envoy-config")["envoy.yaml"])


# ─── hostile tokens (signed with openssl, independent of the Gateway code) ──

def point_xy(pub_pem: Path):
    der = base64.b64decode("".join(l for l in pub_pem.read_text().splitlines() if "-----" not in l))
    pt = der[-65:]
    assert pt[0] == 4
    return pt[1:33], pt[33:]


def kid(pub_pem: Path) -> str:
    x, y = point_xy(pub_pem)
    canon = json.dumps({"crv": "P-256", "kty": "EC", "x": b64u(x), "y": b64u(y)}, separators=(",", ":"))
    return b64u(hashlib.sha256(canon.encode()).digest())


def der_to_raw(der: bytes) -> bytes:
    # SEQUENCE { INTEGER r, INTEGER s } → r||s (32 bytes each)
    i = 2 if der[1] < 0x80 else 3
    out = b""
    for _ in range(2):
        assert der[i] == 2
        n = der[i + 1]
        v = der[i + 2:i + 2 + n].lstrip(b"\x00")
        out += v.rjust(32, b"\x00")
        i += 2 + n
    return out


def token(key: Path, kid_value: str, *, aud="gpu-plane-execution", exp_delta=60, body=b"") -> str:
    now = int(time.time())
    header = {"alg": "ES256", "typ": "JWT", "kid": kid_value}
    claims = {"iss": "synanton-gpu-gateway", "aud": aud, "iat": now, "nbf": now - 5, "exp": now + exp_delta,
              "jti": str(uuid.uuid4()), "op": "HEALTH", "body_sha256": b64u(hashlib.sha256(body).digest())}
    signing_input = b64u(json.dumps(header).encode()) + "." + b64u(json.dumps(claims).encode())
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key)], input=signing_input.encode(),
                         capture_output=True, check=True).stdout
    return signing_input + "." + b64u(der_to_raw(sig))


def envoy(path: str, bearer: str | None, body: bytes | None = None) -> int:
    req = urllib.request.Request("http://127.0.0.1:28080" + path, data=body, method="POST" if body else "GET",
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": "Bearer " + bearer} if bearer else {})})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return -1


def wait(pred, timeout, step=2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if pred():
            return True
        time.sleep(step)
    return False


def gateway_ready() -> bool:
    try:
        with urllib.request.urlopen("http://127.0.0.1:28091/actuator/health/readiness", timeout=3) as r:
            return json.load(r).get("status") == "UP"
    except Exception:
        return False


# ─── main ────────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-build", action="store_true", help="reuse the existing gpu-gateway:t6a-local image")
    ap.add_argument("--keep", action="store_true", help="leave the stack running for inspection")
    args = ap.parse_args()

    if not args.skip_build:
        print("== building the Gateway (bootJar + image)")
        sh(str(ROOT / "gradlew"), ":java:gpu-gateway:bootJar", "-q", cwd=ROOT)
        sh("docker", "build", "-q", "-f", "deployments/homelab/docker/gateway.Dockerfile", "-t", GATEWAY_IMAGE, ".", cwd=ROOT)

    work = Path(tempfile.mkdtemp(prefix="t6a-e2e-"))
    compose = ["docker", "compose", "-f", str(HERE / "compose.yaml")]
    env = {**__import__("os").environ, "WORK_DIR": str(work), "GATEWAY_IMAGE": GATEWAY_IMAGE}
    try:
        prepare(work)
        print(f"== starting stack (work dir {work})")
        sh(*compose, "up", "-d", "--build", "--quiet-pull", cwd=HERE, env=env)

        check("Gateway readiness UP (keys loaded + JWKS listening)", wait(gateway_ready, 150),
              sh(*compose, "logs", "--tail=40", "gpu-gateway", cwd=HERE, env=env, check_rc=False).stdout[-1500:])
        with urllib.request.urlopen("http://127.0.0.1:28090/internal/.well-known/jwks.json", timeout=5) as r:
            jwks = json.load(r)
            cache = r.headers.get("Cache-Control", "")
        kids = {k["kid"] for k in jwks.get("keys", [])}
        cur_kid, prev_kid = kid(work / "keys/current.pub"), kid(work / "keys/previous.pub")
        check("JWKS: exactly current + previous keys, RFC 7638 kids, max-age=300",
              kids == {cur_kid, prev_kid} and "max-age=300" in cache and all("d" not in k for k in jwks["keys"]), str(jwks))

        # Envoy fetched JWKS at startup (async_fetch); allow the 5 s re-fetch if it raced the Gateway
        check("Envoy accepts a valid current-key token (JWKS fetched)",
              wait(lambda: envoy("/healthz", token(work / "keys/current.key", cur_kid)) == 200, 60))

        # ── §24.1.10 through the Gateway (Gateway signs) ──
        pb, rpc = gpu7_check.load_stubs()
        stub = rpc.GPUExecutionServiceStub(gpu7_check.channel("localhost:29090", work / "pki", "synanton-platform", False))

        def execute(model, op, payload):
            return stub.Execute(pb.ExecutionRequest(request_id=f"e2e-{uuid.uuid4()}", tenant_id="e2e-tenant", model=model,
                                                    model_version="1", operation=getattr(pb, op),
                                                    payload=json.dumps(payload).encode()), timeout=60)

        listed = stub.GetModels(pb.GetModelsRequest(operation=pb.EMBED), timeout=30)
        check("GetModels EMBED lists synanton-bge-base-embedding",
              any(m.model_id == "synanton-bge-base-embedding" for m in listed.models))

        r = execute("synanton-bge-base-embedding", "EMBED", {"model": "synanton-bge-base-embedding", "input": ["a", "b"]})
        body = json.loads(r.result) if r.result else {}
        check("EMBED via Gateway → Envoy → TEI mock: SUCCESS, 2×768 vectors",
              r.state == pb.SUCCESS and len(body.get("data", [])) == 2 and len(body["data"][0]["embedding"]) == 768,
              f"{pb.ExecutionState.Name(r.state)} {r.error.code} {r.error.message}")
        check("the execution JWT is not forwarded to the backend (forward: false)", body.get("saw_authorization") is False,
              str(body.get("saw_authorization")))

        r = execute("synanton-qwen3-4b-synthesis", "SYNTHESIZE",
                    {"model": "synanton-qwen3-4b-synthesis", "messages": [{"role": "user", "content": "hi"}]})
        check("SYNTHESIZE via Gateway: SUCCESS", r.state == pb.SUCCESS, f"{r.error.code} {r.error.message}")

        chunks = list(stub.ExecuteStream(pb.ExecutionRequest(
            request_id=f"e2e-{uuid.uuid4()}", tenant_id="e2e-tenant", model="synanton-qwen3-4b-synthesis",
            model_version="1", operation=pb.SYNTHESIZE,
            payload=json.dumps({"model": "synanton-qwen3-4b-synthesis",
                                "messages": [{"role": "user", "content": "hi"}]}).encode()), timeout=60))
        terms = [c for c in chunks if c.WhichOneof("event") == "terminal"]
        check("ExecuteStream via Gateway: data chunks + one SUCCESS terminal (body re-serialized, then signed)",
              len(chunks) >= 2 and len(terms) == 1 and terms[0].terminal.state == pb.SUCCESS,
              str([c.WhichOneof("event") for c in chunks]) + (f" {terms[0].terminal.error.code}" if terms else ""))

        r = execute("synanton-qwen3-reranker-0.6b", "RERANK",
                    {"model": "synanton-qwen3-reranker-0.6b", "query": "q", "documents": ["a", "b"]})
        check("RERANK via Gateway: SUCCESS", r.state == pb.SUCCESS, f"{r.error.code} {r.error.message}")

        # ── §24.2.6 Envoy rejects bad tokens ──
        emb = json.dumps({"model": "synanton-bge-base-embedding", "input": ["a"]}).encode()
        check("unsigned request → 401", envoy("/v1/embeddings", None, emb) == 401)
        check("token from a foreign key (current kid) → 401",
              envoy("/v1/embeddings", token(work / "keys/foreign.key", cur_kid, body=emb), emb) == 401)
        check("expired token → 401",
              envoy("/healthz", token(work / "keys/current.key", cur_kid, exp_delta=-300)) == 401)
        # jwt_authn answers an audience mismatch with 403 ("Audiences in Jwt are not allowed");
        # still a rejection. The Gateway maps 401 and 403 to execution_jwt_rejected.
        st = envoy("/healthz", token(work / "keys/current.key", cur_kid, aud="someone-else"))
        check(f"wrong audience → rejected (HTTP {st})", st == 403, f"status {st}")
        check("rotation: token signed with the previous key → 200",
              envoy("/healthz", token(work / "keys/previous.key", prev_kid)) == 200)

        logs = sh(*compose, "logs", "gpu-gateway", cwd=HERE, env=env, check_rc=False).stdout
        check("Gateway logs name the kids but contain no key material",
              cur_kid in logs and "PRIVATE KEY" not in logs and "BEGIN" not in logs)

        # ── §24.2.7 fail closed when JWKS is unavailable ──
        sh(*compose, "stop", "gpu-gateway", cwd=HERE, env=env)
        sh(*compose, "restart", "envoy", cwd=HERE, env=env)
        time.sleep(4)
        check("JWKS unavailable (Gateway down, fresh Envoy) → valid token rejected (fail closed)",
              envoy("/healthz", token(work / "keys/current.key", cur_kid)) in (401, -1))
        sh(*compose, "start", "gpu-gateway", cwd=HERE, env=env)
        ok = wait(gateway_ready, 150) and wait(
            lambda: envoy("/healthz", token(work / "keys/current.key", cur_kid)) == 200, 60)
        check("recovers once the Gateway is back (JWKS re-fetch, no Envoy restart)", ok)
    except Exception as e:
        check("e2e harness", False, f"{type(e).__name__}: {e}")
    finally:
        if args.keep:
            print(f"== stack left running (WORK_DIR={work}); stop: WORK_DIR={work} docker compose -f {HERE/'compose.yaml'} down -v")
        else:
            sh(*compose, "down", "-v", cwd=HERE, env=env, check_rc=False)
            shutil.rmtree(work, ignore_errors=True)
    print(f"== {len(PASS)} passed, {len(FAIL)} failed")
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
