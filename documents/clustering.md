# Clustering

This document describes how the platform supports running multiple CMS
nodes against a shared repository, what is implemented today, and what is
still to come. The work is delivered in phases; this document is the
single source of truth for the current state.

| Phase | Scope | Status |
|-------|-------|--------|
| 1 | Storage externalization: configurable workspace database (H2 / PostgreSQL) with a SQL dialect layer, pluggable blob storage, database-lease locking instead of the exclusive workspace lock file, cluster node registry, single-node execution of maintenance tasks | **Implemented** |
| 2 | Cluster journal: nodes replay each other's committed transactions for search-index updates, cache invalidation (node cache, access control snapshot, subtree paths), and cross-node event propagation (OSGi events, and through them EIP route reloads, class loader reloads, CMS events, SSE/GraphQL subscriptions) | **Implemented** |
| 3 | Failover hardening: logins made cluster-portable via an encrypted authentication token, cluster coordination API for application code (single-node execution of scheduled work, member listing), stale-node detection with heartbeat warnings | **Implemented** |

> Sticky sessions are no longer required for staying signed in: the
> authentication token re-establishes the login on whichever node
> receives the request. They remain **recommended**, because session
> attributes set by application code (and in-flight multi-step flows
> such as a not-yet-completed TOTP verification) are still node-local.

## Architecture

A repository consists of workspaces. Per workspace, the persistent state
splits into three categories:

| State | Standalone (default) | Clustered |
|-------|----------------------|-----------|
| Content, properties, locks, ACLs, journal (`jcr_*` tables) | Embedded H2 (`<workspace>/var/jcr/data`) | Shared database (PostgreSQL or H2 server mode) |
| Binaries (blobs) | Local files (`<workspace>/var/jcr/bin`) | Shared storage (same directory on NFS or similar), via the blob store |
| Full-text search index (Lucene) | `<workspace>/var/search` | **Node-local** — each node maintains its own index (`<workspace>/var/search/nodes/<nodeId>` by default, or `jcr.yml#search.indexPath`) |

The BPM engine (Camunda) already supports clustering natively through a
shared database and competing job executors; point
`etc/bpm/bpm.yml#jdbcURL` at the shared database on every node (see
`documents/bpm-configuration.md`).

### SQL dialect layer

All workspace SQL is written in a portable subset accepted by H2 and
PostgreSQL. The two constructs that cannot be expressed portably are
isolated behind `org.mintjams.rt.jcr.internal.sql.DatabaseDialect`:

- **Array membership** (`ARRAY_CONTAINS(col, ?)` on H2,
  `? = ANY(col)` on PostgreSQL), used for reference lookups.
- **JDBC array binding.** The data-access layer binds Java arrays with
  `setObject` and reads them back as Java arrays, which H2 supports
  natively. For PostgreSQL, connections are wrapped by
  `JdbcArrayAdapter`, which translates to and from `java.sql.Array` at
  the JDBC boundary — no SQL-issuing code knows which driver is in use.
- **Transaction semantics.** PostgreSQL aborts a transaction on any
  statement failure; code that recovers from an expected failure inside
  a transaction (journal-id collision retry) guards the statement with a
  savepoint when `DatabaseDialect.isTransactionAbortedOnError()` is true.

The dialect is selected from the configured JDBC URL. Adding another
database means implementing `DatabaseDialect` and registering it in
`Dialects`.

JDBC drivers that live in their own OSGi bundle (PostgreSQL) are not
discoverable through `java.sql.DriverManager`, so when
`datasource.driverClassName` is configured the pool is handed a
`SimpleDriverDataSource` built around the driver instance directly. The
bundle declares `DynamicImport-Package: org.postgresql,org.postgresql.*`
so the driver class resolves when its bundle is installed.

### Blob store

Binary values are stored through
`org.mintjams.rt.jcr.internal.blob.BlobStore`, keyed by the identifier
recorded in `jcr_files`. The bundled implementation (`type: fs`) keeps
the existing four-level directory layout and accepts a configurable root
directory, so a clustered deployment simply points every node at the
same shared directory. The interface is deliberately narrow (`write`,
`read`, `getPath`, `exists`, `delete`, `collectGarbage`) so that a
remote store (e.g. S3-compatible) can be added without touching any
caller; `getPath` is specified to allow a locally cached copy.

