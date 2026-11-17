# BPM (Camunda) Workspace Configuration (`bpm.yml`)

This document describes the per-workspace configuration of the embedded
Camunda Platform 7 process engine, managed by
`org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProviderConfiguration`.

## Overview

Each workspace runs its own standalone `ProcessEngine`. Its configuration lives
in a single YAML file:

```
<workspace>/etc/bpm/bpm.yml
```

The file is **auto-generated on first boot** if it does not already exist, and
is then read on every subsequent boot to build the engine
(`createProcessEngine()`). The engine data (H2 database, history tables, etc.)
lives under:

```
<workspace>/var/bpm/
```

## Properties

| Key        | Required | Default                                   | Description |
|------------|----------|-------------------------------------------|-------------|
| `jdbcURL`  | yes      | `jdbc:h2:<workspace>/var/bpm/data`        | JDBC URL for the engine database. Supports `${...}` variable substitution: `${workspace.name}`, `${workspace.home}`, `${repository.home}`, OSGi framework properties, and system properties. |
| `username` | no       | `sa` (the engine default, matching the embedded H2 database) | Database user. Supports `${...}` variable substitution. |
| `password` | no       | empty (the engine default, matching the embedded H2 database) | Database password. Supports `${...}` variable substitution. |
| `driverClassName` | no | resolved from the JDBC URL | JDBC driver class to load before the engine connects. Required for drivers that live in their own OSGi bundle (e.g. `org.postgresql.Driver`); the Camunda bundle declares a dynamic import for `org.postgresql.*`, so installing the PostgreSQL driver bundle is sufficient. A missing driver bundle fails fast at engine bootstrap with a clear message. |
| `history`  | no       | `audit`                                   | Camunda history level. See below. |
| `historyTimeToLive` | no | none (history is kept forever) | Default time to live of finished process instances, as a number of days (`180`) or an ISO-8601 period (`P180D`). See "History cleanup". |
| `historyCleanupBatchWindowStartTime` | no | none (cleanup never runs) | Start of the daily window in which the cleanup job runs, `HH:mm` (e.g. `01:00`). Must be set together with the end time. |
| `historyCleanupBatchWindowEndTime` | no | none | End of that window, `HH:mm` (e.g. `05:00`). |

A freshly generated `bpm.yml` looks like:

```yaml
jdbcURL: jdbc:h2:/path/to/workspace/var/bpm/data
history: audit
```

For a shared database (e.g. a clustered deployment — see
`documents/clustering.md`):

```yaml
jdbcURL: jdbc:postgresql://db:5432/bpm_${workspace.name}
username: bpm
password: secret
driverClassName: org.postgresql.Driver
history: audit
```

## History level (`history`)

The `history` property controls how much process execution data Camunda records
into the `ACT_HI_*` tables. Supported values, in increasing order of detail:

| Value      | What is recorded |
|------------|------------------|
| `none`     | Nothing. History recording is disabled. |
| `activity` | Process and activity instances. |
| `audit`    | The above **+ user tasks and the current value of process variables** (`HistoricVariableInstance`). **This is the default.** |
| `full`     | The above **+ the full change history of variables** (`HistoricDetail`), form properties, and complete user-operation logging. |
| `auto`     | The engine adopts the level already recorded in the database (see below). On a brand-new database this resolves to the engine default (`audit`). |

> The deprecated `variable` level is intentionally not supported.

If the property is omitted or blank, the engine default (`audit`) is used, so
workspaces created before this property existed keep behaving exactly as before.
An unrecognised value is **rejected at startup** (rather than silently falling
back) so that a typo can never quietly disable history recording.

### Choosing a level

- `audit` (default) is sufficient for most monitoring/auditing needs: you can
  see which process and task instances ran, who started each process, who each
  task was assigned to when it was completed, and the final values of
  variables. Through the GraphQL API (Webtop) only the assignee can complete a
  task, so that assignee is the user who completed it; EIP routes and scripts
  complete tasks without this check.
