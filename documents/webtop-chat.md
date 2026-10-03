# Webtop Chat

The **Chat** app holds conversations in **channels**. A channel is private —
read by the users and groups invited to it — or public, open to everyone. New
messages appear as they are posted, and a channel with messages not yet read is
marked in the sidebar.

Every file and folder of the repository has a **conversation of its own** as
well, read by whoever can read the file. It is shown in the Inspector, next to
the file's details, and in the Chat app.

A channel and the conversation of a file are the same thing to use: the same
messages, attachments and links, shown by the same element. They differ only in
whether there is a file they are about, and in who may read them.

Conversations are kept **per workspace**: each workspace has its own channels,
in its own repository.

## Channels

Anyone can create a channel; the creator is its first administrator.

| | Private | Public |
|---|---|---|
| Who reads and posts | the users and groups added to it | everyone signed in |
| Sidebar | appears for every participant | appears for those who add it (*Browse channels* → *Add to sidebar*) |
| Leaving | *Leave channel* gives up one's own invitation | *Remove from sidebar* |

An **administrator** renames the channel, adds and removes participants,
appoints other administrators and archives the channel. A channel always keeps
at least one administrator: the last one cannot leave or be removed.

A user who takes part through a group cannot leave on their own; the group is
taken off the channel by an administrator.

**Direct messages** are a channel of two users (`dm`), shown under the other
user's name. *New direct message* in the toolbar opens them, creating them the
first time; the same two users always get the same one. They have no
administrator, no title and no participants to manage, and are not archived.
Three or more people talk in a private channel.

An **archived** channel leaves every sidebar and takes no more messages. Its
messages stay. It is found again under *Browse channels* with *Include archived
channels* — a private one by its participants only — where it opens read-only
and an administrator can restore it.

## Conversations of files

The conversation of a file is opened from the **Conversation** tab of the
Inspector, wherever the Inspector is shown: the Content Browser, the Memo, the
Text Editor, the PDF Viewer. The tab is offered for a single file or folder and
is marked when the conversation has messages not yet read.

- **Whoever can read the file can read and post to its conversation.** There
  are no participants to manage: the caller's own session resolves the file's
  identifier on every call, and that is the whole check.
- The conversation starts with its first message.
- It is kept by the file's **identifier**, so it stays with the file when the
  file is moved or renamed. A copy has another identifier and starts with an
  empty conversation.
- **Deleting the file deletes its conversation.**

In the Chat app, the **Files** section of the sidebar lists the conversations
the user follows, the most recently active first. Posting to a conversation
follows it; the bookmark in its heading adds it to the sidebar or takes it out.
The heading shows the file with **Open** and **Show in folder**.

Another app opens a conversation in the Chat app by launching it with
`{ fileId }` or `{ channelId }` as its options.

A host that does not want the tab passes `viewOptions.conversation: false` to
the Inspector. While a host shows its own pane in the Inspector
(`viewOptions.pane`), the tab is not offered.

## A conversation inside a memo

The Memo app's **Conversation** block (`/` → *Conversation*) shows a
conversation in the page itself. It is live in the editor: messages appear as
they are posted, and the box under them posts.

- By default the block shows **the conversation of the memo it is in**. A memo
  gets its conversation with its first save; until then the block says so.
- The heading's menu switches the block to a **channel**; a repository file
  dropped on the heading switches it to **that file's conversation**. A file
  dropped on the messages is linked in a message, as everywhere else.
- **The memo stores a reference only**, never the messages. The memo's own
  conversation is stored without an identifier (`<div data-chat-view>`), so a
  copy of the memo shows the copy's own conversation, which starts empty. An
  identifier is stored only for another file (`data-file`) or a channel
  (`data-channel`). A memo kept as HTML carries the same element.
- Typing a message is not typing into the memo: nothing in the block changes
  the memo or marks it as modified.
- **Printing** the memo prints the messages shown at that moment, at their
  full length, without the box to post into.

## Messages