Blob identifiers are random UUIDs and never reused, so concurrent
writers on different nodes never contend for the same blob.

### Cluster controller

`org.mintjams.rt.jcr.internal.cluster.ClusterController` coordinates the
nodes sharing a workspace database through two tables (created on first
use):

- `jcr_cluster_nodes` — node registry; each node upserts its row and
  refreshes `last_heartbeat` every 30 seconds, and removes the row on
  clean shutdown. Operations can query this table to see the live
  cluster membership.
- `jcr_cluster_locks` — lease-based locks (`lock_name`, `owner_id`,
  `lock_expires`). A lease names its owning node and an expiry, so a
  crashed node never blocks the cluster for longer than the lease's
  time-to-live.
- `jcr_cluster_signals` — the signal bus for short-lived control-plane
  notifications (see *Cluster signal bus* below).

The controller serializes the work that must not run on two nodes at
once:

| Lock | Held by | Purpose |
|------|---------|---------|
| `workspace-startup` | `JcrWorkspaceProvider.open()` | Schema creation, data migrations, default-node creation, orphan-node removal |
| `blob-cleaner` | `WorkspaceCleaner` | Removing deleted blobs from shared storage |
| `blob-garbage-collection` | `WorkspaceGarbageCollection` | Sweeping unreferenced blobs |
| `orphan-monitor` | `WorkspaceOrphanMonitor` | Orphan scan (avoids duplicate warnings) |
| `cluster-signals-purge` | `ClusterController.SignalPoller` | Removing expired signal-bus rows |

In standalone mode the controller is a complete no-op: no tables, no
threads, and every lock is granted immediately. The exclusive workspace
lock file (`<workspace>/.lock`) is still taken in standalone mode to
protect local storage against a second process; in cluster mode it is
skipped (the workspace directory is shared) and the database leases take
over.

## Configuration

All settings default to the current standalone behaviour; existing
repositories run unchanged.

### `etc/repository.yml` (repository level)

```yaml
cluster:
  enabled: true
  nodeId: node-1   # optional; must be unique per node
```

`nodeId` can also be supplied per node — which is required when all
nodes share the repository directory and therefore the same
`repository.yml` — via the framework property
`org.mintjams.jcr.cluster.nodeId` or the environment variable
`CMS_CLUSTER_NODE_ID`. When omitted, the host name is used (containers
normally have unique host names), with a random identifier as the last
resort. `cluster.enabled` can likewise be forced per node with the
framework property `org.mintjams.jcr.cluster.enabled`.

The cross-node propagation latency is tunable. Each node polls the cluster
journal to invalidate its caches for changes committed on other nodes, and
the cadence is **adaptive**: it polls at a floor while remote commits keep
arriving and backs off geometrically (doubling) toward a ceiling while the
cluster is idle, so a quiet cluster costs little while a busy one stays
fast. The first remote commit seen after a quiet period snaps the cadence
back to the floor. Two framework properties control it (milliseconds):

- `org.mintjams.jcr.cluster.pollIntervalMillis` — the floor (active cadence;
  sub-second by default, floored at 50). Lower it to tighten cross-node
  read-after-write under load at the cost of more (cheap, indexed) queries.
- `org.mintjams.jcr.cluster.pollMaxIntervalMillis` — the idle ceiling
  (defaults to the historical fixed cadence, never below the floor). Raise
  it to make an idle cluster cheaper at the cost of a longer first-event
  latency after a quiet period; set it equal to the floor to disable the
  back-off and poll at a fixed rate.

Both are read live, so they can be retuned without a restart, and have no
effect on a standalone node (the poller does not run).

### `<workspace>/etc/jcr/jcr.yml` (per workspace)