- Choose `full` only when you need the **time series of variable changes** or
  detailed user-operation logs — who claimed, reassigned or delegated a task
  along the way. It produces substantially more data: a process that keeps
  large JSON values in variables, or rewrites variables in a loop, records
  every single write. Configure "History cleanup" below before choosing it.
- The user-operation log only covers operations performed with an
  authenticated user. The GraphQL API always passes the caller; an EIP route
  does so only when it sets `authenticatedUserId`, and a script only through
  the `ProcessAPI` helpers (not through the raw engine).

## ⚠️ Changing the history level on an existing workspace

This is the single most important operational caveat.

On the **first** engine bootstrap, Camunda persists the resolved history level
into the database:

```
ACT_GE_PROPERTY (NAME_ = 'historyLevel')   -- 0=none, 1=activity, 2=audit, 3=full
```

On **every subsequent** boot, Camunda compares the configured level against the
stored one. If they differ it **fails fast** and the engine will not start:

```
historyLevel mismatch: configuration says <X> and database says <Y>
```

There is no flag to suppress this check — it is unconditional. Camunda forbids
the change because historical data already recorded at the old granularity would
otherwise become inconsistent with newer data.

Consequences:

- **New workspaces** — any level is safe; it is written on first boot.
- **Existing workspaces, same level** — safe (the common case; no change).
- **Existing workspaces, different level** — the engine will **refuse to start**
  until you perform the migration below.

### Migration procedure (deliberate level change)

Perform per workspace, accepting that history recorded before the change keeps
its old granularity while history after the change uses the new one:

1. Stop the workspace / process engine. **In a cluster, stop it on every
   node** before going on: an engine reads the level once, at start, so a node
   left running keeps recording at the old level into the shared database
   while the others use the new one. Stopping the workspace from the Workspace
   Manager acts on the whole cluster and completes once every alive node has
   stopped (see `documents/clustering.md`). A rolling restart is not possible
   for this change; plan for downtime.
2. Update the stored level in the engine database. For the default H2 database:

   ```sql
   -- 0=none, 1=activity, 2=audit, 3=full
   UPDATE ACT_GE_PROPERTY SET VALUE_ = '3' WHERE NAME_ = 'historyLevel';
   ```

3. Set the matching value in `bpm.yml` (e.g. `history: full`).
4. Restart the workspace (on every node).

History recorded before the change is not back-filled. After raising the level
to `full`, instances that ran earlier have no user-operation log and no
variable change history, so anything that reads them must treat the missing
records as "not recorded" rather than as an error.

Alternatively, set `history: auto` in `bpm.yml` to make the engine always adopt
whatever level is already stored in the database. This never causes a mismatch,
but it also means the level can only be raised by editing the database as above.

## History cleanup

By default history is never removed, so the `ACT_HI_*` tables only grow — fast
at `history: full`. Camunda's history cleanup removes the history of finished
process instances once their time to live has passed. It is off until both of
the following are configured:

```yaml
history: full
historyTimeToLive: P180D                    # or 180 (days)
historyCleanupBatchWindowStartTime: "01:00"
historyCleanupBatchWindowEndTime: "05:00"
```

- `historyTimeToLive` is the default for process definitions that do not set
  `camunda:historyTimeToLive` themselves in their BPMN; a value in the BPMN
  wins. Without any time to live an instance is never due for removal.
- The batch window is when the cleanup job runs, daily, in the server's time
  zone. Without it the job is never scheduled. Quote the times so that they are
  always read as strings.
- These settings are read at engine start; restart the workspace (in a
  cluster, on every node) after changing them. Unlike the history level they
  are not stored in the database, so there is no mismatch check.

Caveats, as Camunda documents them — not verified against this build:

- The engine-wide default is applied to a process definition when it is
  deployed. Definitions deployed earlier keep having no time to live until
  they are redeployed.
- An instance's removal time is fixed when it ends. Instances that finished
  before a time to live applied to them are not picked up by the cleanup.

## Related code

- `org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProviderConfiguration`
  — loads `bpm.yml` and builds the engine.
- `org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProvider`
  — manages the engine lifecycle for a workspace.