A message is Markdown: `**bold**`, `*italic*`, `~~strike~~`, `` `code` ``,
fenced code blocks, `> quotes`, `- lists` and links. Raw HTML in a message is
shown as text.

**Enter** posts, **Shift+Enter** starts a new line.

**@mentions.** Typing `@` in the message box offers users to pick from; a
message names a user as `@user`. A name that is no user stays plain text. The
mentioned user is told (the Chat app shows a notice and the mention is
marked), the conversation of a file one was mentioned in appears in the
mentioned user's sidebar, and the mention is shown marked in the message. A
mention gives no access: a user mentioned in a conversation they cannot read
sees nothing of it.

**Search.** The search box in the toolbar finds messages whose text contains
every word typed, newest first, across the channels the user takes part in and
the conversations of the files the user can read. Opening a hit brings the
user to that message in its conversation, with *Jump to the latest* to return
to the end.

The author can edit or delete a message. An edited message is marked as such;
a deleted one keeps its place in the conversation, without its text and
without what it attached.

## Attachments and links

A message can carry files in two ways, up to ten of each.

| | Attachment | Link |
|---|---|---|
| What it is | a copy kept with the message | a reference to the file or folder where it lies |
| Content | as it was when the message was posted | always the current content |
| Who can read it | whoever can read the conversation | whoever can read the original |
| Clicking it | opens the copy | opens the original, in its place |

- A file **from the computer** — dropped on the conversation, pasted, or picked
  with the paperclip — is always attached.
- A file **from the repository**, dragged in from the Content Browser, is
  linked. The author can switch it to a copy before posting. A folder can only
  be linked.

**A link gives nobody access.** It is kept by the file's identifier and
resolved, each time the message is shown, in the session of whoever reads it.
A reader who cannot read the file sees a card saying so, without even the
file's name. Because it is kept by identifier, a link survives the file being
moved or renamed.

A link card offers **Open** (the file in its editor) and **Show in folder**
(a Content Browser on the folder the file lies in, with the file selected).

An attachment is never replaced in place. To correct one, the author edits the
message, takes the attachment off and attaches the new file.

The author must be able to read whatever is attached or linked: the copy is
read in the author's own session and written by the service user.

## Who can do what

Nobody but the chat service user can write under `/var/lib/chat`. Posting,
editing, deleting and managing participants all go through the Chat GraphQL
mutations, each of which checks in the caller's own session what the caller may
do and then writes as the service user. The messages of a channel are therefore
visible to its participants in the Content Browser, and found by the search,
but cannot be edited, moved or deleted there.

The conversation of a file is granted to nobody at all. It is read and written
through the same mutations and queries, by whoever can read the file; nothing
of it shows in the Content Browser.

**The participants of a channel are the principals granted `jcr:read` on its
folder.** There is no other membership list: adding a participant adds an
access control entry, and what a user can read in the repository is exactly
what the user can read in the app. The entries are managed in the app (the
service user writes them); the Inspector cannot change them, because that would
take `jcr:modifyAccessControl`, with which a user could grant themselves more.

A workspace administrator holds `jcr:all` and can edit the area directly.

## Where things live

Everything is in the workspace the Webtop runs in.

