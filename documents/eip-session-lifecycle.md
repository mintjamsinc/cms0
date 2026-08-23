# EIP session lifecycle and transactions

A route that touches the repository decides three things: which identity it acts
as, which of its writes become durable together, and what serializes it against
everything else running at the same time. This document is about making those
three decisions **steps on the canvas** rather than properties hidden inside the
nodes.

- Scope: the `cms:` component's session operations, and the locking that goes
  with them.
- Audience: anyone authoring or reviewing an EIP route that writes content.

---

## 1. The shape

```xml
<route id="reference-order-annotate">
    <from id="annotate-from" uri="direct:reference-order-annotate"/>

    <onCompletion id="annotate-backstop" parallelProcessing="false">
        <toD id="annotate-backstop-logout"
             uri="cms:logout?context=cmsContext"/>
    </onCompletion>

    <doTry id="annotate-tx">
        <to id="annotate-login"
            uri="cms:login?runAs=commerce-service-user&amp;@header.cmsContext=context"/>

        <toD id="annotate-fetch"
             uri="cms:getProperties?context=cmsContext&amp;path=${header.orderPath}&amp;@header.currentStatus=commerce:status"/>

        <setHeader id="annotate-status" name="commerce:status">
            <constant>annotated</constant>
        </setHeader>
        <toD id="annotate-update"
             uri="cms:setProperties?context=cmsContext&amp;path=${header.orderPath}&amp;includes=commerce:*"/>

        <to id="annotate-commit" uri="cms:commit?context=cmsContext"/>

        <doCatch id="annotate-catch">
            <exception>java.lang.Exception</exception>
            <to id="annotate-rollback" uri="cms:rollback?context=cmsContext"/>
            <log id="annotate-report" loggingLevel="ERROR"
                 message="reference-order-annotate rolled back [${exchangeId}]: ${exception.message}"/>
            <throwException id="annotate-refail"
                            exceptionType="java.lang.IllegalStateException"
                            message="reference-order-annotate rolled back: ${exception.message}"/>
        </doCatch>

        <doFinally id="annotate-finally">
            <to id="annotate-logout" uri="cms:logout?context=cmsContext"/>
        </doFinally>
    </doTry>
</route>
```

Read down the nodes and the transaction is legible: it opens here, it becomes
durable there, it is discarded on that path, and it is always closed. Adding a
log line between the fetch and the update is a wiring change.

---

## 2. The operations

| Operation | What it does |
|---|---|
| `cms:login` | Opens a JCR session and publishes it, so the rest of the route can share it |
| `cms:commit` | Makes everything pending durable. The single point at which the work becomes visible to anyone else |
| `cms:rollback` | Discards everything pending. Locks are unaffected |
| `cms:logout` | Closes the session and releases its session-scoped locks. Idempotent |
| `cms:createFolder` | Creates a folder, optionally with mixins. Prepares a lock target |
| `cms:lock` / `cms:unlock` | Takes and releases a lock, reporting whether it won |

The operations that change content — `cms:store`, `cms:setProperties`,
`cms:setProperty`, `cms:setPropertyElement`, `cms:move` — are covered in
`eip-change-operations.md`. Everything below applies to them too: with
a session reachable through `context` they leave their changes for `cms:commit`,
without one they commit on their own.

### `cms:login`

```
cms:login?runAs=<user>
```

`runAs` is **required**. Leaving a credential unset does not fail — the session
falls back to guest, and a guest can still read public content — so a typo would
produce a session that reads a little, writes nothing, and reports success.

**The output binding is optional.** Written, it publishes the session wherever it
says; omitted, the session is published as `cmsContext` — the header the
`context` option reads by default. So the two spellings below do the same thing,
and the first is only worth writing when a route holds more than one session:

```
cms:login?runAs=<user>&@header.cmsContext=context
cms:login?runAs=<user>
```

**There is no lifetime option, and this is deliberate.** A session is never
closed on a timer, because elapsed time cannot tell a leak from a batch that is
supposed to take twenty minutes. Section 5 covers what does close it.

### `cms:commit`

A commit with nothing pending is a quiet no-op, so an idempotent route may
commit freely. A commit whose session cannot be resolved is a hard failure: that
is always a wiring mistake, and a commit that commits nothing while reporting
success is the worst outcome available.

Several `cms:commit` nodes in one login span are legal and independent — a long
route may checkpoint more than once.

