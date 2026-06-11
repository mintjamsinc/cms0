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
  see which process and task instances ran, who completed them, and the final
  values of variables.
- Choose `full` only when you need the **time series of variable changes** or
  detailed user-operation logs. It produces substantially more data.

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

1. Stop the workspace / process engine.
2. Update the stored level in the engine database. For the default H2 database:

   ```sql
   -- 0=none, 1=activity, 2=audit, 3=full
   UPDATE ACT_GE_PROPERTY SET VALUE_ = '3' WHERE NAME_ = 'historyLevel';
   ```

3. Set the matching value in `bpm.yml` (e.g. `history: full`).
4. Restart the workspace.

Alternatively, set `history: auto` in `bpm.yml` to make the engine always adopt
whatever level is already stored in the database. This never causes a mismatch,
but it also means the level can only be raised by editing the database as above.

## Related code

- `org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProviderConfiguration`
  — loads `bpm.yml` and builds the engine.
- `org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProvider`
  — manages the engine lifecycle for a workspace.
