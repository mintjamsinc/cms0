# Task locks

This document describes how application code guarantees that a named
task runs **exactly once at a time** — across concurrent executions on
one node and across the nodes of a cluster — using ordinary JCR locks.

## Design

Single-execution guarding is not a cluster feature. The platform does
not provide a bespoke lock API for it; the guard is a **session-scoped
JCR lock** (`javax.jcr.lock.LockManager`) on a designated lock
resource:

- Lock state lives in the workspace database (`jcr_locks`), so it
  follows the deployment automatically: node-local when standalone,
  shared by all nodes when clustered. The same code gives in-JVM
  exclusion and cluster-wide exclusion with no mode-specific branch.
- Every script execution runs in its own JCR session, so two
  overlapping executions on the same node (a timer tick racing an
  async kick of the same task) exclude each other exactly as two nodes
  do.
- A **session-scoped** lock is released automatically when the session
  ends. A script that completes, throws, or forgets to unlock never
  leaves the lock behind.
- The **timeout** bounds how long a crashed owner (process kill, OOM,
  power loss — cases where the session never closes) can keep the
  lock. A lock past its timeout is treated as free and is reclaimed
  atomically by the next claimer.

The platform's own bootstrap and maintenance work (workspace startup,
blob cleanup, content deployment) is serialized by a different, purely
internal mechanism (`ClusterLeaseStore`, `jcr_cluster_locks`) — see
`documents/clustering.md`. The two are separate by design: one is
repository infrastructure, the other is an application-level guarantee,
and they share neither tables nor lock names.

## The pattern

Every guarded script begins with:

```groovy
def lock = repositorySession.getResource("/var/locks/<task name>")
        .tryLock(false, true, <timeout seconds>)
if (lock == null) {
    return  // another execution (any node) is already doing the work
}
try {
    // task body
} finally {
    lock.unlock()
}
```

- `tryLock(isDeep, isSessionScoped, timeoutSeconds)` acquires the lock
  and returns the resource, or returns `null` without waiting when the
  lock is held by another execution. Pass `isDeep = false` (lock nodes
  have no children worth locking) and `isSessionScoped = true` (so the
  lock can never outlive the execution).
- The explicit `unlock()` releases the lock as soon as the work is
  done; the session close is the safety net, not the primary release.
- `lock(...)` (same signatures) throws when the lock is held instead of
  returning `null`. There is no blocking wait — a recurring task skips
  and lets the next tick retry.

### Choosing the timeout

The timeout is the worst-case crash-recovery time: after a node dies
without closing its session, the task stays blocked until the timeout
passes. Size it at roughly **twice the task's worst-case runtime** — 
long enough that a healthy slow run is never presumed dead, short
enough that a crashed node does not stall the task for long. A running
execution that needs more time can extend its lock:

```groovy
javax.jcr.lock.LockManager lockManager =
        repositorySession.adaptTo(javax.jcr.Session.class).getWorkspace().getLockManager()
lockManager.getLock("/var/locks/<task name>").refresh()  // restarts the timeout
```

## The `/var/locks` convention

Application lock resources live under `/var/locks`, one folder per
task, named after the task (e.g. `/var/locks/nightly-report`). The
folder is empty content — its only job is to carry the lock.

- Create the folder once (provisioning) or on first use
  (`getOrCreateFolder` and commit before locking). Folders created
  through the Resource API are lockable out of the box.
- Access control on `/var/locks` decides who can guard tasks: grant the
  service users that run scheduled work write access there. Locks under
  different subtrees cannot collide, and application locks can never
  collide with the platform's internal leases (different mechanism,
  different table).

## Semantics

| Property | Behaviour |
|----------|-----------|
| Acquisition | Atomic: the lock row insert is guarded by the primary key on the locked item, so of N concurrent claimers exactly one wins |
| Normal release | `unlock()`, or automatically when the owning session closes |
| Crash release | The timeout: a lock past `lock_created + timeoutHint` is treated as free; the next claimer reclaims the stale row atomically (the row is pinned by its token, so a lock refreshed in the meantime is never clobbered) |
| Timeout accounting | `Lock.getSecondsRemaining()` reports the real remaining time (`Long.MAX_VALUE` when the lock has no timeout); `Lock.isLive()` follows it; `Lock.refresh()` restarts the timeout |
| Scope | Workspace-wide: standalone, all executions in the JVM; clustered, all executions on all nodes |
| Observability | Lock acquisition and release are journaled (`LOCKED` / `UNLOCKED` events) and visible in `jcr:lockOwner` on the lock resource; the `jcr_locks` table shows every held task lock by name |

## Java API

The same guard is available to Java code through the standard JCR API:

```java
LockManager lockManager = session.getWorkspace().getLockManager();
try {
    Lock lock = lockManager.lock("/var/locks/nightly-report",
            false, true, 600, null);
    try {
        // task body
    } finally {
        lockManager.unlock("/var/locks/nightly-report");
    }
} catch (LockException held) {
    // another execution is already doing the work
}
```

`timeoutHint` is in seconds, per the JCR specification; the
implementation enforces it as described above. `Long.MAX_VALUE` (or the
Resource API's `timeoutSeconds <= 0`) means no timeout — appropriate
only for interactive content locking, never for task guards.
