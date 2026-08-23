# Resource locks

This document describes the JCR lock: what it guarantees, how it behaves when
the holder dies, and where it belongs.

A lock protects **a specific resource against concurrent mutation**. Two
editors must not overwrite each other; a document being processed must not be
edited underneath the processor. That is what a lock is for.

> **Serializing a recurring task is a different question, and it has a different
> answer.** "This work runs on one node only" is a property of the route that
> drives the work, declared on the route with `cms:lock` and enforced before the
> work begins — not something a script decides for itself. The mechanism is the
> same lock described here; where it is declared is what differs, and it decides
> whether the work it protects can be read and rewired or has to live inside one
> script's `try/finally`. See `documents/eip-session-lifecycle.md` §6.

## Design

Lock state lives in the workspace database (`jcr_locks`), so it follows the
deployment automatically: node-local when standalone, shared by all nodes when
clustered. The same code gives in-JVM exclusion and cluster-wide exclusion with
no mode-specific branch.

The platform's own bootstrap and maintenance work (workspace startup, blob
cleanup, content deployment) is serialized by a different, purely internal
mechanism (`ClusterLeaseStore`, `jcr_cluster_locks`) — see
`documents/clustering.md`. The two are separate by design: one is repository
infrastructure, the other is an application-level guarantee, and they share
neither tables nor lock names.

## Two modes, and why the choice is never implicit

| | Session-scoped | Persistent (open-scoped) |
|---|---|---|
| `isSessionScoped` | `true` | `false` |
| Released by | The owning session closing, or an explicit unlock | An explicit unlock only |
| Token inheritance | The acquiring session alone holds the token | Any session of the same principal inherits it |
| Timeout | Recommended: bounds how long a crashed owner blocks the resource | Usually omitted, meaning no expiry |
| Typical use | A critical section within one unit of work | Content held across requests while someone edits it |

The inheritance rule is what makes each mode usable. A persistent lock outlives
the session that took it, so its token has to be inheritable — otherwise nobody
could ever release it. A session-scoped lock means "held by that one session", so
its token stays with the acquiring session: inheriting it would let a second
session running as the same user unlock, and write through, a critical section
somebody else is inside. Since almost all service work runs as the same service
user, that is not a hypothetical.

Because the lifecycles differ this much, `cms:lock` requires `isSessionScoped` to
be stated rather than defaulted.

## Timeouts

A timeout bounds how long a crashed owner — process kill, OOM, power loss, cases
where the session never closes — can keep the resource. A lock past its timeout
is treated as free and reclaimed atomically by the next claimer; the stale row is
pinned by its token, so a lock refreshed or re-acquired in the meantime is never
clobbered.

**A timeout is not a budget for how long the work may take.** A holder that is
still alive extends its lease:

```groovy
javax.jcr.lock.LockManager lockManager =
        repositorySession.adaptTo(javax.jcr.Session.class).getWorkspace().getLockManager()
lockManager.getLock("/var/locks/<name>").refresh()  // restarts the timeout
```

Routes do not need to do this: `cms:lock` registers the lease and the platform
renews it in the background for as long as the run is alive. That is what lets
the timeout be sized as a failover-detection window instead of being hand-fitted
to the work.

## The `/var/locks` convention

Lock resources that do not correspond to a content node live under `/var/locks`,
one folder per name. The folder is empty content — its only job is to carry the
lock.

`nt:folder` does not inherit `mix:lockable`, so the mixin has to be declared when
the folder is created:

```xml
<to id="ensure-lock-node"
    uri="cms:createFolder?path=/var/locks/nightly-report&amp;mixins=mix:lockable&amp;conflictBehavior=IGNORE&amp;runAs=..."/>
```

Creating content and locking it are separate steps. Folding creation into the
lock would mean the lock operation writes to the caller's session — and JCR
refuses to lock a node whose session has unsaved changes beneath the target, so
the two cannot share a session anyway.

Access control on `/var/locks` decides who can take these locks: grant the
service users that run scheduled work write access there.

## Semantics

| Property | Behaviour |
|----------|-----------|
| Acquisition | Atomic: the lock row insert is guarded by the primary key on the locked item, so of N concurrent claimers exactly one wins |
| Normal release | An explicit unlock, or — for a session-scoped lock — the owning session closing |
| Crash release | The timeout: a lock past `lock_created + timeoutHint` is treated as free; the next claimer reclaims the stale row atomically |
| Timeout accounting | `Lock.getSecondsRemaining()` reports the real remaining time (`Long.MAX_VALUE` when there is no timeout); `Lock.isLive()` follows it; `Lock.refresh()` restarts it |
| Transactions | The lock row is written through a dedicated system session, so a lock survives a rollback of the work it protects and is released when the session closes |
| Scope | Workspace-wide: standalone, all sessions in the JVM; clustered, all sessions on all nodes |
| Observability | Acquisition, refresh and release are journaled (`LOCKED` / `LOCK_REFRESHED` / `UNLOCKED`) and visible in `jcr:lockOwner` on the resource; the `jcr_locks` table shows every held lock |

## Java API

```java
LockManager lockManager = session.getWorkspace().getLockManager();
try {
    Lock lock = lockManager.lock("/var/locks/nightly-report", false, true, 600, null);
    try {
        // critical section
    } finally {
        lockManager.unlock("/var/locks/nightly-report");
    }
} catch (LockException held) {
    // somebody else holds it
}
```

`timeoutHint` is in seconds, per the JCR specification. `Long.MAX_VALUE` (or the
Resource API's `timeoutSeconds <= 0`) means no timeout — appropriate for
interactive content locking, and the wrong choice for a guard, where it would
leave a crashed holder blocking the resource forever.

## Clustering caveat

Cluster-wide exclusion depends on the nodes sharing a database. Enabling
clustering without a shared `jdbcURL` silently degrades to per-node exclusion:
each node takes the lock in its own database and every node believes it won.
There is no detection for this — see `documents/clustering.md`.
