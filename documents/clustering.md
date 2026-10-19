# Clustering

This document describes how the platform supports running multiple CMS
nodes against a shared repository, what is implemented today, and what is
still to come. The work is delivered in phases; this document is the
single source of truth for the current state.

| Phase | Scope | Status |
|-------|-------|--------|
| 1 | Storage externalization: configurable workspace database (H2 / PostgreSQL) with a SQL dialect layer, pluggable blob storage, database-lease locking instead of the exclusive workspace lock file, cluster node registry, single-node execution of maintenance tasks | **Implemented** |
| 2 | Cluster journal: nodes replay each other's committed transactions for search-index updates, cache invalidation (node cache, access control snapshot, subtree paths), and cross-node event propagation (OSGi events, and through them EIP route reloads, class loader reloads, CMS events, SSE/GraphQL subscriptions) | **Implemented** |
| 3 | Failover hardening: logins made cluster-portable via an encrypted authentication token, cluster information API for application code (member listing), stale-node detection with heartbeat warnings | **Implemented** |
| 4 | Cluster connections: direct, signed node-to-node connections on a management port; journal wake-ups, signals without the database, a replicated table holding the HTTP sessions and the owners of the subscription streams, forwarding of subscription changes | **Implemented** |

> Sticky sessions are **optional**. The authentication token
> re-establishes the login on whichever node receives the request, HTTP
> sessions (including an in-flight TOTP verification) are kept in the
> cluster's replicated table, and a subscription change sent to another
> node than the one holding the stream is forwarded to it. Sticky routing
> still removes the remaining windows of a few milliseconds (see
> *Sticky sessions* below).

## Architecture

A repository consists of workspaces. Per workspace, the persistent state
splits into three categories:

| State | Standalone (default) | Clustered |
|-------|----------------------|-----------|
| Content, properties, locks, ACLs, journal (`jcr_*` tables) | Embedded H2 (`<workspace>/var/jcr/data`) | Shared database (PostgreSQL or H2 server mode) |
| Binaries (blobs) | Local files (`<workspace>/var/jcr/bin`) | Shared storage (same directory on NFS or similar), via the blob store |
| Full-text search index (Lucene) | `<node-local>/workspaces/<workspace>/search` | **Node-local** — each node maintains its own index in its node-local directory (or at `jcr.yml#search.indexPath`) |

Next to the repository directory — the root of the data to keep, which a
cluster shares — every node has a **node-local directory**: the root of the
data that belongs to the node alone and can be rebuilt when lost. It never
goes on storage another node uses:

```
<node-local>/                 org.mintjams.jcr.repository.localdir / CMS_NODE_LOCAL_PATH
├── node.id                   the node the data belongs to
├── node.lock                 held while the node runs
├── tmp/                      the temporary directory, wiped on every start
└── workspaces/<workspace>/
    └── search/               the workspace's search index
```

It defaults to `repository-local` under the node's working directory (next
to the default `repository` directory, where `felix-cache` and the logs
are), in standalone and clustered deployments alike, so the repository
directory holds only data worth backing up.

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
  a transaction (journal-id collision retry, the blob cleaner's
  per-row delete) guards the statement with a savepoint when
  `DatabaseDialect.isTransactionAbortedOnError()` is true.
- **Lock-contention detection.** The SQLSTATEs for a lock timeout or
  deadlock differ per product (`HYT00`/`40001` on H2, `55P03`/`40P01`/
  `40001` on PostgreSQL) and are isolated behind
  `DatabaseDialect.isLockContention()`. The blob cleaner uses it to
  skip a `jcr_files` row locked by a concurrent user transaction —
  the row stays marked deleted and is cleaned on a later run — instead
  of failing its whole pass.

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
  clean shutdown. The row also records `transport_address`, where the
  node accepts cluster connections (see *Cluster connections* below).
  Operations can query this table to see the live cluster membership.
- `jcr_cluster_locks` — platform-internal leases (`lock_name`,
  `owner_id`, `lock_expires`) that serialize the repository's own
  bootstrap and maintenance work across nodes. A lease names its owning
  node and an expiry, so a crashed node never blocks the cluster for
  longer than the lease's time-to-live. Application locks do not live
  here — they are ordinary JCR locks (see
  `documents/resource-locks.md`).

The controller serializes the work that must not run on two nodes at
once:

