# Workspaces

The repository is organised into workspaces. Each workspace is a fully
isolated content store with its own database, blob storage, full-text
search index, metadata schemas, process engine (Camunda) and integration
engine (Apache Camel). Workspaces are the unit of deployment for
applications built on the platform: a typical installation runs the
mandatory `system` workspace plus one workspace per domain — for example
`web`, `commerce`, `hr`.

## What lives where

The `system` workspace is the repository's **identity store** and is
shared by every other workspace:

| Data | Workspace | Notes |
| --- | --- | --- |
| Users, groups, roles (`/home/users`, `/home/groups`, `/home/roles`) | `system` | Read by SSO (SAML SP/IdP) and by every workspace's principal resolution. Identity provisioning always writes here, regardless of which workspace's provisioning descriptor declared it. |
| User profile, avatar, preferences, wallpapers | `system` | Personalisation that follows the user across workspaces. The webtop reads these through its dedicated system-workspace client. |
| User working area (`/home/users/<id>/Desktop`) | each workspace | Created lazily on the user's first request to a workspace (see below). Files are not expected to move between workspaces. |
| Content (`/content`), schemas (`/etc/metadata/definitions`), i18n bundles (`/etc/i18n`) | each workspace | Defined and deployed per workspace. |
| BPMN processes, Camel routes | each workspace | Each workspace runs its own engines; see service toggles below. |

Because all content used to live in the `system` workspace, the default
workspace for logins that do not specify one is still `system`. Once
content has moved into its own workspaces, point unqualified logins at
the content workspace instead:

```yaml
# <repository>/etc/repository.yml
defaultWorkspace: web
```

or set the framework property `org.mintjams.jcr.workspace.default`.

## Workspace lifecycle

Workspaces can be created and deleted at runtime — no restart required:

- **Webtop:** the *Workspace Manager* app (administrators only).
- **GraphQL:** the `createWorkspace` / `deleteWorkspace` mutations and
  the `workspaces` query (see `GRAPHQL_SCHEMA.graphql`). Mutations are
  restricted to administrators and service accounts.
- **JCR API:** `javax.jcr.Workspace#createWorkspace(String)` /
  `#deleteWorkspace(String)` from an admin, system, or service session.

Workspace names appear in URLs (`/bin/graphql.cgi/<workspace>`,
`/bin/cms.cgi/<workspace>/...`) and as directory names, so they are
restricted to lowercase letters, digits, hyphens and underscores,
starting with a letter (max 64 characters).

Both creating and deleting a workspace run as background **jobs** on the
server — service start includes provisioning and content deployment and can
take minutes (far beyond typical HTTP idle timeouts), and either operation
can fail. The `createWorkspace` / `deleteWorkspace` GraphQL mutations
validate synchronously (privileges, name, already-exists / not-found) and
return a `jobId`; callers watch the generic `jobProgress(jobId)`
subscription for the operation's phase and its terminal `completed` /
`failed` event. The Workspace Manager app does exactly this — it shows a
progress overlay while the job runs and, on failure, a dismissable error
instead of a spinner that never stops.

Creating a workspace (job phases `creating` then `starting`):

1. Any leftover directory for the name — debris from an incomplete earlier
   delete — is removed first, so create/delete of the same name is
   repeatable.
2. The workspace directory is staged under
   `<repository>/workspaces/.creating-<uuid>` and atomically moved into
   place when fully populated, so a crash or a concurrent cluster rescan
   never sees a half-created workspace.
3. If `<repository>/etc/workspace-template/` exists, its contents seed
   the new workspace directory (copied verbatim — see below).
4. The JCR workspace starts: database **schema** (tables are created if
   missing), default nodes, search index.
5. The CMS brings the workspace's services online: standard folders,
   provisioning (`etc/jcr/provisioning/*.yml`), content deployment
   (`etc/jcr/deploy/`), script engines, process engine, integration
   engine, servlets, events.

If service start fails, the job ends `failed` and the workspace is reported
in the `FAILED` state (carrying the reason) rather than left waiting in
`STARTING`; the half-started services are rolled back so the workspace can
be deleted and recreated cleanly.

Deleting a workspace (job phases `stopping` then `deleting`) stops the
workspace's services and waits for them to come fully down **before**
removing the directory — removing files while the workspace is still
starting or running is what leaves debris behind — then removes the
directory and everything in it (retried briefly if a background flush still
holds a handle, and verified gone). The `system` workspace and the
workspace the calling session is bound to can never be deleted. Open
sessions on the deleted workspace are invalidated.

The underlying JCR API (`Workspace#createWorkspace(String)` /
`#deleteWorkspace(String)`) is synchronous and performs only the JCR-level
work — create stages and starts the workspace directory (steps 1–4); delete
removes it. The safe ordering (stop services fully, *then* delete) and the
progress/error reporting are provided by the job that the GraphQL mutations
submit; a direct JCR-API caller is responsible for stopping services first.

> **External databases and shared blob storage are not deleted.** With the
> embedded per-directory H2 default (standalone), all of a workspace's state
> lives under its directory, so delete removes everything and a same-name
> create starts clean. In a cluster the content lives *outside* the
> directory — in the shared datasource (and, when configured, shared blob
> storage) — and the platform never creates or drops those by itself. See
> *Clustering* below.

The repository posts the OSGi events
`org/mintjams/jcr/Workspace/CREATED` and
`org/mintjams/jcr/Workspace/DELETED` (property `workspace`), which is
how the CMS layer reacts to lifecycle changes; other bundles can listen
to the same topics.

### Observing workspace state

