# CMS Archive — Export & Import

How the platform serialises content to a portable archive and reads it
back with full fidelity. The same artifact serves three purposes:

- **Download** — hand a selection of content to a user as an ordinary ZIP
  they can open anywhere.
- **Export** — capture content *and all of its metadata* so it can be
  re-imported exactly, including identity (UUID) and access control.
- **Migration / copy** — clone content into another path, workspace or
  installation, allocating fresh identity where required.

A single format covers all three. The download experience that exists
today is preserved unchanged; export and import are added *around* it
rather than replacing it.

## Why this document exists

Today's "download multiple items" feature streams only the raw file
content and the folder structure into a ZIP. Everything that makes a
node more than a file is dropped:

| Captured today | Dropped today |
| --- | --- |
| File body (`jcr:content/jcr:data`) | Custom properties (`title`, `author`, `web.template`, …) |
| Folder hierarchy | Binary properties (thumbnails, attachments) |
| File names / relative paths | Primary type, mixins |
| | References / weak references |
| | Access control (ACL) |
| | Node identity (UUID) |

The root cause is in
`bundles/org.mintjams.rt.cms/.../job/archive/ArchiveJob.java`
(`archiveRecursively`): for a file it copies `JCRs.getContentAsStream(node)`
into the ZIP and nothing else; for a folder it emits a bare directory
entry. No code path ever reads a node's properties. The result is a file
dump, not a faithful export.

JCR's own serialisation (`exportSystemView` / `importXML`) is **not**
implemented in this repository — both throw
`UnsupportedRepositoryOperationException` — so there is no existing
lossless format to lean on. This document specifies one.

## Design principles

- **Lossless by default.** The format records everything the repository
  can express. Narrowing happens at *import* time through explicit
  options, never by silently dropping data at *export* time.
- **The ZIP is still just files.** The archive's metadata lives in a
  reserved sidecar directory, so a CMS Archive opened in any unzip tool
  still presents the natural content tree. Casual download keeps working.
- **Symmetry.** Export and import are mirror images. Anything written by
  the exporter is understood by the importer, and vice versa.
- **Identity is first class.** Export/import is only meaningful if a
  node can come back as *the same node*. The format carries UUIDs, and
  the repository core is extended so they can be honoured on the way in.
- **Forward compatible.** Every archive declares a format version; the
  importer refuses versions it does not understand rather than guessing.

## Archive format — "CMS Archive v1"