```yaml
datasource:
  jdbcURL: jdbc:postgresql://db:5432/jcr_${workspace.name}
  username: jcr
  password: secret
  driverClassName: org.postgresql.Driver

blobstore:
  type: fs
  directory: /mnt/shared/cms/blobs/${workspace.name}

search:
  indexPath: /var/lib/cms/search/${workspace.name}   # node-local fast storage
```

`${...}` variables are substituted in `jdbcURL`, `username`, `password`,
`blobstore.directory`, and `search.indexPath`: `${repository.home}`,
`${workspace.home}`, `${workspace.name}`, `${cluster.nodeId}`, plus OSGi
framework and system properties.

Notes:

- **datasource** — omit for the embedded H2 default. Every node of a
  cluster must use the same URL. Each workspace needs its own database
  (or schema).
- **blobstore.directory** — omit to use `<workspace>/var/jcr/bin`; when
  the whole repository directory is on shared storage the default is
  already correct.
- **search.indexPath** — omit to use `<workspace>/var/search`
  (standalone) or `<workspace>/var/search/nodes/<nodeId>` (cluster). A
  node that starts with an empty index directory builds its index from
  the repository content automatically; pointing this at node-local disk
  avoids running Lucene over NFS.

### `<workspace>/etc/bpm/bpm.yml` (per workspace)

```yaml
jdbcURL: jdbc:postgresql://db:5432/bpm_${workspace.name}
username: bpm
password: secret
driverClassName: org.postgresql.Driver
```

Camunda handles distributed job execution and locking by itself once all
nodes share the database (see `documents/bpm-configuration.md`).

### Cluster journal (Phase 2)

Every transaction already records its events in the workspace's
`jcr_journal` table; in cluster mode the commit additionally writes a
marker into `jcr_journal_commits` (within the same transaction, so the
marker is visible if and only if the changes are). Each node runs a
poller (`JournalObserver.RemotePoller`, at an adaptive interval — see the
cluster configuration above) that reads the markers written by the other
nodes, replays the referenced journal entries, and persists its consumed
position in `jcr_journal_offsets`.
Replaying a remote transaction on a node:

1. drops everything the transaction touched from the node cache (for
   moved/removed subtrees, by path prefix — the journal carries only the
   subtree root) and marks the access control snapshot stale when the
   transaction affected entries,
2. posts the same OSGi events a local commit would post — which is what
   makes the existing event-driven layers (Camel route redeployment,
   workspace class loader reloads, CMS events, SSE/GraphQL
   subscriptions) cluster-aware without any change of their own, and
3. updates the node's local search index.

Delivery semantics and operational notes:

- **Ordering/stability.** Markers carry a database-generated sequence
  but become visible only when their transaction commits, so the poller
  only advances its consumed position past a marker once it is 10
  seconds old; younger markers are replayed immediately but deduplicated
  in memory. After a node restart, transactions younger than that window
  may be replayed once more — cache invalidation and index updates are
  idempotent; duplicate OSGi events are possible within that window
  only.
- **Clock synchronization.** The stability window compares the marker's
  origin-node timestamp with the local clock; run the cluster nodes with
  NTP-synchronized clocks (standard practice).
- **Retention.** Consumed markers are purged after 7 days by the
  workspace garbage collection (single node, under lease). A node that
  was offline longer than the retention can no longer catch up by
  replay; it detects this at startup, discards its search index, and
  rebuilds it from the repository content automatically.
- **Lag.** Cross-node visibility of search results, caches, and events
  is the current adaptive poll interval (the floor while the cluster is
  active — sub-second by default — backing off toward the ceiling while
  idle; see the cluster configuration above) plus, for search, indexing
  time. A burst's first event after a quiet period pays the backed-off
  interval; once it lands the cadence snaps to the floor for the rest of
  the burst. Content reads go
  through the shared database, but in front of it sits the per-node node
  cache: the committing node invalidates its own caches synchronously, so
  read-after-write on that node is immediate, while other nodes keep
  serving a cached node/property until the poller invalidates it — after
  which their reads reload from the shared database. Reads that do not hit
  a cached entry (a newly created node, child listings and
  `getWeakReferences()` which query SQL directly) are current on every
  node immediately. For a hard cross-node read-your-writes guarantee,
  route a user's requests to one node (sticky sessions) so their own reads
  ride the synchronous local invalidation.