| Path | What |
|---|---|
| `/var/lib/chat` | Closed to everyone (`jcr:read` denied); owned by `chat-service-group`. |
| `/var/lib/chat/channels/<channel>/` | One folder per channel. Its access control entries are the participants: the invited users and groups, or `everyone` for a public channel. |
| `/var/lib/chat/channels/<channel>/.channel` | The settings, as `chat:*` properties: `chat:title`, `chat:description`, `chat:channelKind` (`public` / `private`), `chat:admins`, `chat:archived`. Written when the channel is managed, never when a message is posted. |
| `/var/lib/chat/channels/<channel>/messages/<yyyy>/<MM>/<dd>/<id>.md` | One file per message (`text/markdown`), in the folder of the day (UTC) it was posted. `chat:author` is the user who posted it — `jcr:createdBy` is always the service user — with `chat:postedAt`, `chat:editedAt`, `chat:deleted` and `chat:kind`. `chat:links` holds the identifiers of what the message links, `chat:mentions` the users it names. |
| `/var/lib/chat/channels/<channel>/messages/<yyyy>/<MM>/<dd>/<id>.files/` | The attachments of the message `<id>`, in the same day folder as the message. |
| `/var/lib/chat/files/<identifier>/messages/…` | The conversation of the file with that identifier, laid out like a channel's. Nobody is granted anything on it. |
| `/var/lib/chat/channels/dm-<hash>/` | The direct messages of two users; the hash is of the two user ids, so the pair has one channel. |
| `/var/lib/chat/mentions/<user>/<yyyy>/<MM>/<dd>/<id>` | A mark per mention of the user, readable by that user only: the message's id, with `chat:channelId` or `chat:fileId` and `chat:author`. |
| `/var/lib/chat/signals/<identifier>/<yyyy>/<MM>/<dd>/<id>` | An empty folder for every change to that conversation, readable by every signed-in user (see below). |
| `/home/users/<user>/chat/read/<key>` | How far the user has read a conversation (`chat:readAt`). |
| `/home/users/<user>/chat/following/<key>` | The conversations in the user's sidebar: the public channels the user added (`channel-<id>`) and the conversations of files the user follows (`file-<identifier>`). |
| `/home/users/<user>/chat/uploads/<draft>/` | Files uploaded for a message not yet posted. Posting copies them next to the message and removes the folder. |
| `/usr/local/classes/webtop/chat/` | The logic (`ChatApi`, `ChatChannels`, `ChatMessages`, `ChatHome`, `ChatStore`). |
| `/etc/graphql/webtop/chat/` | The GraphQL schema (`chatChannels`, `chatMessages`, `chatPostMessage` …) and its resolvers. |
| `/usr/share/webtop/apps/chat/` | The app. The conversation itself is the shared element `components/wt-chat-thread`. `attachment.groovy` there serves the attachments of the conversations of files. |
| `/etc/eip/routes/webtop/chat.xml` | Removes the conversation of a file that is deleted. |
| `/etc/bpm/{processes,scripts,forms}/webtop/chat/` | The maintenance process, its script (`maintain.groovy`) and forms; messages under `/etc/i18n/chat-forms.*.json`. |

`chat-service-user`, its group, the `chat:` namespace and the folders under
`/var/lib/chat` are provisioned by `provisioning/chat.yml`. A user's folder
under `mentions` is created, and granted to the user, with the first mention.

The user's own folder is the one place written in the user's session.

### Posting only adds

A post creates one node and updates nothing else: there is no "last message"
value kept per channel that every post would have to rewrite. Adding a child
does not touch the parent, so posts to the same channel never conflict, however
busy it is. The one exception is the first post of a day, which creates the
day's folder; when two posts do so at once, the one that loses posts into the
folder it then finds.

The id of a message starts with the time it was posted, so messages sort by
name, and the time of a channel's last message is read from the name of its
newest file.

Messages are listed by walking the day folders, never through the search index,
which lags behind on the other nodes of a cluster.

## Live updates and unread marks

There is no subscription of Chat's own. The app watches the folder of each
channel in the sidebar with the platform's `nodeChanged`, on the event stream
the Webtop already holds. `nodeChanged` delivers an event only to those who can
read the node, so it follows the participants without anything further.

- The open channel reads its messages again when something under it changes.
- A new message in another channel marks that channel unread.
- Anything else under a channel — a rename, an archive, a change of
  participants — reads the list of channels again.

At most 50 conversations are watched at a time, the most recently active
first.

### The conversation of a file

Nobody can read the conversation of a file in the repository, so there is
nothing of it to watch. Every post, edit and delete therefore leaves an empty
folder under `/var/lib/chat/signals/<identifier>`, which every signed-in user
can read, and that is what a client watches.

