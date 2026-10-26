# GraphQL

Every workspace has its own GraphQL API at `/bin/graphql.cgi/<workspace>`.
The API is the platform's built-in schema (content, users, processes,
routes, packages, ...) merged with the schema the workspace deployed for
its own applications. An application adds to its workspace's API by
writing files: a schema file and a script per field under `/etc/graphql`.
The engine compiles them the moment they land, with no restart and no
build step.

## Principles

Two rules shape everything below. Platform code, application resolvers and
the Webtop follow them.

### The server runs in the context of the workspace that was called

A request to `/bin/graphql.cgi/<workspace>` runs under the caller's session,
logged into that workspace. Every field reads and writes that workspace,
platform fields as much as application resolvers. There is no argument that
names another workspace, and no field quietly reaches into one.

To work with another workspace's data, call that workspace's endpoint. The
Webtop does this for the data that lives in the `system` workspace (users,
groups and roles, the profile and preferences, second factors, linked
accounts). From whichever workspace it runs in, it sends those calls to
`/bin/graphql.cgi/system` and everything else to its own workspace. Asked
in another workspace, the same fields see that workspace's repository: a
user's home in a content workspace holds only the working area, so for
instance `linkedAccounts` returns no links there.

The platform itself goes outside the called workspace only for what belongs
to the installation as a whole. That is identity and credentials: password
hashes and second factors under `/home/credentials`, the OAuth client
registrations in `/etc/oauth/clients.yml`, and the stored grants of linked
accounts, which a token for any workspace is renewed from. It is also
operations that are cross-workspace by nature, such as removing a deleted
user's homes everywhere, or managing workspaces. These paths use service
sessions internally and never hand another workspace's content to the
caller.

### Each workspace's API and dynamic deployment are self-contained

A workspace's schema is the platform schema plus the files under its own
watched folders, nothing else. The engine compiles those files with the
workspace's own service session, script engines and class loader, and runs
them in that workspace. So:

- An application deployed to one workspace does not exist in another.
  Installing it in a second workspace means deploying it there too (a
  package is installed per workspace, see `documents/packages.md`).
- A broken file in one workspace never affects another workspace's API.
- What a workspace serves is what is in its repository: reading
  `/etc/graphql` tells you its whole application API.

## The endpoint

| Request | Path | Notes |
| --- | --- | --- |
| Query, mutation | `POST /bin/graphql.cgi/<workspace>` | Body `{"query", "operationName", "variables"}` |
| Query | `GET /bin/graphql.cgi/<workspace>?query=&operationName=&variables=` | `variables` is a JSON string; a mutation over GET is refused with 405 |
| Subscription | `/bin/graphql.cgi/<workspace>/stream` | graphql-sse, single connection mode (below) |

- The answer is the GraphQL `{"data", "errors"}` object with HTTP 200,
  including when a field fails.
- A request that cannot run at all is answered 400 with an `errors` list:
  no workspace in the path, a workspace that is unknown or stopped, no
  schema available, or a body that cannot be parsed.
- One operation per request; there is no batching.
- Introspection is available.

**Who the request runs as.** The credentials come from the signed-in
browser session (cookie) or the cluster-wide auth token. Without either,
the request runs as the guest. The endpoint does not demand a sign-in by
itself: each field and the repository's access control decide what the
caller may see and do. On a signed-in user's first request to a content
workspace, `/home/users/<user>/Desktop` is created there and the user is
given their home (see `documents/workspaces.md`, "User home directories").

**Subscriptions** use the graphql-sse protocol in single connection mode
over Server-Sent Events, not WebSockets:

1. `PUT /stream` reserves a stream and answers 201 with a token. A token
   that no stream opens expires after 30 seconds.
2. `GET /stream?token=` opens the `text/event-stream`.
3. `POST /stream?token=` with `{"query", "variables", "operationName",
   "extensions": {"operationId"}}` starts an operation (202). Results arrive
   as `next` and `complete` events whose data is `{"id", "payload"}`.