| Lock | Held by | Purpose |
|------|---------|---------|
| `workspace-startup` | `JcrWorkspaceProvider.open()` | Schema creation, data migrations, default-node creation, orphan-node removal |
| `blob-cleaner` | `WorkspaceCleaner` | Removing deleted blobs from shared storage |
| `blob-garbage-collection` | `WorkspaceGarbageCollection` | Sweeping unreferenced blobs |
| `orphan-monitor` | `WorkspaceOrphanMonitor` | Orphan scan (avoids duplicate warnings) |
| `content-deployment` | `Session#deploy` (CMS layer) | Content deployment at startup |

These leases are repository infrastructure, exposed to platform code as
`org.mintjams.jcr.cluster.ClusterLeaseStore` (adapt a JCR session), and
node-scoped: every holder is a per-JVM singleton (a scheduled
maintenance thread or a startup step), so "one node at a time" is
exactly the right granularity. They are not an application lock
service — application work is guarded with ordinary JCR locks instead
(see `documents/resource-locks.md`).

In standalone mode the controller is a complete no-op: no tables, no
threads, and every lease is granted immediately. The exclusive workspace
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
effect on a standalone node (the poller does not run). With the cluster
connections up, a node does not wait for its next poll after another
node's commit: the committing node wakes it (see *Cluster connections*),
so the ceiling mostly matters while a connection is down.

The cluster connections are configured per node, from the framework
properties or the environment (never the shared `repository.yml`, since the
values differ per node):

| Framework property | Environment variable | Meaning |
|---|---|---|
| `org.mintjams.jcr.cluster.port` | `CMS_CLUSTER_PORT` | Port on which the node accepts connections from the other nodes. Default `9080`; give nodes on one host different ports. |
| `org.mintjams.jcr.cluster.bindAddress` | `CMS_CLUSTER_BIND_ADDRESS` | Interface the port listens on — the management network's. |
| `org.mintjams.jcr.cluster.advertiseAddress` | `CMS_CLUSTER_ADVERTISE_ADDRESS` | Where the other nodes reach this one, `host` or `host:port`. |
| `org.mintjams.jcr.cluster.secretKeyPath` | `CMS_CLUSTER_SECRET_KEY_PATH` | The key that signs the frames; default `<repository>/etc/cluster-secret-key.yml`. |
| `org.mintjams.jcr.repository.localdir` | `CMS_NODE_LOCAL_PATH` | The node-local directory (see *Architecture*); default `repository-local` under the working directory. Not cluster-specific: a standalone node uses it too. |

The two addresses are separate settings: the bind address is where the port
is opened, the advertise address is what the other nodes are told. When
only the bind address is set, it is advertised too. When only the advertise
address is set, the port is opened on that address itself — the name must
then resolve to an address of this host (a Compose service name does) — and
never on every interface, which on a host with a public and a management
interface would expose the port on the public one. When neither is set, the
node uses its only non-loopback site-local address for both. Anything else
— several site-local addresses or none, a name that does not resolve to
this host, a port that cannot be opened — stops the node from starting,
with the setting to fix named in the error: a node whose connections do not
work would follow the journal through the database polling, but its HTTP
sessions and subscription streams would stay on it, and behind a load
balancer without sticky routing its users would fail at random. Host names
are not used by default (a container's host name usually cannot be resolved
by the other containers).

### `<workspace>/etc/jcr/jcr.yml` (per workspace)

```yaml
datasource:
  jdbcURL: jdbc:postgresql://${env.CMS_DB_HOST}:${env.CMS_DB_PORT:-5432}/jcr_${workspace.name}
  username: ${env.CMS_DB_USER}
  password: ${env.CMS_DB_PASSWORD}
  driverClassName: org.postgresql.Driver

blobstore:
  type: fs
  directory: /mnt/shared/cms/blobs/${workspace.name}
```

`${...}` variables are substituted in `jdbcURL`, `username`, `password`,
`driverClassName`, `blobstore.directory`, and `search.indexPath`:
`${repository.home}`, `${workspace.home}`, `${workspace.name}`,
`${cluster.nodeId}`, `${env.NAME}`, plus OSGi framework and system
properties. A variable that resolves to nothing fails the workspace
rather than falling back to a default, so a missing setting is visible.

#### Values that differ per environment or per node