The `workspaces` query reports, per workspace, the lifecycle `state`
(`ONLINE` — CMS services running; `STARTING` — JCR workspace open but
services still coming up, e.g. a workspace just discovered on another
cluster node; `FAILED` — the service start threw and the workspace will
not come up without intervention, with the reason in `stateMessage`) and
the state of the per-workspace engines (`processEngine`,
`integrationEngine`: the `enabled` configuration switch plus the actual
`running` state — enabled but not running means the engine failed to
start). The webtop Dashboard app's Operations section (administrators
only) visualises this: a Workspaces card lists every workspace with its
state and engine health, drills into the Workspace Manager, and surfaces
trouble in the "Are operations healthy?" overall status: a `FAILED`
workspace or a failed engine is an outage, while a workspace still
`STARTING` is the lighter attention tier.

## Workspace template

`<repository>/etc/workspace-template/` is copied verbatim into every
newly created workspace directory. Use it for anything that must exist
before or at the workspace's first start:

- `etc/jcr/jcr.yml` — **required in a clustered deployment**, so that
  the new workspace points at the shared database
  (`datasource.jdbcURL`, typically with `${workspace.name}`
  substitution) instead of the embedded per-directory H2 default.
- `etc/jcr/provisioning/*.yml` — initial groups, ACLs and folder
  structure for new workspaces.
- `etc/jcr/deploy/` — content deployed on first start. To make the
  webtop desktop available in new workspaces, place the webtop
  distribution here (`etc/jcr/deploy/content/webtop/**` plus
  `etc/jcr/deploy/etc/i18n/**`), mirroring the system workspace's
  deploy layout.
- `etc/search/` — full-text search analyzer configuration
  (`search.yml`, `userdict.txt`, `stopwords.txt`, `stoptags.txt`,
  `mapping.txt`). Without it the generated `search.yml` is empty and
  the index falls back to Lucene's `StandardAnalyzer` — no Japanese
  morphological analysis. Copy the system workspace's `etc/search/`
  here so new workspaces get the same search quality.

A workspace has no `etc/web.yml`: the servlet context path is always
`/bin/cms.cgi/<workspace>`, derived from the directory name. Older
versions generated the file on first start; a leftover copy is ignored
and can be deleted.

When the template directory does not exist, new workspaces start empty
with generated defaults — identical to dropping a bare directory under
`<repository>/workspaces/` and restarting.

## Switching workspaces in the webtop

The desktop (shell and apps) is served from the workspace it operates
on: the URL `/bin/cms.cgi/<workspace>/content/webtop/` determines which
workspace every app's data client talks to. The menubar workspace
switcher therefore navigates to the target workspace's webtop rather
than swapping endpoints in place. A workspace only appears usable in
the switcher if the webtop content is deployed in it (see the template
section above).

## User home directories

Identity provisioning creates the full home (`profile`, `preferences`,
`Desktop`, avatar) in the `system` workspace. Content workspaces hold
only the user's working area: on a user's first request to a workspace,
`/home/users/<id>/Desktop` is created automatically and the user is
granted `jcr:all` on their home folder. Deleting a user removes the
identity home and the per-workspace homes in every workspace.

## Per-workspace service toggles

Both engines run per workspace and are enabled by default. Workspaces
that run no processes or routes — commonly the `system` workspace, when
identity workflows are not used — can switch them off to save the
engine database, job executor and Camel context resources:

```yaml
# <workspace>/etc/bpm/bpm.yml
enabled: false
```

```yaml
# <workspace>/etc/eip/eip.yml
enabled: false
```

When an engine is disabled, its GraphQL queries and console apps report
"not available" for that workspace; everything else works normally.

## Clustering

In a cluster (see `clustering.md`) workspace creation and deletion only
post events on the node that performed them. Every node therefore
rescans the workspace root periodically (default 30s, framework
property `org.mintjams.jcr.workspace.discoveryInterval`) and starts or
stops workspaces that appeared or disappeared on the shared storage. A
directory is only picked up once its `etc/jcr/jcr.yml` exists — in a
cluster it must carry the shared datasource configuration, so its
presence marks the workspace as safe to open. Keep a populated
`etc/workspace-template/` on shared storage so runtime-created
workspaces are born with the correct cluster configuration.

### External databases and shared blob storage are managed by the operator

With the embedded H2 default (standalone) a workspace's entire state —
content tables, blobs and search index — lives under its directory, so
creating and deleting workspaces is self-contained: delete removes
everything, and creating the same name again starts from nothing.

In a cluster the durable content lives **outside** the workspace
directory: in the per-workspace database named by
`etc/jcr/jcr.yml#datasource.jdbcURL` (and the BPM database in
`etc/bpm/bpm.yml#jdbcURL`, if used), and optionally in shared blob storage
(`etc/jcr/jcr.yml#blobstore.directory`). The platform deliberately neither
creates nor drops these — they are shared, destructive to remove, and often
outside the runtime's database privileges — so they are the operator's
responsibility:

- **Before creating** a workspace, the per-workspace database(s) must
  already exist. The platform creates the **schema** (its tables) on first
  start, but never the database/schema container itself. Provision it the
  same way as in the deployment checklist (`clustering.md`).
- **After deleting** a workspace, the directory and the node-local search
  index are gone, but the database and any shared blob storage **remain**.
  Drop the database (or empty its tables) and remove the shared blob
  directory manually.
- **Reusing a name** is the trap to remember: because schema creation is
  "create if missing", creating a workspace whose database still holds the
  old tables will **resurrect the old content** instead of starting clean.
  Always drop/empty the database before recreating a workspace of the same
  name.
