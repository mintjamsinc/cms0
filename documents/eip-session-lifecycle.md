# EIP session lifecycle and transactions

A route that touches the repository decides three things: which identity it acts
as, which of its writes become durable together, and what serializes it against
everything else running at the same time. This document is about making those
three decisions **visible in the route** rather than properties hidden inside
the nodes.

- Scope: opening, committing and closing the repository session a route works
  in, and the lock that goes with it. The writes themselves are in
  `eip-change-operations.md`.
- Audience: anyone authoring or reviewing an EIP route that writes content.

There are two ways to write the skeleton, and they are equal. The `cms:`
component spells it as nodes (`cms:login`, `cms:commit`, `cms:rollback`,
`cms:logout`, `cms:lock`, `cms:unlock`); a `<script><groovy>` node spells it
with `ScriptAPI` and the session object. Every route the platform ships, and
every route in the applications built on it, uses the Groovy form, so it is
shown first. The `cms:` form stays because a reader can see the transaction
from the node names alone; whichever form a route uses, the rules in sections
4 to 7 are the same.

---

## 1. The shape, in Groovy

```xml
<route id="members-sweep">
    <routeProperty key="mi:history" value="failure"/>
    <from id="members-sweep-timer" uri="timer:members-sweep?period=3600000&amp;delay=120000"/>

    <doTry id="members-sweep-try">
        <script id="members-sweep-open">
            <groovy>
            <![CDATA[
                exchange.setProperty('mi:cms.context', ScriptAPI.createServiceUserContext('members-service-user'));
            ]]>
            </groovy>
        </script>

        <script id="members-sweep-run">
            <groovy>
            <![CDATA[
                members.Sweep.create(context).run();
            ]]>
            </groovy>
        </script>

        <doCatch id="members-sweep-catch">
            <exception>java.lang.Exception</exception>
            <script id="members-sweep-cause">
                <groovy>
                <![CDATA[
                    def root = exchange.getProperty('CamelExceptionCaught');
                    while (root?.getCause() != null && !root.getCause().is(root)) {
                        root = root.getCause();
                    }
                    exchange.setProperty('cause', root?.toString());
                ]]>
                </groovy>
            </script>
            <log id="members-sweep-log" loggingLevel="ERROR" message="The members sweep failed: ${exchangeProperty.cause}&#10;${exception.stacktrace}"/>
        </doCatch>

        <doFinally id="members-sweep-finally">
            <script id="members-sweep-close">
                <groovy>
                <![CDATA[
                    // Taken off the exchange before it is closed: closing clears the
                    // context's bindings, and a later script that still found the
                    // property would run without ScriptAPI or log.
                    def cmsContext = exchange.getProperty('mi:cms.context');
                    exchange.removeProperty('mi:cms.context');
                    cmsContext?.close();
                ]]>
                </groovy>
            </script>
        </doFinally>
    </doTry>
</route>
```

This is the members site's hourly sweep, verbatim. Read down the nodes and the
transaction is legible: it opens as the service user in the first script, the
work is one line, a failure is logged with its innermost cause, and the session
is always closed. The Webtop's mail synchronization (`webtop/mail.xml`) and the
members package ingest (`members/packages.xml`) have the same three script
nodes around different work.

### What the three nodes do

**Open.** `ScriptAPI.createServiceUserContext(userId)` opens a repository
session as that service user and returns a scripting context. Storing it in the
exchange property `mi:cms.context` is what makes every later `<groovy>` node on
the exchange run *inside* it: the Groovy language reads that property, and when
it holds a context, the script's `context`, `ScriptAPI`, `XPath`, `JSON`,
`YAML`, `ISO8601` and `log` are that context's. A script node on an exchange
with no such property runs in a throw-away context of its own that is closed
when the script returns, which is fine for a script that only reads the
exchange and wrong for one that writes content: its session would be gone before
anything committed.