### `cms:logout`

Idempotent, which is what makes it safe to write in both `doFinally` and the
`onCompletion` backstop: the normal path closes in `doFinally`, `<stop/>` skips
that and the backstop catches it, and when both run the second does nothing.

**With no session to close it also does nothing**, rather than failing. This is
the one place `cms:logout` differs from `cms:commit` and `cms:rollback`, which
fail when they cannot find the session they name. A backstop runs on every path
the exchange can take, including the ones that failed before `cms:login` — most
of them, on a route whose transaction opens late — so it needs no guard.

**Anything not committed is discarded, not saved.** Closing rolls back and then
logs out. A route that forgets `cms:commit` loses its work quietly, which is the
failure mode to review for — and the reason the commit node is never optional.

---

## 3. Joining a session

Every other `cms:` operation takes an optional `context`, whose value is the name
of the header holding the session. When that header holds one, the node works
through the route's session and leaves its changes transient for `cms:commit` to
make durable. When it holds nothing, the node opens and closes its own session
exactly as it always has — which is what lets a route be converted one node at a
time.

**The option defaults to `cmsContext`**, the header `cms:login` publishes to by
convention, so a route that holds one session need not write it anywhere. Naming
it is for the route that holds two at once:

```xml
<toD uri="cms:store?context=itemContext&amp;path=${header.target}"/>
```

**`context` carries the *name* of the header holding the session, not an
expression that reads it.**

```xml
<!-- Right -->
<toD uri="cms:store?context=cmsContext&amp;path=${header.target}"/>

<!-- Wrong: resolves to text before the endpoint exists -->
<toD uri="cms:store?context=${header.cmsContext}&amp;path=${header.target}"/>
```

A session is an object; a URI is text. A `<toD>` builds its URI by evaluating it
against the exchange *before* the endpoint exists, so an expression would arrive
as the session's `toString()`. The producer refuses that rather than letting the
node quietly open a session of its own and drop its writes out of the
transaction — but it refuses on the first message, not at deploy.

Naming it explicitly is for the route that juggles two sessions, and for authors
who would rather see on the canvas which nodes are inside the transaction.

**What decides is the header, not the option.** A node joins when the header it
names — written or defaulted — holds a session. So "this node owns its session"
is not achieved by leaving the option off a route that has a session in flight:
it is achieved by there being no session under that name, which for a node that
must survive a rollback means placing it where the route has not opened one, or
publishing the session under a name it does not read.

**Inside the transaction block, everything is in the transaction.** A `cms:` node
written between `cms:login` and `cms:commit` that reaches no session opens one of
its own and commits it the moment it finishes: it reads like the nodes around it,
but its write is already durable before the block ends and `cms:rollback` will
not undo it. Nothing checks this for you. It is worth reading a scope for once
its `cms:login` publishes under a name other than the default, because there a
node that omits the option looks up the default and finds nothing.

A node that genuinely must not participate — recording the arrival of something
that has to survive a rollback, creating a lock target, calling out to a remote
service — belongs **outside** the `<doTry>`, where a reader can see that it is not
part of the transaction. Two placements follow from this and are worth stating:

- **The receipt goes first.** A route that stores an inbound payload and then
  derives state from it stores the payload before the transaction opens. The
  payload is the input to the transaction rather than part of it, and an error
  handler that stamps or moves that node needs it to be there.
- **Remote calls go outside.** The workspace owns one JDBC connection. Holding
  the session across an HTTP call blocks every other write in the workspace for
  as long as the remote end takes to answer. Fetch first, write the result
  inside.
- **What records the failure goes outside.** Metrics, health, audit entries: a
  rollback must not take with it the record that there was something to roll
  back. `eip-conventions.md` states the rule and what else it covers.

### What deliberately stays outside

| | Why |
|---|---|
| Lock rows | Written through a dedicated system session, which is exactly why a critical section survives `cms:rollback` and is released by `cms:logout` |
| `cms:checkout` / `checkin` / `checkpoint` / `uncheckout` | They read through the route's session, so they see what it wrote, but the version manager writes `jcr:isCheckedOut`, the predecessors and the version history through a system session of its own and commits it there. So a checkout is durable the moment it runs and `cms:rollback` does not undo it — and it cannot carry the route's pending work with it either, because it never writes through the route's session |
| `cms:addVersionControl` | Same. It calls the version manager's own `addVersionControl` rather than adding `mix:versionable` and saving, because the save that persists that mixin is what creates the version history — and on a joined node that save is the route's, which would make everything else it had pending durable |

