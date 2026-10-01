# MCP server

The CMS is an MCP (Model Context Protocol) server. An AI client such as Claude
connects to a workspace and works in it with tools — browse, read, search,
diagnose, and (when allowed) change content — as the user who connected it.

```
POST /bin/mcp.cgi/{workspace}          the MCP endpoint
     /bin/mcp.cgi/{workspace}/…        the authorization flow a client goes through to connect
```

This document covers how to connect, what the tools do, what an MCP client is
and is not allowed to do, and why it is built the way it is.

## Quick start

1. Sign in to the CMS, open **Preferences › MCP**, choose the workspace and
   press **Turn on**. Tick **Allow clients to change content** if the client
   has to do more than read.
2. Copy what the page shows and give it to the client:

   - **Claude desktop app, claude.ai** — copy the **Server URL** and paste it
     into *Settings › Connectors › Add custom connector*.
   - **Claude Code** — copy the command and run it in a terminal:

     ```bash
     claude mcp add --transport http cms-prod-web https://cms.example.org/bin/mcp.cgi/web
     ```

3. The browser opens on a page of the CMS that names the client and what it
   will be allowed to do. Press **Allow**. There is no token to copy.
4. Ask. "Why does `/content/docs/index.html` return 404?" is answered by one
   call to `explain_web_render`.

The client keeps working as you after you sign out of the browser — which is
what lets it wait for something, or watch over it — until you press **Turn
off** in Preferences.

A connection is per workspace: turn it on for `system` and for `web`
separately, and connect each as its own server.

Claude's desktop app and claude.ai connect from Anthropic's network, not from
your computer, so they reach only a CMS that is reachable from the internet.
Claude Code connects from the computer it runs on.

### Server names

A client knows each server by a name, and with more than one CMS — development,
staging, production — the name is what tells the client, and the person
approving its actions, which one a tool call is about to change. The command
Preferences shows names the server `cms-<server>-<workspace>`, where
`<server>` is `serverName` from `mcp.yml` when it is set (`prod`), and the host
name otherwise (`cms-example-org`). The same label is in the server's title and
at the start of the instructions it gives the client.

## Transport

The endpoint speaks MCP's Streamable HTTP transport in its simplest conforming
form.

| Request | Answer |
| --- | --- |
| `POST` with one JSON-RPC request | `200`, the response as `application/json` |
| `POST` with a JSON-RPC batch | `200`, an array of responses |
| `POST` with only notifications | `202`, no body |
| `GET`, `DELETE` | `405` — there is no server-initiated stream and no session to end |
| `OPTIONS` | `204` |

The server is **stateless**: it issues no `Mcp-Session-Id` and keeps nothing
between requests. `initialize` is answered from constants and every later
request carries its own credentials, so any cluster node can answer any
request and a restart loses nothing.

Protocol revisions `2025-11-25`, `2025-06-18`, `2025-03-26` and `2024-11-05`
are accepted. Only the `tools` capability is offered — no resources, prompts,
sampling or logging.

A request must be `Content-Type: application/json` (`415` otherwise) and at most
16 MB (`413`).

## Authentication

There is no anonymous access. An unauthenticated request is answered `401`; it
is never run as the guest user.

### The connection

Whether MCP clients may work in a workspace as a user is that user's own
switch: the **MCP connection**, in Preferences. It holds three things.

| | |
| --- | --- |
| **On / off** | While it is off no client can be authorized, and none that was works. Turning it off stops every client of that user in that workspace at once. |
| **Read, or read and change** | Whether clients may use the tools that change content, and GraphQL mutations. It can be changed while clients are connected and applies to their next call. |
| **Connected clients** | The clients authorized since it was turned on, for the user to look at. |

The connection is the only state the MCP server keeps. It is a small file in
the workspace it belongs to, `/var/mcp/connections/<user>.json`, written with a
service session: every cluster node reads the same answer, and deleting the
file is how an administrator turns someone's connection off.

Turning the connection on starts a new *generation*. Everything issued to a
client carries the generation it was issued under and is honoured only while
that is the current one, so nothing survives an off-and-on.

### Authorizing a client

An MCP client is not a browser: it cannot follow the SAML redirect flow or hold
the session cookie. It connects through the authorization flow of the MCP
specification — OAuth 2.1 with PKCE — which hands the sign-in to a browser and
the result to the client, without the user handling a token.

