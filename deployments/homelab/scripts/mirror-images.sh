#!/usr/bin/env bash
# Mirror the GPU-5 public images into the home-lab registry (local-registry:5000)
# so node1-node3 never need direct internet egress to pull them.
# Pattern copied from speech-to-speech-k8s/scripts/mirror-images.sh.
#
# Usage: ./scripts/mirror-images.sh [registry-host:port]
#
# Requires: `docker login local-registry:5000` on THIS host, and outbound
# internet access from THIS host (not from the cluster nodes).
set -euo pipefail

REGISTRY="${1:-local-registry:5000}"

# local name -> public image (pinned tags; record digests at freeze, spec §8)
declare -A IMAGES=(
  [vllm-openai]="vllm/vllm-openai:v0.29.0"        # CUDA 13.0 default build (D1)
  [envoy]="docker.io/envoyproxy/envoy:v1.31.0"
  [postgres]="docker.io/library/postgres:16.4"
  [eclipse-temurin]="docker.io/library/eclipse-temurin:21-jre" # gateway base
  [curlimages-curl]="docker.io/curlimages/curl:8.10.1"         # smoke-test pod
)

for local_name in "${!IMAGES[@]}"; do
  src="${IMAGES[$local_name]}"
  tag="${src##*:}"
  dest="${REGISTRY}/${local_name}:${tag}"
  echo "== ${src} -> ${dest}"
  docker pull "${src}"
  docker tag "${src}" "${dest}"
  docker push "${dest}"
done

echo
echo "Done. Record immutable digests for the spec §8 pin table:"
echo "  docker buildx imagetools inspect ${REGISTRY}/<name>:<tag> --format '{{.Manifest.Digest}}'"