### Cluster signal bus (Phase 3)

The journal carries the events that ride a JCR transaction. Some
notifications do not: they announce a change that is not itself a
repository write — for example `workspaceChanged`, which fires when a
workspace's runtime state (start/stop) or its settings (display name,
auto-start, engine switches) change. These would otherwise stay
node-local, so a desktop connected to another node would not refresh
until its next poll or reload.

The **signal bus** propagates them. `ClusterCoordinator.publish(topic,
properties)` inserts a row into `jcr_cluster_signals` (publishing node,
OSGi topic, JSON payload); each node's `ClusterController.SignalPoller`
(every 2 seconds) reads the rows written by the *other* nodes and
re-emits each as a **local OSGi event** under the recorded topic and
properties — indistinguishable from a local post, so the existing
event-driven layers (CMS events, SSE/GraphQL subscriptions) deliver it
unchanged. The publishing node does not receive its own signal; a caller
that wants the whole cluster posts the event locally *and* publishes it,
which is exactly what `CmsService.postWorkspaceChanged` does, fanning the
notification out over the **system** workspace's bus (the one workspace
running on every node, hence the repository-wide channel).

Delivery semantics and operational notes:

- **Ephemeral, not durable.** Signals tell *live* clients to refresh, so
  a node that was down when one was published had no client to notify
  and loses nothing — there is no per-node consumed offset, and a node
  simply begins at the current head when it joins. This is the
  deliberate difference from the journal, whose replay must be
  exactly-once for index correctness.
- **Ordering/stability.** Like the journal, the bus uses a
  database-generated sequence and the same 10-second stability window:
  signals are emitted immediately and deduplicated in memory, but the
  consumed position only advances past a signal once nothing can still
  surface behind it. The same clock-synchronization note applies.
- **Retention.** Rows are purged after 5 minutes — long enough for every
  live node to observe them — by whichever node holds the
  `cluster-signals-purge` lease.
- **Standalone.** `publish` is a no-op and the poller does not run; there
  are no other nodes to notify, and the local post already reaches the
  local desktops.

## Identity files (`repository/etc`, secrets)

The following files are the **cluster's** identity, not a node's: every
node must use the exact same files. They are auto-generated on first
boot when missing, so a node that starts with an empty directory mints
its own — which must never happen for the second and later nodes.

| File | Why it must be identical |
|------|--------------------------|
| `secrets/secret-key.yml` | Encryption key for stored secrets (e.g. the SP keystore password in `saml2.yml` is decrypted with it). A node with a different key cannot decrypt shared configuration and data. |
| `etc/boot.id` | Despite the name, a persistent repository identifier, not a per-boot or per-node one: it salts the PBKDF2 key derivation of the script-facing `Mask` and Crypto APIs, i.e. of every masked property stored in content (`setProperty(name, value, mask)`). A node with a different `boot.id` cannot unmask values masked by the others. |
| `etc/idp-keystore.p12` | Signing certificate of the built-in IdP. Service providers validate assertion signatures against the certificate published in the IdP metadata; assertions signed by a different per-node certificate fail validation. |
| `etc/sp-keystore.p12` | SP signing/encryption keys. The IdP holds the SP metadata; a node with different keys cannot sign AuthnRequests or decrypt assertions. |
| `etc/idp.yml`, `etc/saml2.yml` | Entity IDs, endpoints, and encrypted values — one external URL (`CMS_PUBLIC_BASE_URL`), one SAML entity. |

With the repository directory on shared storage (the recommended
layout), `etc/` is shared automatically and nothing needs to be done.
With node-local repository directories, start the first node once, let
it generate these files, and copy `repository/etc` plus
`secret-key.yml` to the other nodes **before** their first start.

