#!/usr/bin/env python3
"""GPU-7 packaging checklist (Deployment Plan §46, T-K8S-53) — executable form.

Static checks run against deployments/external/ (compose + gateway config). With --live
the T-K8S-51 acceptance suite (ExternalAcceptanceTest) and the packaged smoke test are
run too. The freeze attestation (Plan §49) is a human sign-off and is NOT produced here —
this tool gives the reviewer the evidence.

Usage:  python3 tools/gpu7-package-check.py [--live]
Needs:  python3 + PyYAML; --live also needs Docker, grpcurl and the dev PKI.
"""
import argparse
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
EXT = ROOT / "deployments" / "external"
results = []


def check(item, ok, detail=""):
    results.append(ok)
    print(f"{'PASS' if ok else 'FAIL'}  {item}" + (f"  — {detail}" if detail and not ok else ""))


def run(cmd, cwd=ROOT, timeout=1800):
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout + p.stderr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--live", action="store_true", help="also run T-K8S-51 acceptance + packaged smoke")
    args = ap.parse_args()

    compose = yaml.safe_load((EXT / "compose.yaml").read_text())
    services = compose["services"]
    cfg = yaml.safe_load((EXT / "config" / "gateway-external.yaml").read_text())["gpu-gateway"]
    compose_text = (EXT / "compose.yaml").read_text()

    print("== §46 package shape")
    check("no GPU nodes (no nvidia runtime / device reservations)",
          "nvidia" not in compose_text and "devices" not in compose_text)
    check("no Envoy, no vLLM services", not any(k in s for s in services for k in ("envoy", "vllm")),
          str(list(services)))
    check("no execution JWT configured", "jwt" not in (EXT / "config" / "gateway-external.yaml").read_text().lower())
    check("deployment mode external-only (dispatch.strategy=external)", cfg["dispatch"]["strategy"] == "external")

    print("== §8 image pinning")
    rc, out = run([str(ROOT / "tools" / "pin-image-digests.sh"), "--check"])
    check("all images digest-pinned (tools/pin-image-digests.sh --check)", rc == 0, out.strip().splitlines()[-1])

    print("== fail-closed controls configured")
    sec = cfg.get("security", {})
    check("mTLS required (security.mode=mtls) with registered principals",
          sec.get("mode") == "mtls" and bool(sec.get("principals")))
    check("kill switch present (routing.external-enabled)", "external-enabled" in cfg.get("routing", {}))
    check("budget enforcement enabled", cfg.get("budget", {}).get("enforcement") == "enabled")
    check("sensitivity policy configured", bool(cfg.get("sensitivity", {}).get("block-external-tags")))
    check("cost ledger enabled", cfg.get("usage", {}).get("cost-ledger") == "enabled")
    real = [p for p, c in cfg["providers"].items() if p != "mock"]
    check("spend guard on every real provider (allowed-model-pattern)",
          all(cfg["providers"][p].get("allowed-model-pattern") for p in real), str(real))
    ops = cfg["model-catalog"]["operations"]
    check("SYNTHESIZE, EMBED, RERANK and RESPOND routed", all(o in ops for o in ("SYNTHESIZE", "EMBED", "RERANK", "RESPOND")))
    check("mock provider present for T-K8S-51", "mock" in cfg["providers"] and "mock-provider" in services)

    if args.live:
        print("== T-K8S-51 acceptance (ExternalAcceptanceTest, fake providers + PostgreSQL)")
        rc, out = run(["./gradlew", ":java:gpu-gateway:test", "--tests", "*ExternalAcceptanceTest", "-q"])
        check("ExternalAcceptanceTest passes", rc == 0, out[-400:])
        print("== packaged stack smoke (compose, mTLS, mock provider)")
        rc, out = run([str(EXT / "scripts" / "smoke-test.sh")], cwd=EXT, timeout=600)
        last = out.strip().splitlines()[-1] if out.strip() else ""
        check(f"smoke-test.sh: {last}", rc == 0, out[-400:])
    else:
        print("SKIP  T-K8S-51 acceptance + packaged smoke (use --live)")

    print(f"== {sum(results)}/{len(results)} checklist items pass"
          + ("" if all(results) else " — package NOT freeze-ready"))
    print("   Freeze attestation (Plan §49) remains a reviewer sign-off.")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