All of them need a `cms:commit` before them: the version manager refuses to act
on a session that has unsaved changes.

---

## 4. One session, one thread

The workspace holds a **single JDBC connection**, and a JCR session belongs to
the thread that opened it. Two threads sharing one session do not fail cleanly —
they interleave writes into one transaction.

So a route that opens a session must not contain, between `cms:login` and
`cms:logout`:

- `wireTap`, `threads`, `aggregate`
- `split`, `multicast` or `recipientList` with `parallelProcessing`
- `delay` or `throttle` with `asyncDelayed`
- sends to `seda:`, `vm:` or `stub:`
- `<onCompletion>` with `parallelProcessing="true"` or an executor

This is checked when the route is added to the context, so it fails at deploy
time rather than during the first burst of traffic. There is a runtime assertion
as well, naming both threads and the fix.

**`<split>` is fine** — sub-exchanges run on the same thread and share the
session by reference, so a batch of five thousand records runs in one session.
State `stopOnException="true"`
explicitly: without it only the last sub-exchange's failure reaches the parent,
and `cms:commit` would persist a batch in which an earlier item failed silently.

### Claiming work before dispatching it

When a route claims something and then hands it to another thread, the order is
**claim → `cms:commit` → `cms:logout` → dispatch**. Dispatching first means a
failure before the commit rolls the claim back while the asynchronous work is
already running, and the item is processed twice.

---

## 5. How a session gets closed

| Layer | Closes when | Runs on |
|---|---|---|
| `doFinally` | The route finishes or throws | The owning thread |
| `<onCompletion>` backstop | The exchange completes, including after `<stop/>` | The owning thread |
| Unit-of-work synchronization | The exchange completes, whether or not the route wrote either of the above | The owning thread |
| Reaper | Only on evidence of abandonment | The reaper thread |

The first three are the design; the reaper is the backstop. It closes a session
only when the unit of work has finished and nothing closed it — which really
happens, because Camel swallows exceptions thrown by synchronizations — or when
the owning thread is dead, or when the component is stopping.

**Elapsed time is never evidence.** A long batch is not a leak, and closing a
session from another thread while its owner is still using it would cause exactly
the corruption these rules exist to prevent.

Write `doFinally` anyway. Relying on the backstop delays the close until the
exchange completes, and a session holds one of a hard-capped pool of slots.

---

## 6. Locking

`cms:lock` takes a lock and **reports whether it won**. Losing is a normal
outcome, not an error, so the route branches on it:

```xml
<to id="sweep-ensure-lock-node"
    uri="cms:createFolder?path=/var/locks/sweep&amp;mixins=mix:lockable&amp;conflictBehavior=IGNORE&amp;runAs=commerce-service-user"/>

<doTry id="sweep-tx">
    <to id="sweep-login" uri="cms:login?runAs=commerce-service-user&amp;@header.cmsContext=context"/>

    <toD id="sweep-lock"
         uri="cms:lock?context=cmsContext&amp;path=/var/locks/sweep&amp;isSessionScoped=true&amp;timeoutSeconds=240&amp;@header.locked=locked"/>

    <filter id="sweep-guard">
        <simple>${header.locked} == true</simple>
        <!-- the critical section -->
        <to id="sweep-commit" uri="cms:commit?context=cmsContext"/>
    </filter>

    <doFinally id="sweep-finally">
        <to id="sweep-logout" uri="cms:logout?context=cmsContext"/>
    </doFinally>
</doTry>
```

The lock row lives in the workspace database under a primary key on the item, so
every node in the cluster and every overlapping tick in this JVM contend on the
same insert and exactly one wins.

**`isSessionScoped` has no default.** The two modes have entirely different
lifecycles and a wrong guess is expensive in both directions:

| | Session-scoped (`true`) | Persistent (`false`) |
|---|---|---|
| Released by | `cms:logout`, on every exit path | `cms:unlock` only |
| `timeoutSeconds` | Recommended — a failover-detection window | Usually omitted, meaning no expiry |
| Token inheritance | The acquiring session only | Any session of the same principal, so a later exchange can release it |
| Typical use | Serializing a recurring task | Holding content while it is edited |

