#!/usr/bin/env bash
# T-K8S-6a local end-to-end test: real Gateway (current code) + pinned Envoy running the
# blueprint config + mock TEI/vLLM backends, all in docker compose. No GPU, no cluster.
# GPU-5 plan §13.5; Deployment Plan §24.1.10, §24.2.6–7.
#
#   deployments/homelab/scripts/envoy-jwt-local-test.sh [--skip-build] [--keep]
#
# Needs: docker, openssl, and the tools/gpu7-check uv venv (grpcio, PyYAML; see its README).
# All keys and certificates are throwaway (a temp dir, removed afterwards).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PY="${ROOT}/tools/gpu7-check/.venv/bin/python"
[[ -x "$PY" ]] || { echo "missing $PY — set up tools/gpu7-check (uv venv) first" >&2; exit 2; }
exec "$PY" "${ROOT}/deployments/homelab/test/envoy-jwt/e2e_test.py" "$@"
