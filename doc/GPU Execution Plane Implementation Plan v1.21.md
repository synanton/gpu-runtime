# GPU Execution Plane — Implementation Plan v1.21

The plan should now treat the following as **non-negotiable v1.20 invariants**:

1. **PostgreSQL remains the execution-state source of truth.**
2. **Exactly one artifact download may occur for a given `(model_id, digest)`.**
3. **A RUNNING execution must maintain an explicit runtime lease.**
4. **Lease expiry must never be confused with actual runtime failure without reconciliation.**
5. **Runtime retry decisions are based on execution acceptance, not generic network exceptions.**
6. **No background Kubernetes/operator reconciliation is introduced.**
7. **Equalix remains completely optional.**

------

## 1. Updated Architecture

The implementation now has four important infrastructure boundaries:

```
                    GPU Gateway
                         │
          ┌──────────────┼──────────────┐
          │              │              │
          ▼              ▼              ▼
     PostgreSQL     ArtifactResolver  VllmRuntime
          │              │              │
          │              ▼              ▼
          │        Shared Model Cache   vLLM
          │
          ▼
   Execution State
   + Lease/Heartbeat
   + Idempotency
   + Admission
```

The key point is that **PostgreSQL coordinates both execution state and distributed control decisions**.

No Redis is necessary.

------

# 2. GPU-2 — PostgreSQL Concurrency Control

The previous statement:

> "Two concurrent Execute calls cannot both consume the same remaining concurrency slot."

should become an explicit implementation invariant.

## Transaction

For admission:

```
BEGIN
   │
   ├── acquire model-level advisory lock
   │
   ├── lookup request_id
   │
   ├── validate request hash
   │
   ├── read ModelCapabilities
   │
   ├── COUNT active executions
   │
   ├── compare against concurrencyLimit
   │
   ├── INSERT execution
   │
   └── COMMIT
```

Use PostgreSQL:

```
SELECT pg_advisory_xact_lock(?);
```

where the lock key deterministically represents the model.

Then:

```
SELECT COUNT(*)
FROM executions
WHERE model_id = ?
  AND state IN (
      'ACCEPTED',
      'QUEUED',
      'MODEL_LOADING',
      'RUNNING'
  );
```

This makes the admission decision serializable **per model** without requiring distributed locking infrastructure.

### Important refinement

I would prefer the PostgreSQL advisory lock over simply relying on the `COUNT()` query.

The `COUNT()` alone has a race:

```
Gateway A → COUNT = 7
Gateway B → COUNT = 7


A → INSERT
B → INSERT


limit = 8
```

Both can believe the slot is available.

The advisory transaction lock closes that race.

------

# 3. GPU-2 — JDBC Decision

Keep the original decision:

> **JDBC / JdbcTemplate, not jOOQ and not ORM.**

This is especially appropriate because the persistence model is intentionally small and explicit.

Primary queries are:

```
INSERT execution
SELECT by request_id
SELECT by execution_id
COUNT active executions
UPDATE execution state
UPDATE heartbeat
UPDATE queued executions
DELETE expired executions
```

There is no meaningful domain benefit from introducing an ORM.

------

# 4. GPU-3 — Artifact Download Coordination

Add this as a formal `ArtifactResolver` invariant.

## Requirement

For:

```
(model_id, digest)
```

there can be **exactly one active downloader** across all Gateway replicas.

Preferred implementation:

```
PostgreSQL advisory lock
```

rather than filesystem locking.

Why?

The Gateway replicas may have:

- different local filesystems
- different containers
- different nodes
- shared PVC semantics that vary by deployment

PostgreSQL already provides the distributed coordination primitive.

------

# 5. Artifact Resolution Algorithm

```
resolve(model_id, digest)
        │
        ▼
check shared cache
        │
   ┌────┴────┐
   │ exists  │ missing
   ▼         ▼
 return    acquire
           advisory lock
              │
              ▼
         check cache again
              │
         ┌────┴────┐
         │ exists  │ missing
         ▼         ▼
       return    download
                   │
                   ▼
               verify digest
                   │
                   ▼
             atomic publish
                   │
                   ▼
                 return
```

The **second cache check after acquiring the lock is mandatory**.

Otherwise:

```
Gateway A checks → missing
Gateway B checks → missing


A acquires lock
B waits


A downloads
A publishes


B acquires lock


B must NOT download again.
```

