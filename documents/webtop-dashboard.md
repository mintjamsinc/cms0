# Webtop Dashboard

The Dashboard gathers what needs attention in one place: the reader's own
work, and — for those who may see it — how the system is running. It owns
no data. **Apps add sections and cards to it**, and what a card shows is
made **on the server** and **pushed** to every Dashboard that is open; the
Dashboard queries nothing but the declarations and draws what arrives.

- A **section** gathers cards, from any app: an app adds its cards to a
  section another app declared, or declares its own ("Commerce", "Office").
- A **card** is declared in the app's `app.yml` and names a **provider**, a
  class of the workspace that makes the card's **snapshot**.
- The server decides *when* a provider runs — an event, a change in the
  repository, another card having changed, an interval — and never more
  often than once in two seconds per card. A card nobody looks at is not
  made at all.
- A card is **shared** (one snapshot for everyone who may see it, made once
  per cluster) or **user** (one per user, made only for the users whose
  Dashboard is open).
- Everything arrives whichever node of a cluster the browser is connected
  to.

Notifications are a separate thing: a notice is a record kept for a user
([`webtop-notifications.md`](webtop-notifications.md)); a card is a view of
the present, kept nowhere.

## Declaring sections and cards

In `app.yml`, under `dashboard`:

```yaml
dashboard:
  sections:
    jp.example.office:
      title: Office
      icon: bi-building
      order: 400
  cards:
    rooms:
      section: jp.example.office
      title: Meeting rooms
      icon: bi-door-open
      order: 10
      size: 1
      scope: shared
      roles: [office-staff]
      provider: office.dashboard.Rooms
      refresh:
        topics: [jp/example/office/reservation/*]
        paths: [/var/lib/office/reservations]
        interval: 300
      open:
        options: { view: rooms }
```

A section:

| Key | Meaning |
|---|---|
| *(name)* | the identifier of the section, shared by every app that adds cards to it |
| `title` | shown when no bundle has `app.<relPath>.dashboard.section.<identifier>.title` |
| `icon` | a Bootstrap icon class |
| `order` | sections are shown in this order; when several apps declare one section, the lowest order names it |

A card (its identifier is `<app relPath>/<name>`, e.g. `office/rooms`):