With the repository directory on shared storage there is **one** copy of
this file for the whole cluster, so anything that differs between
deployments or between nodes is named in the file and supplied from
outside:

- `${env.NAME}` reads the environment variable `NAME`, and
  `${env.NAME:-default}` falls back to a default when it is unset or
  empty — the `:-` of a shell, spelled the same way.
- When the environment variable `NAME_FILE` is set, the value is read
  from the file it names instead, with one trailing line terminator
  removed. This is how a Docker secret or a mounted Kubernetes Secret
  reaches the configuration without appearing in `docker inspect`. It
  takes precedence over `NAME`.

#### Secrets

A password has three interchangeable forms, all ending up in the same
field:

| Form | Where the secret lives |
|---|---|
| `password: ${env.CMS_DB_PASSWORD}` | the environment of the process |
| `password: ${env.CMS_DB_PASSWORD}` with `CMS_DB_PASSWORD_FILE=/run/secrets/…` | a file, e.g. a Docker secret |
| `password: ENC[v1:…]` | this file, encrypted with the installation's secret key |

`ENC[...]` values are produced by the `cms-encrypt` tool of the container
image, which reads the value from standard input so it never reaches the
process list or the shell history:

```console
$ docker exec -i cms cms-encrypt
<the value, on one line>
ENC[v1:...]
```

The key is `secrets/secret-key.yml`, which every node shares already (see
*Identity files*), so an encrypted value works on all of them. The tool
creates the key file when it does not exist yet, which is what lets a new
cluster put an encrypted password into its configuration **before** its
first node starts. The value of an environment variable may itself be an
`ENC[...]` one.

Do not pass secrets as `-D` system properties: the JVM echoes
`JAVA_TOOL_OPTIONS` to stderr at every start, which puts them in the
container log.

Notes:

- **datasource** — omit for the embedded H2 default. Every node of a
  cluster must use the same URL. Each workspace needs its own database
  (or schema).
- **blobstore.directory** — omit to use `<workspace>/var/jcr/bin`; when
  the whole repository directory is on shared storage the default is
  already correct.
- **search.indexPath** — omit to use `workspaces/<workspace>/search`
  under the node-local directory (see *Architecture*), which is what a
  node needs; a node that starts without an index builds it from the
  repository content automatically. Set it only to put the index
  somewhere else — never on shared storage, and since this file is shared,
  only through a per-node value such as `${env.NAME}`. A `jcr.yml` placed
  by an earlier container image still names
  `${env.CMS_SEARCH_INDEX_PATH}/…` (`/data/index`); remove the line to move
  the index under `/data/local`.

### `<workspace>/etc/bpm/bpm.yml` (per workspace)

```yaml
jdbcURL: jdbc:postgresql://${env.CMS_DB_HOST}:${env.CMS_DB_PORT:-5432}/bpm_${workspace.name}
username: ${env.CMS_DB_USER}
password: ${env.CMS_DB_PASSWORD}
driverClassName: org.postgresql.Driver
```

The same variables, `NAME_FILE` files and `ENC[...]` values as in
`jcr.yml` apply here.

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
- **Lag.** After a commit that wrote a marker, the committing node sends
  every connected node a **wake-up** (workspace name only, no content),
  and their poller reads the journal at once — but never sooner than the
  floor interval after its previous read, so wake-ups (genuine or forged)
  cannot make it poll faster; one that arrives within the floor is served
  when the floor has passed. Cross-node visibility is therefore the round
  trip of a wake-up and a read (tens of milliseconds) plus, for search,
  indexing time. The wake-up only shortens the wait: ordering,
  deduplication and the content still come from the database, and
  without a connection the adaptive poll interval applies (the floor
  while the cluster is active — sub-second by default — backing off
  toward the ceiling while idle). Content reads go
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

### Cluster connections (Phase 4)

Besides the shared database, the nodes talk to each other directly. When the
repository runs as part of a cluster, every node opens one plain TCP port —
the **cluster port**, meant for the management network — and keeps one
outbound connection to every other live node. Nothing is added to the public
HTTP port, and nothing on these connections goes through a servlet.

- **Membership comes from the heartbeat.** Who is alive, and at which
  `transport_address` it listens, is read from `jcr_cluster_nodes` of the
  system workspace (the one workspace running on every node). A connection is
  only a means: a node without one keeps working through the database
  polling, just more slowly.