```
GET  {endpoint}/.well-known/oauth-protected-resource     where to authorize
GET  {endpoint}/.well-known/oauth-authorization-server   how to authorize
POST {endpoint}/register                                 the client introduces itself
GET  {endpoint}/authorize                                the user approves, in the browser
POST {endpoint}/token                                    the client collects, then renews, its token
```

1. The client calls the endpoint and is answered `401` with a
   `WWW-Authenticate` header that names the metadata.
2. It reads the metadata and registers itself: a name, and the address the
   result is to be delivered to (dynamic client registration).
3. It opens the browser at `authorize`. The CMS signs the user in if they are
   not, checks that their connection to the workspace is on, and shows the
   approval page: the client's name, the server, the workspace, the user,
   what the client may do, and where the result goes.
4. On **Allow** the browser is sent back to the client with a one-minute
   authorization code, which the client exchanges at `token` — proving with
   PKCE that it is the one that started the flow — for an access token and a
   refresh token.

Each workspace endpoint is its own authorization server, so the workspace a
client is authorized for is the one in the URL it connected to.

The metadata is also served where a client looks for it by inserting the
well-known name in front of the endpoint's path
(`/.well-known/oauth-authorization-server/bin/mcp.cgi/{workspace}`). A reverse
proxy in front of the CMS has to pass `/.well-known/oauth-*` and
`/.well-known/openid-configuration/*` through as well as `/bin/mcp.cgi/`.

### What a client holds

| | Lifetime | |
| --- | --- | --- |
| **Access token** | `token.accessTtl` (1 hour) | Sent as `Authorization: Bearer …` with every call. |
| **Refresh token** | `token.refreshTtl` (90 days) from the approval | Exchanged for a new access token when the old one expires. It is not extended: after that time the user approves the client again, in the browser. |

Both carry the user's identity exactly as the sign-in established it, the
workspace, the generation of the connection and the time of the approval. They
are encrypted with the cluster-shared secret key (AES/GCM through the CMS
encryptor), like the cluster-portable authentication cookie, so they are
confidential, tamper-evident, and verifiable by any node without shared state.
Neither is stored on the server; neither says what the client may do.

On every call the server reads the user's connection and looks the user up:

- the connection is off, or has moved on to another generation → `401`;
- the approval is older than `token.notBefore` → `401`;
- the user has been disabled or deleted → the call fails as it would for
  anyone; a change to the user's roles applies to the next call;
- the connection is read-only, or `token.allowWrite` is `false` → the client
  is confined to the tools that change nothing.

The identity in a token is as old as the approval. Group memberships that come
from the identity provider at sign-in are therefore confirmed again when the
refresh token runs out, not before; `token.refreshTtl` is how long that may be.

The credentials of the flow are not interchangeable with each other or with the
authentication cookie. Each kind is tagged, and is accepted only as the kind it
is: a refresh token is not an access token, and neither restores a browser
session at `/bin/graphql.cgi`.

### Clients are not trusted to be who they say

Registration is open, as the specification requires, and a registration is not
stored: the client id is the registration itself, encrypted. A client's name
is therefore its own claim, and anyone can register a client named "Claude".
What cannot be chosen freely afterwards is where the authorization is
delivered. A redirect address is fixed at registration — `https`, `http` on
the loopback interface, or an application's own scheme — and the approval page
shows it, as does the list of connected clients in Preferences.

### The approval is guarded

- The page is plain server-rendered HTML with no script, and may not be framed.
- The decision must come from the page itself: same origin, and carrying the
  per-session value the page was rendered with. A reload of the result does
  not approve a second time.
- An authorization code is good for one minute and for one exchange, by the
  client it was issued to, at the address it was issued for.
- A request whose client and redirect address do not belong together is shown
  to the user as an error; nothing is sent to that address.
- Every approval is logged with the user, the workspace, the client's name and
  where the result was delivered — never a code or a token.

### The browser login

A request without a bearer token is authenticated by the CMS login of the
browser (session or authentication cookie), so that a page served by the CMS
can call the endpoint. Such a request acts with the user's full rights, and
does not depend on the connection being on.

