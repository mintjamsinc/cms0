# BPM (Camunda) EventAdmin Bridge

This document describes how the embedded Camunda Platform 7 process engine
publishes its runtime lifecycle to the OSGi **EventAdmin** service, so that
other bundles, EIP/Camel routes, GraphQL subscriptions and server-side scripts
can react to process activity **without any BPMN authoring**.

## Overview

Every workspace engine is augmented at build time with a process engine plugin,
`org.mintjams.rt.cms.internal.bpm.event.EventAdminProcessEnginePlugin`, wired in
`WorkspaceProcessEngineProviderConfiguration.createProcessEngine()`. The plugin
installs two complementary, engine-wide hooks — no `extensionElements`,
listeners or service tasks need to be declared in any process model:

| Hook | Mechanism | Covers | History-level dependent? |
|------|-----------|--------|--------------------------|
| `EventAdminBpmnParseListener` | A pre-parse `BpmnParseListener` that attaches execution/task listeners to **every** process, flow node, sequence flow and user task. | Process start/end, every activity start/end, every sequence flow take, full user task lifecycle. | **No** — fires even with `history: none`. |
| `EventAdminHistoryEventHandler` | A `HistoryEventHandler`, composed with the engine's own DB history handler via `CompositeDbHistoryEventHandler`. | Variable instance create/update/delete, incident create/resolve/delete. | **Yes** — these are produced by the engine only at/above the configured level. |

The two hooks have **non-overlapping responsibilities**, so a given occurrence is
published exactly once. Database history persistence is unaffected — the custom
history handler runs *alongside* the standard `DbHistoryEventHandler`.

## Delivery semantics: published on transaction commit

All hooks run **inside** the engine command and its surrounding transaction.
Publishing directly from them would announce work that may still roll back (a
later command failing, an optimistic-locking retry, etc.).

To give consumers a predictable *"only what actually happened"* contract, every
notification is deferred via a Camunda `TransactionListener` and posted on
`TransactionState.COMMITTED`. **Events for a rolled-back transaction are never
published.** Delivery uses EventAdmin's asynchronous `postEvent`, so it never
blocks the engine and never joins the engine transaction.

## Topics

Topics follow the existing CMS convention — the fully-qualified name of a stable
Camunda public-API type with `.` replaced by `/`, suffixed with an upper-case
action:

```
<fully/qualified/Type>/<ACTION>
```

| Domain | Topic root | Actions |
|--------|-----------|---------|
| Process instance | `org/camunda/bpm/engine/runtime/ProcessInstance` | `STARTED`, `ENDED` |
| Activity (any flow node) | `org/camunda/bpm/engine/runtime/ActivityInstance` | `STARTED`, `ENDED` |
| Sequence flow | `org/camunda/bpm/model/bpmn/instance/SequenceFlow` | `TAKEN` |
| User task | `org/camunda/bpm/engine/task/Task` | `CREATED`, `ASSIGNED`, `COMPLETED`, `UPDATED`, `DELETED` |
| Process variable | `org/camunda/bpm/engine/runtime/VariableInstance` | `CREATED`, `UPDATED`, `MIGRATED`, `DELETED` |
| Incident | `org/camunda/bpm/engine/runtime/Incident` | `CREATED`, `RESOLVED`, `UPDATED`, `MIGRATED`, `DELETED` |

## Event properties

Every event carries `workspace` (the originating workspace name). Optional
attributes are **omitted when null** rather than set to `null`. Beyond
`workspace`:

| Topic root | Additional properties |
|------------|-----------------------|
| `ProcessInstance` | `processDefinitionId`, `processInstanceId`, `executionId`, `businessKey`, `tenantId` |
| `ActivityInstance` | `processDefinitionId`, `processInstanceId`, `executionId`, `activityInstanceId`, `activityId`, `activityName`, `businessKey`, `tenantId` |
| `SequenceFlow` | `processDefinitionId`, `processInstanceId`, `executionId`, `transitionId`, `activityId`, `businessKey`, `tenantId` |
| `Task` | `taskId`, `taskName`, `taskDefinitionKey`, `assignee`, `eventName`, `processInstanceId`, `processDefinitionId`, `executionId`, `tenantId`, `createTime` |
| `VariableInstance` | `variableInstanceId`, `variableName`, `serializerName`, `scopeActivityInstanceId`, `processInstanceId`, `processDefinitionId`, `executionId` |
| `Incident` | `incidentId`, `incidentType`, `incidentMessage`, `activityId`, `causeIncidentId`, `processInstanceId`, `processDefinitionId`, `executionId`, `tenantId` |

## Interaction with the `history` level

Lifecycle and flow events (process, activity, sequence flow, user task) are
emitted via parse-listener-injected execution/task listeners and are therefore
**independent of the history level** — they are published even with
`history: none`.

Variable and incident events ride the engine's history event stream, so they
follow the configured level (see `bpm-configuration.md`):

- `none` — variable/incident events are **not** produced.
- `audit` (default) — variable create/update/delete and incident events are
  produced.
- `full` — additionally records the variable-update *detail* stream; the bridge
  deliberately ignores the extra `UPDATE_DETAIL` record so a single value change
  still yields at most one `VariableInstance/UPDATED` event.

## Consuming the events

Register an OSGi `EventHandler` (or use the existing CMS scripting/EIP event
facilities) with an `event.topics` filter. Wildcards are supported by
EventAdmin, e.g.:

```
org/camunda/bpm/engine/runtime/ProcessInstance/*
org/camunda/bpm/engine/task/Task/*
org/camunda/bpm/engine/runtime/Incident/*
```

## Related code

- `org.mintjams.rt.cms.internal.bpm.event.EventAdminProcessEnginePlugin`
  — installs both hooks into the engine configuration.
- `org.mintjams.rt.cms.internal.bpm.event.EventAdminBpmnParseListener`
  — injects process/activity/sequence-flow/user-task listeners.
- `org.mintjams.rt.cms.internal.bpm.event.EventAdminHistoryEventHandler`
  — bridges variable and incident history events.
- `org.mintjams.rt.cms.internal.bpm.event.BpmEventDispatcher`
  — topic/property conventions and commit-time dispatch.
- `org.mintjams.rt.cms.internal.bpm.WorkspaceProcessEngineProviderConfiguration`
  — registers the plugin while building the engine.
