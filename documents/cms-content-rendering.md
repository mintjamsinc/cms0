# CMS Content Rendering

How the CMS turns stored content into what it serves over the web. The goal is
that authors keep content under **natural file names** (e.g. `index.md`) and the
platform renders it through a template, without per-file boilerplate.

## The three layers

A single request is resolved through three independent concepts:

| Layer | Example | Meaning |
| --- | --- | --- |
| **Source resource** | `index.md`, `about.md` | The stored file. Its extension is the *source format*. |
| **Output extension** | `.html`, `.rss` | The *representation* the client asks for. |
| **Template** | `article.html` | Selected by the binding + output extension; renders the source. |

So `GET /foo/index.html` is served by rendering the stored source `/foo/index.md`
through the template bound to it, for the `html` representation. The same source
can be exposed at several outputs (e.g. `index.html` and `index.rss`).

## Source extensions

`/content/WEB-INF/web.yml`:

```yaml
sourceExtensions:
  - md
```

When a request for an output representation (e.g. `index.html`) has no direct
match, the resolver also looks for a stored source whose base name carries one of
these extensions (`index.md`). Defaults to `md`.

The legacy convention — a node named exactly `index` (no extension) carrying a
`web.template` property — keeps working unchanged.

## Binding content to a template

Resolved in priority order:

1. **Per-file** — a `web.template` property on the file's `jcr:content` (set via
   the Inspector). Output extensions are unrestricted.
2. **Per-folder** — the nearest ancestor folder's `.web.yml` descriptor, which
   binds files by glob so a whole folder renders without touching each file.

### Folder descriptor (`.web.yml`)

Place a file named `.web.yml` in a folder:

```yaml
render:
  - match: "*.md"        # glob over the file name (*, ? supported)
    template: "article"  # /content/WEB-INF/templates/article(.html).<scriptExt>
    output: [html, rss]  # optional; allowed output extensions, empty = any
```

Rules are evaluated nearest-folder-first, first match wins. A descriptor that is
missing or malformed simply binds nothing (it never breaks serving).

## Raw sources are not exposed

A templated source requested at its own source extension (e.g. `/foo/index.md`)
is **not** served raw — it is authoring input, and its rendered output is reached
at the output extension (`/foo/index.html`). A plain file that carries a source
extension but has **no** template binding (e.g. a downloadable `readme.md`) is
still served as-is.

## Reserved names and `.well-known`

`.web.yml` is configuration and is never served over the web. This protection is
keyed on the **reserved name**, deliberately **not** on a "leading dot" rule, so
dot-prefixed paths such as `/.well-known/` (ACME / Let's Encrypt) remain publicly
served.

## Telling clients how a file renders

Clients (notably the text editor's preview) cannot see a folder-descriptor
binding, so the server reports the effective decision on the GraphQL `Node`:

```graphql
node(path: "/content/foo/index.md") {
  webRender {
    templated       # served as rendered output?
    fromDescriptor  # bound via a folder .web.yml (vs a per-file property)?
    source          # source extension the name carries (e.g. "md"), or null
    outputs         # allowed output extensions; empty = any
  }
}
```

This is computed by the same resolver used for serving
(`WebRenders.resolveBinding`), so the editor preview always matches what the CMS
actually serves.

## Implementation

- `WebRenders` — shared binding resolver (source extensions, per-file property,
  folder descriptors). Used by both web serving and GraphQL.
- `WebResourceResolver` — request-time resolution (source lookup, output
  selection, raw-source hiding) on top of `WebRenders`.
- `CheckProtectedAction` — keeps `.web.yml` private while leaving `.well-known`
  served.
- `NodeMapper` — exposes the `webRender` descriptor on file nodes.