**`timeoutSeconds` is a failover-detection window, not a budget for how long the
work may take.** The lease is renewed in the background while the run is alive,
so the timeout governs only how soon another node may take over after this one
dies. It does not need to be fitted to the timer period.

### Ordering

- **`cms:lock` immediately after `cms:login`, before any write.** JCR refuses to
  lock a node whose session has unsaved changes beneath the target, and a guard
  taken after the work has started is not a guard.
- **`cms:commit` before `cms:unlock` and `cms:logout`.** Otherwise the next node
  into the critical section can read state that may still be rolled back.

### `cms:unlock`

Mandatory for a persistent lock. For a session-scoped lock it is optional and
usually left out — `cms:logout` releases those on every path, and writing an
unlock as well adds a node that can throw in front of the one node that must
never be skipped. Write it deliberately when the release point means something:
to shorten the critical section before a long non-exclusive tail, or to take a
second lock in the same route.

**Pass the token you were given.** Ownership is decided by the token, and
unlocking by path alone releases whatever claim happens to be there — including
one taken by someone else after this route's lock expired.

---

## 7. Error handling

**Retries belong on the caller, not inside the transaction.** Camel installs no
error handler inside `doTry`, so `maximumRedeliveries` there has no effect. Put
the `redeliveryPolicy` on the route that calls the transactional one, which also
gives the semantics you want: one redelivery is one full re-run.

```xml
<routeConfiguration id="order-annotate-error-handler">
    <onException>
        <exception>java.lang.Exception</exception>
        <handled><constant>true</constant></handled>
        <redeliveryPolicy maximumRedeliveries="3" redeliveryDelay="5000"/>
        <log loggingLevel="ERROR" message="order annotate failed after retries [${exchangeId}]"/>
    </onException>
</routeConfiguration>

<route id="order-annotate-entry" routeConfigurationId="order-annotate-error-handler">
    <from id="annotate-entry-from" uri="direct:order-annotate"/>
    <to id="annotate-entry-call" uri="direct:reference-order-annotate"/>
</route>
```

**A `direct:` sub-route called from inside the scope must not declare
`handled="true"`.** It would swallow the exception before `doTry` sees it, so the
rollback branch is skipped, `cms:logout` discards the work — and the exchange is
recorded as completed. Data loss reported as success.

(An `onException` on the transactional route itself is fine, and is how a
best-effort route reports without propagating: Camel installs no error handler
inside `doTry`, so `doCatch` sees the exception first and the route-level handler
only ever sees what `doCatch` re-throws.)

**End `doCatch` with `<throwException>`.** `doCatch` clears the exception, so a
route that rolls back and simply falls out of the block finishes with no
exception at all: the exchange is recorded as completed and the caller's
redelivery never runs. The cost is that the original exception type and stack
trace are replaced, which is why the log line goes above it.

Neither the re-throw nor the sub-route rule is checked. Both are review rules.

---

## 8. Reading your own writes

Within one session, a read sees that session's uncommitted writes: after
`cms:store`, a `cms:exists` on the same path returns true before the commit.
This is correct — a transaction sees its own work — but it is a change from the
one-session-per-node behaviour, where the same pair of nodes used different
sessions and the read returned false.

Routes built on "write a marker, and elsewhere check whether the marker exists"
need reviewing when they are converted, because the check now succeeds inside the
same transaction that wrote it.

---

## 9. Checklist

1. `runAs` is set, and set outside `doTry`.
2. `cms:login` is the first node inside `doTry`; `cms:commit` is the last.
3. `context` names a header. It is never an expression, and every `cms:` node
   inside `doTry` has one.
4. `cms:logout` appears in `doFinally` **and** in an `<onCompletion
   parallelProcessing="false">` backstop. The backstop needs no guard: with no
   session to close, the node does nothing.
5. No thread hop between login and logout.
6. `<split>` inside the scope states `stopOnException`.
7. `cms:lock` states `isSessionScoped`, and something branches on its result.
8. `cms:lock` comes before any write; `cms:commit` comes before unlock and
   logout.
9. `doCatch` rolls back, logs, and re-throws.
10. The error handler and its retries live on the caller.
11. Every node has an `id`. A failure you cannot name is a failure you cannot
    find.
12. Nothing inside the scope commits on its own — including a script part, whose
    commit is invisible from the route.