What keeps another website from driving the endpoint through a signed-in
user's browser is that the browser login is honoured for the CMS's own origin
only. A request that carries the `Origin` of another site is served — a
client such as Claude sends its own with every call — but only on a bearer
token, which has to be presented on purpose; its cookies are ignored.

The metadata, registration and token endpoints carry no cookie and may be read
from any origin.

## Authorization

**Every call runs as the user.** The tools open the JCR session, and run the
GraphQL schema, with the caller's own credentials; none of them opens a
privileged session. The repository's access control is therefore the authority
on what an MCP client can see and change, exactly as it is for Webtop, and a
node the user cannot read looks to the client like a node that does not exist.

The connection only narrows that: while it is read-only, the client is confined
to the tools that change nothing, whatever the user's own privileges are. That
is enforced in three places that cannot disagree, because all three derive from
one flag on the tool:

1. `tools/list` does not show a read-only client the tools that write;
2. `tools/call` refuses them anyway — hiding a tool is not access control;
3. the tool's `readOnlyHint` tells the client whether a call needs confirming.

A client lists the tools when it connects. After changes are allowed in
Preferences the calls are accepted at once, but a client that is already
running may have to reconnect before it sees the tools.

## Tools

| Tool | Needs | What it does |
| --- | --- | --- |
| `get_node` | read | Metadata of a file or folder: type, size, MIME type, creation and modification, lock and version state, custom properties, and how a file is rendered over the web. |
| `list_children` | read | The children of a folder. Paged. |
| `read_file` | read | The content of a file. Text is paged by character; PNG, JPEG, GIF and WebP up to 1 MB come back as images; other binary content is described, not returned. |
| `search` | read | Full-text search, most relevant first. |
| `query` | read | A JCR XPath query. |
| `version_history` | read | The versions of a versionable node. Each has a `frozenNodePath` that `read_file` accepts, to read the content as it was. |
| `explain_web_render` | read | Why a web path is, or is not, served the way it is. See below. |
| `write_file` | write | Create a file, or replace the content of an existing one. |
| `create_folder` | write | Create a folder; no change when it exists. |
| `set_properties` | write | Set or delete custom properties, all together or not at all. |
| `move_node` | write | Move, rename, or both. |
| `copy_node` | write | Deep copy. |
| `delete_node` | write | Permanent delete. |
| `graphql` | read / write | Any operation of the workspace GraphQL schema. Mutations need a connection that allows changes. |

Paths are absolute repository paths. A relative path, or one containing `.` or
`..`, is refused rather than guessed at.

Listings return a cursor: when `hasNextPage` is true, pass `endCursor` back as
`after`.

Whether `read_file` treats a file as text is decided by its content when its
MIME type does not say. MIME types are assigned from file names, and many source
formats (templates, scripts, Markdown) map to types that reveal nothing about
being text.

### `write_file` does not replace by accident

`write_file` refuses a path where a file already exists unless `overwrite` is
`true`, and the refusal says what is there:

```
A file already exists at /content/WEB-INF/web.yml (533 bytes, last modified
2026-10-01T06:05:45.588Z by admin). Nothing was written. If it should be
replaced, read it with read_file first, then call write_file again with
overwrite set to true.
```

The whole content is replaced, not merged. A model asked to "add a setting" to
a configuration file otherwise tends to write the file it imagines rather than
the file that exists, and a configuration file that has lost its other keys
fails much later, somewhere else. The server instructions repeat the rule, and
a successful overwrite reports the size, time and author of what it replaced.

Text is written in the encoding the existing file records (UTF-8 for a new
file). The MIME type of a new file is derived from its name by the repository's
type detector; an existing file keeps its type unless one is given.

A checked-in versionable file, or a file locked by someone else, is refused by
the repository as it would be for any other client.

### `graphql` — everything else

The dedicated tools cover content. Everything else the platform exposes —
access control, locks, check-in and check-out, processes and tasks (BPM),
integration routes (EIP), users and groups, and the schema a workspace defines
for its own applications — is already reachable through GraphQL, with
authorization enforced by the resolvers. Rather than mirror each field as a
tool, `graphql` hands the client the schema itself: it can introspect, then
query.

