# EIP (Apache Camel) Exchange History

This document describes how the embedded Apache Camel integration engine
records per-execution history, and how individual routes control that
recording.

## Overview

Every workspace integration engine registers a single engine-wide notifier,
`org.mintjams.rt.cms.internal.eip.ExchangeHistoryEventNotifier`, on its
`WorkspaceCamelContext`. The notifier subscribes to Camel's
`ExchangeCompletedEvent` / `ExchangeFailedEvent` and writes one JSON file per
finished exchange:

```
/var/eip/history/{yyyy}/{MM}/{dd}/{HH}/{routeId}/{exchangeId}.json
```

- The date/hour hierarchy is UTC. `{routeId}` is the exchange's **origin
  route** (`Exchange#getFromRouteId()`); exchanges with no resolvable route
  land in `_unknown`.
- Each file is a single JSON object containing status, timing, redelivery
  counters, exception details, body metadata, captured headers, and the
  step-by-step execution path (from Camel's message history).
- Records are written asynchronously by a dedicated writer thread; JCR I/O
  never blocks the routes. Searchable properties (`mi:exchangeId`,
  `mi:routeId`, `mi:status`, `mi:elapsed`, `mi:createdAt`, `mi:businessKey`)
  ride on each file node.

The **EIP Console** webtop app (admin only) lists and live-tails these
records.

## Per-route recording control: the `mi:history` route property

Recording is on for every route by default. A route opts out — entirely or
for successful executions only — by declaring the `mi:history` route
property at the top of its definition:

```xml
<route id="my-sweep">
    <routeProperty key="mi:history" value="failure"/>
    <from uri="timer:my-sweep?period=15000"/>
    ...
</route>
```

| Value | Behavior |
| --- | --- |
| `all` (default, when absent) | Record every execution. |
| `failure` | Record only executions that failed, or completed with a handled exception (`onException` with `handled=true` still counts as a failure). |
| `none` | Never record. |

Notes:

- The **origin route's** setting governs the whole exchange. Work done in
  `direct:` sub-routes runs on the caller's exchange and is therefore
  covered by the caller's setting; endpoints that start a new exchange
  (`seda:`, timers, webhooks) are governed by their own route's setting.
- An unrecognized value is treated as `all` and logged once per route.
- `failure` is the recommended setting for short-period timer routes
  (sweeps, drains, poll lanes): their successful runs dominate history
  volume while carrying no information, but failures remain visible in the
  EIP Console.
- Routes with `failure`/`none` appear rarely or never in the EIP Console
  history view. The route control actions (start/stop/suspend/resume) are
  unaffected.

## Related per-exchange headers

Independent of the route property, three exchange headers control the
**content** of a record (not whether it is written):

| Header | Meaning |
| --- | --- |
| `mi:history.header.includes` | Comma-separated patterns of headers to capture (exact, `prefix*`, `*suffix`; `prefix~`/`~suffix` strip the matched part from the recorded key). Empty = capture nothing. |
| `mi:history.header.excludes` | Patterns of headers to withhold even when included. |
| `mi:history.businessKey` | Free-form correlation key, recorded and indexed as `mi:businessKey`. |

## Retention

There is no automatic pruning of `/var/eip/history` yet. Until a retention
mechanism exists, per-route `failure`/`none` settings are the only way to
bound growth at the source.
