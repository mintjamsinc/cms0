# Webtop Mail

The **Mail** app reads the IMAP accounts of a user side by side: one inbox
across every account, or one account at a time, with search, unread / flagged
views and a trash of its own. The server downloads the mail in the background,
so the app only shows what is already in the repository and opens instantly
even for mailboxes that hold decades of mail.

Mail is written in the app as well: new messages, replies, replies to all and
forwards, sent through the account's outgoing server.

## What gets downloaded

Every account has a **download period**, set when the account is added (30 days
by default, up to 3650). The first synchronization downloads the mail that
arrived in that period and nothing older, so adding an account with twenty
years of mail on the server is as quick as adding a new one.

Older mail is downloaded **on request**: *Download older mail* at the end of the
list goes back a further 30 days to 5 years from the oldest mail downloaded so
far. After that, every pass downloads what is new.

Two folders are synchronized: **INBOX** and the **Sent** folder, found by its
`\Sent` attribute (RFC 6154) or, on servers without it, by the usual names
(*Sent*, *Sent Items*, *送信済み* …).

### How messages are tracked

Messages are tracked by **UID** within the folder's **UIDVALIDITY**, never by
Message-ID or by date:

- Message-ID is missing on a good share of real mail; the former client dropped
  such messages.
- Date and Received headers are often missing, malformed or wrong (a spam
  message dated in the future used to push the synchronization mark past every
  later message).

The download period is matched against the server's arrival date
(INTERNALDATE), which the server always sets. The dates shown are chosen with
fallbacks, and a value that cannot be real (before 1980 or in the future) is
skipped:

| Date | Taken from |
|---|---|
| Received | the server's arrival date → the latest Received header → the Date header → the time of download |
| Sent | the Date header → the earliest Received header → the arrival date |

If the server renumbers a folder (UIDVALIDITY changes), that folder is
downloaded again.

## Flags and deleting

- **Read / flagged** changes made in the Webtop are shown at once, queued on the
  message and written to the server by the next pass (requested immediately).
  Changes made in other mail clients are read back: with **CONDSTORE**
  (RFC 7162) only what changed, otherwise the recent messages on every pass and
  all of them once a day.
- **Nothing is ever deleted on the server.** Folders are opened read-only for
  reading and are never expunged. *Move to trash* is the Webtop's own trash;
  the server copy stays. A message flagged `\Deleted` by another client shows in
  the trash; one removed from the server is hidden, noticed by the daily scan.
- Removing an account removes its settings and the downloaded mail from the
  Webtop only.

## Colors, tags and locks

Colors and tags mark messages in the Webtop (they are not written to the mail
server). The left pane lists the colors and the tags used so far; each narrows
the selected box and combines with search.

**Locking** guards a message against deletion by mistake. The user locks and
unlocks messages at any time (the lock button of the message, or of the
selected messages). A locked message cannot be moved to the trash, stays listed
when it is removed from the server, and is kept when the server renumbers the
folder. Removing an account warns when it has locked mail.

The lock is a convenience, not an archive: tamper-proof retention belongs to
the archiving of the mail server.

## Writing and sending

*New message* in the toolbar, or *Reply*, *Reply all* and *Forward* on an open
message, opens the compose pane in place of the reader.

- **Text or HTML.** A new message is text. A reply or a forward starts in the
  format of the original: HTML when it has HTML, text otherwise. The format can
  be switched at any time; switching to text drops the formatting. An HTML
  message is sent with a text alternative made from it.
- **Replies** go to the Reply-To address, or to the sender; *Reply all* adds the
  other recipients, leaving out one's own addresses. A reply to one's own sent
  message goes to its recipients again. In-Reply-To and References are set, so
  other mail clients thread the reply, and the original is marked answered
  (written to the server by the next pass).
- **Forwards** carry the original's attachments. They are read from the stored
  original when the message is sent, not copied.
- **Attachments** are uploaded into the draft (button, drop of files from the
  PC onto the pane, or drop of files from a Content Browser window), up to
  25 MB in all. A file dropped from the Content Browser is read from the
  workspace and uploaded into the draft like a file from the PC; folders are
  skipped. The cloud button attaches a file of the user's Google Drive or
  OneDrive instead: it is copied into the draft on the server (a Google
  document as its Office export), or, with *Insert a link to a file*, only a
  link to it goes into the body. The first use asks to link the account and
  allow the *files* bundle (see `linked-accounts.md`).
- **Drafts** are saved as one types, in the Webtop only (the server's Drafts
  folder is not used). Closing the pane keeps the draft; *Drafts* in the left
  pane lists them.
- **Recipients** are suggested from the saved addresses and from the addresses
  mail was sent to before. In a message you read, the button after an address
  saves it; saved addresses and one's own have no button. The × of a suggestion
  removes that address from both lists at once, without asking.