4. `DELETE /stream?token=&operationId=` stops one operation (204).

The server sends a heartbeat every 15 seconds and drops a stream whose
client stops reading. In a cluster the four requests may reach different
nodes: the stream's owner is recorded cluster-wide, and operations are
forwarded to it (503 when the owning node cannot be reached).

## The schema of a workspace

### The platform part

Every workspace serves the platform schema, even when application GraphQL
is turned off for it.

| Area | What it serves |
| --- | --- |
| Core | content (nodes, children, search, XPath), uploads, locks, access control, versions, jobs (`jobProgress`), packages, the cluster, and the change subscriptions (`nodeChanged`, `preferenceChanged`, ...) |
| BPM | process definitions, instances, tasks, incidents (`documents/bpm-*.md`) |
| Workspaces | the workspace list and its lifecycle mutations (`documents/workspaces.md`) |
| IdP | users, groups, roles, `me`, profile and preferences |
| Security | TOTP, backup codes, passkeys |
| EIP | routes, contexts, exchange history (`documents/eip-*.md`) |
| MCP | the caller's MCP connection, `mcpToolDeployments` (`documents/mcp-server.md`) |
| GraphQL | `graphqlSchemaDeployment`: how the workspace's application schema last deployed (below) |
| Files | `documentText`, `fileDiff`, `grepFiles`, `findFiles`, `editFile` |
| Linked accounts, cloud files | `linkedAccounts`, `cloudPicker`, `importCloudFile`, ... (`documents/linked-accounts.md`) |

The core schema defines the root types `Query`, `Mutation` and
`Subscription` and the scalars `Long`, `DateTime` (ISO-8601, UTC), `Base64`
and `JSON`. The other areas `extend` them.

### The application part

The engine reads two folders of the workspace, recursively, through
`nt:folder` nodes:

- `/etc/graphql`, the usual place; packages deploy to
  `/etc/graphql/<app>/`;
- `/content/WEB-INF/graphql`, for a site's own API.

Every `nt:file` ending in `.graphqls` or `.graphql` is schema. All of them,
from both folders, form one schema together with the platform part: the
platform SDL comes first, then the files in the order they are found. Who
may deploy is decided by the access control on these folders.

Rules for application SDL:

- **Extend the roots.** Write `extend type Query { ... }`, never
  `type Query`. The same applies to `Mutation` and `Subscription`.
- **Do not redeclare** `Long`, `DateTime`, `Base64` or `JSON`; use them.
- **Name fields after the application** (`chatPostMessage`, `reversiRooms`,
  `webtopNotices`). A field defined by two schema files fails the
  compile, and with it the application schema of the whole workspace; the
  error names both files. The prefix is what keeps applications apart.
- **Platform definitions cannot be replaced.** The compile fails, naming the
  file, when a workspace's files try to take over something the platform
  defines:
  - a schema file that defines a platform field (`extend type Query { me: String }`);
  - a resolver for a platform field (a file `Query.me.groovy`);
  - a type resolver for a platform type;
  - a `wiring.yml` scalar script for a platform scalar (`DateTime`).

  Extending a platform type with fields of the application's own is
  allowed.

Any other `scalar` a schema declares is passed through as JSON unless
`wiring.yml` gives it a script (below).

## Resolvers

### Binding a field to a script

**By file name.** A file named `<Type>.<field>.<ext>` anywhere under the
watched folders resolves that field. `<ext>` is the extension of a script
engine of the workspace; in practice it is `.groovy`. The type is usually
`Query` or `Mutation`, and can be any object type of the schema:

```
/etc/graphql/catalog/
├── catalog.graphqls              extend type Query { catalogProducts(first: Int): [Product!]! }
└── Query.catalogProducts.groovy  the resolver
```

