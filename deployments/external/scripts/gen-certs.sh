#!/usr/bin/env bash
# Generate a dev PKI for the Gateway's mTLS gRPC transport (Deployment Plan §13,
# T-K8S-7/8): one CA, a server certificate, and one client certificate per caller
# principal. The client certificate's CN is the principal name the Gateway maps to
# allowed tenants (gpu-gateway.security.principals.<CN>).
#
# Usage: gen-certs.sh [out-dir] [client-CN ...]
#   defaults: out-dir=deployments/external/certs, clients=synanton-platform gpu7-smoke synanton-benchmark
#
# Output (git-ignored — never commit keys):
#   ca.crt  ca.key  server.crt  server.key  <cn>.crt  <cn>.key
# Dev/test only. Production certificates come from the platform's CA (GPU-6 T-K8S-26).
set -euo pipefail

OUT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/certs}"
shift || true
CLIENTS=("$@")
[[ ${#CLIENTS[@]} -eq 0 ]] && CLIENTS=(synanton-platform gpu7-smoke synanton-benchmark)
DAYS="${CERT_DAYS:-30}"
SANS="DNS:localhost,IP:127.0.0.1,DNS:gateway,DNS:gpu-gateway,DNS:gpu-gateway.gpu-plane.svc,DNS:gpu-gateway.gpu-plane.svc.cluster.local"

mkdir -p "$OUT"
cd "$OUT"
umask 077

openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" -subj "/CN=synanton-gpu-dev-ca" \
  -keyout ca.key -out ca.crt 2>/dev/null

issue() { # name subject extensions
  openssl req -newkey rsa:2048 -nodes -subj "$2" -keyout "$1.key" -out "$1.csr" 2>/dev/null
  printf '%s\n' "$3" > "$1.ext"
  openssl x509 -req -in "$1.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days "$DAYS" \
    -extfile "$1.ext" -out "$1.crt" 2>/dev/null
  rm -f "$1.csr" "$1.ext"
}

issue server "/CN=gpu-gateway" "subjectAltName=$SANS
extendedKeyUsage=serverAuth"
for cn in "${CLIENTS[@]}"; do
  issue "$cn" "/CN=$cn" "extendedKeyUsage=clientAuth"
done
rm -f ca.srl
chmod 644 ./*.crt
echo "dev PKI written to $OUT (CA, server, clients: ${CLIENTS[*]})"
