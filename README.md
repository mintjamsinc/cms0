# MintJams CMS (cms0)

A lightweight Content Management System built on a simplified implementation
of the **Content Repository for Java Technology API 2.0 (JSR 283)**.
It bundles a JCR-backed server runtime on Apache Felix together with a
browser-based virtual desktop ("Webtop") for content authoring and
administration. Zero-configuration SAML 2.0 (both Service Provider and
Identity Provider) is included out of the box.

- **Server** — Apache Felix runtime, JCR repository, SAML SP/IdP, Camel/Camunda
  integrations. Sources under [`bundles/`](bundles/).
- **Client** — "Webtop" virtual desktop and built-in apps (Content Browser,
  Identity Manager, BPM Console, BPMN/EIP Modeler, OSGi Console, etc.).
  Sources under [`webtop/`](webtop/).

> Status: **0.1.23-beta** — public preview. APIs, on-disk formats, and bundled
> apps may change before 1.0. Installations of 0.1.23-beta or earlier cannot be
> upgraded in place — see [Upgrading](#upgrading).

---

## Quick start (Docker)

The published image bundles the full server runtime, all default bundles, and
the pre-built Webtop assets. The only required configuration is the external
URL the CMS will be reachable on.

```bash
docker run --rm \
  -p 8080:8080 \
  -e CMS_PUBLIC_BASE_URL=http://localhost:8080 \
  -v cms-repository:/data/repository \
  -v cms-secrets:/data/secrets \
  -v cms-index:/data/index \
  --tmpfs /opt/felix/tmp:size=512m,mode=0700 \
  mintjams/cms:0.1.23-beta
```

Then open <http://localhost:8080/> in a browser.

For a fixed deployment behind a reverse proxy, set `CMS_PUBLIC_BASE_URL` to the
externally visible URL (e.g. `https://cms.example.org`) so the SAML SP and IdP
generate correct redirect URLs.

### docker compose

```yaml
services:
  cms:
    image: mintjams/cms:0.1.23-beta
    restart: unless-stopped
    environment:
      CMS_PUBLIC_BASE_URL: "http://localhost:8080"
    ports:
      - "8080:8080"
    volumes:
      - cms-repository:/data/repository
      - cms-secrets:/data/secrets
      - cms-index:/data/index
    tmpfs:
      - /opt/felix/tmp:size=512m,mode=0700

volumes:
  cms-repository:
  cms-secrets:
  cms-index:
```

---

## First login

On the **first** start, the container generates a random password for the
built-in `admin` user and writes it (mode `0600`) into the repository volume.
Read it from the host:

```bash
docker exec <container> cat /data/repository/INITIAL_PASSWORD.txt
```

Log in as `admin` with that password, change it via Preferences, then delete
the file:

```bash
docker exec <container> rm /data/repository/INITIAL_PASSWORD.txt
```

To set the initial password explicitly instead, pass
`-e CMS_INITIAL_ADMIN_PASSWORD=...` on the first run.

---

## Configuration

### Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `CMS_PUBLIC_BASE_URL` | **yes** | External base URL (e.g. `https://cms.example.org`). Drives the SAML SP `rootURL` and IdP `baseUrl`. The container refuses to start without it. |
| `CMS_INITIAL_ADMIN_PASSWORD` | no | Initial password for the auto-created `admin` user. If unset, a random password is generated and written to `/data/repository/INITIAL_PASSWORD.txt`. |
| `CMS_SP_KEYSTORE_PASSWORD` | no | Password for the auto-generated SP keystore. If unset, a random one is generated and written to `/data/repository/SP_KEYSTORE_PASSWORD.txt`. Stored AES-encrypted in `etc/saml2.yml` either way. |
| `CMS_IDP_KEYSTORE_PASSWORD` | no | Password for the auto-generated IdP keystore. If unset, a random one is generated and written to `/data/repository/IDP_KEYSTORE_PASSWORD.txt`. Stored AES-encrypted in `etc/idp.yml` either way. |
| `MINTJAMS_CMS_SECRET_KEY_PATH` | no | Path to the AES master key. Defaults to `/data/secrets/secret-key.yml`. |
| `CMS_CLUSTER_ENABLED` | no | `true` or `false`. Selects the clustered or standalone configuration files placed on first start, and when set overrides `etc/repository.yml`. See [`docker/README.md`](docker/README.md#bundled-assets-and-configuration). |
| `CMS_DB_HOST`, `CMS_DB_PORT`, `CMS_DB_USER`, `CMS_DB_PASSWORD` | cluster | The shared database the clustered configuration files point at. `CMS_DB_PASSWORD_FILE` reads the password from a file (a Docker secret) instead. See [`docker/README.md`](docker/README.md#configuration-values-from-the-environment). |
| `CMS_SEARCH_INDEX_PATH` | no | Where this node keeps its search index. Defaults to `/data/index`. Node-local — never shared storage. |
| `CMS_JAVA_OPTS` | no | Extra JVM flags (e.g. `-Xmx4g`), appended to the image's own. Set this rather than `JAVA_TOOL_OPTIONS`, which replaces what the entrypoint assembles. |

A configuration file can take a value from the environment with
`${env.NAME}` (or `${env.NAME:-default}`), from a file with `NAME_FILE`, or
carry it encrypted as `ENC[v1:...]` — see
[`docker/README.md`](docker/README.md#configuration-values-from-the-environment).

### Persistent volumes

| Mount | Why it must persist |
|---|---|
| `/data/repository` | JCR content, generated SP/IdP keystores (`*.p12`), and the auto-generated `etc/saml2.yml` / `etc/idp.yml`. Losing this means starting from an empty repository. |
| `/data/secrets`    | The AES master key that encrypts keystore passwords in the YAML files and any `ENC[...]` value. **Losing this volume makes those encrypted values unrecoverable.** Back it up separately. |
| `/data/index`      | This node's search index. Rebuilt automatically from the repository content when empty, so it need not be backed up — but it must stay node-local. |

### Exposed port

| Port | Description |
|---|---|
| `8080` | HTTP. Terminate TLS at a reverse proxy and forward to this port. |

### Zero-configuration SAML

On first boot the bundles create `etc/saml2.yml`, `etc/idp.yml`, and both
keystores automatically. The SP trusts the co-located IdP via in-JVM OSGi
services — no manual metadata exchange is required. `CMS_PUBLIC_BASE_URL`
is the single source of truth for the external hostname; restarting with a
new value retargets both SP and IdP. To federate with an external IdP, edit
`etc/saml2.yml` after first boot; values written there take precedence over
the auto-generated defaults.

---

## Upgrading

From **0.1.24-beta** on, upgrading means replacing the image and keeping the
volumes. On every start, the bundled content (Webtop apps, built-in
provisioning, and the other default content shipped with the image) is
brought in line with the running image in every workspace, including the
removal of files the image no longer ships. Configuration files in the
repository volume (`etc/*.yml`, `<workspace>/etc/**/*.yml`) are written only
when missing and never overwritten; settings introduced by a later release
use their defaults until you set them.

### Installations of 0.1.23-beta or earlier

These releases copied the bundled content into the repository volume only
when the volume was first created, so replacing the image never updated it.
They cannot be upgraded in place. Install 0.1.24-beta or later on new, empty
volumes instead:

1. In the old installation, export the content you want to keep as a CMS
   Archive from the Content Browser.
2. Start the new version with new volumes for `/data/repository` and
   `/data/secrets`. A new `admin` password is generated (see
   [First login](#first-login)).
3. Import the archives in the new installation's Content Browser, and reapply
   any changes you made to configuration files (for example an external IdP in
   `etc/saml2.yml`).

Until 1.0, a release may again require a fresh installation; such releases
are announced in this section.

---

## Maintenance

### Rebuilding the search index

Administrators can rebuild the full-text search index from the Webtop
**Tasks** app (*Start a process* → **Search Index Rebuild**) — for
example after changing the search configuration under
`<workspace>/etc/search`. The rebuild runs in the background on every
cluster node while search and content updates keep working; a progress
task shows every node live and supports abort and re-running failed
nodes. See
[`documents/search-index-rebuild.md`](documents/search-index-rebuild.md).

---

## Platform support

The published image is a **multi-arch manifest covering `linux/amd64` and
`linux/arm64`**. Docker automatically pulls the variant matching the host, so
Apple Silicon and arm64 servers run natively — no emulation required. Native
JavaScript support (V8 via JNI) is delivered by per-architecture fragment
bundles (`org.mintjams.rt.cms.linux.x86_64` and
`org.mintjams.rt.cms.linux.arm64`); the runtime loads the libraries for the
running architecture and ignores the rest. See
[`docker/README.md`](docker/README.md#platform-support) for details.

---

## Repository layout

```
bundles/    Server-side OSGi bundles (JCR, CMS, SAML SP/IdP, Camel, Camunda, ...)
webtop/    Client-side virtual desktop and built-in apps (TypeScript + Rollup)
docker/    Dockerfile, entrypoint, compose example, image seed (bundled assets, default configuration)
scripts/   docker-build.sh / docker-build.ps1 wrappers around `docker buildx`,
           assemble-seed.sh / assemble-seed.ps1 to lay the Webtop into the seed
```

## Building from source

The published image is produced from this repository. If you want to build it
yourself (custom bundles, private fork, etc.), see
[`docker/README.md`](docker/README.md) for the full build pipeline:

1. Produce a `felix-dist/` directory at the repo root (Felix runtime + the
   bundles compiled from `bundles/`).
2. Build the Webtop (`cd webtop && npm run build:prod`) and run
   `./scripts/assemble-seed.sh` (or the PowerShell equivalent) to lay it into
   `docker/seed/`.
3. Run `./scripts/docker-build.sh -v <version>` (or the PowerShell
   equivalent) to produce a `mintjams/cms:<version>` image.

Per-component build instructions live in [`webtop/README.md`](webtop/README.md)
for the client and in each bundle's own metadata for the server.

---

## License

MIT. See [`LICENSE`](LICENSE) for the project and
[`docker/THIRD_PARTY_LICENSES.md`](docker/THIRD_PARTY_LICENSES.md) for the
inventory of third-party software shipped inside the container image.

## Links

- Docker Hub: <https://hub.docker.com/r/mintjams/cms>
- Source: <https://github.com/mintjamsinc/cms0>
- Vendor: <https://www.mintjams.jp/>

## Trademarks

All trademarks are the property of their respective owners.

---

Built with ❤️ by [MintJams Inc.](https://www.mintjams.jp/)
