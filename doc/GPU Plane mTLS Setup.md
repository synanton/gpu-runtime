# GPU Plane mTLS Setup (self-signed certificates)

The GPU Gateway's gRPC API (`synanton.gpu.v1`, port 9090) runs **mTLS only** by
default (Deployment Plan §13, T-K8S-7/8). The Gateway presents a server
certificate, **requires** a client certificate signed by its trusted CA, and
treats the client certificate's **CN as the caller principal**. The principal is
mapped to the tenants it may act for, so `ExecutionRequest.tenant_id` can no longer
be spoofed to spend (or evade) another tenant's budget.

This guide uses a **self-signed** PKI: a self-signed CA certificate you generate
locally, which then signs the server and client certificates. No public CA is
involved. That is the right model for the homelab (GPU-5) and the single-node
external profile (GPU-7). Production certificate issuance and rotation are GPU-6
(T-K8S-26).

---

## 1. What gets generated

`deployments/external/scripts/gen-certs.sh` creates, in one directory:

| File | Purpose |
| --- | --- |
| `ca.crt` / `ca.key` | Self-signed CA (`CN=synanton-gpu-dev-ca`). `ca.crt` is the trust anchor for both sides; **`ca.key` signs certificates — keep it private** |
| `server.crt` / `server.key` | Gateway server certificate. SANs: `localhost`, `127.0.0.1`, `gateway`, `gpu-gateway`, `gpu-gateway.gpu-plane.svc[.cluster.local]` |
| `<cn>.crt` / `<cn>.key` | One client certificate per principal (`CN=<cn>`, clientAuth only) |

All keys are RSA-2048 and are written with mode `600`. Validity is 30 days by default
(`CERT_DAYS=90 ./gen-certs.sh …` changes it). The output directory
`deployments/**/certs/` and every `*.key` are **git-ignored**, so never commit them.

## 2. Generate

```bash
cd deployments/external
./scripts/gen-certs.sh                                  # → ./certs, clients: synanton-platform gpu7-smoke
./scripts/gen-certs.sh ./certs synanton-platform gpu7-smoke alice   # custom principals
./scripts/gen-certs.sh /tmp/gpu5-pki synanton-platform  # any output directory
```

Requires `openssl`. Re-running overwrites everything, including the CA. See §7 for
the effect on existing clients.

### Verify

```bash
openssl verify -CAfile certs/ca.crt certs/server.crt certs/gpu7-smoke.crt   # both: OK
openssl x509 -in certs/server.crt -noout -subject -ext subjectAltName
openssl x509 -in certs/gpu7-smoke.crt -noout -subject -enddate           # CN = principal
```

## 3. Map principals to tenants

Principals are configured in the Gateway under `gpu-gateway.security`:

```yaml
gpu-gateway:
  security:
    mode: mtls                                  # default; fails startup without the files below
    tls:
      cert-chain: /etc/gpu-gateway/tls/server.crt
      private-key: /etc/gpu-gateway/tls/server.key
      client-ca: /etc/gpu-gateway/tls/ca.crt    # trusted CA for client certificates
    principals:
      synanton-platform:                        # = client certificate CN
        tenants: ["*"]                          # may assert any tenant_id
        roles: [admin]                          # routing-control RPCs
      gpu7-smoke:
        tenants: [smoke-tenant, smoke-budget-tenant]
```

How each call is decided:

| Situation | Result |
| --- | --- |
| No client certificate, or one signed by another CA | TLS handshake refused (`UNAVAILABLE`) |
| Valid certificate, CN not in `principals` | `UNAUTHENTICATED` / `unauthenticated` |
| `tenant_id` not in the principal's `tenants` | `PERMISSION_DENIED` / `tenant_not_allowed` |
| `GetStatus` / `Cancel` on another tenant's execution | `NOT_FOUND` / `NOT_APPLICABLE` (existence is not leaked) |

Startup fails closed (Plan §5.5) when `mode: mtls` is set and a TLS file is missing or
unreadable, or when `principals` is empty.

## 4. GPU-7 (Docker Compose)

```bash
cd deployments/external
./scripts/gen-certs.sh                  # certs/ is mounted read-only at /etc/gpu-gateway/tls
docker compose up -d --build
./scripts/smoke-test.sh                 # uses certs/gpu7-smoke.* (principal gpu7-smoke)
```

Principals live in `config/gateway-external.yaml`. Manual calls:

```bash
tools/gpu-grpc-call.sh localhost:9090 GetModels '{"operation":"SYNTHESIZE"}'
# explicit form:
grpcurl -cacert certs/ca.crt -cert certs/gpu7-smoke.crt -key certs/gpu7-smoke.key \
  -import-path java/gpu-contract/src/main/proto -proto synanton/gpu/v1/gpu_execution_service.proto \
  -d '{"operation":"SYNTHESIZE"}' localhost:9090 synanton.gpu.v1.GPUExecutionService/GetModels
```

