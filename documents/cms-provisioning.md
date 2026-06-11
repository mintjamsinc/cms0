# CMS Provisioning (`Session.deploy()`)

This document describes the declarative identity & access provisioning that runs
as part of `org.mintjams.script.resource.Session.deploy()`.

## Overview

`deploy()` is invoked once per workspace at boot (see
`CmsService.loadContent(...)`). Historically it only synchronised file content
from `<workspace>/etc/jcr/deploy/` into the repository. It now also applies
**provisioning descriptors** from `<workspace>/etc/jcr/provisioning/`:

```
<workspace>/etc/jcr/
├── jcr.yml                 # repository bootstrap (defaultNodes, security filters)
├── deploy/                 # file content mirrored into the repository
└── provisioning/           # declarative identity & ACL descriptors  ← this feature
    ├── README.md           # schema reference (ignored by deploy)
    ├── commerce.yml        # one descriptor per application
    └── ...
```

Each application ships its own `*.yml` descriptor, so multiple applications can
provision independently without editing a shared file. This directly answers the
"multiple files?" question: yes — drop one file per application into the folder.

## Why descriptors and not just `jcr.yml`

`jcr.yml#defaultNodes` is consumed by the JCR layer (`JcrWorkspaceProvider`)
**only when a node is first created** at repository bootstrap. It cannot create
users/groups/roles, and it does not re-run. Provisioning descriptors are the
**application-facing, re-runnable** layer on top of `deploy()`:

- they create identity (users/groups/roles), which `jcr.yml` cannot;
- they are idempotent and re-applied on every boot;
- they live with the owning application, not in the shared bootstrap file.

The ACL entry syntax is deliberately identical to `jcr.yml`'s `acl` blocks
(`group`/`user`/`principal`, `privileges`, `effect`) so the two are consistent.

## Processing model

1. All `*.yml` / `*.yaml` files in `provisioning/` are loaded (lexical order).
2. Their sections are aggregated and applied in phases so cross-file references
   resolve, and so a custom namespace is registered before any node/property
   name that relies on it is written: **namespaces → roles → groups → users →
   nodes**.
3. Each step is **idempotent**:
   - namespaces are registered only when absent; a prefix/URI already bound
     differently is reported as a conflict rather than silently remapped;
   - principals are created only when absent (passwords are never reset);
   - ACEs are added only when an equivalent entry is not already present.

## Scope: which workspace?

| Section            | Target workspace                                  |
| ------------------ | ------------------------------------------------- |
| `namespaces`       | the workspace currently being deployed            |
| `roles`            | `system` (global identity store, `/home/roles`)   |
| `groups`           | `system` (global identity store, `/home/groups`)  |
| `users`            | `system` (global identity store, `/home/users`)   |
| `nodes` (+ `acl`)  | the workspace currently being deployed            |

Identity is intentionally global: principals are shared across all workspaces.
When `deploy()` runs for the `system` workspace, the provisioner reuses the
deploy session via `Session.adaptTo(javax.jcr.Session.class)`; for any other
workspace it opens a dedicated `system` session for the identity writes.

## Namespaces

Register the JCR namespace mappings an application relies on so that its node
and property names (e.g. `acme:price`) resolve on the deployed workspace:

```yaml
namespaces:
  - prefix: acme
    uri: http://www.example.com/acme/1.0
```

Both `prefix` and `uri` are required. Registration targets the workspace
currently being deployed (namespaces are per-workspace in this repository), and
runs before every other phase so later sections may use the new prefixes. The
predefined prefixes (`jcr`, `nt`, `mix`, `xml`, `mi`) are always available and
need not be declared.

## Identity data model

The provisioner writes the **same** model the Identity Manager uses
(`org.mintjams.rt.cms.internal.graphql.IdpMutationExecutor`), so accounts created
by provisioning are indistinguishable from those created in the UI:

- User: `/home/users/{id}/profile` (`application/vnd.webtop.user`) with
  `password` (`{bcrypt}…`), `identifier`, `isGroup=false`, `isService`,
  `enabled`, optional `sn`/`givenName`/`displayName`/`mail`, and `roles` /
  `memberOf` as weak references; plus `preferences` and `Desktop` folders and a
  `jcr:all` self-grant on the user's home.
- Group: `/home/groups/{id}/profile` (`application/vnd.webtop.group`),
  `mix:referenceable`, `identifier`, `isGroup=true`.
- Role: `/home/roles/{id}/profile` (`application/vnd.webtop.role`),
  `mix:referenceable`.

`id` may be a nested path (e.g. `ops/readonly`) to build the role/group
hierarchy; declare parents before children.

## Service accounts

A user entry marked `service: true` is provisioned as a **service account** — a
non-interactive identity used by integrations (e.g. EIP routes / BPMN service
tasks via `runAs`):

- It is stored exactly like any other user, with `isService=true` on its
  profile content node (`isService` defaults to `false` when absent).
- It has **no password** (none is required, and none is stored), so it can
  never sign in interactively — `JcrUserStore.authenticate(...)` rejects it.
- It is still an ordinary principal subject to ACLs, so grant it access by
  making it a member of a normal group (`memberOf`) and granting that group on
  the relevant `nodes`.

This replaces the former `security.serviceAccounts` block in `cms.yml` and the
dedicated `ServiceAccountPrincipalProvider`: service accounts now live in the
global identity store and are resolved by the `DefaultPrincipalProvider` like
every other principal. There is no separate concept of a "service group" — use
a regular group.

## Implementation

- `org.mintjams.rt.cms.internal.provisioning.Provisioner` — loads, validates and
  applies the descriptors.
- `org.mintjams.script.resource.Session#deploy()` — calls the provisioner after
  the content deploy, within the existing deploy/rollback boundary.

## Schema

See [`provisioning/README.md`](../docker/initial-repository/workspaces/system/etc/jcr/provisioning/README.md)
for the full descriptor schema and examples.
