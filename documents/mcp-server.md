# MCP server

The CMS is an MCP (Model Context Protocol) server. An AI client such as Claude
connects to a workspace and works in it with tools — browse, read, search,
diagnose, and (when allowed) change content — as the user who authorized it.

```
POST /bin/mcp.cgi/{workspace}          the MCP endpoint
GET  /bin/mcp.cgi/{workspace}/token    where a signed-in user issues an access token
```

This document covers how to connect, what the tools do, what an MCP client is
and is not allowed to do, and why it is built the way it is.

## Quick start

1. Sign in to the CMS in a browser, then open
   `https://<cms>/bin/mcp.cgi/<workspace>/token`.
2. Choose a scope (**Read** unless the client has to change content) and a
   lifetime, and press **Issue token**. The page shows the token once, together
   with the command and the `.mcp.json` entry to paste.
3. Register the server with the client. For Claude Code:

   ```bash
   claude mcp add --transport http cms-web https://cms.example.org/bin/mcp.cgi/web \
     --header "Authorization: Bearer mjmcp_…"
   ```

   or, in a project's `.mcp.json`:

   ```json
   {
     "mcpServers": {
       "cms-web": {
         "type": "http",
         "url": "https://cms.example.org/bin/mcp.cgi/web",
         "headers": { "Authorization": "Bearer mjmcp_…" }
       }
     }
   }
   ```

4. Ask. "Why does `/content/docs/index.html` return 404?" is answered by one
   call to `explain_web_render`.

A server entry is per workspace: connect to `system` and to `web` as two
servers, each with its own token.

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

### Access tokens

An MCP client is not a browser: it cannot follow the SAML redirect flow or hold
the session cookie. A signed-in user therefore issues a token for themselves at
`/bin/mcp.cgi/{workspace}/token` and gives it to the client, which sends it as
`Authorization: Bearer <token>`.

The token carries the user's identity exactly as the login established it. It
adds the three things a credential handed to a third-party program needs:

| | |
| --- | --- |
| **Scope** | `read` confines the client to the tools that change nothing, whatever the user's own privileges are. `write` also allows the tools that change content, and GraphQL mutations. |
| **Workspace binding** | The token is valid only at the workspace it was issued for. |
| **Revocation** | Each token has an id, shown when it is issued. Listing the id under `token.revoked` in `mcp.yml` revokes that token; setting `token.notBefore` revokes every token issued before that instant. |

Like the cluster-portable authentication cookie, the token is the identity
encrypted with the cluster-shared secret key (AES/GCM through the CMS
encryptor), so it is confidential, tamper-evident, and verifiable by any node
without shared state. The token is not stored on the server and cannot be shown
again after the page that issued it.

A token is not a session. The user is looked up on every request, so a token
whose user has been disabled or deleted stops working at once, and a change to
the user's roles applies to the next call.

The token and the authentication cookie are not interchangeable. Their payloads
share no field name and the token is tagged with its type, so a token presented
as the cookie fails the cookie's validation and the cookie presented as a token
fails this one. A read-only token cannot be turned into a full session by
replaying it against `/bin/graphql.cgi`.

### Issuing is guarded

Issuing turns a browser login into a long-lived credential, so:

- only the browser login is accepted at the token page — a token cannot mint
  another token, so a leaked token dies at its own expiry;
- the form post must come from the page itself: same origin, and carrying the
  per-session value the form was rendered with (a reload of the result page
  does not issue a second token);
- the lifetime is capped by `token.maxTtl`, and the write scope is offered only
  while `token.allowWrite` permits it;
- every issue is logged with the user, the token id, the scope and the expiry —
  never the token.

The page is plain server-rendered HTML with no script: it works before any
Webtop application knows about MCP, and carries nothing that could read the
token it displays.

### The browser login

A request without a bearer token is authenticated by the CMS login of the
browser (session or authentication cookie), so that a page served by the CMS
can call the endpoint. Such a request acts with the user's full rights.

Two rules keep another website from driving the endpoint through a signed-in
user's browser:

- a request carrying an `Origin` header is accepted only from the CMS's own
  origin or one listed in `allowedOrigins` (`403` otherwise);
- an origin allowed through `allowedOrigins` must use a bearer token; the
  browser login is honoured for the CMS's own origin only.

## Authorization

**Every call runs as the user.** The tools open the JCR session, and run the
GraphQL schema, with the caller's own credentials; none of them opens a
privileged session. The repository's access control is therefore the authority
on what an MCP client can see and change, exactly as it is for Webtop, and a
node the user cannot read looks to the client like a node that does not exist.

The scope only narrows that. It is enforced in three places that cannot
disagree, because all three derive from one flag on the tool:

1. `tools/list` does not show a read-scoped client the tools that write;
2. `tools/call` refuses them anyway — hiding a tool is not access control;
3. the tool's `readOnlyHint` tells the client whether a call needs confirming.

## Tools

| Tool | Scope | What it does |
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
| `graphql` | read / write | Any operation of the workspace GraphQL schema. Mutations need the write scope. |

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

The scope still holds. A read-scoped client may only run an operation that is
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

token:
    defaultTtl: 604800      # seconds; the lifetime the token page proposes (7 days)
    maxTtl: 7776000         # seconds; the longest lifetime an issuer may choose (90 days)
    allowWrite: true        # whether write-scoped tokens may be issued and used
    notBefore:              # ISO-8601 instant; tokens issued before it are rejected
    revoked: []             # ids of individually revoked tokens

