# GPU-5 Local Models Setup

> **Placement update (2026-09-23):** node1 no longer runs GPU inference. The
> reranker is colocated with the embedding model on **node3** (one pod, two vLLM
> containers), and node1 hosts only CPU workloads (Gateway, Envoy, PostgreSQL).
> Verified 2026-09-23: node2 and node3 each already hold all three models under
> `/mnt/local-fast/qwen3-*` (flat) — no inter-node copies are needed, only
> normalization into the canonical `/mnt/local-fast/models/<model>` layout.
> Authoritative placement and normalization commands:
> `deployments/homelab/gpu-5-implementation-plan.md` (D2–D4, §4.3–4.5).

## 1. Purpose

This document describes how to download and install the open Hugging Face models used by the Synanton GPU-5 local deployment.

The GPU-5 baseline uses three local models:

| Role      | Hugging Face model            | Node    | Local model path                                |
| --------- | ----------------------------- | ------- | ----------------------------------------------- |
| Synthesis | `Qwen/Qwen3-4B-Instruct-2507` | `node2` | `/mnt/local-fast/models/qwen3-4b-instruct-2507` |
| Embedding | `Qwen/Qwen3-Embedding-0.6B`   | `node3` | `/mnt/local-fast/models/qwen3-embedding-0.6b`   |
| Reranking | `Qwen/Qwen3-Reranker-0.6B`    | `node1` | `/mnt/local-fast/models/qwen3-reranker-0.6b`    |

Models are stored on **node-local fast storage** rather than on a shared filesystem.

------

## 2. Node-Local Model Storage

The GPU-5 model layout is:

```text
node1
└── /mnt/local-fast/models/
    └── qwen3-reranker-0.6b/

node2
└── /mnt/local-fast/models/
    └── qwen3-4b-instruct-2507/

node3
└── /mnt/local-fast/models/
    └── qwen3-embedding-0.6b/
```

Each model is downloaded only to the node where its inference workload runs.

This avoids transferring model weights through a shared network filesystem during inference and allows each GPU workload to use the node's local storage.

------

## 3. Model Placement

### node2 — Synthesis

```text
node2
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

------

### node3 — Embedding

```text
node3
└── /mnt/local-fast/models/
    └── qwen3-embedding-0.6b/
```

Model:

```text
Qwen/Qwen3-Embedding-0.6B
```

Purpose:

```text
/v1/embeddings
```

------

### node1 — Reranking

```text
node1
└── /mnt/local-fast/models/
    └── qwen3-reranker-0.6b/
```

Model:

```text
Qwen/Qwen3-Reranker-0.6B
```

Purpose:

```text
/v1/rerank
```

------

## 4. Prerequisites

Run the following on each node that will download a model:

```bash
python3 -m pip install -U "huggingface_hub[cli]"
```

Verify:

```bash
hf --help
```

If Hugging Face authentication is required:

```bash
hf auth login
```

The models are public, so authentication is normally not required.

------

# 5. Download Models

## 5.1 node2 — Qwen3-4B Synthesis

SSH into `node2`:

```bash
ssh node2
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
du -sh /mnt/local-fast/models/qwen3-4b-instruct-2507
```

------

## 5.2 node3 — Qwen3 Embedding

SSH into `node3`:

```bash
ssh node3
```

Create the model directory:

```bash
sudo mkdir -p /mnt/local-fast/models/qwen3-embedding-0.6b
```

Download:

```bash
hf download Qwen/Qwen3-Embedding-0.6B \
  --local-dir /mnt/local-fast/models/qwen3-embedding-0.6b
```

Verify:

```bash
ls -lah /mnt/local-fast/models/qwen3-embedding-0.6b
```

Check model size:

```bash
du -sh /mnt/local-fast/models/qwen3-embedding-0.6b
```

------

## 5.3 node1 — Qwen3 Reranker

SSH into `node1`:

```bash
ssh node1
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
du -sh /mnt/local-fast/models/qwen3-reranker-0.6b
```

------

# 6. Complete Per-Node Commands

The complete setup can also be performed directly on each node.

### node1

```bash
mkdir -p /mnt/local-fast/models/qwen3-reranker-0.6b

hf download Qwen/Qwen3-Reranker-0.6B \
  --local-dir /mnt/local-fast/models/qwen3-reranker-0.6b
```

### node2

```bash
mkdir -p /mnt/local-fast/models/qwen3-4b-instruct-2507

hf download Qwen/Qwen3-4B-Instruct-2507 \
  --local-dir /mnt/local-fast/models/qwen3-4b-instruct-2507
```

### node3

```bash
mkdir -p /mnt/local-fast/models/qwen3-embedding-0.6b

hf download Qwen/Qwen3-Embedding-0.6B \
  --local-dir /mnt/local-fast/models/qwen3-embedding-0.6b
```

------

# 7. Verify Model Files

Run the appropriate checks on each node.

### node1

```bash
find /mnt/local-fast/models/qwen3-reranker-0.6b \
  -name '*.safetensors' \
  -printf '%f %s\n'