The read-only limit still holds. A read-only client may only run an operation that is
*provably* a query: a mutation, a subscription, or a document whose operation
cannot be determined (unparseable, or several operations and no
`operationName`) is refused before execution. This is as strong as the schema's
own convention that queries do not change state; application-defined resolvers
are trusted to follow it.

Subscriptions are refused for every client: a tool call returns once, and the
server holds no stream open.

### `query` is XPath

The repository's query language is JCR XPath; JCR-SQL2 is not supported.

```
/jcr:root/content//element(*, nt:file)                                  every file under /content
/jcr:root/content//element(.web.yml, nt:file)                           by name: every folder descriptor
/jcr:root/content//element(*, nt:file)[@web.template = "article"]       by property
/jcr:root/content//element(*, nt:file)[jcr:like(@jcr:mimeType, "image/%")]
/jcr:root/content//element(*, nt:file)[jcr:contains(., "invoice")]      full text
```

## `explain_web_render`

Serving is deliberately forgiving (see `documents/cms-content-rendering.md`). A
folder descriptor that cannot be parsed binds nothing instead of failing; a
binding whose template is missing makes the resolver move on to the next
candidate; and the visitor is only ever told 404. That keeps one bad file from
taking a site down. It also means the reason a page disappeared is recorded
nowhere.

This tool walks the same decisions the web resolver makes and reports each one
instead of swallowing it. Give it the repository path that was requested, even
if no node exists there — for a public URL, the path under the site's document
root. A `/bin/cms.cgi/{workspace}/…` path is accepted as it is.

The binding itself is not re-implemented: the tool asks `WebRenders`, the
resolver that serving and the GraphQL `webRender` field share, so its answer
cannot drift from what is served.

### What it reports

| Field | Content |
| --- | --- |
| `resolution.outcome` | `RENDERED_THROUGH_TEMPLATE`, `SERVED_AS_FILE`, `SERVED_AS_SCRIPT`, `FOLDER_REDIRECT`, `HIDDEN_SOURCE`, `PROTECTED`, `ACCESS_DENIED` or `NOT_FOUND`. |
| `resolution.source`, `.template`, `.binding` | What is rendered, through which template, and whether the binding came from a `.web.yml` rule or the file's `web.template` property. |
| `resolution.considered` | Every source that exists and was considered, and why each one was rejected: not bound to a template, output not allowed by the binding, template not found (with the template paths tried). |
| `resolution.anonymous` | Whether an anonymous visitor can read the source and the template. |
| `resolution.blockedBy` | Set to `web.yml` when it cannot be used: the resolution is what would be served, and right now nothing is. |
| `webYml` | `/content/WEB-INF/web.yml`: its status, keys, parsed content, and who last modified it. |
| `descriptors` | Every `.web.yml` from the folder up to `/content`, nearest first — the order in which rules apply. Each with its status, its rules, and who last modified it. **Descriptors that exist but are broken are listed, not skipped.** |
| `findings` | The conclusions in plain sentences. |

A file's status is `OK`, `MISSING`, `EMPTY`, `MALFORMED` (with the parser's
message, line and column, and the content), `NOT_A_MAPPING`, `NOT_A_FILE` or
`UNREADABLE`.

### Example

A `.web.yml` is overwritten and loses a closing quote. The page it rendered
now answers 404, and nothing is logged.

```json
{
  "requestedPath": "/content/docs/index.html",
  "resolution": {
    "outcome": "NOT_FOUND",
    "considered": [
      { "source": "/content/docs/index.md", "output": ".html", "rejected": "not bound to a template" }
    ]
  },
  "descriptors": [
    {
      "path": "/content/docs/.web.yml",
      "size": 47,
      "modified": "2026-10-01T06:09:16.557Z",
      "modifiedBy": "admin",
      "status": "MALFORMED",
      "error": "ScannerException: while scanning a quoted scalar\n in reader, line 2, column 12: …",
      "content": "render:\n  - match: \"*.md\n    template: article\n"
    }
  ],
  "findings": [
    "The source /content/docs/index.md exists but is not bound to a template: …",
    "/content/docs/.web.yml exists but is MALFORMED (…). When serving, a descriptor that cannot be used is ignored without an error: it binds no file to a template, and files under /content/docs fall back to the next descriptor up, or are not rendered at all. Last modified 2026-10-01T06:09:16.557Z by admin."
  ]
}
```