allowedOrigins: []          # extra browser origins allowed to call the endpoint
```

| Key | Effect |
| --- | --- |
| `enabled` | `false` makes the endpoint and the token page answer `404`. |
| `token.defaultTtl`, `token.maxTtl` | Lifetimes in seconds. The default is capped by the maximum. |
| `token.allowWrite` | `false` removes the write scope from the token page and rejects write-scoped tokens already issued. |
| `token.notBefore` | Set it to the current time to revoke every token at once. |
| `token.revoked` | Token ids, as shown on the token page and in the log line written when the token was issued. |
| `allowedOrigins` | Origins such as `https://app.example.org`. Requests without an `Origin` header (every non-browser client) are not affected. |

**A file that cannot be parsed, or holds a value of the wrong shape, disables
the endpoint** rather than falling back to the defaults. The file carries the
revocation list, and serving requests without it would silently re-admit every
revoked token. The reason is written to the log.

Revocation is by configuration because the tokens are stateless. There is no
list of issued tokens to browse; the log line written at issue time is the
record of which ids exist.

### Clustering

Tokens verify on every node, because every node shares the secret key.

`mcp.yml` lives in the repository's `etc` directory. Where that directory is
not on shared storage, keep the file identical on all nodes: a token revoked on
one node only is still accepted by the others.

The token page keeps its form value in the node-local HTTP session. Without
sticky sessions the form post can land on another node, which answers that the
form had expired; nothing is issued, and submitting again succeeds.

## Logging

Every change made through MCP is logged at `INFO` with the user, the workspace
and the path, in addition to the modification metadata the repository records
on the node — the node can tell you who changed it, not that the change came
through MCP.

```
MCP access token issued: user=admin workspace=web id=bbbf0f70d7f5 scope=write expires=2026-12-30T06:12:42.588Z
MCP write_file: user=admin workspace=web path=/content/docs/.web.yml bytes=47 (replaced)
MCP set_properties: user=admin workspace=web /content/docs/index.md [web.template]
MCP delete_node: user=admin workspace=web /content/a
MCP graphql mutation: user=admin workspace=web operation=(anonymous)
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
| `401` | No credentials, or a token that is invalid, expired, revoked, issued for another workspace, or write-scoped while `allowWrite` is off. The message says which. |
| `403` | The `Origin` is not allowed. |
| `404` | The endpoint is disabled, or the workspace is unknown or stopped. |
| `405` | Not `POST`. |
| `413`, `415` | The body is too large, or not `application/json`. |

## Trying it with curl

```bash
TOKEN=mjmcp_…
URL=https://cms.example.org/bin/mcp.cgi/web

curl -s -X POST "$URL" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

curl -s -X POST "$URL" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"explain_web_render","arguments":{"path":"/content/docs/index.html"}}}'
```

## Not included

- **OAuth.** Clients that only connect through the MCP authorization flow
  (OAuth 2.1 with dynamic client registration) cannot connect yet; a client must
  be able to send a fixed `Authorization` header. The token format and the
  scopes are what an OAuth access token would carry, so the flow can be added in
  front of them without changing the tools.
- **Dedicated BPM and EIP tools.** Starting a process, completing a task or
  triggering a route is possible today through `graphql`. Tools that resolve
  "start the approval for this document" into the right definition and
  variables, and that confirm before starting, are the next step.
- **Server-initiated messages.** No notifications and no subscriptions: a
  client cannot be told that a task was assigned or a process ended; it has to
  ask.
- **Resources and prompts.** Content is reached through tools only.
- **Per-token listing and self-service revocation.** Revoking a token is an
  administrator's edit of `mcp.yml`.

## Implementation

`bundles/org.mintjams.rt.cms/src/org/mintjams/rt/cms/internal/mcp/`

| Class | Role |
| --- | --- |
| `McpServlet` | The endpoint: transport, origin check, authentication. Registered through `OSGI-INF/org.mintjams.rt.cms.internal.mcp.McpServlet.xml`. |
| `McpServer` | The protocol: JSON-RPC in, JSON-RPC out. Knows nothing about HTTP, authentication or the repository. |
| `McpTool`, `McpToolResult` | A tool's description, argument handling and result. |
| `McpCallContext` | Who is calling, where, and with what limits. The only way a tool reaches the repository — always as the caller. |
| `ContentTools` | The content tools. |
| `WebRenderTools` | `explain_web_render`. |
| `GraphQLTools` | `graphql`. |
| `McpGraphQL` | The GraphQL the content tools are built from, and the reshaping of its responses into something compact for a model. |
| `McpAccessToken` | Issuing and verifying tokens. |
| `McpTokenPage` | The token page. |
| `McpConfiguration` | `mcp.yml`. |

The content tools go through the workspace GraphQL schema wherever a field or
mutation already exists. That keeps an MCP client's view of a node identical to
Webtop's, and every rule a mutation enforces is enforced once, in one place.
The JCR API is used directly only where GraphQL has nothing to offer: reading
content, overwriting a file, and the walk `explain_web_render` performs.

To add a tool, build it with `McpTool.named(…)` and return it from one of the
`*Tools.all()` methods. Mark it `write()` or `destructive()` if it changes the
repository: that one flag hides it from read-scoped clients, refuses their
calls and sets its hints.
