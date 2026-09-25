#!/usr/bin/env bash
# Issue a GPU-plane client certificate (mTLS principal = CN) from an EXISTING dev CA.
#
# gen-certs.sh always creates a NEW CA, which would invalidate the server certificate already
# in the gpu-gateway-tls Secret. This script signs one more client certificate with the CA
# the cluster trusts. The CN must also be registered under gpu-gateway.security.principals
# (gateway-local.yaml / Helm) with its allowed tenants. Doc: doc/GPU Plane mTLS Setup.md.
#
# Usage: issue-client-cert.sh <cn> [--pki DIR] [--days N] [--force]
#   --pki DIR  directory holding ca.crt + ca.key (default: <repo>/git-ignored/gpu5-pki)
#   --days N   validity (default: CERT_DAYS or 30, like gen-certs.sh)
#   --force    replace an existing <cn>.crt/<cn>.key
# Writes <cn>.crt (0644) and <cn>.key (0600) into DIR. Keys are never printed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CN="${1:-}"; [[ -n "$CN" && "$CN" != --* ]] || { sed -n '2,15p' "$0"; exit 2; }
shift
PKI="${ROOT}/git-ignored/gpu5-pki"
DAYS="${CERT_DAYS:-30}"
FORCE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --pki) PKI="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ "$CN" =~ ^[a-z0-9][a-z0-9.-]*$ ]] || { echo "CN must be lowercase [a-z0-9.-]: $CN" >&2; exit 2; }
for f in ca.crt ca.key; do [[ -r "$PKI/$f" ]] || { echo "missing $PKI/$f" >&2; exit 1; }; done
if [[ -e "$PKI/$CN.crt" && $FORCE -eq 0 ]]; then
  echo "$PKI/$CN.crt exists — use --force to re-issue" >&2; exit 1
fi

umask 077
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
openssl req -newkey rsa:2048 -nodes -subj "/CN=$CN" -keyout "$tmp/$CN.key" -out "$tmp/$CN.csr" 2>/dev/null
printf 'extendedKeyUsage=clientAuth\n' > "$tmp/ext"
openssl x509 -req -in "$tmp/$CN.csr" -CA "$PKI/ca.crt" -CAkey "$PKI/ca.key" -CAcreateserial \
  -CAserial "$tmp/ca.srl" -days "$DAYS" -extfile "$tmp/ext" -out "$tmp/$CN.crt" 2>/dev/null
openssl verify -CAfile "$PKI/ca.crt" "$tmp/$CN.crt" >/dev/null
install -m 0644 "$tmp/$CN.crt" "$PKI/$CN.crt"
install -m 0600 "$tmp/$CN.key" "$PKI/$CN.key"
echo "issued $PKI/$CN.crt (CN=$CN, $DAYS days, signed by $(openssl x509 -in "$PKI/ca.crt" -noout -subject | sed 's/subject=//'))"
echo "register it: gpu-gateway.security.principals.$CN.tenants in gateway-local.yaml / Helm, then restart the Gateway"