| Key | Meaning |
|---|---|
| `section` | required; the section the card is shown in — a section of any app |
| `provider` | required; the class that makes the snapshot (below) |
| `title`, `icon` | the heading; the title is looked up as `app.<relPath>.dashboard.<name>.title` first |
| `order` | the place of the card within its section |
| `size` | `1` (default), `2` (twice as wide) or `full` |
| `scope` | `shared` (default) or `user` |
| `roles` | who sees the card: the holders of one of these roles, and administrators. An empty list: administrators only. Without the key: whoever is offered the app (the app's own `roles`) |
| `summary` | `true`: the card is the summary of its section (below) |
| `detail` | `true`: the card can be maximized (below) |
| `collect` | `node`: every node of a cluster contributes a part (below) |
| `runAs` | a service user the provider runs as; without it the provider runs as the system |
| `options` | handed to the provider as it is |
| `refresh.topics` | EventAdmin topics (`a/b/C`, or `a/b/*`) whose events make the card anew |
| `refresh.paths` | repository paths; a change at or below one makes the card anew |
| `refresh.cards` | other cards (`<app relPath>/<name>`); the card is made anew when one of them changed, and is handed their snapshots |
| `refresh.interval` | seconds; the card is made anew this often even without an event (at least 5) |
| `refresh.users`, `refresh.groups` | for a `user` card: the event properties that name the users, and the groups or roles, an event concerns. The card is then made anew for those among the readers only; without them, for every reader |
| `open` | where the heading leads: `app` (the folder name of an app, the card's own when absent) and `options`, its launch options |

Scope and roles are **per card**: one section holds shared and user cards,
cards for everyone and cards for administrators. A section is shown to
whoever sees at least one of its cards.

Unlike a Content Browser extension, nothing turns a card on: it is there
when the app is installed and the reader may see it.

### Names

So that the work of several makers does not collide:

- **Event topics** start with the maker's domain, reversed:
  `jp/example/office/reservation/CREATED`. `org/mintjams/…` and
  `org/camunda/…` belong to the platform.
- **Section identifiers** are either one of the platform's (`today`,
  `operations`; no dot), which any app may add cards to, or start with the
  maker's reversed domain (`jp.example.office`).
- A card is named within its app, so it needs no prefix.

## The provider

A class of the workspace (`/usr/local/classes`, shipped in a package's
`deploy/`), with static methods:

```groovy
package office.dashboard;

class Rooms {

    /** A shared card: one snapshot for everyone. */
    static Map snapshot(scriptAPI, Map card) {
        int free = ...;
        return [
            status: free > 0 ? 'ok' : 'attention',
            blocks: [
                [type: 'metric', value: free, denom: 12,
                    unit: [id: 'app.office.rooms.free', fallback: 'rooms free']],
            ],
        ];
    }

}
```

| Method | For |
|---|---|
| `snapshot(scriptAPI, card)` | a shared card |
| `snapshot(scriptAPI, card, userId)` | a user card |
| `detail(scriptAPI, card)` / `detail(scriptAPI, card, userId)` | the detail of a card declared `detail: true` |
| `part(scriptAPI, card)` | this node's part of a card declared `collect: node` |

`scriptAPI` is the `ScriptAPI` of the account the provider runs as. `card`
holds:

| Key | Meaning |
|---|---|
| `id`, `name`, `app`, `section`, `scope`, `options` | the declaration |
| `detail` | whether the detail is being made |
| `workspace`, `node` | where the provider runs |
| `userId`, `timeZone`, `groups`, `roles` | for a user card: the reader, the reader's time zone (IANA; may be null), and the names of the groups and roles the reader holds |
| `snapshots` | the current snapshots of the cards named in `refresh.cards`, by card identifier |
| `parts` | for `collect: node`: what each node returned from `part`, by node id |
| `graphql` | `graphql.query(String query, Map variables)` runs a query against the workspace's GraphQL schema as the provider's account and returns its `data`; it throws when the answer carries errors |

A provider must be quick: one that runs longer than ten seconds is
interrupted, and the card keeps its last snapshot. One that throws is
logged once, and again keeps the last snapshot.

## The snapshot

A map, sent as JSON; at most 32 KB (48 KB for a detail, 16 KB for a part):

```json
{
  "status": "ok",
  "facts": { "free": 3 },
  "blocks": [ ... ]
}
```

| Key | Meaning |
|---|---|
| `status` | `ok`, `attention` or `issue` |
| `facts` | anything; not drawn. The place for the numbers another card's provider reads from `card.snapshots` |
| `blocks` | what is drawn, top to bottom. A block with `col: 2` goes to the second column of a wide card |

**One snapshot is shown to everyone**, so it holds nothing that depends on
the reader's language or clock:

- A **text** is a string, or a message reference `{ id, params, fallback }`
  the Dashboard resolves in the reader's language, in the bundles of the
  card's app first. A parameter is a text again, `{ age: <ISO time> }`
  (shown as the distance from now: "5m", "3h") or `{ duration: <ms> }`.
  The fallback may hold the same `{placeholders}`.
- A **number** is given as a number and formatted by the Dashboard
  (`format: 'compact'` for 1.2k).
- A **time** is an ISO 8601 string.

What depends on the reader — "due today" in the reader's time zone, say —
belongs in a `user` card.

The blocks:

| `type` | Shows | Fields |
|---|---|---|
| `kpis` | a strip of numbers | `items`: `label`, `hint`, value fields, `open` |
| `metric` | the one number the card is about | value fields, `unit` |
| `rows` | labels with values | `items`: `label`, `icon`, `hint`, value fields |
| `list` | rows that lead somewhere | `ranked`, `empty`, `items`: `title`, `sub`, `icon`, `tags`, `badges` (`text`, `tone`, `hint`), `value`, `tone`, `due` (ISO; shown as overdue, today, or how far ahead), `hint`, `open`, `static` |
| `matrix` | a state for every pair | `caption`, `columns`, `rows` (`label`, `cells`: `ok`, `warn`, `down`, `self`, `unknown`) |
| `note` | a sentence | `text` |

Every block may have a `title`. The **value fields** are `value` (a
number), or `text`; `denom` (shown as "/n"); `format`; `tone` (`danger`,
`warning`, `success`, `neutral`); and `badge: true` to show the value as a
badge. `open` is `{ app, options }` as in the declaration; it is a link
only when the reader is offered that app.

A block of a type the Dashboard does not know is left out and the rest of
the card is drawn, so a newer provider does no harm to an older Dashboard.

## The summary of a section

A card declared `summary: true` leads its section at full width — "My
day", "Are operations healthy?". It is a card like any other: its provider
decides what it shows, from its own queries or, with `refresh.cards`, from
the snapshots of the section's other cards (their `facts`), so that
nothing is counted twice.

A shared summary reads shared snapshots only, and whatever it shows is
seen by everyone its own `roles` admit: a provider must not put into a
summary what the cards it reads would not show to the same readers.

A section with a summary can be **folded** to it: the button in the
section's heading switches between the summary alone and every card. The
choice is remembered per user and section. When several cards of a section
are declared summaries, the first in order is.

## Maximizing a card

A card declared `detail: true` has a button that shows it alone, filling
the window, with the snapshot of its `detail` method — the same blocks,
with more in them: where the card shows the total over the cluster, the
detail shows each node. The detail is made **only while someone has the
card maximized**, by the same rules as the card itself.

## What every node knows on its own

Some numbers live in the memory of each node — the statistics of the
routes a node runs. A card declared `collect: node` has each node call
`part` at the card's interval (30 seconds without one) and gathers the
answers: `snapshot` and `detail` receive them as `card.parts`, by node id,
and add them up. The route cards of the Dashboard are made this way.

## How the snapshots travel

The Dashboard opens one subscription:

```graphql
subscription {
  dashboardCards(path: "/usr/share/webtop/apps", timeZone: "Asia/Tokyo") {
    card detail version at data
  }
}
```

`path` is the apps folder. The current snapshot of every card the
subscriber may see comes first, then each snapshot as it changes; a
connection that was lost starts over the same way. A maximized card adds
`dashboardCardDetail(path, card, timeZone)` for as long as it is shown.
What the apps declare is listed by `apps { dashboard { sections cards } }`,
with the cards the caller may see.

On the server, a worker per workspace keeps the snapshots
(`org.mintjams.rt.cms.internal.dashboard.DashboardWorker`):

- **Shared cards**: in a cluster one node makes them and puts them into a
  replicated table (`dashboard/cards`); every node sends them to its own
  subscribers when the table changes
  (`org/mintjams/rt/cms/dashboard/CARD_UPDATED`). Which node that is stands
  in a table too, as a claim the node renews and another node takes over
  when it lapses. Each node tells in a table which cards its subscribers
  look at, so that only those are made.
- **User cards**: made on the node that holds the user's stream, kept in
  its memory, and dropped when the user closes the Dashboard.
- Nothing is written to the repository. When every node has stopped the
  snapshots are gone, and they are made again for whoever opens the
  Dashboard next.
- A standalone deployment runs the same code with maps in place of the
  tables.

The process engine's task, process instance and incident events reach the
worker on every node as the view topics
`org/mintjams/rt/cms/bpm/<Type>/<ACTION>`
([`bpm-eventadmin.md`](bpm-eventadmin.md)); a card about tasks names
`org/mintjams/rt/cms/bpm/Task/*` in `refresh.topics`.

## The Dashboard's own cards

Declared in the Dashboard's `app.yml`, with providers in
`/usr/local/classes/webtop/dashboard`:

| Section | Card | Scope | Made anew on |
|---|---|---|---|
| `today` | `my-day` (summary) | user | task events that concern the reader; 5 min |
| | `my-tasks` | user | task events that concern the reader; 5 min |
| | `team-queue` | shared | task events; 5 min |
| | `bottlenecks` | shared | task events; 5 min |
| `operations` (administrators) | `health` (summary) | shared | the cards below changing |
| | `definitions` | shared | 1 min |
| | `running` | shared | process instance and incident events; 1 min |
| | `incidents` | shared | incident events; 1 min |
| | `routes`, `throughput`, `connections` | shared, by node, with detail | 30 s |
| | `workspaces` | shared | 20 s |
| | `cluster` | shared | 15 s |

Incident events exist at history level `full` only; at other levels the
interval picks incidents up.