------

# 6. Atomic Artifact Publication

Do not download directly into the final model directory.

Use:

```
/model-cache/
    model-v2/
        sha256-abc123/
            ...
```

with staging:

```
/model-cache/.staging/
    model-v2/
        sha256-abc123/
```

Then:

```
download
   ↓
verify SHA-256
   ↓
fsync if required
   ↓
atomic rename/move
   ↓
available
```

A partially downloaded 70 GB model must never appear as an available artifact.

------

# 7. Optional `model_artifact_downloads` Table

I would **not add the table unless operational requirements later justify it**.

PostgreSQL advisory locking is enough for mutual exclusion.

A table becomes useful if we need persistent visibility such as:

```
DOWNLOADING
READY
FAILED
FAILED_INTEGRITY
```

for operational dashboards.

For v1.20:

> **Advisory lock + filesystem artifact state is simpler.**

------

# 8. GPU-3 — Runtime Lease

This should become part of the execution domain model.

Add:

```
updated_at
lease_timeout
```

or equivalently configure:

```
execution:
  lease:
    timeout: 5m
    heartbeatInterval: 60s
```

Invariant:

```
RUNNING execution
       │
       ▼
updated_at must continuously advance
       │
       ▼
until terminal state
```

------

# 9. VllmRuntime Heartbeat

After the execution is confirmed as accepted:

```
VllmRuntime.execute()
        │
        ▼
execution accepted
        │
        ├───────────────┐
        │               │
        ▼               ▼
   vLLM request    heartbeat scheduler
                        │
                        ▼
                 every 60 seconds
                        │
                        ▼
              UPDATE executions
              SET updated_at = NOW()
```

Heartbeat stops when:

```
SUCCEEDED
FAILED
CANCELLED
```

------

# 10. Heartbeat Implementation

Use a shared application scheduler rather than creating an unrestricted thread per request.

For example:

```
ScheduledExecutorService
```

with a bounded pool.

Conceptually:

```
ScheduledFuture<?> heartbeat =
    scheduler.scheduleAtFixedRate(
        () -> executionRepository.refreshHeartbeat(executionId),
        60,
        60,
        TimeUnit.SECONDS
    );
```

Then:

```
try {
    executeRuntime();
} finally {
    heartbeat.cancel(false);
}
```

The implementation should ensure heartbeat failure does **not** silently turn into execution success.

------

# 11. Streaming Optimization

If the vLLM integration uses streaming:

```
vLLM chunk
   ↓
heartbeat implicitly refreshed
```

can reduce the need for a dedicated heartbeat.

However, I would **retain the explicit heartbeat abstraction**.

Why?

Because a long inference may legitimately have periods with no response chunks.

Therefore:

```
streaming activity
       +
explicit lease heartbeat
```

is safer than assuming streaming itself guarantees liveness.

------

# 12. Lazy Reconciliation

The existing decision remains correct:

**No background controller.**

`GetStatus()` performs lazy reconciliation.

```
GetStatus
    │
    ▼
terminal?
    │
    ├── yes → return
    │
    ▼
RUNNING?
    │
    ▼
lease expired?
    │
 ┌──┴──┐
no    yes
│      │
│      ▼
│   runtime ping
│      │
│   ┌──┴───┐
│ alive   dead
│   │       │
│   ▼       ▼
│ refresh  FAILED
│
▼
return
```

------

# 13. Important Reconciliation Rule

The runtime heartbeat and reconciliation should use the same repository operation semantics.

If the runtime is alive:

```
UPDATE executions
SET updated_at = NOW()
WHERE execution_id = ?
  AND state = 'RUNNING';
```

If it is dead:

```
UPDATE executions
SET state = 'FAILED',
    error_code = 'RUNTIME_UNAVAILABLE',
    updated_at = NOW()
WHERE execution_id = ?
  AND state = 'RUNNING';
```

The state predicate prevents a late reconciliation request from overwriting:

```
SUCCEEDED
FAILED
CANCELLED
```

with `RUNTIME_UNAVAILABLE`.

This is an important race-prevention detail.

------

# 14. RetryDisposition

The previous design should be made even more explicit.

`VllmRuntime` must determine:

