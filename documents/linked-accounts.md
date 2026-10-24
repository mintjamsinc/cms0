# Linked accounts

A user can link their **Google account** or **Microsoft account** to their
Webtop account (*Preferences › Linked accounts*), so that apps can use it: the
Mail app with a Gmail or Outlook mailbox, a file picker with Drive or OneDrive,
and so on. One account per provider.

An app never asks for everything at once. It asks for a **permission bundle**
when it needs one, and the user sees the provider's consent screen for that
bundle only (incremental authorization). Preferences lists what was granted,
in words, and takes any of it back.

| Bundle | Shown as | Used by |
| --- | --- | --- |
| `mail` | Send and receive mail | Mail (IMAP and SMTP with XOAUTH2; for a Microsoft account, sending through Microsoft Graph as well) |
| `files` | Read and write the files you pick | Content Browser import and export, Mail attachments, Memo export (see *Files* below) |
| `contacts` | Read contacts | recipient suggestions |
| `calendar` | Read and write calendar events | calendar views, invitations |

Nothing is shipped: a provider appears in Preferences only after an
administrator registers a client with it (next section).

The scopes behind a bundle, which the consent screen shows:

| Bundle | Google | Microsoft |
| --- | --- | --- |
| `mail` | `https://mail.google.com/` | `IMAP.AccessAsUser.All` and `SMTP.Send` (Outlook), `Mail.Send` (Graph) |
| `files` | `drive.file` | `Files.ReadWrite` |
| `contacts` | `contacts.readonly` | `Contacts.Read` |
| `calendar` | `calendar.events` | `Calendars.ReadWrite` |

Microsoft issues an access token per resource, so the `mail` bundle's scopes
are consented to together but used as two tokens: one for Outlook (IMAP and
SMTP) and one for Graph. A Microsoft account linked before `Mail.Send` was
part of the bundle keeps working over IMAP and SMTP; the first time something
needs the Graph side, the provider refuses that scope, the bundle is taken off
the account, and Preferences and the Mail app offer to allow it again (one
consent screen, listing the added scope).

## For the administrator: enabling a provider

