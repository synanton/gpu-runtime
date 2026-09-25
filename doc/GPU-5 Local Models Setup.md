# GPU-5 Local Models Setup

> **Canonical placement (PR #15):** one inference workload per physical GPU —
> **node1** embedding (GTX 1650, TEI + BGE-base), **node2** reranking (RTX 4060 Ti,
> vLLM), **node3** synthesis (RTX 5060 Ti, vLLM). This is the only supported layout;
> rationale: `deployments/homelab/gpu-5-implementation-plan.md` (D2/D4, §4).
>
> **State 2026-09-24:** Qwen3 models verified in place post-swap. BGE-base is
> **complete on all nodes** (models are mirrored everywhere by operator practice);
> BGE-small (fallback) is **complete on all nodes** (verified 2026-09-25). Downloads are manual via a `uv` venv (§4); if huggingface.co fails
> with SSL EOF, use the mirror endpoint (§4.1).

## 1. Purpose

This document describes how to download and install the open Hugging Face models used by the Synanton GPU-5 local deployment.

The GPU-5 baseline uses three models (one per GPU node):

| Role      | Hugging Face model            | Runtime | Node    | Local model path                                |
| --------- | ----------------------------- | ------- | ------- | ----------------------------------------------- |
| Synthesis | `Qwen/Qwen3-4B-Instruct-2507` | vLLM    | `node3` | `/mnt/local-fast/models/qwen3-4b-instruct-2507` |
| Embedding | `BAAI/bge-base-en-v1.5`       | TEI     | `node1` | `/mnt/local-fast/models/bge-base-en-v1.5`       |
| Reranking | `Qwen/Qwen3-Reranker-0.6B`    | vLLM    | `node2` | `/mnt/local-fast/models/qwen3-reranker-0.6b`    |

Documented embedding fallback (not deployed by default): `BAAI/bge-small-en-v1.5` at `/mnt/local-fast/models/bge-small-en-v1.5` on `node1`.

Models are stored on **node-local fast storage** rather than on a shared filesystem.

---

## 2. Node-Local Model Storage

The GPU-5 model layout is:

```text
node1
└── /mnt/local-fast/models/
    ├── bge-base-en-v1.5/
    └── bge-small-en-v1.5/        # documented fallback only

node2
└── /mnt/local-fast/models/
    └── qwen3-reranker-0.6b/

node3
└── /mnt/local-fast/models/
    └── qwen3-4b-instruct-2507/
```

Each model is downloaded only to the node where its inference workload runs.

> **Operator practice (2026-09-24):** in the homelab the models are in fact
> **mirrored on all nodes** (extra copies are harmless — hostPath mounts only
> ever read the local node's copy, plan D6). The per-node layout above is the
> *required* set; §5's per-node downloads remain the canonical provenance.

This avoids transferring model weights through a shared network filesystem during inference and allows each GPU workload to use the node's local storage.

---

## 3. Model Placement

### node3 — Synthesis

```text
node3
└── /mnt/local-fast/models/
    └── qwen3-4b-instruct-2507/
```

Model:

```text
Qwen/Qwen3-4B-Instruct-2507
```

Runtime:

```text
vLLM
```

Purpose:

```text
/v1/chat/completions
```

Synthesis gets the newest GPU (RTX 5060 Ti 16 GB) because it carries the greatest memory pressure (KV cache, concurrency, long context).

---

### node1 — Embedding

```text
node1
└── /mnt/local-fast/models/
    └── bge-base-en-v1.5/
```

Model:

```text
BAAI/bge-base-en-v1.5
```

Runtime:

```text
TEI (text-embeddings-inference, turing build)
```

Purpose:

```text
/v1/embeddings
```

node1's GTX 1650 is consumer Turing (sm_7.5, 4 GB, no bf16) — vLLM's pinned image ships no sm_75 kernels, so embedding is served by TEI's `turing` variant in fp16 (~0.5 GB weights, comfortable in 4 GB). Fallback: `BAAI/bge-small-en-v1.5` (same runtime), then TEI CPU on node1 if the GPU path fails bring-up validation (plan D4).

---

### node2 — Reranking

```text
node2
└── /mnt/local-fast/models/
    └── qwen3-reranker-0.6b/
```

Model:

```text
Qwen/Qwen3-Reranker-0.6B
```

Runtime:

```text
vLLM
```

Purpose:

```text
/v1/rerank
```

---

## 4. Prerequisites

Model downloads are **manual operations** (like container-image mirroring): run them by hand on each target node, never via automation.

Use a **`uv` venv** on each node (avoids PEP 668 externally-managed errors and system-Python quirks; `uv` is installed at `~/.local/bin/uv`):

```bash
# --seed installs pip into the venv; --python pins the interpreter
uv venv --python 3.12 --seed ~/k8s/.venv
source ~/k8s/.venv/bin/activate

uv pip install "huggingface_hub[cli]"   # or: pip install "huggingface_hub[cli]"
```

Verify (the venv must stay active — `hf` lives inside it):

```bash
hf version
```

### 4.1 Troubleshooting: SSL EOF against huggingface.co

Observed 2026-09-24 (node2): downloads failing with

```text
Error: Local entry not found. [SSL: UNEXPECTED_EOF_WHILE_READING] EOF occurred in violation of protocol (_ssl.c:1010)
```

Workaround — use the HF mirror endpoint, then re-run the download (it resumes, fetching only missing files):

```bash
export HF_ENDPOINT=https://hf-mirror.com
hf download <repo> --local-dir /mnt/local-fast/models/<model>
```

If Hugging Face authentication is required:

```bash
hf auth login
```

The models are public, so authentication is normally not required.

---

## 5. Download Models

## 5.1 node3 — Qwen3-4B Synthesis

SSH into `node3`:

```bash
ssh node3
```

Create the model directory:

```bash
sudo mkdir -p /mnt/local-fast/models/qwen3-4b-instruct-2507
```

Make sure the user performing the download has write access.

Then download:

```bash
hf download Qwen/Qwen3-4B-Instruct-2507 \
  --local-dir /mnt/local-fast/models/qwen3-4b-instruct-2507
```

Verify:

```bash
ls -lah /mnt/local-fast/models/qwen3-4b-instruct-2507
```

Check model size:

```bash
du -sh /mnt/local-fast/models/qwen3-4b-instruct-2507   # expected ~7.6G
```

---

## 5.2 node1 — BGE Embedding

SSH into `node1` and activate the venv from §4:

```bash
ssh node1
source ~/k8s/.venv/bin/activate
```

Create the model directory:

```bash
sudo mkdir -p /mnt/local-fast/models/bge-base-en-v1.5
```

Download:

```bash
hf download BAAI/bge-base-en-v1.5 \
  --local-dir /mnt/local-fast/models/bge-base-en-v1.5
```

Verify — TEI needs the tokenizer files, not just the weights:

```bash
ls /mnt/local-fast/models/bge-base-en-v1.5
test -f /mnt/local-fast/models/bge-base-en-v1.5/tokenizer.json \
  -a -f /mnt/local-fast/models/bge-base-en-v1.5/tokenizer_config.json \
  -a -f /mnt/local-fast/models/bge-base-en-v1.5/vocab.txt \
  && echo bge-base complete
```

Check model size:

```bash
du -sh /mnt/local-fast/models/bge-base-en-v1.5   # expected ~0.5–1.3G (fp32 weights; served fp16)
```

---

## 5.3 node2 — Qwen3 Reranker

SSH into `node2`:

```bash
ssh node2
```

Create the model directory:

```bash
sudo mkdir -p /mnt/local-fast/models/qwen3-reranker-0.6b
```

Download:

```bash
hf download Qwen/Qwen3-Reranker-0.6B \
  --local-dir /mnt/local-fast/models/qwen3-reranker-0.6b
```

Verify:

```bash
ls -lah /mnt/local-fast/models/qwen3-reranker-0.6b
```

Check model size:

```bash
du -sh /mnt/local-fast/models/qwen3-reranker-0.6b   # expected ~1.2G
```

---

## 5.4 node1 — BGE-small fallback (optional)

The documented embedding fallback (plan D4, §2 of the implementation plan). Not deployed by default; download so it is ready if needed. **State 2026-09-25: complete on node1/node2/node3** (weights + tokenizer files verified). To re-create it on a new node:

```bash
ssh node1
source ~/k8s/.venv/bin/activate   # uv venv from §4
hf download BAAI/bge-small-en-v1.5 \
  --local-dir /mnt/local-fast/models/bge-small-en-v1.5
```

---

## 6. Complete Per-Node Commands

The complete setup can also be performed directly on each node. Activate the venv
from §4 first (`source ~/k8s/.venv/bin/activate`); if huggingface.co fails with
SSL EOF, `export HF_ENDPOINT=https://hf-mirror.com` (§4.1).

### node1

```bash
mkdir -p /mnt/local-fast/models/bge-base-en-v1.5
hf download BAAI/bge-base-en-v1.5 \
  --local-dir /mnt/local-fast/models/bge-base-en-v1.5

# optional documented fallback
mkdir -p /mnt/local-fast/models/bge-small-en-v1.5
hf download BAAI/bge-small-en-v1.5 \
  --local-dir /mnt/local-fast/models/bge-small-en-v1.5
```

### node2

```bash
mkdir -p /mnt/local-fast/models/qwen3-reranker-0.6b

hf download Qwen/Qwen3-Reranker-0.6B \
  --local-dir /mnt/local-fast/models/qwen3-reranker-0.6b
```

### node3

```bash
mkdir -p /mnt/local-fast/models/qwen3-4b-instruct-2507

hf download Qwen/Qwen3-4B-Instruct-2507 \
  --local-dir /mnt/local-fast/models/qwen3-4b-instruct-2507
```

---

## 7. Verify Model Files

Run the appropriate checks on each node.

### node1

```bash
find /mnt/local-fast/models/bge-base-en-v1.5 \
  -name '*.safetensors' \
  -printf '%f %s\n'

# TEI also needs the tokenizer files:
ls /mnt/local-fast/models/bge-base-en-v1.5/tokenizer.json \
   /mnt/local-fast/models/bge-base-en-v1.5/tokenizer_config.json \
   /mnt/local-fast/models/bge-base-en-v1.5/vocab.txt
```

### node2

```bash
find /mnt/local-fast/models/qwen3-reranker-0.6b \
  -name '*.safetensors' \
  -printf '%f %s\n'
```

### node3

```bash
find /mnt/local-fast/models/qwen3-4b-instruct-2507 \
  -name '*.safetensors' \
  -printf '%f %s\n'
```

Verify configuration files:

```bash
test -f /mnt/local-fast/models/qwen3-4b-instruct-2507/config.json   # node3
test -f /mnt/local-fast/models/bge-base-en-v1.5/config.json         # node1
test -f /mnt/local-fast/models/qwen3-reranker-0.6b/config.json      # node2
```

Each command is executed on the respective node.

---

## 8. Kubernetes Scheduling Requirement

Because model storage is node-local, **model pods MUST be scheduled onto the node containing their model**.

For example:

```text
node3
    |
    +-- /mnt/local-fast/models/qwen3-4b-instruct-2507
    |
    +-- GPU (RTX 5060 Ti)
    |
    +-- synthesis pod


node1
    |
    +-- /mnt/local-fast/models/bge-base-en-v1.5
    |
    +-- GPU (GTX 1650)
    |
    +-- embedding pod


node2
    |
    +-- /mnt/local-fast/models/qwen3-reranker-0.6b
    |
    +-- GPU (RTX 4060 Ti)
    |
    +-- reranker pod
```

A pod must not be allowed to float between nodes unless the model is also present on every possible target node.

---

## 9. Kubernetes HostPath Mount

The local model directory can be mounted into a pod using `hostPath`.

For the synthesis pod on `node3`:

```yaml
volumes:
  - name: model
    hostPath:
      path: /mnt/local-fast/models/qwen3-4b-instruct-2507
      type: Directory
```

Mount:

```yaml
volumeMounts:
  - name: model
    mountPath: /models
    readOnly: true
```

The container sees:

```text
/models/
├── config.json
├── tokenizer.json
├── tokenizer_config.json
└── *.safetensors
```

The same pattern applies to the embedding (TEI) and reranker pods — both load the model from `/models` (`vllm serve /models`, `text-embeddings-inference --model-id /models`).

---

## 10. Kubernetes Node Affinity

The pod should explicitly target the node containing its model.

For example, if Kubernetes node labels are:

```text
synanton.ai/model-synthesis=qwen3-4b
synanton.ai/model-embedding=bge-base
synanton.ai/model-reranker=qwen3-reranker-0.6b
```

the synthesis deployment can use:

```yaml
nodeSelector:
  synanton.ai/model-synthesis: qwen3-4b
```

Embedding:

```yaml
nodeSelector:
  synanton.ai/model-embedding: bge-base
```

Reranking:

```yaml
nodeSelector:
  synanton.ai/model-reranker: qwen3-reranker-0.6b
```

The exact labels should follow the existing cluster's node-label conventions. (The homelab blueprints currently pin with `nodeName` instead — same coupling, simpler for one node per workload.)

---

## 11. Recommended Kubernetes Model Paths

Inside containers, use a consistent path:

```text
/models
```

while the host paths remain node-specific:

| Node  | Host path                                       | Container path |
| ----- | ----------------------------------------------- | -------------- |
| node1 | `/mnt/local-fast/models/bge-base-en-v1.5`       | `/models`      |
| node2 | `/mnt/local-fast/models/qwen3-reranker-0.6b`    | `/models`      |
| node3 | `/mnt/local-fast/models/qwen3-4b-instruct-2507` | `/models`      |

This keeps container configuration independent of the host's storage layout.

---

## 12. Runtime Model Mapping

Use stable Synanton model IDs instead of exposing Hugging Face repository names as the public API contract. Clients see only these logical IDs — never node addresses or runtimes.

```text
synanton-qwen3-4b-synthesis
    → /models
    → node3
    → Qwen/Qwen3-4B-Instruct-2507

synanton-bge-base-embedding
    → /models
    → node1
    → BAAI/bge-base-en-v1.5

synanton-qwen3-reranker-0.6b
    → /models
    → node2
    → Qwen/Qwen3-Reranker-0.6B
```

The public Synanton model ID should remain stable even if the underlying model revision or runtime configuration changes.

Documented fallback mapping (not deployed by default): `synanton-bge-small-embedding` → node1 → `BAAI/bge-small-en-v1.5`.

Note: the embedding public ID changed from `synanton-qwen3-embedding-0.6b` in the 2026-09-24 revision — the backend model changed (plan D4). Recorded as a deliberate PoC contract change.

---

## 13. GPU-5 Runtime Architecture

The logical Synanton API remains unified:

```text
                         Synanton Gateway
                                |
                              Envoy
                                |
              +-----------------+-----------------+
              |                 |                 |
              v                 v                 v
       /v1/chat/          /v1/embeddings       /v1/rerank
     completions              |                 |
              |               |                 |
              v               v                 v
          node3            node1             node2
              |               |                 |
              v               v                 v
         Qwen3-4B         BGE-base         Qwen3-Reranker
           vLLM             (TEI)              0.6B
                          fp16, turing         vLLM
```

The three inference workloads are independently deployable, each on its own physical GPU, and independently tied to their node-local model storage.

---

## 14. Local Storage Principle

GPU-5 uses:

```text
/mnt/local-fast/
```

as the node-local storage root.

Models should therefore **not** be downloaded to:

```text
~/.cache/huggingface
```

as the runtime model location.

Instead, the deployment should use explicit model directories:

```text
/mnt/local-fast/models/<model>
```

This makes the model inventory visible and deterministic and simplifies Kubernetes `hostPath` configuration.

---

## 15. Model Version Reproducibility

For repeatable deployments, production model downloads should eventually pin a specific Hugging Face revision.

The deployment record should contain:

```text
Synanton model ID
Hugging Face repository
Hugging Face revision / commit
runtime
quantization, if any
container image
Kubernetes deployment configuration
node placement
```

For example:

```yaml
model:
  id: synanton-qwen3-4b-synthesis
  repository: Qwen/Qwen3-4B-Instruct-2507
  revision: <PINNED_HF_COMMIT>
  host_path: /mnt/local-fast/models/qwen3-4b-instruct-2507
  runtime: vllm
```

The same approach should be used for the embedding (runtime: `tei`) and reranker models.

---

## 16. GPU-5 Reference Model Set

The initial GPU-5 local model set is:

```text
Synthesis
    Qwen/Qwen3-4B-Instruct-2507
    node3 (RTX 5060 Ti)
    /mnt/local-fast/models/qwen3-4b-instruct-2507

Embedding
    BAAI/bge-base-en-v1.5
    node1 (GTX 1650, TEI turing build, fp16)
    /mnt/local-fast/models/bge-base-en-v1.5
    fallback: BAAI/bge-small-en-v1.5

Reranking
    Qwen/Qwen3-Reranker-0.6B
    node2 (RTX 4060 Ti)
    /mnt/local-fast/models/qwen3-reranker-0.6b
```

This arrangement keeps each GPU inference workload isolated on its own physical GPU and uses the existing node-local fast storage for model weights.

---

## 17. Operational Lifecycle

The intended lifecycle is:

```text
Hugging Face
     |
     | hf download (manual operation)
     v
node-local /mnt/local-fast/models
     |
     | Kubernetes hostPath
     v
Inference pod
     |
     +---- node3 → synthesis
     |
     +---- node1 → embedding
     |
     +---- node2 → reranking
```

Models are downloaded once during node preparation.

Inference pods use the existing local model files and **do not download model weights from Hugging Face during normal startup**.

This provides deterministic startup behavior and avoids unnecessary network transfers.