**By `wiring.yml`.** Any number of files named `wiring.yml` map fields to
scripts explicitly. An explicit entry wins over a file name for the same
field. Only `wiring.yml` can attach a type resolver, which every
`interface` and `union` needs, or a scalar script:

```yaml
types:
  Query:
    fields:
      catalogSearch: { script: resolvers/search.groovy }
      catalogAudit: { script: resolvers/audit.groovy, runAs: catalog-service }
  SearchResult:
    typeResolver: { script: resolvers/searchresult-type.groovy }
scalars:
  Money: { script: scalars/money.groovy }
```

- Script paths are relative to the `wiring.yml`, or absolute when they
  start with `/`. Keep the scripts under the watched folders: the engine
  reads every script when the schema compiles, and a change outside those
  folders is not noticed.
- `runAs` runs that resolver as the named service user, with a session of
  its own in the same workspace, instead of as the caller.
- A **type resolver** gets `object` (also `source`), `args` and `env`, and
  returns the name of the concrete type.
- A **scalar script** returns a map of closures `serialize`, `parseValue`
  and `parseLiteral`; a missing one passes the value through. It runs once,
  when the schema compiles, without a session or any API, so it must be a
  pure converter.

### What a resolver gets

| Binding | What it is |
| --- | --- |
| `args` | the field's arguments, a `Map` |
| `source`, `parent` | the object the field belongs to (null on a root field) |
| `env` | graphql-java's `DataFetchingEnvironment` |
| `context` | the script context: `context.session` is the caller's session in this workspace; `context.getAttribute('<API>')` returns the APIs below |
| `log`, `JSON`, `YAML`, `XPath`, `ISO8601` | logging and value helpers |
| `ScriptAPI` | `createServiceUserContext(user)`, `createUserContext(user)`: contexts of another identity, in the same workspace |
| `SessionAPI`, `SearchIndexAPI`, `MetadataAPI`, `MimeTypeAPI`, `CryptoAPI`, `EventAdminAPI`, `ClusterAPI` (`cluster`) | repository, search and platform services |
| `ProcessAPI`, `IntegrationAPI`, `PackageAPI` | the workspace's process engine, integration engine and packages |
| `Connections` | tokens of the caller's linked accounts (`documents/linked-accounts.md`) |

There is no `WebAPI`, request or response: a resolver answers GraphQL, not
HTTP.

Every resolver of a request shares one context and the caller's session,
and runs on one thread. The resolver's value is its last expression. Return
a `Map`, a `List` or a scalar; the fields of a returned map or bean are
resolved by name unless they have resolvers of their own.

**Changes are not saved for you.** The engine logs the session out at the
end of the request without saving it. A mutation commits what it changed
and rolls back on failure:

```groovy
def session = context.session;
try {
	def file = session.getResource('/var/catalog/' + args.id + '.json');
	// ... change it ...
	session.commit();
	return true;
} catch (Throwable ex) {
	session.rollback();
	throw ex;
}
```

The resolvers the Webtop ships are one-line scripts that hand `context`
and `args` to a Groovy class under `/usr/local/classes/<app>/`. Once a
resolver does more than a lookup, that is the layout to follow.

### Errors

An exception thrown by a resolver becomes an entry of the response's
`errors`. Its message is sent as it is, so it must not contain anything the
caller may not see. `extensions.code` classifies it:

| Exception | `code` |
| --- | --- |
| `IllegalArgumentException` | `BAD_REQUEST` |
| access denied, login failure | `FORBIDDEN` |
| path or item not found | `NOT_FOUND` |
| `LockException` | `LOCKED` |
| `IllegalStateException` | `UNAVAILABLE` |
| `ConsentRequiredException` (linked accounts) | `CONSENT_REQUIRED` |
| anything else | `RESOLVER_ERROR` |

The message is also in `extensions.reason`. Application errors are not
logged; log what you need with `log`. An internal error of the platform is
reported only as "Internal server error" and logged on the server.
Platform mutations that validate input report problems in their payload's
`errors` list instead of throwing.

