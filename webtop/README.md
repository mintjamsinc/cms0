# Webtop

Webtopはブラウザ上に構築される仮想デスクトップ環境です。
リアルタイム通知にはSSE（EventSource）を使用し、ローカルストレージにIndexedDBを利用します。
テーマ切り替えや壁紙管理も可能で、デフォルトでdark／lightテーマに対応しています。

## Directory Structure

```
/src/webtop/
├── components/# UI components
├── services/# IndexedDB, SSE, Theme, Auth, etc.
├── apps/# Webtop applications
├── assets/# Static files
└── api/# API integration layer
```

## Build Workflow

Sources in `src/` are compiled to `dist/`.

Bundled third-party assets (Bootstrap Icons, Inter, Noto Sans JP) are copied
from `node_modules/` into `dist/webtop/vendor/`, a sibling of `assets/` rather
than a subdirectory of it: `assets/` holds what this repository authors or
owns, `vendor/` holds what the build pulls in from upstream packages.

Development build (unminified, inline sourcemaps):

```
npm run build
```

Production build (minified JS and CSS, external sourcemaps):

```
npm run build:prod
```

Per-target scripts follow the same pattern, e.g. `npm run build:webtop`
and `npm run build:webtop:prod`.

All of these run `scripts/build.mjs`, which type-checks `src/` once with
`tsc --noEmit` and then runs one rollup process per target, a few at a time
(`--jobs N`, or `BUILD_JOBS`; default 3). Each target's TypeScript program
lives in its own process, so a full build no longer needs a large
`--max-old-space-size` and stays within memory as apps are added. Targets are
named as in `TARGET_NAMES` in `rollup.config.js`, e.g.
`node scripts/build.mjs --prod memo eip-console`; `--no-check` skips the
type check.

`npx rollup -c` still works on its own, with the build mode selected via the
`BUILD` environment variable (`development` or `production`) and `TARGETS`
set to a comma-separated list of targets, but it only transpiles: type errors
are reported by the build script or `npm run typecheck`.

## Checks

```
npm run check
```

Two checks, both of which have caught defects that shipped:

- `check:camel-roundtrip` reads each fixture route, writes it back and compares.
  The Modeler regenerates a route rather than patching it, so anything the
  parser understands and the serializer does not is deleted the first time a
  route is saved — silently. See `scripts/fixtures/camel/README.md`.
- `check:endpoint-options` compares the `cms:`/`bpm:`/`transform:` option
  catalogue against what the producers actually read, in both directions.
  Requires `python3`.

`build:prod` runs them first, so a release cannot be cut past a failing one. A
development build does not, so the edit loop stays fast.

**Both need `npm install` to have run** — `check:camel-roundtrip` uses
`@xmldom/xmldom` to parse without a browser. A check that does not run is not a
check.

## Testing

Run unit tests with:

```
npm test
```
