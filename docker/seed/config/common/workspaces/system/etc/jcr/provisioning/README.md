# CMS Provisioning Descriptors

This folder holds **declarative provisioning descriptors** that are applied by
`org.mintjams.script.resource.Session.deploy()` on every boot, right after the
content under `../deploy/` is deployed.

A descriptor lets an application register the **namespaces** and the **users /
groups / roles** it needs and configure **ACLs on folders and files** — without
writing any code.

## Multiple files

Every `*.yml` / `*.yaml` file placed directly in this folder is loaded. This is
intentional: each application ships **its own** descriptor (e.g. `commerce.yml`,
`crm.yml`, `portal.yml`) so applications can be provisioned side by side without
editing a shared file. Other file types (such as this `README.md`) are ignored.

Descriptors are aggregated and applied in well-defined phases — **namespaces →
roles → groups → users → nodes** — so references between authorizables resolve
even when they are declared in different files, and so a custom namespace is
registered before any node/property name that relies on it is written. Files are
processed in lexical filename order within each phase.

## Idempotency

`deploy()` runs on every boot, so provisioning is **idempotent**:

- Namespaces are registered **only when absent**. A mapping that already exists
  exactly as declared is skipped; a prefix or URI already bound differently is
  reported as a conflict instead of being silently remapped.
- Users, groups and roles are created **only when absent**. Existing principals
  are never modified and **passwords are never reset**.
- Access control entries are added **only when an equivalent entry** (same
  principal, same `allow`/`deny`, same privileges) is not already present.

To change an already-provisioned principal afterwards, use the Identity Manager
application — descriptors only establish the initial state.

## Scope

- **Namespaces** (`namespaces`) are registered on the workspace currently being
  deployed (namespaces are per-workspace).
- **Identity** (`users` / `groups` / `roles`) is always written to the global
  identity store in the **`system`** workspace, regardless of which workspace is
  being deployed.
- **Nodes and ACLs** (`nodes`) are applied to the resources of the workspace
  currently being deployed.

## Schema

```yaml
# Namespaces — JCR namespace mappings registered on the deployed workspace.
# Registered before every other phase so later names (e.g. acme:price) resolve.
# Idempotent: an identical mapping is skipped; a conflicting prefix/URI is an
# error. The predefined prefixes (jcr, nt, mix, xml, mi) need not be declared.
namespaces:
  - prefix: acme
    uri: http://www.example.com/acme/1.0

# Roles — hierarchical, stored under /home/roles in the system workspace.
roles:
  - id: administrator           # path under /home/roles; nesting allowed (e.g. ops/readonly)
    displayName: Administrators  # optional
    description: Built-in administrators   # optional

# Groups — hierarchical, stored under /home/groups in the system workspace.
groups:
  - id: commerce-operators
    displayName: Commerce Operators
    description: Operators delegated to administer commerce

# Users — stored under /home/users in the system workspace.
users:
  - id: alice
    password: changeit          # required for interactive users; stored as {bcrypt}
    displayName: Alice Smith     # optional
    givenName: Alice             # optional
    sn: Smith                    # optional
    mail: alice@example.com      # optional
    enabled: true                # optional, default true
    roles: [administrator]       # optional; ids under /home/roles
    memberOf: [commerce-operators]   # optional; group ids under /home/groups

  # A service account: a non-interactive identity used by integrations (via
  # runAs). It has no password and can never sign in, but it is an ordinary
  # principal subject to ACLs — so grant it access through a normal group.
  - id: commerce-service-user
    service: true                # marks a service account; sets isService=true
                                 #   (defaults to false when omitted). No password.
    displayName: Commerce Service    # optional
    memberOf: [commerce-service-group]   # optional; a normal group

# Nodes & ACLs — same shape as the `defaultNodes` entries in jcr.yml.
nodes:
  - path: /content/commerce
    primaryType: nt:folder       # optional; the node is created if it is missing
                                 #   nt:folder (default) or nt:file
    acl:
      - group: commerce-operators   # one of: group | user | principal
        privileges: jcr:read, jcr:write   # comma-separated string OR a YAML list
        effect: allow              # allow | deny
      - user: anonymous
        privileges: jcr:all
        effect: deny
```

### ACL entry keys

- Exactly one of `group`, `user` or `principal` identifies the grantee.
- `privileges` accepts either a comma-separated string (`jcr:read, jcr:write`)
  or a YAML list (`[jcr:read, jcr:write]`).
- `effect` is `allow` or `deny`.

This is the same convention used by the `acl` blocks in
[`../jcr.yml`](../jcr.yml), so the two are interchangeable in spirit: `jcr.yml`
seeds the very first nodes at repository creation, while descriptors here are the
application-facing, re-runnable mechanism layered on top of `deploy()`.
