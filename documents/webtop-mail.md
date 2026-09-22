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
- **Attachments** are uploaded into the draft (button or drop onto the pane), up
  to 25 MB in all.
- **Drafts** are saved as one types, in the Webtop only (the server's Drafts
  folder is not used). Closing the pane keeps the draft; *Drafts* in the left
  pane lists them.
- **Recipients** are suggested from the saved addresses and from the addresses
  mail was sent to before. In a message you read, the button after an address
  saves it; saved addresses and one's own have no button. The × of a suggestion
  removes that address from both lists at once, without asking.

What was sent is kept **in the Webtop only**: the copy is listed under *Sent*
but is not appended to the server's Sent folder. Removing the account removes
these copies, like the downloaded mail.

There is no signature: write it into the message.

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
| `/home/users/<user>/mail/accounts/<account>.json` | Account settings. Passwords are properties of this file, encrypted with the installation key (`secret-key.yml`, see [clustering](clustering.md)); they are never returned by the API. |
| `/home/users/<user>/mail/state/<account>.json` | Synchronization state: per folder UIDVALIDITY, highest UID, oldest date, CONDSTORE mark; the last run and error. |
| `/home/users/<user>/mail/messages/<account>/<folder>/<UIDVALIDITY>/<UID/1000>/<UID>.eml` | The messages. What the list shows is kept in `mail:*` properties; the search index reads the full text from the `.eml`. |
| `/var/lib/mail/accounts/<user>/<account>.json` | The account index of the background synchronization (owned by `mail-service-user`): interval, next run, "run now". No settings, no secrets. |
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

`mail-service-user`, its group, the `mail:` namespace and the two `/var`
folders are provisioned by `provisioning/mail.yml` of the system workspace.

## Background synchronization

Every minute the route `webtop-mail-sync` runs the passes that are due: an
account's interval has elapsed (5 minutes by default) or the app asked for a run
(new settings, the refresh button, a flag change, older mail). Each pass runs as
the account's owner, so it can reach that user's mail and nothing else.

A pass downloads at most 400 messages or runs for 3 minutes, then asks for the
next tick when more is left: a large download is spread over many short passes,
each committed message by message, and resumes where it stopped after a restart.
The status bar of the app shows the progress.

To run a pass by hand, set `mail:requested` to `true` on the account's entry
under `/var/lib/mail/accounts/<user>/`.

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
