#!/usr/bin/env bash
# Call the GPU Gateway over its platform transport: gRPC synanton.gpu.v1 over mTLS
# (Deployment Plan §4, §13). Thin wrapper around grpcurl that loads the canonical proto
# from this repo, so the server needs no reflection service.
#
# Usage:
#   tools/gpu-grpc-call.sh <host:port> <Method> [json-request]
#     Method: Execute | ExecuteStream | Cancel | GetStatus | GetCapacity | GetModels
#             or Service/Method for the admin control API, e.g.
#             GPUControlService/SetExternalRouting (needs an admin principal)
#
#   tools/gpu-grpc-call.sh localhost:9090 GetModels '{"operation":"SYNTHESIZE"}'
#
# ExecutionRequest.payload is proto `bytes` → base64 in JSON. Helper (when sourced):
#   payload_b64 '{"messages":[{"role":"user","content":"hi"}]}'
#
# Env:
#   GRPCURL            grpcurl binary (default: grpcurl on PATH)
#                      install: https://github.com/fullstorydev/grpcurl/releases
#   GPU_GRPC_CERT_DIR  PKI from gen-certs.sh (default: deployments/external/certs)
#   GPU_GRPC_CLIENT    client certificate name / principal CN (default: gpu7-smoke)
#   GPU_GRPC_PLAINTEXT=1  talk plaintext (only to a gateway in security.mode=insecure-plaintext)
#   GRPCURL_EXTRA      extra grpcurl flags, e.g. "-v" to print trailers
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRPCURL="${GRPCURL:-grpcurl}"
CERT_DIR="${GPU_GRPC_CERT_DIR:-$ROOT/deployments/external/certs}"
CLIENT="${GPU_GRPC_CLIENT:-gpu7-smoke}"

payload_b64() { printf '%s' "$1" | base64 -w0; }

tls_flags() {
  if [[ "${GPU_GRPC_PLAINTEXT:-0}" == "1" ]]; then
    echo "-plaintext"
    return
  fi
  for f in ca.crt "$CLIENT.crt" "$CLIENT.key"; do
    [[ -r "$CERT_DIR/$f" ]] || { echo "missing $CERT_DIR/$f — run deployments/external/scripts/gen-certs.sh" >&2; exit 2; }
  done
  echo "-cacert $CERT_DIR/ca.crt -cert $CERT_DIR/$CLIENT.crt -key $CERT_DIR/$CLIENT.key"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  ADDR="${1:?usage: gpu-grpc-call.sh <host:port> <Method> [json-request]}"
  METHOD="${2:?method required}"
  BODY="${3:-{\}}"
  # shellcheck disable=SC2046,SC2086
  exec "$GRPCURL" $(tls_flags) ${GRPCURL_EXTRA:-} \
    -import-path "$ROOT/java/gpu-contract/src/main/proto" \
    -proto synanton/gpu/v1/gpu_execution_service.proto \
    -d "$BODY" "$ADDR" "synanton.gpu.v1.$([[ "$METHOD" == */* ]] && echo "$METHOD" || echo "GPUExecutionService/$METHOD")"
fi
