# BPM Task Forms — the `TasksFormHost` bridge

A task form (`camunda:formKey`) is user-authored HTML stored in the CMS. The
Tasks app loads it into a **same-origin, non-sandboxed** iframe and publishes a
bridge object the form calls directly:

```js
const host = window.parent.TasksFormHost;
```

There is no postMessage RPC and no serialization boundary: the bridge hands the
form the live GraphQL client and the BPM / CMS / IdP service facades the Tasks
app already built, running as the signed-in user. Server-side authorization
(Camunda + JCR ACLs) is the only thing gating what a form can do — the method
list is convenience, not a security boundary. **Deploying a form is as
privileged as deploying an app**; the JCR write ACL on the form node is the
trust boundary.

Implementation: `webtop/src/webtop/apps/tasks/app.ts` (`buildFormHost()`).

## Versioning

`host.version` is bumped whenever the shape changes incompatibly, so a form can
refuse to run against a host older than it expects.

| Version | Change |
|---------|--------|
| 1 | Initial bridge. |
| 2 | `notifyReady()` became **mandatory** — the host holds a loading overlay over the frame until the form calls it. A version 1 form never calls it and sits behind the overlay until the watchdog fires. |
| 3 | Adds app launch: `listApps()`, `openApp()`, `openFile()`. Purely additive; every version 2 form keeps working. |

```js
if (!host || host.version < 3) throw new Error('Tasks host is too old for this form');
```

## Method summary

| Group | Members |
|-------|---------|
| Environment | `workspace`, `webtopBaseUrl` |
| GraphQL | `graphql`, `createGraphQLClient(workspace)`, `bpm`, `cms`, `idp` |
| Context | `context`, `theme`, `localization`, `currentUser`, `subscribe(listener)` |
| Readiness | `notifyReady()`, `activate()` |
| **App launch** | **`listApps()`, `openApp(appId, options?)`, `openFile(path, opts?)`** |
| Identity | `getUser(username)` |
| Localization | `translate(id, params?, fallback?)`, `formatTemplate(template, params?)`, `getI18nMessages(prefix?)` |
| Process start (`start` mode) | `getProcessDefinition()`, `startProcess(opts?)` |
| Task operations | `getTask()`, `getTaskWithVariables()`, `getTaskVariables()`, `setTaskVariables()`, `getProcessVariables()`, `setProcessVariables()`, `claimTask()`, `unclaimTask()`, `setAssignee()`, `completeTask()` |
| CMS convenience | `getNode(path)`, `listChildren(path, opts?)`, `setNodeProperty()`, `readNodeText(path)` |

Task, process and CMS values come back as deep copies (plain JSON data), so a
form can never mutate the host's list or selection through a returned
reference. The service facades (`graphql`, `bpm`, `cms`, `idp`) are the
deliberate exception — they are the live objects, which is the point of them.

`notifyReady()` is required — see the doc comment on the method. Call it on
failure paths too, once the form has rendered its own error state.

## App launch

The drill-down a review step usually wants: *"show me the order this approval is
about"*. The Webtop shell owns window creation; the bridge validates the request
and hands it over through the same messages Content Browser uses for a
double-click.

### `listApps(): AppInfo[]`

The installed apps as plain data. Use it to resolve an id by title or category
rather than hard-coding a UUID that differs per deployment.

```js
const orderApp = host.listApps().find(a => a.title === 'Order Manager');
```

| Field | Description |
|-------|-------------|
| `id` | Application id (`identifier` in `app.yml`). |
| `title` | Display title. |
| `category` | App-menu category id, or `null`. |
| `editor` | True when the app is registered as an editor for content types. |
| `contentTypes` | MIME patterns the editor claims (`text/*` wildcards allowed). |
| `singleton` | True when only one instance may run at a time. |

### `openApp(appId, options?): void`

Launches an app by id, optionally handing it a launch payload.

```js
host.openApp(orderApp.id, { view: 'order', orderId: task.businessKey });
```

`options` arrives at the target app as the second argument of its
`window.appLaunch(instance, options)`. One key is consumed by the shell:
`options.initialWindowState` (`{ x, y, width, height }`) places the window
verbatim instead of cascading it. Every other key is the target app's own
business.

Throws when `appId` is empty or unknown. Otherwise fire-and-forget: the new
window has its own lifecycle, so there is nothing to await and nothing to
return.

**Singleton apps are re-targeted, not duplicated.** If the app is already
running, the shell focuses the existing window and sends it
`{ type: 'app-reopen', options }` instead of opening a second one — a form
button clicked repeatedly lands on one window rather than a stack of them. Apps
that do not handle `app-reopen` still get focused.

### `openFile(path, opts?): Promise<void>`

Content Browser's double-click, addressable from a form.

```js
await host.openFile('/content/orders/4711/invoice.pdf');
```

Both parts of the resolution are optional and filled in when omitted: the MIME
type from the node itself, the app from the editor registered for that type
(`text/*` wildcards included, matching Content Browser). Pass `opts.appId` to
force a specific editor, or `opts.mimeType` to skip the node lookup when the
form already knows it.

The target app receives `{ path, mimeType }` as its `appLaunch` options.

Async only because of those lookups — it resolves once the request has been
handed to the shell, not when the app is up. Throws on an empty path, a node
that does not exist, an unknown `appId`, or no editor for the content type.

> Unlike `openApp()`, this path always opens a **new** window; the shell does
> not apply the singleton rule to `open-file-with-app`.

### Receiving side (the launched app)

```js
window.appLaunch = async (instance, options) => {
  if (options?.view === 'order') await openOrder(options.orderId);
  if (options?.path) await openDocument(options.path, options.mimeType);
  instance.notifyLaunched();
};

// Singleton apps: handle re-targeting from a second launch request.
window.addEventListener('message', (e) => {
  if (e.origin !== location.origin) return;
  if (e.data?.type === 'app-reopen') routeTo(e.data.options);
});
```

## Worked example

A commerce approval form with a link to the order screen:

```html
<button id="show-order" type="button">Show order</button>
<script type="module">
  const host = window.parent.TasksFormHost;
  if (!host || host.version < 3) throw new Error('Tasks host is too old');

  try {
    const task = host.getTask();
    const orderApp = host.listApps().find(a => a.title === 'Order Manager');

    document.querySelector('#show-order').addEventListener('click', () => {
      if (!orderApp) return;
      host.openApp(orderApp.id, { view: 'order', orderId: task.businessKey });
    });
  } finally {
    // Always signal, success or failure, or the user waits out the watchdog.
    host.notifyReady();
  }
</script>
```
