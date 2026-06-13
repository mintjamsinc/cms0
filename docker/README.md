# MintJams CMS — Container packaging

This directory contains the artifacts needed to run the CMS as a Docker
container with zero manual SAML configuration on first boot.

## Files

- `Dockerfile`   — runtime image based on `eclipse-temurin:17-jre`
- `entrypoint.sh` — startup script that prepares persistent dirs and validates
   `CMS_PUBLIC_BASE_URL`
- `docker-compose.yml` — example compose file with the two named volumes

## Required runtime configuration

| Env var | Purpose |
|---|---|
| `CMS_PUBLIC_BASE_URL` | External base URL (e.g. `https://cms.example.org`). The SP `rootURL` and IdP `baseUrl` are derived from it when `saml2.yml` / `idp.yml` leave them blank. **Required.** |
| `MINTJAMS_CMS_SECRET_KEY_PATH` | Location of the AES master key. Default in this image: `/data/secrets/secret-key.yml`. |
| `CMS_INITIAL_ADMIN_PASSWORD` | Optional. Initial password for the auto-created `admin` user. If unset, a random password is generated on first boot and written to `/data/repository/INITIAL_PASSWORD.txt` (mode 0600). Read it via `docker exec`, log in, change the password, then delete the file. |
| `CMS_SP_KEYSTORE_PASSWORD` | Optional. Password for the auto-generated SP keystore (`etc/sp-keystore.p12`). If unset, a random password is generated on first boot and written to `/data/repository/SP_KEYSTORE_PASSWORD.txt` (mode 0600). Stored AES-encrypted in `etc/saml2.yml` either way. |
| `CMS_IDP_KEYSTORE_PASSWORD` | Optional. Password for the auto-generated IdP keystore (`etc/idp-keystore.p12`). If unset, a random password is generated on first boot and written to `/data/repository/IDP_KEYSTORE_PASSWORD.txt` (mode 0600). Stored AES-encrypted in `etc/idp.yml` either way. |

## Volumes

| Mount | Why it must be persistent |
|---|---|
| `/data/repository` | JCR content, generated SP/IdP keystores (`*.p12`), and the auto-generated `saml2.yml` / `idp.yml`. Losing this means starting from a blank repository. |
| `/data/secrets` | The AES key that encrypts the keystore passwords in `*.yml`. **Losing this makes the encrypted values in `saml2.yml` / `idp.yml` unrecoverable** — back it up on its own schedule. |

## Zero-configuration SAML

On first boot the bundles do all of the following automatically:

1. `Saml2ServiceProviderConfiguration` creates `etc/saml2.yml` with blank
   `sp.rootURL` and blank `idp.*` fields. The blanks are deliberate.
2. `SpKeyStoreManager` generates `etc/sp-keystore.p12` from the certificate
   template.
3. `IdpConfiguration` creates `etc/idp.yml` with blank `baseUrl` and empty
   `trustedSPs`. Empty `trustedSPs` means "starter mode": the IdP trusts the
   co-located SP automatically.
4. `FileKeyStoreManager` generates `etc/idp-keystore.p12`.
5. Both bundles register OSGi services
   (`LocalIdentityProvider`, `LocalServiceProvider`). When the SP next builds
   its SAML settings the IdP entityId / endpoints / certificate are pulled
   straight from the live IdP service in the same JVM — no manual exchange
   of metadata XML is necessary.
6. `CMS_PUBLIC_BASE_URL` becomes the single source of truth for the external
   hostname; restarting with a new value retargets both SP and IdP.

The auto-created YAML files leave the relevant fields blank intentionally.
If you want to pin a specific value, set it in YAML and it will take
precedence over the OSGi fallback.

## Building the Felix distribution

The Dockerfile assumes the build context already contains `felix-dist/`, a
pre-laid-out Felix runtime including the project's bundles. Wire your build
pipeline (Tycho/Maven/Gradle/etc.) to produce that directory before invoking
`docker build`.

## Building the container image

Once `felix-dist/` exists, build the image with one of the included wrappers
from the repository root.

**Linux / macOS (bash):**

```bash
# Local smoke test: single arch, loaded into the local docker daemon.
./scripts/docker-build.sh -v 1.0.0 -p linux/amd64

# Release build: both arches, tag :1.0.0 + :latest, push as one manifest.
./scripts/docker-build.sh -v 1.0.0 --latest --push
```

**Windows (PowerShell):**

```powershell
.\scripts\docker-build.ps1 -Version 1.0.0 -Platforms linux/amd64
.\scripts\docker-build.ps1 -Version 1.0.0 -Latest -Push
```

Both wrappers call `docker buildx build` and:

- Bootstrap a `docker-container` buildx builder named `cms-builder`.
- Stamp the image with OCI metadata (`org.opencontainers.image.version`,
  `revision`, `created`) derived from git.
- Target both `linux/amd64` and `linux/arm64` by default — see
  "Platform support" below.

A multi-platform build cannot be loaded into the local daemon. With `--push`
the wrappers publish a single multi-arch manifest; without it, the build is
written to `cms-image.tar` (OCI archive). For a quick local smoke test, pass
a single platform — it loads into the local daemon automatically, as shown
above.

If you prefer to call `docker buildx` directly:

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --build-arg IMAGE_VERSION=1.0.0 \
  --build-arg IMAGE_REVISION="$(git rev-parse HEAD)" \
  --build-arg IMAGE_CREATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  -f docker/Dockerfile \
  -t mintjams/cms:1.0.0 \
  --push \
  .
```

## Platform support

The published image is a **multi-arch manifest covering `linux/amd64` and
`linux/arm64`**. Docker automatically pulls the variant matching the host, so
Apple Silicon and arm64 servers run natively — no emulation, no performance
penalty.

Native JavaScript support (V8 via JNI) is delivered by per-architecture
fragment bundles attached to the `org.mintjams.rt.cms` host:

- `org.mintjams.rt.cms.linux.x86_64` — ships `native/linux/x86_64/*.so`
- `org.mintjams.rt.cms.linux.arm64`  — ships `native/linux/arm64/*.so`

Both fragments must be present in `felix-dist/bundle/`. They carry only
native libraries under distinct `native/linux/<arch>/` paths, so shipping both
in one image is harmless: at runtime the host bundle loads the libraries for
the running architecture (resolved from `os.arch`) and ignores the rest. This
is why a single `felix-dist/` yields a working image for both architectures —
the base image (`eclipse-temurin:17-jre`) is itself multi-arch, and `buildx`
selects the matching JRE layer per platform.

> Adding a new architecture is purely additive: build the matching
> `org.mintjams.rt.cms.linux.<arch>` fragment, drop its jar into
> `felix-dist/bundle/`, and append the platform to the `--platform` list. The
> Dockerfile and the runtime loader need no changes.
