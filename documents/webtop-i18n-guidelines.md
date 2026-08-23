# Webtop Internationalization (i18n) Guidelines

How the Webtop shell and its apps present text, numbers, dates and currency in
the user's language and region. This is the authoritative reference; follow it
whenever you add user-facing text or a locale-aware value.

The platform supports **English (`en`)** and **Japanese (`ja`)** today, and is
built so that adding a locale is "drop in one JSON file" — no code change.

---

## 1. The two layers

| Layer | What it controls | Source of truth |
| --- | --- | --- |
| **Localization preference** | locale, time zone, number-format locale, currency | `services/localization-manager.ts` (per-user, IndexedDB + JCR sync) |
| **Message bundles** | the actual translated strings | global `/etc/i18n/*.json` + per-app `<app>/i18n/<locale>.json`, loaded by `services/webtop-i18n-service.ts` |

The user edits the preference in **Preferences → Localization**. Every change is
broadcast to all app iframes and folded into each app's reactive snapshot, so
the UI repaints live. An empty preference means "use the system/browser
default"; the manager's `effective*` getters resolve the fallback for you.

---

## 2. Message bundles

### Layout — app bundles live in the app folder, global bundles in /etc/i18n

Bundles are flat JSON files in **two locations**. The locale of a file is the
**last dot-delimited segment** of its name (before `.json`):

```
# App-scoped bundles — inside each app's folder, deployed WITH the app
/content/webtop/apps/content-browser/i18n/en.json   → locale "en"
/content/webtop/apps/content-browser/i18n/ja.json   → locale "ja"
/content/webtop/apps/commerce-orders/i18n/ja.json   → locale "ja"   (add-on app)

# Global bundles — /etc/i18n/, for keys visible outside a single app
/etc/i18n/en.json                  → locale "en"   (cms0 core: common.* + webtop.* + cms.*)
/etc/i18n/ja.json                  → locale "ja"
/etc/i18n/wt-inspector.en.json     → locale "en"   (shared component, webtop.inspector.*)
/etc/i18n/searchindex-forms.ja.json→ locale "ja"   (BPMN forms, form.*)
/etc/i18n/en-US.json               → locale "en-us"
```

**App bundles are scoped to their app.** The i18n service keeps them per app
(the app id is the app folder name, i.e. `relPath`) and consults them only for
lookups made in that app's scope — the `translate()` composable passes
`instance.app.relPath` automatically, so every app's `t()` resolves its own
bundle first with **no extra wiring**. Two consequences:

- An app's keys **cannot collide** with another app's keys, whatever they are
  named. The conventional `app.<appId>.*` prefix keeps working (and stays the
  recommended, greppable style), but it is no longer what prevents collisions.
- An app's keys are **not visible** to the shell or other apps. Any key that
  must be read outside the app (e.g. a start-menu category label
  `webtop.appmenu.category.<id>`) belongs in a **global** file, not the app
  bundle.

Lookups fall back from the app scope to the global bundles, so apps use the
shared `common.*` / `webtop.*` primitives exactly as before.

All **global** files for the same locale are still **merged** into one bundle
(sorted by file name, deterministic). Independently deployed units contribute
global keys via their own `<unit>.<locale>.json` in disjoint namespaces — but
with per-app bundles in place, a unit only needs a global file for its
cross-app keys; everything else travels inside its app folders.

Seed copies in the repositories:

- **App bundles** live next to the app source at
  `webtop/src/webtop/apps/<appId>/i18n/<locale>.json`; the rollup build copies
  them into `dist/webtop/apps/<appId>/i18n/`, so they deploy (and version)
  with the app itself. Add-on repos (e.g. commerce-dev) do the same in their
  own app folders.
- **Global bundles** (core `en.json` / `ja.json`, `wt-inspector.*`,
  `searchindex-forms.*`) live at
  `docker/initial-repository/workspaces/system/etc/jcr/deploy/etc/i18n/` and
  are deployed into the workspace at boot.

Editing any bundle at runtime hot-reloads everything: the i18n service watches
`/etc/i18n` (shallow) and the apps tree (deep, filtered to `i18n/` folders),
reloads + re-merges, and broadcasts `i18n-bundles-updated` so every app
repaints.