Node-local by design (never share between nodes): the search index
(`var/search` or `search.indexPath`), the temporary directory,
`felix-cache`, and logs. The temporary directory (`repository/tmp`) is
**wiped on every node start**, so sharing it would let one node destroy
another's in-use files; in cluster mode each node therefore
automatically uses `repository/tmp/nodes/<nodeId>`, and the framework
property `org.mintjams.jcr.repository.tmpdir` can redirect it per node
(e.g. to fast local disk or tmpfs). The CMS layer obtains the directory
from the repository descriptor `repository.tmp.path`, so all upload
buffers, class loader caches, and file caches follow automatically.

> **First boot:** generation is a "create if missing" check, so on a
> completely empty shared directory, start a single node first and bring
> up the others after it has finished generating. Subsequent concurrent
> starts are unproblematic.

### Content deployment (`<workspace>/etc/jcr/deploy`, `provisioning`)

The provisioning and deployment directories are ordinary shared
configuration — sharing them is desirable, since all nodes should serve
the same content. Every node runs the content deployment at startup, and
the runs are serialized cluster-wide through the `content-deployment`
lease (`ClusterCoordinator`, exposed by the repository and obtainable by
adapting a JCR session): the first node performs the actual work, the
nodes after it walk the same files, find everything up to date via the
modification-time check, and pass through without writing. Concurrent
node starts therefore do not race over creating or updating the same
resources.

## Deployment checklist (Phase 1 + 2)

1. Provision PostgreSQL with one database per workspace for JCR (and one
   per workspace for BPM if used).
2. Install the PostgreSQL JDBC driver bundle into the Felix runtime.
3. Place the repository directory on shared storage (or at minimum
   configure `blobstore.directory` to shared storage on every node).
4. Configure `jcr.yml#datasource` and `bpm.yml#jdbcURL` identically on
   all nodes; configure `search.indexPath` (or accept the per-node
   default).
5. Ensure all nodes share the identity files (see above); on first boot,
   start one node alone first.
6. Enable `cluster.enabled` and give each node a unique `nodeId`
   (e.g. via `CMS_CLUSTER_NODE_ID`); keep node clocks NTP-synchronized.
7. Put the nodes behind a load balancer; sticky sessions are
   recommended (application-set session attributes are node-local)
   though logins survive node switches via the authentication token.
   Verify membership in `jcr_cluster_nodes` and consumption progress in
   `jcr_journal_offsets`.

Migrating existing H2 data to PostgreSQL is a one-time export/import of
the `jcr_*` tables; the schema is identical apart from the array
columns, which H2's `SCRIPT`/CSV tooling and PostgreSQL's `COPY` both
represent portably as text.

### Workspace databases are provisioned and removed by the operator

The "one database per workspace" rule of step 1 is not only an
initial-setup concern — it governs the **runtime** workspace lifecycle too
(the Workspace Manager app, the `createWorkspace` / `deleteWorkspace`
mutations). Unlike the standalone embedded-H2 case, where a workspace's
whole state lives under its directory, a clustered workspace keeps its
durable content in the shared datasource (`jcr.yml#datasource`, and
`bpm.yml#jdbcURL` if BPM is used) and optionally in shared blob storage
(`jcr.yml#blobstore.directory`). The platform never creates or drops those
external resources by itself:

- **Creating** a workspace requires its database(s) to **already exist**.
  The platform creates the *schema* (its tables) on first start under the
  `workspace-startup` lease, but not the database/schema container — create
  it (and grant the datasource user rights on it) beforehand, exactly as in
  step 1.
- **Deleting** a workspace removes its directory and node-local search
  index; the shared **database is not dropped** and shared blob storage is
  not removed. Clean both up manually afterwards.
- Because schema creation is "create if missing", **recreating a workspace
  of the same name against a database that still holds the old tables
  resurrects the old content.** Drop or empty the database before reusing
  the name.

See `documents/workspaces.md` for the full workspace lifecycle.

## Authentication across nodes (Phase 3)

A successful SAML login issues, next to the usual session attributes, an
**authentication token cookie** (`mintjams.cms.auth`): the authenticated
state (name ID, SAML attributes, authenticated factors, expiry)
encrypted with the cluster-shared secret key (AES/GCM via the CMS
encryptor, confidential and tamper-evident). Every credential-resolving
entry point falls back to the token when the local session has no login
and transparently re-establishes the session, so a failover or node
switch does not log the user out.

