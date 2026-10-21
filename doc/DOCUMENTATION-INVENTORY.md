# GPU Runtime documentation inventory

**Reviewed:** 2026-10-21. Scope covers the repo entry point, deployment
contracts, setup/runbooks, acceptance evidence, and historical design plans.
**Parent tracker:** [platform #368](https://github.com/synanton/platform/issues/368); **component workstream:** [gpu-runtime #25](https://github.com/synanton/gpu-runtime/issues/25).

| Document/set | Classification | Disposition / action |
|---|---|---|
| [`../README.md`](../README.md) | Current / repo entry point | Corrected GPU-5/GPU-7 summary statuses and replaced obsolete platform design links. Dated evidence remains in the detailed sections. |
| [`README.md`](README.md) | Current / navigation | Added component documentation entry point and status vocabulary. |
| [`GPU-5  GPU-6  GPU-7 Deployment Plan.md`](GPU-5%20%20GPU-6%20%20GPU-7%20Deployment%20Plan.md) | Current / normative contract plan | Retain as cross-profile deployment plan; see its own revision and section statuses before relying on detailed evidence. |
| [`../deployments/homelab/gpu-5-implementation-plan.md`](../deployments/homelab/gpu-5-implementation-plan.md) | Current / GPU-5 execution plan | Canonical homelab steps, decisions, and pending acceptance evidence. |
| [`../deployments/external/gpu-7-implementation-plan.md`](../deployments/external/gpu-7-implementation-plan.md) | Current / GPU-7 execution plan | Canonical external-provider implementation and acceptance record. |
| [`GPU-5 Local Models Setup.md`](GPU-5%20Local%20Models%20Setup.md), [`GPU Plane mTLS Setup.md`](GPU%20Plane%20mTLS%20Setup.md), [`TEST_ENVIRONMENT_SETUP.md`](TEST_ENVIRONMENT_SETUP.md) | Current / setup guides | Retain as task-specific prerequisites; README links are collected in `doc/README.md`. |
| [`../deployments/homelab/docs/`](../deployments/homelab/docs/architecture.md) | Current / operations | Architecture, benchmarks, observability, and troubleshooting are deployment-specific operational references; retain. |
| [`GPU Execution Plane Implementation Plan v1.20.md`](GPU%20Execution%20Plane%20Implementation%20Plan%20v1.20.md), [`GPU Execution Plane Implementation Plan v1.21.md`](GPU%20Execution%20Plane%20Implementation%20Plan%20v1.21.md) | Historical / superseded plans | Retain as implementation history. Current runtime contract is Platform Design 1.30 plus the current deployment plan; the files should not be read as current status. |
| [Platform Design 1.20](https://github.com/synanton/platform/blob/main/docs/architecture/archive/synanton-design-1.20.md) | Superseded design history | Retained and labeled historical in README; current platform architecture is Design 1.30 / Platform Architecture 1.0. |

No deployment/evidence history was deleted. The README's older dated status
blocks remain labeled as snapshots; the Roadmap and dated integration section
are the current summary sources.