> **Migration note.** Workspaces deployed before app-scoped bundles existed may
> still carry the old `/etc/i18n/<appId>.<locale>.json` files. They are
> harmless — the app's own scoped bundle wins for every key it defines — and
> can be deleted at leisure.

### Key naming convention

Keys are hierarchical, dotted, **flat** (the JSON is not nested). Namespaces:

| Prefix | Owner | Example |
| --- | --- | --- |
| `common.*` | shared UI primitives reused everywhere | `common.save`, `common.cancel` |
| `webtop.*` | the shell (desktop, start menu, taskbar, window chrome, system dialogs) | `webtop.startMenu.signOut` |
| `app.<appId>.*` | a single app; `<appId>` is the app folder name | `app.preferences.localization.title` |
| `cms.*` | cross-cutting platform messages (validation, errors) surfaced by services | `cms.validation.string.tooLong` |

Within an app, group by feature/section and suffix by role, e.g.
`app.preferences.localization.currency.title` / `.desc` / `.auto`.

**App display title.** An app's title (shown in the start menu, dock preview and
menubar) is localized by convention under **`app.<appId>.title`**, where
`<appId>` is the app folder name (the `relPath`, e.g. `content-browser`). The
shell's `appTitle(app)` helper resolves this key and falls back to the literal
`title` from `app.yml` when no bundle key exists — so adding a title is purely a
bundle edit, with no server or `app.yml` change. (We deliberately avoid an
`app.yml` `titleKey` field: that metadata is parsed server-side and exposed over
GraphQL, so a convention key keeps title localization fully client-side and
consistent with the rest of the `app.<appId>.*` namespace.)

App **action** labels (`app.yml` `actions.<id>.label`) are not surfaced in any
shell UI today, so there is nothing to translate yet. When an action becomes
visible (e.g. a future "open with" menu), localize it under
`app.<appId>.action.<id>.label`, falling back to the literal `label`, by the
same convention.

### Values are ICU MessageFormat

Bundles are formatted with `intl-messageformat`, so values may use ICU
placeholders, plurals and select:

```json
"cms.validation.string.tooLong": "Value is too long (max {max} chars)",
"app.tasks.count": "{count, plural, =0 {No tasks} one {# task} other {# tasks}}"
```

### Rules

- **Keep all locale files in sync.** Every key in `en.json` must exist in
  `ja.json` and vice versa. `en` is the ultimate fallback, so a key missing from
  `en.json` is a bug.
- **Never concatenate translated fragments.** Put the whole sentence in one key
  with placeholders; word order differs between languages.
- **Don't put user data in keys.** Keys are identifiers, not content.

---

## 3. Using i18n from an app

Everything flows through the **localization composable**
(`composables/use-localization.ts`). Wire it in three places, then call the
helpers from your template.

### 3.1 Wiring (once per app)

```ts
import {
  createLocalizationSnapshot,
  refreshLocalization,
  handleLocalizationMessage,
  translate, formatNumber, formatCurrency, formatDate,
} from "../../composables/use-localization.js";

// data()
localization: createLocalizationSnapshot(),

// appLaunch(instance), after `this.instance` is assigned
refreshLocalization(this.localization, this.instance);

// window 'message' listener — handles BOTH locale changes and bundle reloads
if (handleLocalizationMessage(type, vm.localization, vm.instance)) return;
```

### 3.2 Expose thin method wrappers

```ts
methods: {
  t(id, params, fallback) { return translate(this.localization, this.instance, id, params, fallback); },
  formatNumber(v, o)      { return formatNumber(this.localization, v, o); },
  formatCurrency(v, o)    { return formatCurrency(this.localization, v, o); },
  formatDate(v, o)        { return formatDate(this.localization, v, o); },
}
```

### 3.3 Use them in the template

```html
<h2>{{ t('app.preferences.localization.title') }}</h2>
<span>{{ formatNumber(stats.bytes) }}</span>
<span>{{ formatCurrency(order.total) }}</span>      <!-- uses effective currency -->
<span>{{ formatDate(node.lastModified) }}</span>    <!-- effective locale + zone -->
<span>{{ t('app.tasks.count', { count: n }) }}</span>
```

`t()` always takes a key, never an English literal. Pass a `fallback` literal
only as a safety net while a key is being introduced.