- **One connection per direction.** A node writes only to the connections it
  opened and reads only from the ones it accepted (an answer to a request goes
  back on the connection the request came in on). A broken connection is
  reopened with exponential back-off.
- **Signed, not encrypted.** Every frame carries an HMAC-SHA256 made with a
  key derived from `cluster-secret-key.yml` (see *Identity files*), over the
  frame's kind, id and body; the body names the sender and the time it was
  sent. A wrongly signed frame, or one more than 30 seconds off this node's
  clock, drops the connection, and the node logs it once per sender. A
  connection from an address that is not a live member's (or the loopback,
  for several nodes on one host) is dropped before anything is read. The
  content is not encrypted, which is why the port belongs on the management
  network.
- **Sending never blocks.** Frames are queued per node and dropped when the
  node is not connected or does not keep up; the counters show up in the
  `cluster` query (see *Operational visibility*).

Four things travel on the connections:

| Frame | What it does |
|---|---|
| wake-up | After a commit that wrote a journal marker: "read this workspace's journal now" (see *Lag* above). Wake-ups for the same workspace that pile up while one is being sent go out as one. |
| signal | A notification that is not a repository write (below). |
| table write | A change of the replicated table (below). |
| request / answer | A synchronous call to one node, used to forward subscription changes (below). |

#### Signals

Some notifications do not ride a JCR transaction: they announce a change
that is not itself a repository write — for example the workspace settings
signal (`org/mintjams/rt/cms/workspace/SETTINGS_CHANGED`), which fires when a
workspace's display name, auto-start or engine switches are edited in the
files every node reads, or a topic message
([`topic-messages.md`](topic-messages.md)). (A workspace's runtime state is
not announced this way: every node records it as a repository write, see
*Workspace operations across the cluster* below.)

`ClusterCoordinator.publish(topic, properties)` sends the topic and the
properties (JSON scalars) to every connected node, which posts them as a
**local OSGi event** — indistinguishable from a local post, so the existing
event-driven layers (CMS events, SSE/GraphQL subscriptions) deliver it
unchanged. The publishing node does not receive its own signal; a caller
that wants the whole cluster posts the event locally *and* publishes it,
which is exactly what `CmsService.postWorkspaceSettingsChanged` does.

- **Ephemeral, not durable.** A signal is sent once, to the nodes connected
  at that moment. A node that is down, or alive but not connected, does not
  receive it (the drop is logged at debug level). Signals tell *live*
  clients to refresh, so this loses nothing that matters; a durable fact is
  a repository write.
- **Ordering.** Signals from one node arrive in the order it sent them;
  signals from different nodes have no common order.
- **Standalone.** `publish` is a no-op; there are no other nodes to notify,
  and the local post already reaches the local desktops.

(The signals used to go through a database table, `jcr_cluster_signals`,
polled every 2 seconds. The table is dropped when a node starts.)

#### The replicated table

The connections carry a small in-memory table that every node holds in full:
a write is applied locally at once and sent to every connected node, a read
never leaves the node, and a node that connects receives the whole table.
Of two values for one key the one with the later timestamp wins (ties are
decided by the node id), and a removal is remembered for a minute so that an
older value arriving late cannot bring it back. A value can carry a
time-to-live; every node drops it on its own when it passes.

The table is the place for state that may be lost but must be visible on
every node quickly — it is empty again once every node has stopped. Values
are small and their number is bounded; whatever must survive goes to the
repository. Platform code reaches it through
`org.mintjams.jcr.cluster.ClusterTransport`, an OSGi service the repository
registers in a cluster (not part of `ClusterCoordinator`, which application
code uses). It holds:

- **HTTP sessions.** The CMS registers a Jetty session store backed by the
  table (through the patched Felix Jetty bundle,
  `bundles/org.apache.felix.http.jetty`), so a session — the sign-in, an
  in-flight TOTP verification, an application's attributes — is the same on
  every node. Jetty waits for the store before it starts when
  `org.apache.felix.http.session.store.require=true`; the container image
  sets it together with `CMS_CLUSTER_ENABLED=true`, other deployments set
  it in `conf/config.properties`. A changed session is written back before
  the response is committed; an unchanged one is written at most once a
  minute.
- **The owners of the subscription streams** (below).
- **Each node's view of its connections**, refreshed every 10 seconds, for
  the Dashboard.

