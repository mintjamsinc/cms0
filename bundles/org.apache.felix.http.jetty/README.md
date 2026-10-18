# org.apache.felix.http.jetty (patched)

Apache Felix HTTP Jetty with one addition: Jetty's session store can be
supplied by an OSGi service. This is an Eclipse PDE plug-in project like the
other bundles: the unchanged upstream jar lives in `lib/` and is embedded on
the `Bundle-ClassPath`, the few sources we patch live in `src/`, and the
exported bundle goes into `felix-dist/bundle`.

## Why

Jetty's `SessionHandler` resolves its `SessionCacheFactory` and
`SessionDataStoreFactory` from the beans of the Jetty `Server` while it starts
(`SessionHandler.doStart()`). A bean added afterwards is never seen. Felix
creates the server and starts it inside `JettyService.initializeJetty()` and
offers no hook that runs in between (`ConnectorFactory` is called after
`server.start()`; there is no `JettyCustomizer` and no jetty.xml support), so
without this patch the session store cannot be replaced from another bundle.

## What is patched

| File | Change |
| --- | --- |
| `internal/SessionStoreFactoryTracker.java` | New. Tracks `org.eclipse.jetty.server.session.SessionDataStoreFactory` and `SessionCacheFactory` services; the highest ranking of each kind wins; a change notifies `JettyService`. |
| `internal/JettyService.java` | Opens the tracker in `start()` before Jetty starts. `configureSessionStore()` adds the tracked factories as server beans right after `configureSessionManager()`, before `server.start()`. A factory change restarts Jetty when it is running. With `org.apache.felix.http.session.store.require=true`, Jetty does not start until a `SessionDataStoreFactory` is available. Start / stop / configuration update are serialized on one lock. |
| `internal/JettyConfig.java` | New property `org.apache.felix.http.session.store.require` (default `false`) and `isRequireSessionStore()`. |

The bundle is laid out as

```
META-INF/MANIFEST.MF                         upstream headers + Bundle-ClassPath + X-MintJams-Patch
org/apache/felix/http/jetty/internal/*.class the patched classes (compiled from src/)
lib/org.apache.felix.http.jetty-5.2.6.jar    the upstream jar, unchanged
```

`Bundle-ClassPath: ., lib/org.apache.felix.http.jetty-5.2.6.jar` puts the
patched classes in front of the upstream ones; everything else is loaded
from the upstream jar as released. `META-INF/MANIFEST.MF` repeats the
upstream manifest's headers (exports, imports, capabilities, activator)
verbatim, except that the build-tool headers are dropped and
`Bundle-RequiredExecutionEnvironment: JavaSE-17` replaces the upstream
`osgi.ee` requirement (JavaSE 11), like the other bundles. It adds
`Bundle-ClassPath` and the header `X-MintJams-Patch: session-store-factory`.

`upstream/` holds the unmodified copies of the patched sources so the change
stays reviewable:

```
git diff --no-index bundles/org.apache.felix.http.jetty/upstream bundles/org.apache.felix.http.jetty/src
```

## How a bundle supplies the store

Register a `SessionDataStoreFactory` (and optionally a `SessionCacheFactory`,
for example Jetty's own `NullSessionCacheFactory` so that every request reads
the store) as OSGi services. The packages `org.eclipse.jetty.server.session`
and `org.eclipse.jetty.server` are exported by this bundle. With
`org.apache.felix.http.session.store.require=true` in `conf/config.properties`
the HTTP service waits for the data store factory, so the order in which
bundles start does not matter and Jetty is started exactly once.

Register both factories in one call (one object registered under both
interface names): a change of either factory restarts Jetty, so two separate
registrations at boot cost one restart.

Session attributes pass through the store, so they must be serializable and
small; the CMS credentials, principals and the IdP's pending user are. A
store implementation must restore attributes with the class loader of the
bundle that owns each attribute's class (Jetty's own stores resolve classes
through this bundle's loader, which cannot see application classes).

## Building

Import the directory into Eclipse as an existing project and export it with
the other bundles (Export > Deployable plug-ins and fragments) into
`felix-dist/bundle`. The export is named `org.apache.felix.http.jetty_5.2.6.jar`;
remove any `org.apache.felix.http.jetty-*.jar` (the plain upstream file name)
from `felix-dist/bundle`, otherwise Felix finds two bundles with the same
symbolic name and version.

`src/` comes first on the project's build path, so its classes shadow the
copies inside the upstream jar. `JettyService` names the Jetty WebSocket
initializers that the bundle does not embed (they are optional imports and
used only when a WebSocket bundle is deployed); `compile-lib/` holds the two
WebSocket jars needed to compile those references. They are on `.classpath`
and on `jars.extra.classpath` in `build.properties`, but not in
`bin.includes`, so they are not exported. The remaining compile warnings
(an unused import, raw `ServiceFactory` / `ServiceRegistration`) are in
upstream code and kept so that `upstream/` and `src/` differ only by the patch.

## Upgrading upstream

1. Put the new upstream jar in `lib/` and remove the old one; update its name
   in `.classpath`, `build.properties` and `Bundle-ClassPath`.
2. Copy the new upstream manifest's headers into `META-INF/MANIFEST.MF`
   (keeping `Bundle-ClassPath`, `Bundle-RequiredExecutionEnvironment` and
   `X-MintJams-Patch`).
3. Copy the new upstream `JettyService.java` and `JettyConfig.java` (from the
   sources jar) into `upstream/`, then re-apply the diff above onto `src/`.
4. Replace the jars in `compile-lib/` with the versions matching the upstream
   jar's `X-Jetty-Version`.
5. Export, then check `Started Jetty <version>` and
   `Using session data store factory` in the log.
