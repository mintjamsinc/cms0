# Webtop notifications

The Webtop tells the user that something happened — a message for them, a
task — whether or not the app it concerns is open: a **toast** appears at
the lower right of the desktop for a few seconds, and the **notification
center**, opened from the bell or the clock in the menubar, keeps what the
toasts showed. A click on either opens the app at what the notice is about.

A notice is **kept for the user**, on the server, in the user's home
(`/home/users/<user>/notices`): the notification center shows the same
list on every desktop the user opens, a notice that arrived while the user
was away is there when they come back, and one read or dismissed on one
desktop is read or dismissed on all. The list holds the latest hundred.
Being in the home, it goes with the user: deleting the user removes it, and
a user made anew under the same id starts with none.
Whatever a notice is about is in the repository besides — the message with
its unread and mention marks, the task — and the app that owns it shows it
on its own; the center is what one glances at to catch up, and what one
clicks to get there.

Any app can raise a notice. Nothing in the shell is written per app: a
notice names the app a click opens, carries its texts as messages the shell
resolves in the reader's language, and tells what it is about, so that the
shell can hold it back while the reader is looking at that.

## A notice

A notice is a JSON object:

| Field | Meaning |
|---|---|
| `title` | required; text, or a message reference (below) |
| `body` | text or a message reference; the toast shows up to three lines of it |
| `app` | the folder name of the app a click opens (`chat`, `tasks`); also the i18n scope the texts are resolved in |
| `options` | launch options for the app, as for `open-app` (`openAppWithOptions`): a singleton already running receives them as `app-reopen` |
| `icon` | a Bootstrap icon class (`bi-chat-dots`), shown when the app has no icon |
| `context` | what the notice is about, e.g. `chat:channel:<id>`; see *Not while the reader is looking* |
| `key` | notices with the same key supersede one another: the newer replaces the older, read or not |

