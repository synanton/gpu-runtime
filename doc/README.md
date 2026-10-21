# GPU Runtime documentation

**Reviewed:** 2026-10-21. GPU Runtime is an active implementation repository;
individual design approval, implemented code, acceptance evidence, and
production readiness are separate statuses.

## Start here

| Need | Canonical document |
|---|---|
| Current implementation and acceptance status | [Repository README](../README.md#roadmap) |
| GPU-5 / GPU-6 / GPU-7 contract and rollout plan | [Deployment Plan](GPU-5%20%20GPU-6%20%20GPU-7%20Deployment%20Plan.md) |
| Homelab GPU-5 implementation steps and acceptance | [GPU-5 plan](../deployments/homelab/gpu-5-implementation-plan.md) |
| GPU-7 external-provider implementation and acceptance | [GPU-7 plan](../deployments/external/gpu-7-implementation-plan.md) |
| Local model download and verification | [GPU-5 Local Models Setup](GPU-5%20Local%20Models%20Setup.md) |
| mTLS setup | [GPU Plane mTLS Setup](GPU%20Plane%20mTLS%20Setup.md) |
| Test environment prerequisites | [Test Environment Setup](TEST_ENVIRONMENT_SETUP.md) |
| Homelab operations and troubleshooting | [Homelab docs](../deployments/homelab/docs/architecture.md), [observability](../deployments/homelab/docs/observability.md), [troubleshooting](../deployments/homelab/docs/troubleshooting.md) |
| Document disposition | [Documentation inventory](DOCUMENTATION-INVENTORY.md) |

## Status vocabulary

- **Approved (design):** architecture decision accepted; not implementation.
- **Contract defined / frozen:** named interface is recorded; only a freeze
  decision with linked artifacts counts as frozen.
- **Implemented:** code or deployment manifests exist.
- **Validated:** a named test or environment produced dated evidence.
- **Production ready:** requires the rollout, operations, security, and
  acceptance evidence; neither GPU-5 nor GPU-7 is currently claimed as such.
- **Superseded:** useful historical record retained and linked to its successor.

The repository contains both local-inference GPU-5 and external-provider GPU-7
work. The Main Platform consumes the versioned `synanton.gpu.v1` gRPC contract;
it does not directly access GPU nodes or serving endpoints.