Session values travel as Java serialization, each class resolved through the
bundle that defines it. A script's own class cannot be stored — the
workspace class loader redefines it on every redeployment, so it would break
on the next node or the next deployment — and the `session` that web
scripts and templates see accepts **strings only** (structured values as
JSON). A session is created only when a script writes to it. A value that
cannot be serialized is left out with a warning, so it is gone on the next
request. Keep state in the repository; the session carries who the user is.
The servlet context (`application`) is not shared: it holds only what the
node may lose.

#### Subscription streams

The subscription stream (`/bin/graphql.cgi/<ws>/stream`, graphql-sse single
connection mode: `PUT` reserves a token, `GET` opens the stream, `POST` adds
a subscription, `DELETE` stops one) stays in the memory of the node that
holds it, and that node records (token → node, user) in the table. The four
requests may reach different nodes:

- A `GET` on another node than the `PUT` opens the stream there when its own
  sign-in is the reserving user's; the reservation on the first node expires.
- A `POST` or `DELETE` on another node is forwarded to the stream's node and
  answered with its result. Every change of a stream is applied in one place,
  in the order it arrives, and a `202` means the subscription is running.
  The browser sends the changes of one stream one at a time
  (`subscription-client.ts`), so a stop can never overtake the add it
  follows.
- An unknown token is `404`; a stream whose node cannot be reached is `503`.
  The client reconnects in both cases. An entry of a node that died expires
  with its time-to-live (a few heartbeats of the stream).

#### Sticky sessions

None of the above needs sticky routing, but two windows remain, as they would
with an external session store:

- **A few milliseconds.** A table write takes a moment to reach the other
  nodes, so two requests a page fires in parallel can land on two nodes
  before the first one's change has arrived. State that cannot tolerate
  this travels with the request (the authentication token is a cookie).
- **Reading back what another node wrote.** The node cache is invalidated by
  the journal (a wake-up away), so a read on another node right after a write
  can still see the old value for that long. A client that re-reads on a
  signal and finds an older state than the signal announced should read
  again after a short pause.

Routing a user to one node closes both. It is a choice, not a requirement.

One more thing to know when running without sticky routing: the table is
only shared over the cluster connections. While a node's connection to
another node is down — the port is blocked, the keys differ, the other node
is restarting — the journal still follows through the database, but the
sessions and streams written on one side do not reach the other. A user
whose requests are spread over the two nodes is then signed out (no shared
session) or their subscription stream is answered `404`/`503` and reconnected,
until the connection is back. The Dashboard's Cluster card shows the broken
connection; the `rejected` and `droppedWhileDisconnected` counters of the
`cluster` query say why. A node that cannot open its own port does not start
at all, so this state only arises between nodes that both started.

### Workspace operations across the cluster

Webtop cannot choose a node, so every operation on a workspace or an EIP
route acts on the whole cluster. Creating, deleting, starting, stopping
and restarting a workspace, and starting, stopping, suspending and
resuming a route, record a **desired state** in the system workspace under
`/var/operations/workspaces/<workspace>`; every node converges on it
(`WorkspaceReconciler`) and records what it actually runs next to it.

| Record | Written by | Content |
|--------|------------|---------|
| `desired` | the operation | presence (`PRESENT` / `ABSENT`), run (`RUNNING` / `STOPPED`), restart generation |
| `routes/<route>` | route operations | the state every node keeps the route in (`STARTED` / `STOPPED` / `SUSPENDED`); absent: the route definition decides |
| `nodes/<node>/request` | a retry | the node's retry generation |
| `nodes/<node>/state` | that node only | its state (`ONLINE` / `STARTING` / `STOPPED` / `FAILED` / `CLOSED`), the failure reason, its engines, the generations it has applied |
| `nodes/<node>/routes` | that node only | its route statuses |

The records are ordinary writes to the system workspace, so the cluster
journal delivers them to every node as local node events: the write is
both the durable state and the notification, and nothing depends on the
ephemeral signals. Each node also runs a full pass every 30 seconds,
which catches anything an event did not deliver.

- **Convergence, not commands.** A node that was down converges when it
  boots, so the order in which operations were requested never matters.
- **Restart and retry are generations.** A restart is an action, not a
  state: the restart generation is increased, and every node applies it
  once. A failed start is not retried automatically, which would loop on a
  broken workspace; a retry increases the retry generation of the chosen
  nodes.
