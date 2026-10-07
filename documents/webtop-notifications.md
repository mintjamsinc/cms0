# Webtop notifications

The Webtop tells the user that something happened — a message for them, a
task — whether or not the app it concerns is open: a **toast** appears at
the lower right of the desktop for a few seconds, and the **notification
center**, opened from the bell or the clock in the menubar, keeps what the
toasts showed. A click on either opens the app at what the notice is about.

The notification center is for this session only: a reload starts it
empty. Whatever is durable about a notice is in the repository already —
the message with its unread and mention marks, the task — and the app that
owns it shows it on its own; the center is what one glances at to catch up.

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
| `key` | notices with the same key supersede one another in the notification center: the newer replaces the older, read or not |

A **message reference** is `{ id, params, fallback }`: an i18n message id,
the values of its placeholders, and the text to show when no bundle has the
id. It is resolved when shown, in the language of whoever is looking, in
the scope of `app` first (that app's own `i18n/<locale>.json`) and the
global bundles then — the same lookup that localizes an app's title. A
notice published once to several users therefore reads right for each. A
text given as a plain string is shown as it is.

The shell takes what is well-formed and drops the rest: a payload without a
title is no notice; an `app` that is not installed leaves the notice with
nothing to open; `icon` must be a `bi-*` class. Notices come from other
users and from other apps, so nothing in them is trusted beyond its shape.

## Raising a notice

**From a script**, as a topic message on `webtop/notifications` to the
users it is for ([`topic-messages.md`](topic-messages.md)); the Chat app's
`ChatNotices` is the model:

```groovy
EventAdminAPI.publish('webtop/notifications', [
    app: 'chat',
    title: [id: 'app.chat.notify.direct.title', params: [author: name], fallback: name],
    body: text,
    icon: 'bi-chat-dots',
    options: [channelId: channelId, messageId: messageId],
    context: 'chat:channel:' + channelId,
    key: 'chat:channel:' + channelId,
], [recipients: [peer]]);
```

`recipients` is the usual audience: the notice reaches those users only,
on every desktop they have open, on every node of the cluster. `path` works
too (whoever can read the node), at a session per subscriber and message,
so it suits rare notices. A notice is a notification, not a record: a user
who is not connected when it is published never sees it. Publish one only
for what the app also keeps in some durable form.

Any signed-in user may publish to any topic, `webtop/notifications`
included, through the `publish` mutation. A notice can therefore come from
another user rather than from an app's server code; what it can do is
show two lines of text and open an app with launch options — no more than
that user could do by sending a message. Whoever acts on a notice beyond
that reads the state from the repository.

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
shell is `{ type: 'notify', ...notice }`.

**From the shell** itself: the task subscriptions in
`realtime/event-hub.ts` put a notice in the store when a task is assigned
to the user. Anything the shell learns on the event stream can be shown the
same way, through `notificationActions` of
`stores/notification-store.ts`.

## Not while the reader is looking

An app tells the shell what its window is showing — a conversation, a room,
a file — with `setAppContext(key)` from `lib/notifications.ts`, whenever
that changes (an empty key clears it). A notice whose `context` equals the
key of the **active** window, while the browser has focus, is not raised at
all: the reader is looking at it, and the app shows the change itself. The
Chat app announces `chat:channel:<id>` / `chat:file:<id>`, which is what
`ChatNotices` puts on its notices.

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
| `webtop/src/webtop/lib/notifications.ts` | the notice's shape and the checks on it; `notify`, `setAppContext` for apps |
| `webtop/src/webtop/stores/notification-store.ts` | the list the center shows, newest first, 100 at most |
| `webtop/src/webtop/index.ts`, `index.gsp` | the bell, the center and the toasts (*Notices* in both); the `webtop/notifications` watch |
| `docker/seed/.../classes/webtop/chat/ChatNotices.groovy` | what the Chat app publishes |
| `/etc/i18n/*.json` (`webtop.notifications.*`) | the shell's texts; an app's notice texts live in the app's own bundle |