The user id is **required and is the one thing to check twice**. The context
opens the session at once, so a service user that does not exist fails here
rather than at the first write. The routes shipped so far carry the id as a
constant or a route property set above the `doTry`, so it can be read without
opening the script.

**Commit.** Nothing the work does is durable until `context.session.commit()`.
A commit with nothing pending is a no-op, so a loop that commits per item may
commit freely. The commit is written where the work is — in the Groovy that
decided what to write — rather than in a separate node, because what has to be
durable *together* is a fact about the work: the sweep above commits per member,
so one member's failure rolls back that member and no other.

**Close.** `close()` on the context logs the session out, and a JCR logout
**discards whatever was not committed**. So a script that forgets to commit
loses its work quietly, which is the failure to review for. The close is in
`doFinally`, which runs whether the route finished or threw; the property is
removed first for the reason in the comment.

**There is no second close.** A context opened in Groovy is known to nothing but
the exchange property, so no unit-of-work hook and no reaper will close it if
`doFinally` is missing: the session leaks, and a session holds one of a
hard-capped pool of slots. The `doFinally` node is therefore not optional. This
is the one place where the two forms differ in what the platform does for you —
section 3.

**Several sessions.** A route that must act as two users — a pass that reads
settings as the service user and then writes into each account's home as its
owner — opens the second context inside the Groovy that needs it and closes it
in a `finally` there. The Webtop mail pass does this per account. Only one
context at a time can be *the* `mi:cms.context` of the exchange.

---

## 2. The same shape, as `cms:` nodes

```xml
<route id="reference-order-annotate">
    <from id="annotate-from" uri="direct:reference-order-annotate"/>

    <onCompletion id="annotate-backstop" parallelProcessing="false">
        <to id="annotate-backstop-logout" uri="cms:logout"/>
    </onCompletion>

    <doTry id="annotate-tx">
        <to id="annotate-login" uri="cms:login?runAs=commerce-service-user"/>
        <setProperty id="annotate-share" name="mi:cms.context">
            <constant>@property.cmsContext</constant>
        </setProperty>

        <script id="annotate-update">
            <groovy>
            <![CDATA[
                def order = context.session.getResource(exchange.getProperty('orderPath'));
                exchange.setProperty('previousStatus', order.getProperty('commerce:status').string);
                order.setProperty('commerce:status', 'annotated');
            ]]>
            </groovy>
        </script>

        <to id="annotate-commit" uri="cms:commit"/>

        <doCatch id="annotate-catch">
            <exception>java.lang.Exception</exception>
            <to id="annotate-rollback" uri="cms:rollback"/>
            <log id="annotate-report" loggingLevel="ERROR"
                 message="reference-order-annotate rolled back [${exchangeId}]: ${exception.message}"/>
            <throwException id="annotate-refail"
                            exceptionType="java.lang.IllegalStateException"
                            message="reference-order-annotate rolled back: ${exception.message}"/>
        </doCatch>

        <doFinally id="annotate-finally">
            <to id="annotate-logout" uri="cms:logout"/>
        </doFinally>
    </doTry>
</route>
```

| Node | What it does |
|---|---|
| `cms:login?runAs=<user>` | Opens a session as that user and publishes the context as the exchange property `cmsContext` (or wherever `@header.x=context` / `@property.x=context` says) |
| `cms:commit` | `session.commit()` on that context. A commit that cannot find the context is a hard failure: a commit that commits nothing while reporting success is the worst outcome available |
| `cms:rollback` | Discards everything pending. Locks are unaffected |
| `cms:logout` | Closes the context. Idempotent, and with no context to close it does nothing, which is what makes it safe in both `doFinally` and the `<onCompletion>` backstop |
| `cms:lock` / `cms:unlock` | Section 6 |

`runAs` is required in practice: left unset, the session falls back to guest,
which reads public content and writes nothing, so a typo would produce a route
that reports success and does nothing.

