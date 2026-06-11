# EIP (Apache Camel) EventAdmin Bridge

This document describes how the embedded Apache Camel integration engine
publishes its runtime lifecycle to the OSGi **EventAdmin** service, so that
other bundles, EIP/Camel routes, GraphQL subscriptions and server-side scripts
can react to engine activity **without any route authoring**.

It is the Camel counterpart of the BPM (Camunda) bridge documented in
[`bpm-eventadmin.md`](bpm-eventadmin.md), and deliberately shares the same topic
and property conventions so consumers see one consistent event vocabulary across
both engines.

## Overview

Every workspace integration engine registers a single engine-wide notifier,
`org.mintjams.rt.cms.internal.eip.EventAdminEventNotifier`, on its
`WorkspaceCamelContext`. The notifier translates Camel's internal
`org.apache.camel.spi.CamelEvent` stream into EventAdmin events.

Apache Camel fires lifecycle events internally and exposes them through the
`ManagementStrategy`'s **EventNotifier** SPI — *not* the Route DSL. Registering
one notifier on the context therefore yields **cross-cutting, engine-wide**
coverage that no route has to opt into: routes added or reloaded later are
covered automatically. No `from(...)`, interceptor or `to("eventadmin:...")`
needs to be declared in any route.

```java
// WorkspaceCamelContext
getManagementStrategy().addEventNotifier(
        new EventAdminEventNotifier(workspaceName));
```

## Delivery semantics

Delivery uses EventAdmin's asynchronous `postEvent`, so it never blocks the
routes and never participates in any exchange transaction. Any delivery failure
is caught and logged so it can never disturb the engine.

## Topics

Topics follow the existing CMS convention — the fully-qualified name of a stable
Camel public-API type with `.` replaced by `/`, suffixed with an upper-case
action:

```
<fully/qualified/Type>/<ACTION>
```

| Domain | Topic root | Actions |
|--------|-----------|---------|
| CamelContext | `org/apache/camel/CamelContext` | `INITIALIZING`, `INITIALIZED`, `STARTING`, `STARTED`, `STARTUP_FAILURE`, `STOPPING`, `STOPPED`, `STOP_FAILURE`, `SUSPENDING`, `SUSPENDED`, `RESUMING`, `RESUMED`, `RESUME_FAILURE`, `RELOADING`, `RELOADED`, `RELOAD_FAILURE`, `ROUTES_STARTING`, `ROUTES_STARTED`, `ROUTES_STOPPING`, `ROUTES_STOPPED` |
| Route | `org/apache/camel/Route` | `ADDED`, `REMOVED`, `STARTING`, `STARTED`, `STOPPING`, `STOPPED`, `RELOADED`, `RESTARTING`, `RESTARTING_FAILURE` |
| Exchange | `org/apache/camel/Exchange` | `CREATED`, `COMPLETED`, `FAILED`, `FAILURE_HANDLING`, `FAILURE_HANDLED`, `REDELIVERY`, `SENDING`, `SENT`, `ASYNC_PROCESSING_STARTED`, `STEP_STARTED`, `STEP_COMPLETED`, `STEP_FAILED` |
| Service | `org/apache/camel/Service` | `STARTUP_FAILURE`, `STOP_FAILURE` |

Step events are `ExchangeEvent`s in Camel (they ride the exchange), so they are
published under the `Exchange` root with `STEP_*` actions and additionally carry
`stepId`.

## Event properties

Every event carries `workspace` (the originating workspace name) and `timestamp`
(epoch millis). Optional attributes are **omitted when null** rather than set to
`null`. Beyond those two:

| Topic root | Additional properties |
|------------|-----------------------|
| `CamelContext` | `camelContextName`, `camelContextManagementName`; on `*_FAILURE`: `causeType`, `causeMessage` |
| `Route` | `routeId`, `routeDescription`, `endpointUri`, `camelContextName`; on `RELOADED`: `index`, `total`; on `RESTARTING_FAILURE`: `attempt`, `exhausted`, `causeType`, `causeMessage` |
| `Exchange` | `exchangeId`, `routeId`, `fromEndpoint`, `camelContextName`, `businessKey`; on `SENDING`/`SENT`: `endpointUri`; on `SENT`: `timeTaken`; on `REDELIVERY`: `attempt`; on `FAILED`/`FAILURE_HANDLING`/`FAILURE_HANDLED`: `causeType`, `causeMessage`, `failureRouteId`, `failureEndpoint` |
| `Exchange` (`STEP_*`) | as Exchange, plus `stepId`; on `STEP_FAILED`: `causeType`, `causeMessage` |
| `Service` | `serviceType`, `causeType`, `causeMessage` |

`businessKey` is read from the `mi:history.businessKey` exchange header — the
same correlation header used by the exchange-history recorder — so an exchange
can be followed across both facilities.

## What is published by default

`CamelContext`, `Route` and `Service` events are low-frequency and **enabled by
default**.

`Exchange` and `Step` events fire **per message** and can be extremely high
volume, so they are **disabled by default**. The translation is complete for
every event type — only the default delivery gate differs. Enable them
explicitly when needed:

```java
new EventAdminEventNotifier(workspaceName)
        .setPublishExchangeEvents(true)
        .setPublishStepEvents(true);
```

Finer-grained control (for example, publish only `ExchangeFailed`) is available
through the inherited `setIgnore*Event` setters of Camel's
`EventNotifierSupport`.

## Consuming the events

Register an OSGi `EventHandler` (or use the existing CMS scripting/EIP event
facilities) with an `event.topics` filter. Wildcards are supported by
EventAdmin, e.g.:

```
org/apache/camel/CamelContext/*
org/apache/camel/Route/*
org/apache/camel/Exchange/FAILED
```

Because the topic/property conventions match the BPM bridge, a consumer can
subscribe to both engines uniformly, e.g. by filtering on the shared
`workspace` property.

## Related code

- `org.mintjams.rt.cms.internal.eip.EventAdminEventNotifier`
  — translates the `CamelEvent` stream into EventAdmin events.
- `org.mintjams.rt.cms.internal.eip.WorkspaceCamelContext`
  — registers the notifier (and the exchange-history notifier) on the engine.
- `org.mintjams.rt.cms.internal.eip.ExchangeHistoryEventNotifier`
  — sibling notifier that persists exchange execution history to JCR.
- `org.mintjams.rt.cms.internal.eip.EventAdminComponent`
  — the `eventadmin:` Camel component for producing/consuming EventAdmin events
  from within routes.