1. Register a client with the provider, as a **web application**, with this
   redirect URI, where `CMS_PUBLIC_BASE_URL` is the public URL of the server:

   ```
   <CMS_PUBLIC_BASE_URL>/bin/auth.cgi/oauth/callback
   ```

   - **Google**: Google Cloud console › APIs & Services › Credentials › Create
     OAuth client ID › Web application. A Google Workspace organization
     registers the app as *Internal*; then no verification is needed. An app
     open to any Google account has to be published and verified, because the
     `mail` bundle uses a restricted scope (`https://mail.google.com/`). An app
     left in *Testing* issues refresh tokens that expire after seven days, so
     users would be asked to link again every week.
   - **Microsoft**: Entra admin center › App registrations › New registration,
     platform *Web*. Set *Supported account types* to match the `tenant` you
     configure: `organizations` (work or school accounts of any organization,
     the default), `common` (those plus personal Microsoft accounts) or one
     tenant id. Add a client secret. The API permissions need not be listed
     on the registration: the scopes of a bundle are asked for in the
     authorization request, and the user consents to them there. A tenant
     that does not let users consent needs an administrator to grant them
     (list the permissions on the registration and grant admin consent).
     Sending over SMTP needs SMTP AUTH to be enabled for the mailbox; a tenant
     that keeps it off can send through Microsoft Graph instead (the Mail
     account's *Send through* setting, see `documents/webtop-mail.md`).

2. Copy `/etc/oauth/clients.yml.example` in the system workspace to
   `clients.yml` next to it and fill in the client id and secret:

   ```yaml
   google:
     clientID: 1234-abcd.apps.googleusercontent.com
     clientSecret: ${env.CMS_OAUTH_GOOGLE_SECRET}
   microsoft:
     clientID: 00000000-0000-0000-0000-000000000000
     clientSecret: ENC[v1:...]
     tenant: organizations
   ```

   A secret may be written in the clear, as `ENC[v1:...]` (encrypted with the
   installation key), or as `${env.NAME}` to take it from the environment
   variable `NAME` or the file named by `NAME_FILE`. The file is re-read when
   it changes; no restart is needed. The folder `/etc/oauth` is closed to
   everyone but administrators by provisioning (the root of a workspace lets
   everyone read, so the folder carries an explicit deny); the platform reads
   the file with a service session, which access control does not apply to,
   so no user, service user or app needs to be granted anything on it.

   A provider section may also carry `endpoints:` (`authorization`, `token`,
   `userinfo`, `revocation`, and the API bases `drive` for Google and `graph`
   for Microsoft) to go through a proxy or a test double; normally it is left
   out.

   For the `files` bundle with Google, add two more values from the same
   project to the `google` section: `apiKey`, an API key (Credentials › Create
   credentials › API key; restrict it to the Google Picker API and to the
   server's origin), and `appId`, the project number (project settings). The
   Google Picker, which runs in the browser, needs both. Neither is a secret;
   they are the only values of the file a browser ever receives. Without them
   the Drive features still work, but the user can reach only the files this
   server created on their Drive, never pick one of their own (next section).

3. Check *Preferences › Linked accounts* as any user: the provider is listed.
   A mistake in the file is shown there as a configuration error, without the
   values.

## What the user sees

- **Link** opens the provider's sign-in and consent in a popup. The link asks
  for identity only (who the account is). The popup closes by itself.
- Under a linked account, each bundle has **Allow** or **Revoke**. *Allow*
  opens the consent screen for that bundle. An app that needs a bundle the
  user has not allowed opens the same popup itself, at the moment it needs it.
- **Unlink** removes the link and every bundle; apps that relied on it (a mail
  account signed in with it, say) stop working until a new link is made.
- **Link again needed** appears when the provider stopped accepting the grant:
  the user revoked the app at the provider, changed the password, or the grant
  expired. *Link again* fixes it; the bundles granted before are kept.
- A second account of the same provider cannot be linked while one is: the
  consent screen is told which account to show, and a different choice is
  refused with a message.

## Where things live

| Where | What |
| --- | --- |
| `/etc/oauth/clients.yml` (system workspace) | the clients an administrator registered; `clients.yml.example` next to it is replaced on upgrades |
| `/home/users/<user>/connections/<provider>.json` (system workspace) | who the account is (`subject`, `email`, `displayName`), the bundles granted, `linkedAt`, `lastUsed`, `needsReauth` |
| the property `oauth:refreshToken` of that file | the grant, encrypted with the installation key; never in the JSON, never returned by any API |
| `/var/lock/oauth/<user>-<provider>` | the lock that keeps two cluster nodes from renewing one grant at once |

Access tokens are not stored. A node keeps the ones it obtained in memory,
per user, provider and audience, and renews a token from the stored grant a
minute before it expires; a restart costs one renewal. Microsoft issues a new
refresh token with every renewal; the stored one is replaced, under the lock,
after the stored token has been read again, so a renewal another node just
made is used rather than repeated.

Everything in the user's home is read and written with the user's own
session, so the home's access control applies and deleting the user takes the
links away.

## Files: Google Drive and OneDrive

The `files` bundle lets apps copy files between the user's drive and the
workspace, with the user's own grant. Nothing is synchronized or mounted:
every copy is an action of the user, and what it produces is an ordinary file.

- **Content Browser**: the cloud button in the toolbar offers, per provider,
  *Import files*, *Import as links* and *Export selected files*. An import
  copies the picked files into the folder being browsed; an export uploads the
  selected files to a folder of the drive the user picks.
- **Mail**: the compose pane attaches a file of the drive (it is copied into
  the draft like an uploaded file) or inserts a link to one into the body.
- **Memo**: a memo is exported as a Google document (Drive converts the HTML)
  or as an HTML file.

**Choosing a file differs per provider.** Google grants the app access only
to the files the user picks for it, and to the files the app created (the
`drive.file` scope). So with Google the user picks in the **Google Picker**,
Google's own dialog, which runs in the browser with the user's token and the
`apiKey` / `appId` from `clients.yml`; without those two values the dialog
cannot run, and the Webtop's own list shows only what this server created.
Microsoft's `Files.ReadWrite` reaches the whole OneDrive, so there the Webtop
lists the drive itself, a folder at a time.

**Google documents** (Docs, Sheets, Slides, ...) have no bytes of their own.
An import exports one as an Office file (`.docx`, `.xlsx`, `.pptx`; other
kinds as PDF) and adds the extension to the name. Exporting HTML, text or an
Office file *as a Google document* does the reverse. OneDrive stores files as
they are.

**Cloud links** are small files of type
`application/vnd.mintjams.webtop.cloud-link+json` holding
`{provider, id, name, mimeType, folder, webUrl}`. Opening one in the Content
Browser opens the file where the provider shows it, in a new tab. Nothing is
copied, and the link grants nothing: whoever opens it needs their own access
at the provider.

**Names that collide** with a file of the folder get a number appended
(`report (2).pdf`) unless the caller asks otherwise; the providers rename on
their side the same way.

The GraphQL fields behind this (`cloudPicker`, `cloudChildren`, `cloudFile`,
`importCloudFile`, `exportCloudFile`, `exportCloudContent`,
`createCloudLink`) are part of the platform schema of every workspace and run
as the caller; the token is the caller's, the repository reads and writes
are the caller's. A call made without the grant fails with the error code
`CONSENT_REQUIRED`, and the app opens the grant popup and tries again.

## For scripts: the `Connections` binding

```groovy
String token = Connections.accessToken('google', 'mail');   // XOAUTH2 password
boolean ok = Connections.has('microsoft', 'files');
Map account = Connections.account('google');               // null when not linked
```

`accessToken` returns a token for the user the script runs as, or throws
`ConsentRequiredException`, whose message starts with `consent_required` and
names the provider, the bundle and the reason: `not_configured`,
`not_linked`, `bundle_not_granted` or `reauthorize`. A GraphQL resolver that
lets it through reports it as an error with that message; a Webtop app can
then call `instance.api.connections.ensure(provider, bundle)`, which opens
the popup when needed and resolves with whether the bundle is granted, and
try again.

## The flow, for the record

`GET /bin/auth.cgi/oauth/authorize?provider=&bundle=&return=` requires a
signed-in browser (otherwise it sends the user to sign in and comes back),
seals the user, provider, bundle, return path and PKCE verifier into
`state`, and redirects to the provider with the identity scopes plus the
bundle's. `GET /bin/auth.cgi/oauth/callback` opens the state, checks that the
browser is still signed in as that user, redeems the code (PKCE, client
secret), reads `sub`, `email` and `name` from the ID token (or the userinfo
endpoint), stores the account and the refresh token, and ends on a page that
posts `{type: "connection", ok, provider, bundle}` on the
`webtop-connections` BroadcastChannel and closes itself. Google is asked with
`access_type=offline`, `prompt=consent` and `include_granted_scopes=true`, so
a refresh token is issued at every grant and covers every scope granted so
far; Microsoft with `offline_access`.

Revoking a bundle in Preferences stops the Webtop from using it; the
provider's own record of the consent is not changed, because Google revokes
only a whole grant and Microsoft offers no revocation. Unlinking revokes the
grant at Google and deletes it locally; at Microsoft it is deleted locally
only, and the user can remove the app under their Microsoft account.