- **Boot.** Before starting any workspace, a node reads the desired state.
  When no other node is alive — the cluster is starting with every node
  stopped — each workspace's run state is reset from its `autoStart`
  setting (`etc/workspace.yml`), and deletions left unfinished are
  completed. Otherwise the node follows the running cluster's desired state
  and starts what the cluster runs within its own boot sequence. A node
  that crashed counts as alive until its heartbeat goes stale (90 seconds),
  so a node restarted within that window follows the previous desired state
  rather than `autoStart`; a graceful stop deregisters the node and leaves
  no such window.
- **Lifecycle jobs wait for the cluster.** The jobs behind the Workspace
  Manager record the desired state and complete once every alive node has
  converged; a node that fails makes the job fail with that node's reason,
  and the desired state stays.
- **Creation and deletion.** Nodes do not rescan the workspace root. A
  workspace created on one node is opened on the others through its
  record. Deletion marks the workspace `ABSENT`, waits until every alive
  node has stopped its services and closed it, and only then removes the
  directory and the record.
- **Routes** keep their desired state across redeployments and restarts:
  it is applied right after a route file is (re)deployed.

The Workspace Manager shows each workspace's cluster-wide state and, for
every node, its state, engines, whether the latest restart and the saved
engine settings are applied there, and the failure reason, with a retry
for the nodes it failed on. The EIP Console shows a route's status on each
node. The OSGi Console (the Felix web console) is inherently per node; the
app names the node that served it.

The workspace directories — including `etc/workspace.yml`,
`etc/bpm/bpm.yml` and `etc/eip/eip.yml` — must be on storage shared by all
nodes: with node-local repository directories, neither runtime creation
nor settings changes reach the other nodes.

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
| `etc/cluster-secret-key.yml` | Signs the frames between the nodes (see *Cluster connections*). Held by the repository bundle alone and separate from `secret-key.yml`, so that one leaking does not expose the other. Created only in cluster mode (or at `CMS_CLUSTER_SECRET_KEY_PATH`), written in one step so that a node starting at the same time never reads a half-written file. A node with a different key has its connections refused — it logs a warning once per node (and an info message once they are accepted again) and keeps following the journal through the database polling; but its HTTP sessions and subscription streams stay on it, so without sticky routing its users are signed out or reconnected at random until the key is fixed (see *Sticky sessions*). To replace the key, replace the file and restart every node. |

With the repository directory on shared storage (the recommended
layout), `etc/` is shared automatically and nothing needs to be done.
With node-local repository directories, start the first node once, let
it generate these files, and copy `repository/etc` plus
`secret-key.yml` to the other nodes **before** their first start.

Node-local by design (never share between nodes): the node-local directory
(see *Architecture* — the search indexes and the temporary directory),
`felix-cache`, and logs. Keep the node's working directory, where all of
them default to, off the shared storage.

- **Temporary directory** (`<node-local>/tmp`) — **wiped on every node
  start**. It holds the workspace class loaders' copies of `/lib`,
  `/usr/local/{classes,lib}` and `/content/WEB-INF/{classes,lib}`, the file
  caches of binary values and web resources, upload and archive buffers,
  and the MIME index. The framework property
  `org.mintjams.jcr.repository.tmpdir` can redirect it (e.g. to a tmpfs).
  The CMS layer obtains the directory from the repository descriptor
  `repository.tmp.path`, so everything follows automatically.
- **`node.lock`** — held while the node runs; a second process given the
  same node-local directory (two nodes pointed at one directory, or the
  directory on shared storage) is refused at start.
- **`node.id`** — the node the data belongs to. A directory that comes from
  another node (copied, or the node renamed) is logged and taken over; its
  search indexes are rebuilt when they do not match.