### What it does not see

- **Request filters.** The filters configured in `web.yml` are scripts. They run
  before the path is resolved and can change or block a request; the tool
  reports that they are present, and does not evaluate them.
- **Routing in front of the CMS.** How a public URL maps to a repository path
  (reverse proxy, document root) is outside the repository.
- **What the template does.** A template that resolves and then fails while
  rendering is a `RENDERED_THROUGH_TEMPLATE` here and a 500 for the visitor.

The tool reads as the caller. A descriptor the caller may not read is reported
as it appears to them.

## Configuration

`<repository>/etc/mcp.yml` is generated with its defaults on first use and
re-read whenever it changes; no restart is needed.

```yaml
enabled: true

serverName:                 # label that tells this server from your others (prod, staging)

token:
    accessTtl: 3600         # seconds; lifetime of an access token (1 hour)
    refreshTtl: 7776000     # seconds; how long a client stays connected before it is approved again (90 days)
    allowWrite: true        # whether connections may change content
    notBefore:              # ISO-8601 instant; connections approved before it are cut off
```

| Key | Effect |
| --- | --- |
| `enabled` | `false` makes the endpoint and the authorization flow answer `404`, and Preferences says MCP is not enabled. |
| `serverName` | Part of the name clients know the server by (see *Server names*). Defaults to the host name. |
| `token.accessTtl` | A client renews its access token by itself, so this does not limit how long it stays connected. |
| `token.refreshTtl` | Counted from the user's approval and not extended by use. |
| `token.allowWrite` | `false` makes every connection read-only, whatever its user chose, from the next call. |
| `token.notBefore` | Set it to the current time to cut off every client of every user in every workspace at once. Clients approved after that instant work; the connections stay on, so users only have to approve their clients again. |

**A file that cannot be parsed, or holds a value of the wrong shape, disables
the endpoint** rather than falling back to the defaults. The file carries
`token.notBefore`, and serving requests without it would silently re-admit
every client it had cut off. The reason is written to the log.

To stop one user's clients, turn that user's connection off: the user in
Preferences, or an administrator by deleting
`/var/mcp/connections/<user>.json` in the workspace.

### Clustering

Tokens verify on every node, because every node shares the secret key, and the
connection is read from the repository, which every node shares.

`mcp.yml` lives in the repository's `etc` directory. Where that directory is
not on shared storage, keep the file identical on all nodes: a `notBefore` set
on one node only is not applied by the others.

The approval page keeps its form value in the node-local HTTP session. Without
sticky sessions the decision can land on another node, which answers that the
page had expired; nothing is approved, and pressing **Allow** again succeeds.
The record of redeemed authorization codes is node-local too: a code replayed
on another node within its one minute is stopped by PKCE, not by that record.

The endpoint's address is taken from the request (`Host`, and the scheme the
servlet sees), and a client compares it with the URL it connected to. Behind a
TLS-terminating proxy the scheme has to reach the CMS (the Felix SSL filter
takes it from `X-Forwarded-Proto`), or the metadata names `http://` and the
client refuses it.

## Logging

Turning a connection on or off, approving a client, and every change made
through MCP are logged at `INFO` with the user and the workspace. The
modification metadata the repository records on a node can tell you who
changed it, not that the change came through MCP; the log can.

```
MCP connection turned on: user=admin workspace=web write=false
MCP client authorized: user=admin workspace=web client=Claude redirect=https://claude.ai
MCP write_file: user=admin workspace=web path=/content/docs/.web.yml bytes=47 (replaced)
MCP set_properties: user=admin workspace=web /content/docs/index.md [web.template]
MCP delete_node: user=admin workspace=web /content/a
MCP graphql mutation: user=admin workspace=web operation=(anonymous)
MCP connection turned off: user=admin workspace=web
```

Read-only calls are not logged.

## Errors

A tool that fails reports it inside a successful response, with `isError: true`
and a message written for the model to act on — which argument was wrong, what
exists at the path, what to do instead. JSON-RPC errors are reserved for
requests that are malformed at the protocol level.

