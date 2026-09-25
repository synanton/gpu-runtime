#!/usr/bin/env bash
# Generate the GPU-5 execution-JWT signing keys (T-K8S-6a; Deployment Plan §12.1) and,
# optionally, create the gpu-gateway-jwt-keys Secret the Gateway mounts.
#
# ES256 = EC P-256. The private key is PKCS#8: the Gateway rejects SEC1
# ("BEGIN EC PRIVATE KEY", which `openssl ecparam -genkey` writes). The Secret holds exactly
# current.key + current.pub + previous.pub. Only the current private key is deployed.
#
# Usage:
#   generate-key.sh [--dir DIR] [--force] [--apply]   # new current + previous key pairs
#   generate-key.sh [--dir DIR] --rotate [--apply]    # previous := current, new current
#
#   --dir DIR   key directory (default: <repo>/git-ignored/gpu5-jwt; git-ignored, chmod 700)
#   --force     overwrite existing keys in DIR (initial generation only)
#   --rotate    rotation (T-K8S-25): the current pair becomes previous, a new current is made.
#               After --apply, restart the Gateway. Envoy picks up the new JWKS within 5 min,
#               and tokens signed with the previous key keep verifying meanwhile.
#   --apply     create/replace Secret gpu-gateway-jwt-keys in namespace gpu-plane (kubectl)
#
# Keys are never printed. Keep DIR/previous.key offline, or delete it: it isn't deployed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DIR="${ROOT}/git-ignored/gpu5-jwt"
NAMESPACE="${NAMESPACE:-gpu-plane}"
SECRET="${SECRET:-gpu-gateway-jwt-keys}"
FORCE=0 ROTATE=0 APPLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    --rotate) ROTATE=1; shift ;;
    --apply) APPLY=1; shift ;;
    -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1 (see --help)" >&2; exit 2 ;;
  esac
done

command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 2; }
mkdir -p "$DIR" && chmod 700 "$DIR"
umask 077

new_pair() { # name
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$DIR/$1.key" 2>/dev/null
  openssl pkey -in "$DIR/$1.key" -pubout -out "$DIR/$1.pub" 2>/dev/null
  chmod 600 "$DIR/$1.key"; chmod 644 "$DIR/$1.pub"
}

verify() { # name — PKCS#8, P-256, key/pub pair
  grep -q -- "-----BEGIN PRIVATE KEY-----" "$DIR/$1.key" || { echo "$1.key is not PKCS#8" >&2; exit 1; }
  openssl pkey -in "$DIR/$1.key" -noout -text 2>/dev/null | grep -q "NIST CURVE: P-256" \
    || { echo "$1.key is not P-256" >&2; exit 1; }
  cmp -s <(openssl pkey -in "$DIR/$1.key" -pubout 2>/dev/null) "$DIR/$1.pub" \
    || { echo "$1.key and $1.pub are not a pair" >&2; exit 1; }
}

kid() { # RFC 7638 thumbprint of <name>.pub (the kid the Gateway publishes)
  python3 - "$DIR/$1.pub" <<'EOF'
import base64, hashlib, json, sys
der = base64.b64decode("".join(l for l in open(sys.argv[1]) if "-----" not in l))
pt = der[-65:]; b = lambda v: base64.urlsafe_b64encode(v).rstrip(b"=").decode()
canon = json.dumps({"crv": "P-256", "kty": "EC", "x": b(pt[1:33]), "y": b(pt[33:])}, separators=(",", ":"))
print(b(hashlib.sha256(canon.encode()).digest()))
EOF
}

if [[ $ROTATE -eq 1 ]]; then
  [[ -f "$DIR/current.key" && -f "$DIR/current.pub" ]] || { echo "--rotate needs an existing current pair in $DIR" >&2; exit 1; }
  mv -f "$DIR/current.key" "$DIR/previous.key"
  mv -f "$DIR/current.pub" "$DIR/previous.pub"
  new_pair current
  echo "rotated: previous := old current, new current generated"
else
  if [[ -e "$DIR/current.key" || -e "$DIR/previous.key" ]] && [[ $FORCE -eq 0 ]]; then
    echo "keys already exist in $DIR — use --rotate to rotate, or --force to overwrite" >&2
    exit 1
  fi
  new_pair current
  new_pair previous
  echo "generated current + previous key pairs"
fi
verify current
[[ -f "$DIR/previous.key" ]] && verify previous
cmp -s "$DIR/current.pub" "$DIR/previous.pub" && { echo "current and previous keys are identical" >&2; exit 1; }

echo "  dir:          $DIR"
echo "  current kid:  $(kid current)"
echo "  previous kid: $(kid previous)"

if [[ $APPLY -eq 1 ]]; then
  kubectl -n "$NAMESPACE" create secret generic "$SECRET" \
    --from-file=current.key="$DIR/current.key" \
    --from-file=current.pub="$DIR/current.pub" \
    --from-file=previous.pub="$DIR/previous.pub" \
    --dry-run=client -o yaml | kubectl apply -f -
  echo "Secret $NAMESPACE/$SECRET applied. Restart the Gateway to load it:"
  echo "  kubectl -n $NAMESPACE rollout restart deploy/gpu-gateway"
else
  echo "Create the Secret with --apply, or:"
  echo "  kubectl -n $NAMESPACE create secret generic $SECRET --from-file=current.key=$DIR/current.key \\"
  echo "    --from-file=current.pub=$DIR/current.pub --from-file=previous.pub=$DIR/previous.pub"
fi
echo "Keep $DIR/previous.key offline or delete it (not deployed)."
