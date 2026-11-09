# Access control: users, groups and roles

Who may read or change a node is decided by the access control entries on
the node and its ancestors. An entry names a **principal**, a set of
privileges and whether it allows or denies them. This document describes the
three kinds of principal an entry can name, how the entries are evaluated,
and where the same decision is applied outside a session (search, event
delivery, workflow tasks).

## Principals

| Kind | Where it lives | Name used in entries | Notes |
| --- | --- | --- | --- |
| User | `/home/users/{name}/profile` | the user name | Signs in; has roles and group memberships |
| Group | `/home/groups/{path}/profile` | the last segment of the path (`identifier`) | Groups nest; a user is a member of a group directly (`memberOf`) |
| Role | `/home/roles/{path}/profile` | the last segment of the path (`identifier`) | Roles nest; a user holds a role directly (`roles`) |
| `everyone` | built in | `everyone` | Every session, signed in or not |
| `anonymous` | built in | `anonymous` | The guest session |

Users, groups and roles are managed in the Identity Manager and by package
[provisioning](cms-provisioning.md). Roles were always part of the identity
model (the built-in `administrator` and `supervisor` roles decide who may
administer the installation); since this version a role is also an ordinary
access control principal, so a folder can be granted to "everyone holding the
`site-approver` role" without a parallel group.

### Names are unique across kinds

Entries store the bare principal name, and the name is resolved to a user, a
group or a role, in that order. A name therefore identifies exactly one
principal: the Identity Manager and provisioning refuse to create a role whose
name is already a user's or a group's, and a user or group whose name is a
role's. Nested roles and groups are no exception: the identifier is the last
path segment, so two roles `sales/approver` and `support/approver` cannot
coexist.

### Hierarchy does not inherit

A user holds the roles assigned to them and is a member of the groups listed
in `memberOf`. Holding `ops/readonly` does not imply `ops`, and belonging to a
child group does not imply the parent. (The Identity Manager shows the parent
groups as `effectiveGroups` for navigation; access control does not use them.)

## Evaluation

The entries from the root down to the node are applied in order. An entry
counts when its principal is `everyone`, the session's user, one of the user's
groups or one of the user's roles. An allow entry adds its privileges, a deny
entry removes them; the deeper entry, and within one node the later entry,
wins. Aggregate privileges (`jcr:write`, `jcr:all`) stand for their
constituents.

Sessions of the system, service and admin principals hold every privilege;
everyone may read below `/jcr:system`; public-access rules of the workspace
configuration grant read without entries.

## Where else the decision is applied

- **Search.** The index records, per document, the principals allowed to read
  it (`name@user`, `name@group`, `name@role`). A query is restricted to the
  session's user, groups and roles plus `everyone`, so results, facet counts
  and suggestions only cover what the session may read.
- **Event delivery.** Subscriptions decide readability without a session
  through `Repository.hasPrivileges(workspace, user, groups, roles, path,
  privileges...)`; the decision cache is invalidated when `/home/users`,
  `/home/groups` or `/home/roles` change.
- **Workflow tasks.** The BPM engine knows groups only. A user's roles are
  reported to it beside the groups, so a task's `candidateGroups` may name a
  role (`camunda:candidateGroups="site-approver"`), and the Tasks app lists
  such tasks under "my group tasks".

## Granting

- **Content Browser.** The access control dialog searches users, groups and
  roles alike; a role is shown with a badge icon and the label *Role*.
- **Provisioning.** An `acl` entry names its principal with `user:`, `group:`
  or `role:` (or `principal:` to resolve any kind):

  ```yaml
  nodes:
    - path: /content/site/draft
      acl:
        - role: site-approver
          privileges: jcr:read, jcr:write
          effect: allow
  ```

- **GraphQL.** `setAccessControl` takes the bare principal name. The read side
  (`accessControl`, `effectiveAccessControl`) reports `isGroup` and `isRole`
  on each principal; `searchPrincipals` returns roles with `isRole: true`.
- **Archives.** Exported entries carry the principal name; on import it is
  resolved in the target installation, so a role must exist there as well.

## Upgrading

Role profiles created by earlier versions carry no `identifier`. The IdP
bundle completes them at start (the folder name becomes the identifier and
`isRole` is set), so existing roles can be used in entries without any manual
step. A name that collides with an existing user or group is reported in the
log; rename one of them before granting.