A **message reference** is `{ id, params, fallback }`: an i18n message id,
the values of its placeholders, and the text to show when no bundle has the
id. It is resolved when shown, in the language of whoever is looking, in
the scope of `app` first (that app's own `i18n/<locale>.json`) and the
global bundles then — the same lookup that localizes an app's title. A
notice published once to several users therefore reads right for each. A
text given as a plain string is shown as it is. A placeholder value may
itself be a message reference, resolved the same way before it is put in:
the "task assigned" notice passes the task's name as
`{ id: 'bpm.process.<processKey>.task.<taskDefinitionKey>.name', params: { name: <name> }, fallback: <name> }`.

The server and the shell take what is well-formed and drop the rest: a
payload without a title is no notice; an `app` that is not installed leaves
the notice with nothing to open; `icon` must be a `bi-*` class; a text is
cut at 500 characters, the whole at 16 KB. Notices come from other users
and from other apps, so nothing in them is trusted beyond its shape.

## Raising a notice

**From a script**, through `webtop.notices.Notices.publish` with the
script's `ScriptAPI` (in a GraphQL resolver, `context.getAttribute('ScriptAPI')`),
the notice and the user ids it is for; the Chat app's `ChatNotices` is the
model:

```groovy
webtop.notices.Notices.publish(scriptAPI, [
    app: 'chat',
    title: [id: 'app.chat.notify.direct.title', params: [author: name], fallback: name],
    body: text,
    icon: 'bi-chat-dots',
    options: [channelId: channelId, messageId: messageId],
    context: 'chat:channel:' + channelId,
    key: 'chat:channel:' + channelId,
], [peer]);
```

The notice is written for each recipient, as the notice service user, and
each recipient's desktops — every one they have open, on every node of the
cluster — show it at once; a recipient who is not connected finds it when
they next sign in. A recipient that is no user is skipped; at most a
hundred recipients at a time. `Notices.withdraw(scriptAPI, key, recipients)`
takes a notice back — the "task assigned" notice goes when the task is done.

A notice is kept, but it is not the record of what it tells: the message,
the task, the file is what the app keeps and shows, and a notice that
could not be written (`publish` throws) loses nothing of that. Publish one
only for what the app also keeps in some durable form.

**From an app**, for something that happened in the browser only (a long
job finished, an export is ready), with `notify` from
`webtop/src/webtop/lib/notifications.ts`:

```ts
import { notify } from '../../lib/notifications.js';

notify({
  title: { id: 'app.myapp.export.done', fallback: 'Export finished' },
  body: fileName,
  options: { path: exportedPath },
});
```

The app is the sender's unless `app` names another. The message to the
shell is `{ type: 'notify', ...notice }`. Such a notice is for this browser
only and goes with the session: it is about something this browser did.

**On the topic** `webtop/notifications`
([`topic-messages.md`](topic-messages.md)), as before: a topic message to
named recipients is shown on the desktops connected at that moment and
kept nowhere — the right thing for what is of the moment only. Any
signed-in user may publish to it through the `publish` mutation; what such
a notice can do is show two lines of text and open an app with launch
options — no more than that user could do by sending a message. A notice
kept on the server comes from a script only.

**From the server, for a task**: the route
`etc/eip/routes/webtop/notices.xml` keeps a notice for the assignee of
every user task the process engine assigns
([`bpm-eventadmin.md`](bpm-eventadmin.md)) — *New task: "…" was assigned
to you*, opening the Tasks app at the task — and takes it back when the
task is completed or deleted (`webtop.notices.TaskNotices`).

## What the shell does

At start the shell asks for the user's list (`webtopNotices`) and watches
the user's folder (`webtopNoticeWatchPath`) with the platform's
`nodeChanged`; whenever something under it changes — a notice added, read
or dismissed, on this desktop or another — it reads the list again. A
notice that is new and unread is shown as a toast; one that arrives
already read was read elsewhere and only takes its place in the center. A
click, *Mark all as read*, *Dismiss* and *Clear all* change the list on
the server (`webtopNoticeRead`, `webtopNoticesReadAll`,
`webtopNoticeRemove`, `webtopNoticesClear`), and every desktop follows.

The resolvers act on the caller's own list only, as the notice service
user. The folder is created with the first notice, as the user — only the
user may write the home — through `ScriptAPI.createUserContext`, which also
makes sure of the user's home in the workspace; it is granted to the notice
service group then, which writes it from then on (`provisioning/notices.yml`).
The user owns the folder as any of the home, and the shell and the server
check the shape of what they read, so a record the user alters by hand is
dropped, not shown.

## Not while the reader is looking

An app tells the shell what its window is showing — a conversation, a room,
a file — with `setAppContext(key)` from `lib/notifications.ts`, whenever
that changes (an empty key clears it). A notice whose `context` equals the
key of the **active** window, while the browser has focus, is not shown:
the reader is looking at it, and the app shows the change itself. A notice
kept on the server is marked read then, for every desktop; one of this
browser only is dropped. The Chat app announces `chat:channel:<id>` /
`chat:file:<id>`, which is what `ChatNotices` puts on its notices.

The shell knows only keys; what they mean is between the app's client and
its server code. Keep them in one place on each side.

## What the Chat app publishes

A direct message is a notice to the other user, titled with the author's
name. A message that names a user as `@user` is a notice to that user,
titled *A mentioned you in B* (a channel) or *A mentioned you about F* (the
conversation of a file) — only when that user is a participant, as for the
mention mark, and only when a user is newly named by an edit. Nothing is
published for an ordinary message in a channel: the channel's unread mark
is enough there. The body is the first 140 characters of the message.

A click opens the Chat app at the message (`channelId` or `fileId` with
`messageId`). The notice's key is the conversation, so the notification
center shows one entry per conversation, the latest.

## Where things are

| | |
|---|---|
| `/home/users/<user>/notices/<id>` | one file per notice, the notice as JSON; `notice:read`, `notice:key`, `notice:app` as properties |
| `docker/seed/.../classes/webtop/notices/Notices.groovy` | the records: `publish`, `withdraw`, and what the resolvers do for the caller |
| `docker/seed/.../classes/webtop/notices/TaskNotices.groovy` | the "task assigned" notice, from the engine's task events |
| `docker/seed/.../etc/graphql/webtop/notices` | `webtopNotices`, `webtopNoticeWatchPath`, `webtopNoticeRead`, `webtopNoticesReadAll`, `webtopNoticeRemove`, `webtopNoticesClear` |
| `docker/seed/.../etc/eip/routes/webtop/notices.xml` | the route that keeps a task's notice |
| `docker/seed/.../provisioning/notices.yml` | the service user and its group |
| `webtop/src/webtop/lib/notifications.ts` | the notice's shape and the checks on it; `notify`, `setAppContext` for apps |
| `webtop/src/webtop/services/notice-service-graphql.ts` | the shell's calls to the resolvers (`api.notices`) |
| `webtop/src/webtop/stores/notification-store.ts` | the list the center shows, newest first, 100 at most |
| `webtop/src/webtop/index.ts`, `index.gsp` | the bell, the center and the toasts (*Notices* in both); the folder and the `webtop/notifications` watches |
| `docker/seed/.../classes/webtop/chat/ChatNotices.groovy` | what the Chat app publishes |
| `/etc/i18n/*.json` (`webtop.notifications.*`, `webtop.notification.task.*`) | the shell's texts; an app's notice texts live in the app's own bundle |