**The one line that makes the two forms meet** is the `<setProperty>` after
`cms:login`. `cms:login` publishes under `cmsContext`; a `<groovy>` node reads
`mi:cms.context`. The Groovy language dereferences a value of the form
`@property.<name>` or `@header.<name>`, so pointing `mi:cms.context` at the
property `cms:login` wrote puts every script node in the login's session,
without copying the object and without a second close: `cms:logout` closes it,
and the script nodes never see a context after that because the property only
points at one. Without that line the script nodes would run in contexts of their
own, outside the transaction, and their writes would never be committed.

What the `cms:` form adds over the Groovy one is the **three layers of close**
(section 3). What it costs is the join line above, and a second way to spell the
same thing. A route may use either; a route that already carries its work as
Groovy gains little from the nodes.

The `context=<property name>` option on `cms:commit`, `cms:rollback` and
`cms:logout` is for the route that holds two sessions at once; the default is
`cmsContext`. It carries the **name** of the property, never an expression:
`context=${exchangeProperty.cmsContext}` would arrive as the context's
`toString()` and is refused — on the first message, not at deploy.

---

## 3. How a session gets closed

| Layer | Closes when | Groovy-opened | `cms:login`-opened |
|---|---|---|---|
| `doFinally` | The route finishes or throws | Yes, if written | Yes, if written |
| `<onCompletion>` backstop | The exchange completes, including after `<stop/>` | Only if written as a script that closes `mi:cms.context` | Yes, if written |
| Unit-of-work synchronization | The exchange completes, whether or not the route wrote either of the above | **No** | Yes |
| Reaper | Only on evidence of abandonment: the unit of work finished and nothing closed it, the owning thread is dead, or the component is stopping | **No** | Yes |

**Elapsed time is never evidence**, in either form. A long batch is not a leak,
and closing a session from another thread while its owner is still using it
would cause exactly the corruption these rules exist to prevent. There is no
lifetime option and no timer.

Write `doFinally` in both forms. In the Groovy form it is the only close; in
the `cms:` form, relying on the backstops delays the close until the exchange
completes, and a session holds a pool slot until then.

---

## 4. One session, one thread

The workspace holds a **single JDBC connection**, and a JCR session belongs to
the thread that opened it. Two threads sharing one session do not fail cleanly —
they interleave writes into one transaction.

So a route that opens a session must not contain, between open and close:

- `wireTap`, `threads`, `aggregate`
- `split`, `multicast` or `recipientList` with `parallelProcessing`
- `delay` or `throttle` with `asyncDelayed`
- sends to `seda:`, `vm:` or `stub:`
- `<onCompletion>` with `parallelProcessing="true"` or an executor

Nothing checks this for you; it is a review rule. The `cms:` form refuses a
commit from a thread other than the one that logged in, which turns one case of
the mistake into a loud failure; the Groovy form has no such check.

**`<split>` is fine** — sub-exchanges run on the same thread and share the
exchange properties by reference, so a batch of five thousand records runs in
one session. State `stopOnException="true"` explicitly: without it only the
last sub-exchange's failure reaches the parent, and a commit after the split
would persist a batch in which an earlier item failed silently. The commerce
queue routes split the paths a query returned and commit per item inside the
split, so that an item's failure is that item's rollback and nothing else's.

### Claiming work before dispatching it

When a route claims something and then hands it to another thread, the order is
**claim → commit → close → dispatch**. Dispatching first means a failure before
the commit rolls the claim back while the asynchronous work is already running,
and the item is processed twice.

---

## 5. What stays outside the transaction

A node that genuinely must not participate — recording the arrival of
something that has to survive a rollback, creating a lock target, calling out to
a remote service — belongs **outside** the `<doTry>` that opens the session, or
in a script that opens and closes a context of its own, where a reader can see
that it is not part of the transaction.