A CMS Archive is a ZIP with two parts: the **content tree** (the files,
exactly where today's download puts them) and a reserved
**`.cms-archive/`** directory holding the metadata needed to import it.

```
archive.zip
├── docs/                          ← the content tree (human-usable as-is)
│   ├── report.pdf                 ← nt:file body
│   └── images/
│       └── cover.png
└── .cms-archive/                  ← import metadata (ignored by casual users)
    ├── manifest.json              ← archive-level header
    ├── nodes.ndjson               ← one node per line: type, mixins, order, properties
    ├── acl.ndjson                 ← one ACL per line (optional section)
    └── blobs/
        └── 0001                   ← binary *property* payloads kept out of line
```

The `.cms-archive/` name is reserved: the exporter refuses to archive a
node literally named `.cms-archive` at a root, and the importer treats
the directory as control data, never as content.

### `manifest.json`

The header read first. It identifies the producer and declares what the
archive contains so the importer can validate before touching anything.

```json
{
  "format": "cms-archive",
  "version": 1,
  "createdAt": "2026-06-15T08:30:00Z",
  "createdBy": "k1.fukuda",
  "source": {
    "repository": "<repository-id>",
    "workspace": "web"
  },
  "roots": ["/content/docs"],
  "contains": {
    "properties": true,
    "binaries": true,
    "acl": true,
    "order": true
  },
  "counts": { "nodes": 128, "blobs": 12 }
}
```

`roots` are the top-level selected paths, recorded so an import can
reproduce the selection relative to a chosen destination. `contains`
tells the importer which optional sections are present, so it can offer
only the toggles that make sense for *this* archive.

### `nodes.ndjson`

The heart of the format: newline-delimited JSON, **one node per line**,
emitted in document order — a parent always precedes its children, and
siblings appear in repository order. That line order *is* the structure:
the importer creates nodes top-down, so parents always exist before their
children and sibling order is reproduced by creation order (the most an
engine without `orderBefore` can honour — see *Ordering*). NDJSON is
chosen over a single JSON array so both writing and reading stream in
constant memory — an export of a large subtree never has to be held whole
in RAM.

```json
{"path":"/content/docs","name":"docs","primaryType":"nt:folder","mixins":["mix:referenceable"],"uuid":"7b1e…","entry":"docs","properties":[…]}
{"path":"/content/docs/report.pdf","name":"report.pdf","primaryType":"nt:file","mixins":[],"entry":"docs/report.pdf","properties":[…]}
{"path":"/content/docs/report.pdf/jcr:content","name":"jcr:content","primaryType":"nt:resource","mixins":[],"properties":[…]}
```

Per node:

| Field | Meaning |
| --- | --- |
| `path` | Absolute repository path at export time. |
| `name` | The node name (last path segment). |
| `primaryType` | `jcr:primaryType` (e.g. `nt:file`, `nt:folder`). |
| `mixins` | `jcr:mixinTypes`, empty array if none. |
| `uuid` | `jcr:uuid` for `mix:referenceable` nodes; omitted otherwise. |
| `entry` | The node's path inside the content tree (file body or folder), so the importer can map a node to its bytes unambiguously. Omitted for metadata-only nodes such as `jcr:content`. |
| `properties` | Every non-protected property (below). |

`jcr:content` for an `nt:file` is emitted as its own node line; its body
is **not** duplicated in the sidecar — the importer reads the body from
the file named by the parent's `entry` in the content tree. Structural
identity (`jcr:primaryType`, `jcr:mixinTypes`, `jcr:uuid`) is carried by
the dedicated fields above.

The repository-managed **audit properties** are handled deliberately
rather than replayed as ordinary data:

- `jcr:created` and `jcr:lastModified` travel as the dedicated, optional
  node fields **`created`** and **`lastModified`** (ISO-8601, UTC). On
  import they are re-applied only when the operator keeps *Preserve
  timestamps* on (the default). `jcr:created` is a **protected** property,
  so it can only be honoured at creation time — the importer supplies it
  through the core `addNode(relPath, primaryType, identifier, created)`
  primitive (see *Core extension*); `jcr:lastModified` is not protected
  and is set after the body is in place.
- `jcr:createdBy` and `jcr:lastModifiedBy` are **never** carried. They
  always record the user performing the import, so provenance of who
  introduced the content into *this* repository is never falsified.

Other protected/auto-created properties the repository recomputes on
write are not stored as ordinary properties; they are re-derived on
import.

### Property serialisation

Properties carry their JCR type so they round-trip exactly. Every type
the value factory supports is representable
(`JcrValueFactory` / `JcrValue`: String, Long, Double, Boolean, Date,
Decimal, Name, Path, URI, Reference, WeakReference, Binary).

```json
{ "name": "title",   "type": "String",  "value": "Annual report" }
{ "name": "tags",    "type": "String",  "multiple": true, "value": ["a","b"] }
{ "name": "views",   "type": "Long",    "value": 42 }
{ "name": "ratio",   "type": "Double",  "value": 0.5 }
{ "name": "live",    "type": "Boolean", "value": true }
{ "name": "issued",  "type": "Date",    "value": "2026-06-15T08:30:00.000Z" }
{ "name": "owner",   "type": "Reference",     "value": "a02c…" }
{ "name": "related", "type": "WeakReference", "value": "7b1e…" }
{ "name": "thumb",   "type": "Binary",  "value": { "blob": "blobs/0001", "size": 20480 } }
```

Rules:

- **`multiple`** distinguishes a single-valued property from a
  single-element array; without it the round-trip would silently collapse
  `String[1]` into `String`.
- **Date** is ISO-8601 with milliseconds, normalised to UTC.
- **Reference / WeakReference** store the *target UUID*, not a path —
  this is what the repository stores and what makes UUID preservation
  matter (see *References*).
- **Binary** values are never inlined as Base64. The payload goes to
  `.cms-archive/blobs/<id>` and the property references it by relative
  path plus size. This keeps `nodes.ndjson` small and text-diffable, and
  avoids the ~33 % bloat Base64 would add to large media. (File *bodies*
  are likewise kept in the content tree, not inlined.)

### `acl.ndjson` (optional)

Access control is emitted only when the caller asks for it and only for
nodes that carry an explicit policy. One line per node:

```json
{"path":"/content/docs","entries":[
  {"principal":"editors","allow":true,"privileges":["jcr:read","jcr:write"]},
  {"principal":"anonymous","allow":false,"privileges":["jcr:read"]}
]}
```

This maps directly onto the implemented
`JcrAccessControlManager` / `JcrAccessControlList` API (allow/deny ACEs
of principal + privilege set).

## What the repository can and cannot round-trip

The format is the *complete* form; a few capabilities are not yet
implemented in this repository's JCR core. Where that is so, the archive
still **records** the information (so existing exports become fully
faithful the day the capability lands) but the importer's behaviour today
is documented honestly.

| Capability | Core status | In the archive | On import today |
| --- | --- | --- | --- |
| Properties (all types) | Implemented | Recorded | Imported exactly |
| Primary type / mixins | Implemented (`addMixin`, …) | Recorded | Imported exactly |
| References / weak refs | Implemented (depends on UUID) | Target UUID | Imported; remapped in new-UUID mode |
| ACL | Implemented (`set/getPolicy`) | `acl.ndjson` | Imported when enabled |
| Node identity (UUID) | **Added by this work** (core extension) | `uuid` | Honoured in preserve mode |
| Child ordering | **Not implemented** (`orderBefore` is a stub) | `children` order | Best-effort: children created in recorded order |
| Same-name siblings | **Disallowed** (no index support) | n/a | n/a — names are unique |

Two honest limitations, both flagged to the operator in the UI:

- **Ordering.** `JcrNode.orderBefore` checks privileges and returns
  without reordering, and `hasOrderableChildNodes()` is always `false`.
  The archive stores child order regardless; the importer creates
  children in that order so the result is as faithful as the engine
  permits today, and becomes exact once `orderBefore` is implemented.
- **Same-name siblings.** SNS is disallowed unless a node definition
  opts in (`"sns"`), and `getIndex()` is always `1`. Names are therefore
  unique within a parent and no indexed paths (`foo[2]`) occur, which
  simplifies path handling everywhere.

## Core extension — UUID-preserving node creation

An export that cannot bring a node back as *the same node* is not worth
the name. Today the core has no way to do this: `importXML` throws,
identity comes from the database `item_id`, and there is no
`IMPORT_UUID_*` behaviour. Importing would mint new UUIDs and break every
reference.

It turns out the lowest level already has the missing primitive: the
item-creation path (`WorkspaceQuery.items().createNode`) honours a
caller-supplied `identifier`, and a `mix:referenceable` node's `jcr:uuid`
is derived from that `item_id` — so preserving the identifier preserves
the UUID, and references (stored as the target's identifier) resolve.
What was missing was a *safe, intentional* way to reach it. This work adds
exactly that, the principled fix every future migration, clone and version
feature will reuse:

1. **Create-with-identity (core API).** A new
   `org.mintjams.jcr.Node#addNode(relPath, primaryType, identifier)`
   creates a child with a caller-supplied UUID. It is the identity-
   preserving counterpart of the standard `addNode`, reserved for trusted
   import code; the standard `addNode` still mints a fresh id.
2. **Collision safety (core API).** The new method refuses to mint a
   duplicate identity: if the UUID is already in use it throws
   `ItemExistsException`. Overwriting live data is never an implicit side
   effect of preserving identity — it is the caller's explicit decision.
3. **Create-with-creation-time (core API).** A further overload
   `addNode(relPath, primaryType, identifier, created)` seeds the
   **protected** `jcr:created` at creation time (the only point it can be
   written), so an import can bring a node back with its original creation
   timestamp. `jcr:createdBy` is deliberately *not* caller-supplied: it
   always records the importing user. A `null` `created` (or `identifier`)
   falls back to the standard behaviour, so the three overloads compose
   cleanly.

The importer composes the product's collision modes from this primitive
plus ordinary node removal, so the policy lives where the decision is made
rather than buried in the store:

| Mode | How the importer realises it |
| --- | --- |
| `THROW` | Call create-with-identity directly; its built-in check aborts on collision. |
| `REPLACE_EXISTING` / `REMOVE_EXISTING` | Remove the colliding node first, then create-with-identity. |
| `CREATE_NEW` | Use the standard `addNode` (no identifier); a fresh UUID is allocated. |

The two import identities the product exposes map onto this:

- **Import (preserve identity)** → create-with-identity, with `THROW` or
  remove-then-create. References resolve untouched.
- **Copy / migrate (new identity)** → standard `addNode` + reference
  remapping.

## References — the two-pass importer

References store target UUIDs, so import is two passes regardless of
mode:

1. **Pass 1 — materialise.** Walk `nodes.ndjson` in order. Create each
   node with its primary type and mixins (with or without its original
   UUID per mode), write the file body from the content tree, and set
   all non-reference properties. Reference and weak-reference properties
   are deferred — their targets may not exist yet. Record an
   `oldUuid → newUuid` map as nodes are created.
2. **Pass 2 — link.** Resolve every deferred reference. In preserve mode
   the target UUID is unchanged and resolves directly; in new-identity
   mode it is translated through the map. A reference whose target is
   outside the archive is reported (kept if the target already exists in
   the repository, otherwise surfaced as an import warning rather than
   silently dropped).

ACLs (when present and enabled) are applied in a final pass, after all
principals' target nodes exist.

## Import options

Exposed to the operator; defaulted for the common case.

| Option | Values | Default | Notes |
| --- | --- | --- | --- |
| Destination | repository path | archive `roots` | Where the tree lands. |
| Identity | `preserve` / `new` | `preserve` | Maps to the UUID modes above. |
| On UUID collision | `throw` / `replace` / `remove` | `throw` | Only meaningful when identity = `preserve`. |
| On path conflict | `skip` / `overwrite` / `merge` / `rename` | `merge` | Independent of UUID identity. |
| Preserve timestamps | on / off | on | Carry each node's original `jcr:created`/`jcr:lastModified`. Off stamps the import time. `jcr:createdBy`/`jcr:lastModifiedBy` are always the importing user either way. |
| Import ACL | on / off | off | Offered only if the archive `contains.acl`. Lets an import deliberately drop access control. |
| Dry run | on / off | off | Validate and report (counts, conflicts, dangling references, missing types) without writing. |

"Import ACL = off" is the explicit answer to *"import everything except
permissions"*: ACL is always captured, and dropping it is an import-time
choice, never a gap in the archive.

## GraphQL API

### Export (extends the existing flow)

The download job already runs as `initDownloadArchive` →
`appendDownloadArchive` (≤100 paths/call) → `startDownloadArchive`, with
progress and the terminal `downloadUrl` on the `jobProgress(jobId)`
subscription, and `abortDownloadArchive` to cancel. That flow is kept;
`startDownloadArchive` gains an options input so a download can opt into
the metadata sidecar and ACL:

```graphql
input StartDownloadArchiveInput {
  jobId: String!
  fileName: String!
  # New — defaults preserve today's plain-ZIP behaviour for callers that omit them.
  includeMetadata: Boolean = true   # write .cms-archive/ (properties, mixins, refs)
  includeAcl: Boolean = false       # add acl.ndjson
}
```

### Import (new, mirrors the export shape)

The archive is uploaded with the existing multipart-upload mutations,
then handed to an import job that reports through the same
`jobProgress(jobId)` subscription:

```graphql
mutation InitImportArchive(input: InitImportArchiveInput!): JobHandle
mutation StartImportArchive(input: StartImportArchiveInput!): JobHandle
mutation AbortImportArchive(input: AbortImportArchiveInput!): JobHandle

input StartImportArchiveInput {
  jobId: String!
  archivePath: String!              # uploaded ZIP in the repository
  destinationPath: String!
  identity: ImportIdentity = PRESERVE      # PRESERVE | NEW
  onUuidCollision: UuidCollision = THROW   # THROW | REPLACE | REMOVE
  onPathConflict: PathConflict = MERGE     # SKIP | OVERWRITE | MERGE | RENAME
  preserveTimestamps: Boolean = true       # carry jcr:created / jcr:lastModified
  importAcl: Boolean = false
  dryRun: Boolean = false
}
```

`InitImportArchive` validates synchronously (privileges, archive exists,
readable `manifest.json`, understood `version`) and returns a `jobId`, or
`errors` with a null `jobId` — the same contract the other async
mutations use.

## Server implementation

| Area | File | Change |
| --- | --- | --- |
| Export worker | `…/job/archive/ArchiveJob.java` | After streaming a file body, also serialise the node to `nodes.ndjson`; emit folder nodes too; spool binary properties to `blobs/`; write `manifest.json`/`acl.ndjson` at the end. Reuse the property extraction already in `NodeMapper`. |
| Property model | new `…/job/archive/NodeSerializer.java` | Shared read/write of the per-node JSON and property type mapping, used by both jobs so export and import never drift. |
| Import worker | new `…/job/archive/ImportArchiveJob.java` | Two-pass import + ACL pass; honours all import options; emits `jobProgress`. Symmetric to `ArchiveJob`. |
| Repository core | `…/rt/jcr/internal/WorkspaceQuery.java` (+ items API) | Create-with-identity and UUID-collision behaviour. Trusted callers only. |
| GraphQL | `…/internal/graphql/MutationExecutor.java` | `StartDownloadArchiveInput` options; `init/start/abortImportArchive`. |
| Servlet | `…/internal/web/ArchiveDownloadServlet.java` | Unchanged — it already streams whatever the job produced. |

## Webtop

- **Download** stays one click; an *Include metadata* affordance (and, for
  privileged users, *Include permissions*) turns a plain download into an
  export. `content-archive.ts` passes the new options to
  `startDownloadArchive`.
- **Import** is a new flow in the content browser (named *Import*, the
  mirror of *Export* — not "Restore", which would imply disaster
  recovery): upload a CMS Archive, show the manifest summary (source,
  counts, what it contains), let the operator pick destination and the
  import options above, run a **dry run** first by default, then execute —
  both watched through the shared job-progress overlay used by other long
  jobs (e.g. Workspace Manager). Its dropdowns are the same shell-rendered
  popups the rest of the webtop uses, anchored to each field.
- Limitations that cannot be honoured by the engine today (ordering) are
  shown inline so the operator is never surprised.

## Security

- Export reads every property and, when requested, ACLs: it runs with the
  caller's privileges, so a user only ever archives what they may read
  (`jcr:read`, `jcr:readAccessControl` for the ACL section).
- Import is a privileged operation: creating nodes, writing properties,
  and — for preserve/replace and ACL import — `jcr:nodeTypeManagement`
  and `jcr:modifyAccessControl`. Create-with-identity is reachable only
  from the trusted import path, never from the public node API.
- The downloadable archive remains owner-scoped, exactly as the existing
  `ArchiveDownloadServlet` enforces.

## Compatibility & versioning

- The content tree is written identically with or without the sidecar, so
  a casual download still opens as ordinary files and nothing regresses.
  `includeMetadata` defaults to **true** — every download is re-importable
  by default, directly fixing the original "properties disappear" problem;
  pass `includeMetadata: false` for a bare file-only ZIP.
- `manifest.version` gates the importer: an unknown version is refused up
  front. New optional sections are added by extending `contains` so older
  importers can detect, and decline, what they cannot import.
- The format is independent of database schema and JCR internals; an
  archive taken from one installation imports into another of the same
  or newer format version.

## Delivery phases

1. **Lossless export.** `NodeSerializer` + extend `ArchiveJob` to write
   `.cms-archive/` (properties, mixins, references, binaries). Download
   gains *Include metadata*. Archives are now complete even before
   import exists.
2. **Core identity.** Create-with-identity + UUID-collision behaviour in
   the repository core, with tests.
3. **Import.** `ImportArchiveJob` (two-pass + reference remap),
   import GraphQL mutations, dry run.
4. **ACL.** `acl.ndjson` on export, ACL pass on import, the
   include/import toggles.
5. **Webtop import flow.** Upload, manifest summary, options, dry-run,
   execute, progress.