```

### node2

```bash
find /mnt/local-fast/models/qwen3-4b-instruct-2507 \
  -name '*.safetensors' \
  -printf '%f %s\n'
```

### node3

```bash
find /mnt/local-fast/models/qwen3-embedding-0.6b \
  -name '*.safetensors' \
  -printf '%f %s\n'
```

Verify configuration files:

```bash
test -f /mnt/local-fast/models/qwen3-4b-instruct-2507/config.json
test -f /mnt/local-fast/models/qwen3-embedding-0.6b/config.json
test -f /mnt/local-fast/models/qwen3-reranker-0.6b/config.json
```

The final command should be executed on the respective nodes or against the corresponding local filesystem.

------

# 8. Kubernetes Scheduling Requirement

Because model storage is node-local, **model pods MUST be scheduled onto the node containing their model**.

For example:

```text
node2
    |
    +-- /mnt/local-fast/models/qwen3-4b-instruct-2507
    |
    +-- GPU
    |
    +-- synthesis pod


node3
    |
    +-- /mnt/local-fast/models/qwen3-embedding-0.6b
    |
    +-- GPU
    |
    +-- embedding pod


node1
    |
    +-- /mnt/local-fast/models/qwen3-reranker-0.6b
    |
    +-- GPU
    |
    +-- reranker pod
```

A pod must not be allowed to float between nodes unless the model is also present on every possible target node.

------

# 9. Kubernetes HostPath Mount

The local model directory can be mounted into a pod using `hostPath`.

For the synthesis pod on `node2`:

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

The same pattern applies to the embedding and reranker pods.

------

# 10. Kubernetes Node Affinity

The pod should explicitly target the node containing its model.

For example, if Kubernetes node labels are:

```text
synanton.ai/model-synthesis=qwen3-4b
synanton.ai/model-embedding=qwen3-embedding-0.6b
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
  synanton.ai/model-embedding: qwen3-embedding-0.6b
```

Reranking:

```yaml
nodeSelector:
  synanton.ai/model-reranker: qwen3-reranker-0.6b
```

The exact labels should follow the existing cluster's node-label conventions.

------

# 11. Recommended Kubernetes Model Paths

Inside containers, use a consistent path:

```text
/models
```

while the host paths remain node-specific:

| Node  | Host path                                       | Container path |
| ----- | ----------------------------------------------- | -------------- |
| node1 | `/mnt/local-fast/models/qwen3-reranker-0.6b`    | `/models`      |
| node2 | `/mnt/local-fast/models/qwen3-4b-instruct-2507` | `/models`      |
| node3 | `/mnt/local-fast/models/qwen3-embedding-0.6b`   | `/models`      |

This keeps container configuration independent of the host's storage layout.

------

# 12. Runtime Model Mapping

Use stable Synanton model IDs instead of exposing Hugging Face repository names as the public API contract.

```text
synanton-qwen3-4b-synthesis
    → /models
    → node2
    → Qwen/Qwen3-4B-Instruct-2507

synanton-qwen3-embedding-0.6b
    → /models
    → node3
    → Qwen/Qwen3-Embedding-0.6B

synanton-qwen3-reranker-0.6b
    → /models
    → node1
    → Qwen/Qwen3-Reranker-0.6B
```

The public Synanton model ID should remain stable even if the underlying model revision or runtime configuration changes.

------

# 13. GPU-5 Runtime Architecture

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
              |                 |                 |
              v                 v                 v
          node2             node3             node1
              |                 |                 |
              v                 v                 v
         Qwen3-4B          Qwen3-Embedding     Qwen3-Reranker
           vLLM                0.6B                0.6B
```

The three inference workloads are independently deployable and independently tied to their node-local model storage.

------

# 14. Local Storage Principle

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

------

# 15. Model Version Reproducibility

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

The same approach should be used for the embedding and reranker models.

------

# 16. GPU-5 Reference Model Set

The initial GPU-5 local model set is:

```text
Synthesis
    Qwen/Qwen3-4B-Instruct-2507
    node2
    /mnt/local-fast/models/qwen3-4b-instruct-2507

Embedding
    Qwen/Qwen3-Embedding-0.6B
    node3
    /mnt/local-fast/models/qwen3-embedding-0.6b

Reranking
    Qwen/Qwen3-Reranker-0.6B
    node1
    /mnt/local-fast/models/qwen3-reranker-0.6b
```

This arrangement keeps each GPU inference workload isolated on its own node and uses the existing node-local fast storage for model weights.

------

# 17. Operational Lifecycle

The intended lifecycle is:

```text
Hugging Face
     |
     | hf download
     v
node-local /mnt/local-fast/models
     |
     | Kubernetes hostPath
     v
Inference pod
     |
     +---- node2 → synthesis
     |
     +---- node3 → embedding
     |
     +---- node1 → reranking
```

Models are downloaded once during node preparation.

Inference pods use the existing local model files and **do not download model weights from Hugging Face during normal startup**.

This provides deterministic startup behavior and avoids unnecessary network transfers.