> **Pitfall — never put an ICU message as an inline fallback inside `{{ }}`.**
> ichigojs delimits interpolations with `}}`, and an ICU plural/select message
> ends with `}}` (e.g. `… other {# tasks}}`). Writing
> `{{ t('x.count', { count: n }, '{count, plural, …}}') }}` makes the ICU `}}`
> close the interpolation early → *Unterminated string constant* at mount.
> For a `{{ }}` interpolation, **omit the inline fallback** (the key resolves
> from the bundle): `{{ t('x.count', { count: n }) }}`. Plain (brace-free)
> fallbacks are fine; keep ICU-bearing fallbacks out of `{{ }}` (they're OK in
> attribute bindings like `:title="…"`, or define them in `app.ts`).

### 3.4 Why it repaints automatically

ichigojs re-evaluates a binding when a reactive value it *read* mutates. Each
helper reads the snapshot fields it depends on (`locale`, `numberFormat`,
`currency`, `timeZone`, and an internal `revision` bumped on bundle reload), so
switching language in Preferences or hot-editing a bundle re-runs every
`t()` / `format*()` binding with no extra code in the app.

---

## 4. Adding a string (checklist)

1. Pick a key in the right namespace (§2).
2. Add it to **both** locale files of the unit that owns the namespace:
   - an app string (`app.<appId>.*`) → the app's own
     `webtop/src/webtop/apps/<appId>/i18n/en.json` **and** `i18n/ja.json`;
   - a shared/shell string (`common.*`, `webtop.*`, `cms.*`) → core `en.json`
     **and** `ja.json` under `docker/initial-repository/.../etc/i18n/`.
3. Reference it via `t('your.key')` in the template (or `translate(...)` in TS).
4. Never hardcode the English text in the component.

Remember the scope rule (§2): a key defined in an app bundle is visible only
inside that app. If the shell or another app must read it, it is a global key —
put it in a global file under the owning global namespace instead.

## 5. Adding a locale

Drop a new `<locale>.json` for every existing unit — the core `<locale>.json`
in `/etc/i18n` plus one `i18n/<locale>.json` inside each app folder — with the
same key sets as the `en` files. No code change is required: the loader derives
the locale from the file name, the fallback chain is
`exact → language-only → en` (applied in the app scope first, then globally),
and the Preferences language list is populated from all available bundles.

## 5a. Adding a new app

Give the app its own `i18n/en.json` / `i18n/ja.json` pair inside its app folder
(`webtop/src/webtop/apps/<appId>/i18n/`; the rollup app config copies the
folder to dist automatically). Put its title under `app.<appId>.title` and the
rest of its strings under `app.<appId>.*`, and reuse the shared `common.*` /
`webtop.*` primitives rather than redefining them. No loader change is needed —
the folder is discovered per app at boot and hot-reloaded on edit.

---

## 6. Roadmap

The English + Japanese rollout is complete across the platform:

1. ~~**Shell** (`webtop.*`)~~ — done (side menu, session save/restore dialogs,
   desktop context menus, upload/delete/rename dialogs, conflict & alert
   dialogs, dock).
2. ~~**App display titles**~~ — done. All standard apps localize their title in
   the start menu / dock / menubar via the `app.<appId>.title` convention above.
3. ~~**Apps**~~ — done. Every standard app is fully localized under its
   `app.<appId>.*` namespace: preferences, content-browser, tasks, dashboard,
   identity-manager, schema-manager, osgi-console, bpm-console, eip-console,
   text-editor, text-editor-preview, eip-modeler, bpmn-modeler.
4. ~~**Shared components**~~ — done. The reusable Inspector custom element
   (`components/wt-inspector.ts`), embedded by multiple apps (content-browser,
   text-editor), is localized under the `webtop.inspector.*` namespace and
   ships its own modular bundle pair (`wt-inspector.<locale>.json`). It runs
   inside the host app's iframe, so its `t()` resolves the shell's I18nService
   via `window.parent.Webtop.i18n` (the host passes the reactive `localization`
   snapshot down as a `:localization` prop, exactly as in §3.4). Other shared
   shell components follow the same pattern (`webtop.window.*` for wt-window).

When **adding a new app** (or new strings to an existing one), follow §3–§4:
wire the snapshot, expose `t()`, and add keys to both `en.json` and `ja.json`.
Note: apps restored at startup (session restore) may render once before the
bundles finish their initial load; refresh on the `i18n-bundles-updated`
message (the shell and every converted app already do this via
`handleLocalizationMessage`).
