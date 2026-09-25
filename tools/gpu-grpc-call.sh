#!/usr/bin/env bash
# Call the GPU Gateway over its platform transport: gRPC synanton.gpu.v1
# (Deployment Plan §4). Thin wrapper around grpcurl that loads the canonical
# proto from this repo, so the server needs no reflection service.
#
# Usage:
#   tools/gpu-grpc-call.sh <host:port> <Method> [json-request]
#     Method: Execute | ExecuteStream | Cancel | GetStatus | GetCapacity | GetModels
#
#   tools/gpu-grpc-call.sh localhost:9090 GetModels '{"operation":"SYNTHESIZE"}'
#
# ExecutionRequest.payload is proto `bytes` → base64 in JSON. Helper:
#   payload_b64 '{"messages":[{"role":"user","content":"hi"}]}'
#
# Env:
#   GRPCURL        grpcurl binary (default: grpcurl on PATH)
#                  install: https://github.com/fullstorydev/grpcurl/releases
#   GRPCURL_FLAGS  extra flags (default: -plaintext; the current build serves
#                  plaintext gRPC — mTLS is T-K8S-7/T-K8S-8, see Plan §13)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRPCURL="${GRPCURL:-grpcurl}"
FLAGS="${GRPCURL_FLAGS:--plaintext}"

payload_b64() { printf '%s' "$1" | base64 -w0; }

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  ADDR="${1:?usage: gpu-grpc-call.sh <host:port> <Method> [json-request]}"
  METHOD="${2:?method required}"
  BODY="${3:-{\}}"
  # shellcheck disable=SC2086
  exec "$GRPCURL" $FLAGS \
    -import-path "$ROOT/java/gpu-contract/src/main/proto" \
    -proto synanton/gpu/v1/gpu_execution_service.proto \
    -d "$BODY" "$ADDR" "synanton.gpu.v1.GPUExecutionService/$METHOD"
fi