- **The receipt goes first.** A route that stores an inbound payload and then
  derives state from it stores the payload before the transaction opens. The
  payload is the input to the transaction rather than part of it, and an error
  handler that stamps or moves that node needs it to be there.
- **Remote calls go outside.** Holding the session across an HTTP call blocks
  every other write in the workspace for as long as the remote end takes to
  answer. Fetch first, write the result inside.
- **What records the failure goes outside.** Metrics, health, audit entries: a
  rollback must not take with it the record that there was something to roll
  back. `eip-conventions.md` states the rule and what else it covers.

Two things stay outside by construction, whichever form the route uses:

| | Why |
|---|---|
| Lock rows | Written through a dedicated system session, which is exactly why a lock survives a rollback of the work it protects and is released when the session closes |
| Version operations (`checkout`, `checkin`, `checkpoint`, `addVersionControl`) | The version manager writes through a system session of its own, so a checkout is durable the moment it runs and a rollback does not undo it. They refuse a session with unsaved changes, so commit first |

---

## 6. Locking

A lock serializes a recurring task across overlapping ticks and across the
nodes of a cluster: the lock row lives in the workspace database under a
primary key on the item, so every claimer contends on the same insert and
exactly one wins. `resource-locks.md` is the full account; this section is
what a route does with it.

### In Groovy

```groovy
// Before the lock: the target has to exist, and creating it is a commit of
// its own, because JCR refuses to lock a node whose session has unsaved
// changes beneath the target.
def lockFile = context.session.getResource('/var/lock/members/sweep');
if (!lockFile.exists()) {
    try {
        lockFile.getParent().getOrCreateFolder();
        lockFile.createFile();
        context.session.commit();
    } catch (Throwable ex) {
        try { context.session.rollback(); } catch (Throwable ignore) {}
    }
}

// isDeep, isSessionScoped, timeoutSeconds. Losing is a result, not an error.
def locked = lockFile.tryLock(false, true, 600);
if (locked == null) {
    exchange.setProperty('skipped', true);
    return;
}
try {
    // the critical section, committing as it goes
} finally {
    try { lockFile.unlock(); } catch (Throwable ignore) {}
}
```

`tryLock` returns the resource when it won and `null` when someone else holds
the lock; `lock(...)` with the same arguments throws `LockException` instead.
A route that skips a tick on `null` writes the branch in Groovy, as above, or
puts the result on the exchange and branches with a `<filter>` — either way
**something has to branch on it**, or the node that lost carries on exactly like
the one that won.

**`isSessionScoped` has no sensible default, so write it.** The two modes have
entirely different lifecycles:

| | Session-scoped (`true`) | Persistent (`false`) |
|---|---|---|
| Released by | Closing the context, on every exit path; or `unlock()` | `unlock()` only |
| `timeoutSeconds` | Recommended, so a crashed holder does not block the next tick for ever | Usually `0`, meaning no expiry |
| Token inheritance | The acquiring session only | Any session of the same principal, so a later exchange can release it |
| Typical use | Serializing a recurring task | Holding content while it is edited |

**The timeout has to outlast the work, or the work has to refresh it.** A lock
past its timeout is treated as free and reclaimed by the next claimer. Nothing
renews a lease in the background for you: a pass that may run longer than its
timeout calls `lockFile.getLock().refresh()` as it goes — the commerce queue
routes do this once per round of their loop, with a timeout sized for one round.
Size the timeout as the window within which another node may take over after
this one dies, and refresh inside it.

### As `cms:` nodes

```xml
<doTry id="sweep-tx">
    <to id="sweep-login" uri="cms:login?runAs=commerce-service-user"/>

    <doTry id="sweep-claim">
        <to id="sweep-lock"
            uri="cms:lock?path=/var/locks/sweep&amp;isSessionScoped=true&amp;timeoutSeconds=600"/>
        <doCatch id="sweep-held">
            <exception>javax.jcr.lock.LockException</exception>
            <log id="sweep-skip" loggingLevel="DEBUG" message="sweep skipped: the lock is held"/>
            <stop/>
        </doCatch>
    </doTry>

    <!-- the critical section -->
    <to id="sweep-commit" uri="cms:commit"/>

    <doFinally id="sweep-finally">
        <to id="sweep-logout" uri="cms:logout"/>
    </doFinally>
</doTry>
```