A signal tells that the conversation of that identifier changed, and when. It
does not tell what was said, by whom, or where the file is; a user who cannot
read the file cannot read the conversation either, whatever signals arrive.

The attachments of such a conversation cannot be fetched from the repository
for the same reason. They are served by `apps/chat/attachment.groovy`, which
checks in the caller's session that the caller can read the file and then
reads the attachment as the service user. Images are shown inline; anything
else is always a download.

### Mentions

A message that names a user as `@user` leaves that user a mark under
`/var/lib/chat/mentions/<user>`, which the user's Chat app watches with
`nodeChanged`. The mark names the conversation, not the message's text, and
only the mentioned user can read it. Editing a message marks only the users it
newly names; one's own name leaves no mark.

The sidebar's *Files* section takes in the conversations of files from the
user's latest hundred mention marks, so that a conversation one was pulled
into is at hand without following it.

### Search

The channels are searched in the caller's own session, with the repository's
full-text search (`jcr:contains` on the message files), so access control
applies on its own. The conversations of files are searched as the service
user and only the hits whose file the caller can read are kept. A page is at
most fifty hits from each, merged newest first; the search index of a cluster
node lags a little behind the repository, so the newest messages may take a
moment to be found.

### When a file is deleted

The repository posts a `javax/jcr/Node/REMOVED` event for every node removed —
one per node, also when a whole folder goes — carrying its identifier. The
route `webtop-chat-file-removed` removes the conversation kept by that
identifier, with its signals.

The route's queue is short and what does not fit is dropped, so removing a
large tree never holds the repository up. A conversation left behind that way
belongs to a file nobody can open any more. What a user's home still holds for
a deleted file is dropped when it is next looked at.

A user who loses access to a file loses its conversation with it, and the
conversation leaves the user's sidebar. It is not forgotten: when the user can
read the file again, it is back in the sidebar.

A channel is **unread** when its last message is later than the user's read
position. Nothing is written for the other participants when somebody posts,
and no count is kept.

An invitation raises no event the invited user could be watching for, so the
list is read again when the Chat window comes to the front.

What changes outside the conversations raises no event either: a linked file
that is moved or renamed keeps showing its old place until the conversation is
read again. *Refresh* in the toolbar (F5) reads the channels and the open
conversation again.

## Removing old messages

Nothing in the chat is removed on its own. When it is time, an administrator
runs **Chat Maintenance** from the Tasks app (*Start a process*), in the
workspace whose conversations are to be cleaned. The start form asks:

- which conversations to remove old messages from: channels, direct messages,
  the conversations of files;
- how many days of messages to keep;
- whether to remove the conversations of files that no longer exist.

Messages are removed **by the day**: a day folder older than what is kept
goes as a whole, with the attachments in it, and the signals and mention marks
of the same days go with it. The days are UTC days, as the folders are:
keeping 1 day keeps the UTC day of today. Nothing in a day is looked at, so the run is as
quick as removing folders. Each folder is committed on its own; a run that is
stopped has still removed what it removed. A *Chat Maintenance Result* task
shows the counts afterwards.

The process runs its script as the chat service user, which owns the chat
area; the administrator role of whoever started it is checked inside the
script, as for the search index rebuild.

Uploads for messages that were never posted stay in the users' homes
(`chat/uploads`); the maintenance does not reach into homes.

## Deployment notes

- `scripts/assemble-seed` lays the server side (the schema, the classes, the
  route, the maintenance process with its script and forms, and
  `provisioning/chat.yml`) into every workspace, next to the app.
- The Webtop build copies `attachment.groovy` next to the app
  (`webtop/rollup.config.js`).
- A file is uploaded in the user's own session into the user's home, because an
  upload can only be completed there; the resolver then copies it. Uploads for
  a message that was never posted stay in `chat/uploads` of the home.
- *Show in folder* uses the Content Browser's `select` launch option: the path
  of an item to select, whose folder is opened.