- **Lifetime** — `saml2.yml#authToken.ttl` (seconds, default 43200 =
  12 hours). The token is bearer-style with no server-side revocation
  list: logging out (`/logout`) clears the cookie and invalidates the
  local session, but a stolen token stays usable until it expires, so
  keep the lifetime moderate.
- **Factors** — the token carries the factors at login time
  (`saml2`). A factor added later by application code (e.g. TOTP) lives
  in the node-local session; after a node switch the user re-verifies
  that factor. This is intentional: re-prompting is safe, silently
  trusting is not.
- **Requirements** — all nodes must share `secret-key.yml` (see the
  identity files above); the cookie is `HttpOnly`, `SameSite=Lax`, and
  `Secure` on HTTPS.

## Application jobs and cluster coordination (Phase 3)

Scheduled application work (EIP timer routes, recurring scripts) runs on
every node. The `cluster` script API makes it run on exactly one:

```groovy
def lease = cluster.tryLock("nightly-report", 600000)
if (lease != null) {
    try {
        // ... runs on exactly one node ...
    } finally {
        lease.close()
    }
}
```

`cluster.lock(name, ttl)` waits instead of skipping;
`cluster.isClusterEnabled()`, `cluster.nodeId`, and
`cluster.listMembers()` (node id, host name, started, last heartbeat,
alive) expose the cluster state, e.g. for an operations dashboard. In
standalone deployments locks are granted immediately and the member list
is empty, so the same code runs unchanged. Application locks live in
their own namespace and cannot collide with the platform's internal
locks. The same coordination is available to Java code through
`org.mintjams.jcr.cluster.ClusterCoordinator` (adapt a JCR session).

Each node also watches the other members' heartbeats and logs a warning
when a node goes silent for three heartbeat intervals (and a notice when
it recovers) — the operational signal for a dead or partitioned node.
The same judgement is exposed as `Member.isAlive()` (and as the `alive`
entry of `cluster.listMembers()`), so consumers never hard-code the
heartbeat interval.

### Operational visibility

The cluster state is part of the operations surface:

- **GraphQL** — the `cluster` query (administrators only) reports
  `enabled`, the `nodeId` serving the request, and every registered
  member with `nodeId`, `hostName`, `started`, `lastHeartbeat`, `alive`,
  and `self`. In standalone deployments `enabled` is `false` and
  `members` is empty. See `documents/GRAPHQL_SCHEMA.graphql`.
- **Webtop dashboard** — the Dashboard app's Operations section
  (administrators only) shows a Cluster card listing every member with
  its heartbeat health, plus a cluster KPI in the "Are operations
  healthy?" hero; a member with a stale heartbeat turns the overall
  status to "Issues". A standalone deployment is reported explicitly as
  a single-node configuration — a normal, healthy state, not a degraded
  cluster.

### Built-in background jobs

The built-in background jobs (folder deletion, archive downloads, BPM
instance migration) run on the node that accepted the submission; their
state lives in content (`/var/jobs/...`), so progress is visible from
every node. The lifecycle is cluster-safe:

- **Abort works from any node.** When the abort request lands on a node
  that is not executing the job, the job is marked ABORTING in content;
  the executing worker polls the persisted status (about every 5
  seconds) and stops, and a job still queued elsewhere is finalised at
  pickup. A job is never marked aborted while the work actually
  continues.
- **A restarted node finalises its own dead jobs.** Queues and workers
  are in-memory, so jobs that were QUEUED or RUNNING when a node went
  down can never resume; at startup the node finalises exactly the jobs
  it owned (ABORTING → ABORTED, otherwise FAILED with an explanatory
  message) and leaves the other nodes' live jobs strictly alone. Jobs
  owned by a node that never returns keep their last status until an
  operator cleans them up.

## Future hardening

- Server-side revocation of authentication tokens (logout everywhere).
- Generic replication of application-set session attributes.
- Finalising background jobs owned by a permanently removed node.
- Automated cluster-wide consistency verification tooling.
