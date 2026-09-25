#!/usr/bin/env bash
# Pin the GPU-7 packaged images to immutable digests (Deployment Plan §8, T-K8S-53).
# Floating tags are prohibited in execution deployments: every image becomes
# <repo>:<tag>@sha256:<digest> (the tag stays for readability; the digest is what
# Docker resolves).
#
# Pins, in place:
#   deployments/external/compose.yaml              postgres image, gateway BASE_IMAGE
#   deployments/external/mock-provider/Dockerfile  FROM
#
# Usage: tools/pin-image-digests.sh [--check]
#   --check  do not modify; exit 1 if any reference is not digest-pinned
# Requires: docker buildx (network access to the registries).
# GPU-5 images live in local-registry:5000 and are pinned in helm/gpu-plane/values.yaml
# at freeze (gpu-5-implementation-plan.md §5) — that needs `docker login local-registry:5000`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXT="$ROOT/deployments/external"
CHECK=0
[[ "${1:-}" == "--check" ]] && CHECK=1

# file | sed-extended regex capturing the image reference in group 2 | description
REFS=(
  "$EXT/compose.yaml|^(    image: )(postgres:[^ #]+)|postgres"
  "$EXT/compose.yaml|^(        BASE_IMAGE: )([^ #]+)|gateway base (eclipse-temurin)"
  "$EXT/mock-provider/Dockerfile|^(FROM )([^ #]+)|mock provider base (python)"
)

digest_of() {
  docker buildx imagetools inspect "$1" --format '{{json .Manifest}}' \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['digest'])"
}

unpinned=0
for entry in "${REFS[@]}"; do
  IFS='|' read -r file regex desc <<<"$entry"
  ref="$(sed -nE "s/${regex}.*/\2/p" "$file" | head -1)"
  [[ -n "$ref" ]] || { echo "!! $desc: reference not found in $file" >&2; exit 2; }
  if [[ "$ref" == *@sha256:* ]]; then
    echo "pinned    $desc: $ref"
    continue
  fi
  if [[ $CHECK -eq 1 ]]; then
    echo "UNPINNED  $desc: $ref ($file)"
    unpinned=1
    continue
  fi
  digest="$(digest_of "$ref")"
  sed -i -E "s|${regex}|\1${ref}@${digest}|" "$file"
  echo "pinned    $desc: ${ref}@${digest}"
done

[[ $CHECK -eq 1 && $unpinned -eq 1 ]] && { echo "== floating tags present (Plan §8)"; exit 1; }
echo "== all GPU-7 image references are digest-pinned"