`tools/gpu-grpc-call.sh` reads `GPU_GRPC_CERT_DIR` (default
`deployments/external/certs`) and `GPU_GRPC_CLIENT` (default `gpu7-smoke`).

## 5. GPU-5 (Kubernetes)

Generate the PKI on the admin workstation. Only the server material and the CA
certificate go into the cluster:

```bash
deployments/external/scripts/gen-certs.sh /tmp/gpu5-pki synanton-platform

kubectl -n gpu-plane create secret generic gpu-gateway-tls \
  --from-file=server.crt=/tmp/gpu5-pki/server.crt \
  --from-file=server.key=/tmp/gpu5-pki/server.key \
  --from-file=ca.crt=/tmp/gpu5-pki/ca.crt
```

The blueprint (`blueprints/gateway/gateway.yaml`) and the Helm chart mount the secret
at `/etc/gpu-gateway/tls` and register the principal `synanton-platform`
(Helm: `gateway.platformPrincipal`). Keep `/tmp/gpu5-pki/ca.key` offline. It is only
needed to issue new certificates.

## 6. Platform client (`platform/java/gateway`)

The Platform connects with its own client certificate (CN `synanton-platform`):

```bash
GPU_GATEWAY_ENDPOINT=localhost:9090
GPU_GATEWAY_TLS_ENABLED=true
GPU_GATEWAY_TLS_CA=/etc/synanton/gpu-tls/ca.crt
GPU_GATEWAY_TLS_CERT=/etc/synanton/gpu-tls/synanton-platform.crt
GPU_GATEWAY_TLS_KEY=/etc/synanton/gpu-tls/synanton-platform.key
```

Copy `ca.crt`, `synanton-platform.crt` and `synanton-platform.key` from the PKI
directory to the Platform host. On Kubernetes, use a Secret in the Platform's
namespace.

## 7. Add a principal, rotate, revoke

- **New principal:** `./scripts/gen-certs.sh` overwrites the CA. To add a client to
  an **existing** PKI, issue it with the existing CA:
  ```bash
  cd certs
  openssl req -newkey rsa:2048 -nodes -subj "/CN=alice" -keyout alice.key -out alice.csr
  printf 'extendedKeyUsage=clientAuth\n' > alice.ext
  openssl x509 -req -in alice.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 30 \
    -extfile alice.ext -out alice.crt && rm alice.csr alice.ext ca.srl
  ```
  Then add `alice` under `security.principals` and restart the Gateway.
- **Rotate everything:** re-run `gen-certs.sh`, redistribute the client material, and
  restart the Gateway (compose: `docker compose restart gateway`; GPU-5: recreate the
  `gpu-gateway-tls` Secret and `kubectl rollout restart deploy/gpu-gateway`). Existing
  clients fail the handshake until they have the new certificates.
- **Revoke a principal:** remove it from `security.principals` and restart the
  Gateway. Its calls then fail `UNAUTHENTICATED` even though the certificate is still
  valid. No CRL/OCSP is used with the self-signed dev CA.
- **Expiry:** check with `openssl x509 -noout -enddate -in <file>` and regenerate
  before expiry (30-day default).

## 8. Insecure plaintext (tests only)

`gpu-gateway.security.mode: insecure-plaintext` disables TLS, caller
authentication and tenant authorization. The Gateway logs a warning at startup. It
exists for unit and integration tests and loopback-only experiments. Never use it on
a network. Clients must then opt in explicitly (`GPU_GRPC_PLAINTEXT=1` for
`tools/gpu-grpc-call.sh`).

## 9. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Gateway exits: `security.tls.cert-chain is not a readable file` | PKI not generated or not mounted. Run `gen-certs.sh`; check the compose volume / `gpu-gateway-tls` Secret |
| Client: `UNAVAILABLE ... handshake` / `certificate_unknown` | Client cert not signed by the Gateway's `client-ca` (e.g. the PKI was regenerated). Redistribute |
| Client: `x509: certificate is valid for …, not <host>` | Connecting via a hostname not in the server SANs (§1). Use one of them, or add it to `SANS` in `gen-certs.sh` and regenerate |
| `UNAUTHENTICATED` / `unauthenticated` | Certificate CN is not in `security.principals` |
| `PERMISSION_DENIED` / `tenant_not_allowed` | `tenant_id` is not in that principal's `tenants` |
| `grpcurl: missing .../gpu7-smoke.crt` | Run `deployments/external/scripts/gen-certs.sh` |
