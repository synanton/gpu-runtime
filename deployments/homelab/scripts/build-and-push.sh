#!/usr/bin/env bash
# Build and push the GPU-5 gateway image to the local registry.
# Only the gateway image is built — vLLM/Envoy/PostgreSQL are mirrored
# upstream images (see scripts/mirror-images.sh).
# Adapted from speech-to-speech-k8s/scripts/build-and-push.sh.
#
# Usage: ./scripts/build-and-push.sh <tag>
set -euo pipefail

TAG="${1:?usage: build-and-push.sh <tag>}"
REGISTRY="${REGISTRY:-local-registry:5000}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)" # repo root

echo "== gradle bootJar"
( cd "${ROOT_DIR}" && ./gradlew bootJar )

echo "== gpu-gateway image"
docker build -f "${ROOT_DIR}/deployments/homelab/docker/gateway.Dockerfile" \
  -t "${REGISTRY}/gpu-gateway:${TAG}" "${ROOT_DIR}"
docker push "${REGISTRY}/gpu-gateway:${TAG}"

echo
echo "Pushed ${REGISTRY}/gpu-gateway:${TAG}"
echo "Update image tags in blueprints/gateway/gateway.yaml and"
echo "helm/gpu-plane/values.yaml (images.gateway.tag) to match."
echo "At freeze, pin the digest (spec §8):"
echo "  docker buildx imagetools inspect ${REGISTRY}/gpu-gateway:${TAG} --format '{{.Manifest.Digest}}'"
