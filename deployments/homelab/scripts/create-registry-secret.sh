#!/usr/bin/env bash
# Create the imagePullSecret every pod in the `gpu-plane` namespace references
# (`local-registry-cred`). Copied from speech-to-speech-k8s — credentials are
# never stored in this repo; they come from your shell environment or a prompt.
#
# Usage:
#   REGISTRY_USER=docker-agent REGISTRY_PASSWORD=... ./scripts/create-registry-secret.sh
# or, to be prompted:
#   ./scripts/create-registry-secret.sh
set -euo pipefail

REGISTRY_HOST="${REGISTRY_HOST:-local-registry:5000}"
NAMESPACE="${NAMESPACE:-gpu-plane}"
SECRET_NAME="${SECRET_NAME:-local-registry-cred}"

if [[ -z "${REGISTRY_USER:-}" ]]; then
  read -rp "Registry username: " REGISTRY_USER
fi
if [[ -z "${REGISTRY_PASSWORD:-}" ]]; then
  read -rsp "Registry password: " REGISTRY_PASSWORD
  echo
fi

kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret docker-registry "${SECRET_NAME}" \
  --namespace "${NAMESPACE}" \
  --docker-server="${REGISTRY_HOST}" \
  --docker-username="${REGISTRY_USER}" \
  --docker-password="${REGISTRY_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f -

unset REGISTRY_PASSWORD

echo "Secret ${SECRET_NAME} created/updated in namespace ${NAMESPACE}."