The copy of what was sent is listed under *Sent*. With **Also put sent mail
into the server's Sent folder** on (the account dialog), it is appended to
the server's Sent folder as well, so other mail clients see it; the copy is
stored under that folder with the UID the server gave it, so the next
synchronization finds it in place instead of downloading it again. Off, the
copy stays in the Webtop only. The setting is on for a new account, except
for Gmail, which files what SMTP sends by itself. Either way a message is
listed once: when a message the Webtop sent arrives in the server's Sent
folder through the synchronization (Gmail filed it, or the copy could not be
appended), the Webtop's own copy is removed, matched by Message-ID; a locked
copy stays. Removing the account removes the copies kept in the Webtop, like
the downloaded mail.

There is no signature: write it into the message.

## Attachments of a message you read

An attachment is listed under the message's header. Clicking it opens or
downloads it (`attachment.groovy`, see *Where things live*). It can also be
put into the workspace the Webtop is running in, in two ways:

- **Drag it into a Content Browser window**, onto the file list or onto a
  folder row. The Content Browser reads the file from the Mail app's
  attachment URL and uploads it the way it uploads a file from the PC, so an
  existing file of the same name asks before it is replaced, and a versioned
  file is checked out and in as usual.
- **The folder button on the attachment** opens a folder picker of the
  workspace. Folders you may not create anything in are shown muted with a
  lock and cannot be picked, but they open, so a writable folder below them
  can be reached. *New folder* creates one in the picked folder. The folder
  picked last is offered first the next time. An existing file of the same
  name asks before it is replaced.

Either way the copy is made by the browser: the attachment is read from the
system workspace, where the mail lives, and written to the current workspace
with the caller's own session, so the repository's access control decides
where it can be saved. Nothing changes in the mail itself.

## Messages that are broken

Mail with broken headers or bodies no longer stops the synchronization. Parsing
is done by `org.mintjams.tools.mail` (2.2.0 or later), which reads whatever it
can:

- Encoded words are decoded in one pass (malformed ones used to hang the
  download), including ISO-2022-JP split across words; raw Shift_JIS / EUC-JP /
  UTF-8 / ISO-2022-JP headers are recovered; unknown or wrong charset labels
  fall back to detection; Windows extensions (①, ㈱) are kept.
- Unknown transfer encodings, broken base64 or quoted-printable, a missing
  multipart boundary or header lines without a colon yield what is readable
  instead of an exception.
- Files without `Content-Disposition` (common for PDF), inline images and
  forwarded messages are attachments; forwarded messages no longer replace the
  body.

Every message is stored exactly as the server sent it (`.eml`), so a later
improvement of the parser applies to mail already downloaded. A message that
cannot be read completely is stored anyway and marked; the reader offers the
original for download. A message whose download fails is retried on the next
two passes and then skipped, without holding up the messages after it.

## Where things live

All of it is in the **system** workspace; the app calls the system workspace
whichever workspace the Webtop runs in.

| Path | What |
|---|---|
| `/home/users/<user>/mail/accounts/<account>.json` | Account settings. Passwords are properties of this file, encrypted with the installation key (`secret-key.yml`, see [clustering](clustering.md)); they are never returned by the API. The schedule of the background synchronization is in its `mail:*` properties too: `mail:accountId`, `mail:enabled`, `mail:intervalMinutes`, `mail:requested` ("run now") and `mail:nextRun`. |
| `/home/users/<user>/mail/state/<account>.json` | Synchronization state: per folder UIDVALIDITY, highest UID, oldest date, CONDSTORE mark; the last run and error. |
| `/home/users/<user>/mail/messages/<account>/<folder>/<UIDVALIDITY>/<UID/1000>/<UID>.eml` | The messages. What the list shows is kept in `mail:*` properties; the search index reads the full text from the `.eml`. |
| `/home/users/<user>/mail/messages/<account>/local/sent/<yyyy>/<MM>/<id>.eml` | Copies of the mail sent from the Webtop (`mail:local`); never synchronized. |
| `/home/users/<user>/mail/drafts/<draft>.json` | Drafts; the files uploaded for a draft are in the folder `drafts/<draft>/` next to it. |
| `/home/users/<user>/mail/labels.json` | The tags used so far, for suggestions and the filter list. |
| `/home/users/<user>/mail/recipients.json` | Addresses mail was sent to, for suggestions (the latest 500). |
| `/home/users/<user>/mail/addresses.json` | Addresses saved from mail read, for suggestions (up to 1000). |
| `/var/lock/mail/<user>/<account>.lock` | One lock per account, so a pass runs on one cluster node at a time. |
| `/etc/eip/routes/webtop/mail.xml` | The timer route (every minute). |
| `/usr/local/classes/webtop/mail/` | The synchronization, the API and sending (`MailSync`, `MailScheduler`, `MailApi`, `MailCompose` …). |
| `/etc/graphql/webtop/mail/` | The GraphQL schema (`mailAccounts`, `mailMessages`, `saveMailAccount` …) and its resolvers. |
| `/usr/share/webtop/apps/mail/` | The app; `attachment.groovy` there serves attachments and the original messages. |