`cms:lock` takes the lock through the login's session and **throws
`LockException` when it is held**, so the branch is a `doCatch` around it. It
binds the `javax.jcr.lock.Lock` it took as `lock` (`@property.x=lock`) for a
script that wants to refresh it. `timeoutSeconds` is accepted only with
`isSessionScoped=true`. The lock target has to exist already; create it in a
script node outside the `doTry`, with its own commit.

### Ordering, in both forms

- **Lock immediately after opening, before any write.** JCR refuses to lock a
  node whose session has unsaved changes beneath the target, and a guard taken
  after the work has started is not a guard.
- **Commit before unlock and before close.** Otherwise the next node into the
  critical section can read state that may still be rolled back.
- **Unlock is mandatory for a persistent lock.** For a session-scoped lock it is
  optional — closing the context releases it on every path — and the `finally`
  above writes it anyway so that the release does not wait for the exchange to
  finish. A node that is not locked is a `LockException` on unlock, which is why
  it is wrapped.

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
rollback branch is skipped, the close discards the work — and the exchange is
recorded as completed. Data loss reported as success.

**End `doCatch` with `<throwException>` when the caller is to retry.** `doCatch`
clears the exception, so a route that rolls back and simply falls out of the
block finishes with no exception at all: the exchange is recorded as completed
and the caller's redelivery never runs. The sweep in section 1 does not
re-throw, and that is deliberate: a timer tick has no caller to retry it, the
next tick is the retry, and the failure is on record in the log and in the
exchange history (`mi:history=failure`).

**A failure inside a script arrives wrapped.** The exception a `doCatch` sees for
a Groovy node is "Failed to evaluate groovy expression", with the real cause
underneath; the `members-sweep-cause` script walks to the innermost cause so the
log line says what failed. Copy it.

**Rollback in the catch.** A commit is explicit, so a failure between two
commits leaves the session holding pending changes; the close discards them,
and a `rollback()` in the catch is what makes the discard visible on the route.
A catch that rolls back and then goes on to record the failure in the repository
must do so after the rollback, or the record goes with the discard.

---

## 8. Reading your own writes

Within one session, a read sees that session's uncommitted writes: after
`createFile()`, an `exists()` on the same path returns true before the commit.
A transaction sees its own work. Routes built on "write a marker, and elsewhere
check whether the marker exists" need that check to run in another session,
or after the commit.

The search index is updated asynchronously, so an `XPath` query may not see a
node the same session wrote moments ago even after the commit; a route that
writes and then walks what it wrote lists the folder (`resource.list()`) rather
than querying.

---

## 9. Checklist

1. The service user is a constant or route property set outside the `doTry`.
2. The context is opened in the first node inside `doTry` and stored in
   `mi:cms.context` (or `cms:login` is first and `mi:cms.context` points at
   its property).
3. Every script that writes commits, and says in a comment what the commit
   makes durable together.
4. `doFinally` removes `mi:cms.context` and closes it (or runs `cms:logout`).
   In the Groovy form there is no backstop.
5. No thread hop between open and close.
6. `<split>` inside the scope states `stopOnException`.
7. A lock states `isSessionScoped` and a timeout, something branches on the
   result, and work longer than the timeout refreshes it.
8. Lock before any write; commit before unlock and close.
9. `doCatch` rolls back, logs the innermost cause, and re-throws when a caller
   is to retry.
10. The error handler and its retries live on the caller.
11. Every node has an `id`. A failure you cannot name is a failure you cannot
    find.
12. Nothing inside the scope opens a second context on `mi:cms.context`.