```
NOT_ACCEPTED
ACCEPTED_UNKNOWN
COMPLETED_UNKNOWN
DEFINITELY_FAILED
```

from **runtime protocol semantics**, not:

```
IOException → retry
```

For HTTP-based vLLM communication, inspect:

- HTTP status
- response headers
- whether the request was accepted
- whether a response body/stream was established
- connection state

The runtime adapter translates those facts into `RetryDisposition`.

The domain never needs to understand HTTP.

------

# 15. GPU-3 State Ownership

A useful final rule is:

```
Component             Owns
──────────────────────────────────────────
Gateway               execution lifecycle
PostgreSQL             execution truth
AdmissionService       admission decision
ModelManager           model readiness
ArtifactResolver       artifact availability
VllmRuntime            runtime communication
Scheduler               target selection
Kubernetes              physical placement
vLLM                    inference execution
```

This prevents responsibility leakage.

------

# 16. Updated GPU-3 Definition of Done

I would now make GPU-3 DoD:

### Artifact lifecycle

- □ `ArtifactResolver` interface implemented.
- □ Secure registry authentication implemented.
- □ Artifact digest verification implemented.
- □ Shared model cache implemented.
- □ Artifact downloads use distributed PostgreSQL advisory locking.
- □ Second cache check occurs after acquiring the lock.
- □ Artifact publication is atomic.
- □ Concurrent Gateway replicas cannot download the same artifact simultaneously.

### Runtime lifecycle

- □ `VllmRuntime` implemented behind `ExecutionRuntime`.
- □ `RetryDisposition` implemented.
- □ Runtime acceptance semantics are explicitly detected.
- □ Automatic retry only occurs for `NOT_ACCEPTED`.
- □ RUNNING executions receive heartbeat updates.
- □ Heartbeat interval is configurable.
- □ Heartbeat stops on terminal execution state.
- □ Runtime shutdown uses Kubernetes readiness/draining semantics.

### Reconciliation

- □ No background controller exists.
- □ `GetStatus()` performs lazy reconciliation.
- □ Expired RUNNING leases trigger runtime verification.
- □ Live runtime refreshes the execution heartbeat.
- □ Missing runtime transitions execution to `RUNTIME_UNAVAILABLE`.
- □ Terminal executions cannot be overwritten by stale reconciliation.

### Model loading

- □ `MODEL_LOADING` is represented explicitly.
- □ Load failures transition the model to `LOAD_FAILED`.
- □ Queued executions for a failed model are atomically failed.
- □ Retry uses exponential backoff.
- □ Artifact integrity failures are distinguishable from transient registry failures.

------

# 17. Updated PR Sequence

I would now lock the implementation into these PRs:

| PR       | Scope                                                        | GPU   |
| -------- | ------------------------------------------------------------ | ----- |
| **PR-1** | Gradle, Java 21, Spring Boot, contract, Cursor rules         | GPU-1 |
| **PR-2** | Domain, state machine, PostgreSQL, idempotency               | GPU-2 |
| **PR-3** | ModelManager, ArtifactResolver, shared cache, vLLM runtime, heartbeat | GPU-3 |
| **PR-4** | SynAnton Core `GpuExecutionClient` + CPU fallback            | GPU-4 |
| **PR-5** | On-prem Helm, HA PostgreSQL, mTLS, monitoring                | GPU-5 |
| **PR-6** | Failure injection, load testing, production hardening        | GPU-5 |
| **PR-7** | Equalix scheduler, only if justified by measurements         | GPU-6 |

### PR-3 is now the critical integration boundary

It should prove:

```
Main Platform
      │
      │ gRPC/mTLS
      ▼
GPU Gateway
      │
      ├── PostgreSQL
      │      ├── idempotency
      │      ├── admission
      │      ├── execution state
      │      └── heartbeat
      │
      ├── ArtifactResolver
      │      └── shared model cache
      │
      └── VllmRuntime
             └── vLLM
```

At that point the architecture has essentially reached the **minimum production-grade GPU execution plane** without introducing Equalix, Kafka, Redis, Kubernetes operators, or other unnecessary infrastructure.

**One additional recommendation:** use **PostgreSQL advisory locks for both model-level admission serialization and artifact-download coordination**. That gives the GPU plane one consistent distributed-coordination primitive and avoids introducing a second mechanism whose failure semantics the team would have to reason about.