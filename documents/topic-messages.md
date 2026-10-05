# Topic messages (publish/subscribe)

The platform carries **topic messages**: a general-purpose publish/subscribe
channel that an application uses to tell its clients, live, that something
happened. A message is published to a **topic**, a slash-separated name such
as `game/reversi/rooms/4f2a`, with a JSON payload, and is delivered over the
Webtop's existing event stream (graphql-sse over HTTPS) to the subscribers
of the same workspace whose topic pattern matches and who are in its
audience. In a cluster it reaches the subscribers of every node.

No application has to build a transport of its own, and no platform code is
written per application: an application's GraphQL mutations (hot-deployed
from `/etc/graphql`) apply its rules, keep its state in the repository and
publish a message; its clients subscribe to the topic.

## What a message is, and is not

A message is a **notification, not a record**. Nothing is stored: a
subscriber that is not connected when the message is published never sees
it, and a slow subscriber may miss some (the stream drops on lag, 256
messages buffered). An application therefore keeps its state in the
repository and treats a message as "read again" or as a small,
self-contained event it can check against what it already knows.

The payload is **data from its publisher**. `userId` names who published the
message; a receiver that acts on a payload checks that it comes from whom it
expects, or reads the state from the repository instead.

## Topics and audience

A topic is one or more names separated by `/`, each of letters, digits,
`_`, `.` and `-`, at most 255 characters in all. An application prefixes its
topics with its own name (`game/reversi/...`, `chat/...`).

Who receives a message is the **audience** named when it is published:

| Published with | Delivered to |
|---|---|
| `recipients` (user ids) | those users only, however they subscribe |
| `path` (a node of the workspace) | whoever can read that node, decided by the repository's access control in a session of the subscriber's own (as `nodeChanged` does) |
| neither | every subscriber of the workspace, anonymous included |

`recipients` costs nothing per subscriber; `path` logs a short-lived session
in per message and subscriber, so it suits messages that are rare or that
have few subscribers.

A subscriber subscribes to one topic, to a topic followed by `/*` (that
topic and everything under it), or to `*`. A pattern never widens the
audience: a message for two users reaches those two, whatever anyone else
subscribes to.

## Publishing

From a GraphQL resolver, an EIP route, a process script or any other
workspace script, through the `EventAdminAPI` binding:

```groovy
EventAdminAPI.publish('game/reversi/rooms/' + roomId,
    [type: 'move', ply: 12, move: 'd3'],
    [recipients: [black, white]]);
```

Options: `recipients` (a collection of user ids), `path` (a node of the
workspace), `cluster` (`false` to post on this node only; the default is the
whole cluster). The publisher recorded on the message is the user of the
script's session: in a GraphQL resolver, the caller. The method returns the
message id.

From a client, through the platform mutation:

```graphql
mutation {
  publish(topic: "game/reversi/rooms/4f2a", payload: { type: "typing" },
          recipients: ["alice"])
}
```

Signed-in users only. The payload is any JSON of at most 16 KB when written
out; at most 100 recipients. Since any user may publish to any topic,
receivers rely on `userId`, not on the topic, to decide what a message is
worth.

## Subscribing

The platform subscription:

```graphql
subscription {
  topicMessage(topic: "game/reversi/rooms/*") {
    topic payload userId messageId timestamp
  }
}
```

In the Webtop, `EventHub.watchTopic(topic, handler)`
(`webtop/src/webtop/realtime/event-hub.ts`) opens it on the Webtop's one
event stream:

```ts
const stop = instance.api.eventHub.watchTopic('game/reversi/rooms/*', (event) => {
  // event.payload is unknown: check it before using it.
});
```

## How it is carried

Every message is posted as one OSGi event on the topic
`org/mintjams/rt/cms/pubsub/MESSAGE`
(`org.mintjams.rt.cms.internal.pubsub.TopicMessages`); the message's own
topic, payload, publisher, audience, id and time are its properties. The
subscription is an `OsgiEventPublisher` on that topic that filters by
workspace, topic pattern and audience.

In a cluster the publishing node also writes the event to the **cluster
signal bus** (`jcr_cluster_signals`, see
[`clustering.md`](clustering.md#cluster-signal-bus-phase-3)); every other
node re-emits it as a local event within its poll interval (2 seconds), so
its own subscribers receive it unchanged. The signal bus carries JSON scalars
only, which is why the payload and the recipients travel as strings. The
event stream itself is node-local: a client's subscriptions live on the node
its stream is connected to, so the stream endpoint (`/bin/graphql.cgi/<ws>/stream`)
needs the sticky routing that `clustering.md` already recommends.

## When to use what

| Need | Use |
|---|---|
| Tell clients that a node they can read changed | `nodeChanged` (nothing to publish; the repository write is the event) |
| Tell particular users something, or carry a small event, without a repository write per notification | a topic message with `recipients` |
| Tell whoever can read a folder something that is not a node change | a topic message with `path` |
| Keep a record of what happened | write it to the repository; publish a message so clients read it |

The Reversi app ([`webtop-reversi.md`](webtop-reversi.md)) is the model: the
room is the record, each move is a repository write by a mutation, and a
message tells the two players to show it.