- **Search indexes** record which workspace they were built for (the
  workspace's root node identifier). An index found for a workspace that
  was deleted and created again under the same name, or for a repository set
  up anew, is discarded and rebuilt. When a workspace is deleted, every node
  removes its own `<node-local>/workspaces/<workspace>`; a node that was down
  then removes it at its next start.
- In a cluster, a node-local directory (or a `tmpdir` / `search.indexPath`)
  under the repository directory is reported as a warning at start.

Earlier versions kept the index in `<workspace>/var/search` (cluster:
`…/var/search/nodes/<nodeId>`) and the temporary directory in
`<repository>/tmp` (cluster: `…/tmp/nodes/<nodeId>`). A node rebuilds its
index in the new place and then removes its own old index and temporary
directory; the other nodes' are left to them.

> **First boot:** generation is a "create if missing" check, so on a
> completely empty shared directory, start a single node first and bring
> up the others after it has finished generating. Subsequent concurrent
> starts are unproblematic.

### Content deployment (`<workspace>/etc/jcr/deploy`, `provisioning`)

The provisioning and deployment directories are ordinary shared
configuration — sharing them is desirable, since all nodes should serve
the same content. Every node runs the content deployment at startup, and
the runs are serialized cluster-wide through the `content-deployment`
lease (`ClusterLeaseStore`, exposed by the repository and obtainable by
adapting a JCR session): the first node performs the actual work, the
nodes after it walk the same files, find everything up to date via the
modification-time check, and pass through without writing. Concurrent
node starts therefore do not race over creating or updating the same
resources.

The bundled assets of the container image (`docker/README.md`, "Bundled
assets and configuration") are applied under the same lease, before the
workspace's own folders. Their record of the applied manifest lives in the
workspace (`/var/seed`), so the first node applies what the image changed
and the others find the record current. Nodes started from different images
would take turns bringing the assets to their own image's state: upgrade by
stopping every node and starting them all on the new image.

## Deployment checklist (Phase 1 + 2)

1. Provision PostgreSQL with one database per workspace for JCR (and one
   per workspace for BPM if used).
2. Install the PostgreSQL JDBC driver bundle into the Felix runtime.
3. Place the repository directory on shared storage — at least
   `<repository>/workspaces`, whose directories carry the settings every
   node reads and are created and deleted at runtime (see *Workspace
   operations across the cluster*). Blobs follow it unless
   `blobstore.directory` points at other shared storage.
4. Configure `jcr.yml#datasource` and `bpm.yml#jdbcURL` identically on
   all nodes. Give every node a node-local directory on its own storage
   (the default, `repository-local` under the working directory, is right as long as
   the working directory is not shared). With the shared configuration
   these are written once, and
   the per-deployment values come from the environment
   (`${env.CMS_DB_HOST}` and friends). To keep the database password out
   of the environment as well, encrypt it first — `cms-encrypt` mints
   the secret key when it is missing, so this can be done before any
   node has ever started.
5. Ensure all nodes share the identity files (see above); on first boot,
   start one node alone first.
6. Enable `cluster.enabled` and give each node a unique `nodeId`
   (e.g. via `CMS_CLUSTER_NODE_ID`); keep node clocks NTP-synchronized.
   With the container image, `CMS_CLUSTER_ENABLED=true` does the former
   and also places the clustered configuration files on first start.
7. Let the nodes reach each other on the cluster port (default `9080`;
   one port per node on a shared host) over the management network, and
   keep it closed to everything else: bind it to the management
   interface (`CMS_CLUSTER_BIND_ADDRESS`), leave it out of `ports:` and of
   public Services, and where there is no management network firewall it
   to the cluster's nodes. Set `CMS_CLUSTER_ADVERTISE_ADDRESS` when a node
   has more than one site-local address; without a bind address the port is
   then opened on the advertised address, not on every interface. A node
   that cannot determine or open its own address does not start (the error
   names the setting). A node that cannot reach another one keeps running
   and logs once per peer that it could not connect; while the connection
   is down the journal still follows through the database, but the two
   nodes do not share HTTP sessions or subscription streams, which matters
   without sticky routing (see *Sticky sessions*).
8. Outside the container image, set
   `org.apache.felix.http.session.store.require=true` in
   `conf/config.properties` of every clustered node, so that Jetty starts
   with the cluster's session store.
9. Put the nodes behind a load balancer; sticky sessions are optional
   (see *Sticky sessions*). Verify membership in `jcr_cluster_nodes`,
   consumption progress in `jcr_journal_offsets`, and the connections on
   the Dashboard's Cluster card.

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
- **Deleting** a workspace removes its directory, and every node removes
  its own node-local data of it (the search index); the shared **database
  is not dropped** and shared blob storage is not removed. Clean both up
  manually afterwards.
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
  (`saml2`). A factor added later (e.g. TOTP) lives in the session, which
  the cluster shares (see *The replicated table*), so a node switch keeps
  it. Should the session be lost — every node restarted, or it expired —
  the user re-verifies that factor. This is intentional: re-prompting is
  safe, silently trusting is not.
- **Requirements** — all nodes must share `secret-key.yml` (see the
  identity files above); the cookie is `HttpOnly`, `SameSite=Lax`, and
  `Secure` on HTTPS.

## Application work and cluster information (Phase 3)

Scheduled application work (EIP timer routes, recurring scripts) runs on
every node. Guarding it so that it runs exactly once at a time is not a
cluster feature: the guard is an ordinary JCR lock, which works
identically in standalone and clustered deployments because the lock
storage (`jcr_locks`) lives in the workspace database — node-local when
standalone, shared when clustered.

The guard is declared on the route that drives the work, so that losing
the race is a visible branch rather than an early return buried in a
script:

```xml
<toD id="sweep-lock"
     uri="cms:lock?context=cmsContext&amp;path=/var/locks/nightly-report&amp;isSessionScoped=true&amp;timeoutSeconds=600&amp;@header.locked=locked"/>
<filter id="sweep-guard">
    <simple>${header.locked} == true</simple>
    <!-- runs exactly once at a time, cluster-wide -->
</filter>
```

See `documents/eip-session-lifecycle.md` for the route shape and
`documents/resource-locks.md` for the lock semantics and the
`/var/locks` convention.

The `cluster` script API exposes the cluster state, e.g. for an
operations dashboard: `cluster.isClusterEnabled()`, `cluster.nodeId`,
and `cluster.listMembers()` (node id, host name, started, last
heartbeat, alive). In standalone deployments `isClusterEnabled()` is
`false` and the member list is empty. The same information is available
to Java code through `org.mintjams.jcr.cluster.ClusterCoordinator`
(adapt a JCR session).

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
  `self` and `address` (its cluster port). `link` is the connection
  between the serving node and the member as the serving node sees it
  right now (`outbound` connected / connecting / down and since when,
  `inbound` connected / none, the last wake-up sent and received, how
  many connections were refused for a wrong signature or clock
  (`rejected`), how many messages were dropped because there was no
  connection at the time (`droppedWhileDisconnected`, covered by the
  database polling) and how many because the connection's queue was full
  (`droppedOnOverflow`)); `reportedLinks` is the member's own view of
  its connections, reported through the replicated table every 10
  seconds, so the whole picture is available from any node. In
  standalone deployments `enabled` is `false` and `members` is empty. See
  `platform-schema.graphqls` (`Cluster`, `ClusterMember`, `ClusterLink`).
- **Webtop dashboard** — the Dashboard app's Operations section
  (administrators only) shows a Cluster card listing every member with
  its heartbeat health and, in its own badge, its connection to the
  node that answered (connected both ways, one way, or not connected —
  the card names that node). With three nodes or more it adds a
  node-by-node matrix of the reported connections. A cluster KPI sits in
  the "Are operations healthy?" hero; a member with a stale heartbeat
  turns the overall status to "Issues", a live member without its
  connections to "Attention" (its journal falls back to polling, and
  without sticky routing its sessions and streams are not shared — see
  *Sticky sessions*). A
  standalone deployment is reported explicitly as a single-node
  configuration — a normal, healthy state, not a degraded cluster.

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

### Search index rebuild

The full-text index is node-local (see the state table above), so an
administrator-triggered rebuild (`documents/search-index-rebuild.md`)
targets **every** node: one job record per alive member is persisted
under `/var/jobs`, all sharing a correlation id. The dispatch is
deliberately content-based rather than signal-based — a signal is
ephemeral, while a persisted record reaches a running node through
the replicated node events and reaches a node that was down at dispatch
time through its startup scan.

Two behaviours extend the generic job lifecycle above:

- **Restart casualties are re-run, not just finalised.** A rebuild is
  idempotent (a staged build that swaps in atomically), so after the
  generic recovery marks this node's dead rebuild jobs FAILED, the
  node's rebuild service re-queues and re-runs them automatically.
- **A node that joins after the dispatch needs nothing.** Its index
  directory does not exist yet, so it builds the index at startup; the
  progress form lists it as "not targeted".

## Future hardening

- Server-side revocation of authentication tokens (logout everywhere).
- TLS on the cluster connections, for deployments without a management
  network.
- Finalising background jobs owned by a permanently removed node.
- Automated cluster-wide consistency verification tooling.