`mail-service-user`, its group, the `mail:` namespace and `/var/lock/mail` are
provisioned by `provisioning/mail.yml` of the system workspace.

## Background synchronization

Every minute the route `webtop-mail-sync` runs the passes that are due: an
account's interval has elapsed (5 minutes by default) or the app asked for a run
(new settings, the refresh button, a flag change, older mail). The route runs as
`mail-service-user`, which cannot list the homes: it finds the accounts with a
query on the settings files. The `accounts` folder of a user grants the group
`mail-service-group` read and modifyProperties, which the app sets in the user's
own session when the first account is saved; the service user sees nothing else
of the home, and a deleted user takes the accounts away with the home. Each pass
runs as the account's owner, so it can reach that user's mail and nothing else.

A pass downloads at most 400 messages or runs for 3 minutes, then asks for the
next tick when more is left: a large download is spread over many short passes,
each committed message by message, and resumes where it stopped after a restart.
The status bar of the app shows the progress.

To run a pass by hand, set `mail:requested` to `true` on the account's settings
file under `/home/users/<user>/mail/accounts/`.

## Account types

The account dialog offers three types:

| Type | Servers | Signs in with |
| --- | --- | --- |
| IMAP / SMTP | typed in | a password, stored encrypted |
| Gmail | imap.gmail.com 993 SSL/TLS, smtp.gmail.com 587 STARTTLS | the linked Google account |
| Microsoft (Outlook) | outlook.office365.com 993 SSL/TLS, smtp.office365.com 587 STARTTLS | the linked Microsoft account |

A Gmail or Microsoft account has no password field. It signs in with the
account linked in *Preferences › Linked accounts* (see
`documents/linked-accounts.md`), over IMAP and SMTP with SASL XOAUTH2: the
access token of the *Send and receive mail* bundle is renewed by the platform
as needed, so nothing of the sign-in is stored with the mail account. The
dialog shows whether the account is linked and the bundle allowed, and offers
to link right there; saving or testing opens the grant popup when something
is missing. When the link is gone (unlinked, or the provider stopped
accepting it), the synchronization stops with *Link the Google account in
Preferences › Linked accounts* as the account's error, and runs again once the
link is back. The type of an account cannot be changed after it is saved.

### Sending through Microsoft Graph

Microsoft 365 tenants often have SMTP AUTH turned off; sending over SMTP then
fails with *535 5.7.139 SmtpClientAuthentication is disabled for the Tenant*
while reading over IMAP works. A Microsoft account has a **Send through**
setting for that: *SMTP* (the default) or *Microsoft Graph*. With Graph,
reading stays on IMAP and only sending changes: the message goes out through
the Graph API with the same linked account (the *Send and receive mail* bundle
covers `Mail.Send` as well), **Test connection** opens the mailbox through
Graph instead of signing in to SMTP, and the error of a refused SMTP sign-in
points to the setting.

What differs from SMTP:

- Exchange files what Graph sends into *Sent Items* by itself, so **Also put
  sent mail into the server's Sent folder** does not apply and is not shown.
  No copy is kept in the Webtop either: a synchronization pass is asked for
  right after sending and brings the message in from *Sent Items* (with the
  account's synchronization off, the copy is kept in the Webtop as for Gmail).
- A message whose MIME is within Graph's request limit (about 2.8 MB) goes out
  in one call. A larger one is created as a draft without its attachments, the
  attachments are added one by one (through an upload session when larger
  than about 2.8 MB), and the draft is sent; the message as a whole is still
  subject to the 25 MB attachment limit of the Webtop and to the tenant's
  message size limit.
- The *From* address has to be the mailbox's own address or one of its
  aliases; Exchange refuses to send as anyone else.
- A Microsoft account linked before Graph sending existed has the bundle
  without `Mail.Send`: the first Graph send reports *Allow "Send and receive
  mail" for the Microsoft account in Preferences › Linked accounts*, and
  allowing it again adds the scope.

## Connection settings

| Security | IMAP | SMTP |
|---|---|---|
| SSL/TLS | port 993 | port 465 |
| STARTTLS | port 143, TLS required | port 587, TLS required |
| None | port 143 | port 25 |

Server certificates are checked against the host name. **Test connection** in
the account dialog signs in to both servers and lists the folders without
saving anything.

## Deployment notes

- The server needs `org.mintjams.tools` **2.2.0** (the IMAP UID API and the
  tolerant parser). Replace `felix-dist/bundle/org.mintjams.tools_2.1.0.jar`
  with the 2.2.0 bundle built from the `tools` repository.
- The Webtop build copies `attachment.groovy` next to the app
  (`webtop/rollup.config.js`); `scripts/assemble-seed` lays the app into every
  workspace.