| Status | Meaning |
| --- | --- |
| `400` | The body is not JSON, or `MCP-Protocol-Version` names an unsupported revision. |
| `401` | No credentials, or a token that is invalid, expired, issued for another workspace, older than `notBefore`, or whose connection has been turned off. The message says which, and `WWW-Authenticate` names the metadata to authorize with. |
| `404` | The endpoint is disabled, or the workspace is unknown or stopped. |
| `405` | Not `POST`. |
| `413`, `415` | The body is too large, or not `application/json`. |

The authorization flow reports its errors the way OAuth defines them: as
`error` and `error_description` in a JSON body from `register` and `token`,
and as parameters on the redirect from `authorize`.

## Trying it with curl

The metadata needs no credentials:

```bash
URL=https://cms.example.org/bin/mcp.cgi/web

curl -si -X POST "$URL" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'      # 401, WWW-Authenticate: Bearer … resource_metadata="…"

curl -s "$URL/.well-known/oauth-protected-resource"
curl -s "$URL/.well-known/oauth-authorization-server"
```

Calling a tool takes an access token, which only the flow issues. A page of the
CMS can call the endpoint with the browser login instead; from the browser's
console, signed in:

```js
await (await fetch('/bin/mcp.cgi/web', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call',
    params: { name: 'explain_web_render', arguments: { path: '/content/docs/index.html' } } }),
})).json();
```

## Not included

- **Clients that cannot run the authorization flow.** There is no token to
  paste into a client's configuration or a script: a client has to be able to
  open a browser for the user and receive the result.
- **Client ID metadata documents.** A client is known by its dynamic
  registration only; a client id that is a URL is not fetched.
- **Dedicated BPM and EIP tools.** Starting a process, completing a task or
  starting and stopping a route is possible today through `graphql`. Sending a
  message into a route is not: the schema has no mutation for it. Tools that
  resolve "start the approval for this document" into the right definition and
  variables, and that confirm before starting, are the next step.
- **Server-initiated messages.** No notifications and no subscriptions: a
  client cannot be told that a task was assigned or a process ended; it has to
  ask. A connection that outlives the browser session is what lets it keep
  asking.
- **Resources and prompts.** Content is reached through tools only.
- **Stopping one client.** A connection is turned off as a whole; its other
  clients are approved again afterwards. When a client was last used is not
  recorded.

## Implementation

`bundles/org.mintjams.rt.cms/src/org/mintjams/rt/cms/internal/mcp/`

| Class | Role |
| --- | --- |
| `McpServlet` | The endpoint: transport, origin check, authentication, and the routing of the authorization flow. Registered through `OSGI-INF/org.mintjams.rt.cms.internal.mcp.McpServlet.xml`. |
| `McpServer` | The protocol: JSON-RPC in, JSON-RPC out. Knows nothing about HTTP, authentication or the repository. |
| `McpTool`, `McpToolResult` | A tool's description, argument handling and result. |
| `McpCallContext` | Who is calling, where, and with what limits. The only way a tool reaches the repository — always as the caller. |
| `ContentTools` | The content tools. |
| `WebRenderTools` | `explain_web_render`. |
| `GraphQLTools` | `graphql`. |
| `McpGraphQL` | The GraphQL the content tools are built from, and the reshaping of its responses into something compact for a model. |
| `McpOAuth` | The authorization flow: metadata, registration, the approval page, the token endpoint. |
| `McpConnections` | A user's connection to a workspace. |
| `McpAccessToken` | Issuing and verifying access tokens. |
| `McpSealed` | The encrypted envelope every credential of the flow travels in. |
| `McpConfiguration` | `mcp.yml`. |

Preferences reads and changes the connection through the workspace's GraphQL
schema (`mcpConnection`, `setMcpConnection`; `mcp-schema.graphqls`, wired by
`PlatformMcpWiringContributor`). Both act on the caller's own connection.

The content tools go through the workspace GraphQL schema wherever a field or
mutation already exists. That keeps an MCP client's view of a node identical to
Webtop's, and every rule a mutation enforces is enforced once, in one place.
The JCR API is used directly only where GraphQL has nothing to offer: reading
content, overwriting a file, and the walk `explain_web_render` performs.

To add a tool, build it with `McpTool.named(…)` and return it from one of the
`*Tools.all()` methods. Mark it `write()` or `destructive()` if it changes the
repository: that one flag hides it from read-only clients, refuses their
calls and sets its hints.