## Dynamic deployment

The engine listens to changes of the workspace's repository. A change to
any file under a watched folder (added, changed, moved or removed)
recompiles the workspace's whole schema and swaps it in at once. A request
is served entirely by either the old schema or the new one. Edits that
arrive while a compile runs are combined into one more compile. The same
pass also redeploys the workspace's MCP tools (`tools.yml`, below).

All application files of a workspace compile as one unit:

| Situation | What the workspace serves |
| --- | --- |
| The new files compile | the new schema |
| A file is broken (invalid SDL, a field defined twice, a replaced platform definition, an `interface` without a type resolver, an unreadable `wiring.yml`), and a schema is being served | the previous schema, unchanged, until the files compile again |
| A file is broken at the first compile (after a restart or a workspace start) | the platform schema only: sign-in and the built-in API stay up, every application field of the workspace is missing until the files are fixed |

So a broken file holds back every other application's new edits in that
workspace, and after a restart it takes the workspace's whole application
API down. Fix it promptly; the error names the file.

A script that does not compile surfaces only when its field is called:
Groovy is compiled on first use.

### Seeing what deployed

`graphqlSchemaDeployment` reports the last compile of the workspace it is
asked in. It is for signed-in users; a guest gets `FORBIDDEN`.

```graphql
{
  graphqlSchemaDeployment {
    status          # DEPLOYED, KEEPING_PREVIOUS, PLATFORM_ONLY, DISABLED or UNAVAILABLE
    attemptedAt     # when the last compile ran
    servedSince     # when the schema now served was compiled
    files { path kind target }   # kind: schema, wiring, resolver, typeResolver, scalar
    errors { path message }      # why the last compile failed; empty when it did not
    warnings { path message }    # what it ignored without failing
  }
}
```

| `status` | Meaning |
| --- | --- |
| `DEPLOYED` | The files compiled and are served. |
| `KEEPING_PREVIOUS` | The last compile failed; the schema served before it still is (`servedSince` does not move). |
| `PLATFORM_ONLY` | The compile at start failed; only the platform schema is served. |
| `DISABLED` | Application GraphQL is turned off for the workspace (`graphql.yml`, below). |
| `UNAVAILABLE` | Nothing is served. |

An error names the file at fault, when one file is: a schema file that
does not parse, a field two files define, a file that replaces a platform
definition, an unreadable `wiring.yml`. Warnings name what was found but is
not used: a resolver for a field no schema file defines, a script
`wiring.yml` names that does not exist, a resolver that another one for the
same field replaces, a scalar script that could not be built.

After writing a file, wait until `attemptedAt` moves and read `status` and
`errors`. The server log has the same compile lines, and the MCP tools have
their own report, `mcpToolDeployments`.

**Turning it off.** `<repository>/workspaces/<workspace>/etc/graphql/graphql.yml`
on the server's file system (not in the repository) holds `enabled: true`.
It is created with that default. With `enabled: false` the workspace serves
the platform schema only and ignores its folders. The file is read when
the workspace starts.

**In a cluster** every node runs its own engine. A change made on one node
reaches the others as the same repository events, and each node
recompiles by itself.

## MCP tools from the same folders

A file named `tools.yml` under the watched folders defines MCP tools for
the workspace's MCP server. Each tool is a GraphQL operation run against
this workspace's schema, or a script run like a resolver as the caller.
Tools deploy in the same pass as the schema but separately from it. A tool
file that fails keeps its last good version, and a schema that fails does
not take the tools down, or the reverse. See `documents/mcp-server.md`,
"Tools defined by the workspace".

## Not included

- Depth, complexity or size limits for queries. The MCP `graphql` tool caps
  its results; the HTTP endpoint does not.
- Response caching. Compiled scripts are cached until the schema or the
  workspace's classes change.
- Subscriptions in application schemas. The engine runs subscription
  fields, but no application defines one; applications watch a path with
  the platform's `nodeChanged` instead.
