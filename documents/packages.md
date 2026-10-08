# Packages — installing applications into a workspace

A **package** is a ZIP file that carries an application or an extension for
cms0: the files to place in a workspace and the provisioning to apply. An
administrator obtains it from its provider and installs it from the Webtop
**Tasks** app; the platform reads it, runs a set of checks, shows what
installing it would do, and installs it when the administrator confirms.
Installing a newer package of the same id upgrades the installation and
removes what the previous version placed and the new one no longer ships.

The platform does not verify a signature on a package and keeps no trust
store. Who to obtain a package from is the administrator's decision, made
when downloading it; the installer unpacks and places what it is given. For
the same reason an installation always starts with an administrator's
action — there is no scheduled or background path that fetches and applies
packages.

Two routes lead to the same installer. The Tasks app route described here
ships with cms0. A vendor's **portal app**, installed once by hand and then
used to browse, download and install that vendor's packages, is a separate
project; it hands the installer the same package file and gets the same
inspection and installation (see
[One installer, two routes](#one-installer-two-routes)).

## Package format (version 1)

```
jp.example.blog-1.2.0.zip
├── package.yml                       the manifest (required, at the root)
├── deploy/                           files mirrored into the workspace
│   └── usr/share/webtop/apps/blog/   → /usr/share/webtop/apps/blog/
│       ├── app.yml
│       └── ...
│   └── usr/local/classes/blog/       → /usr/local/classes/blog/  (hot-deployed)
│   └── etc/graphql/blog/             → /etc/graphql/blog/
└── provisioning/                     descriptors applied at installation
    └── blog.yml                      (the schema of documents/cms-provisioning.md)
```

The layout under `deploy/` is the layout of the repository: an entry
`deploy/usr/share/webtop/apps/blog/app.yml` becomes the file
`/usr/share/webtop/apps/blog/app.yml` in the workspace. This is the same
layout as the image seed (`docker/seed/assets/*/deploy/`) and the
workspace's own `etc/jcr/deploy/`, so an application authored for either is
packaged by zipping its `deploy/` and `provisioning/` folders together with
a manifest. Nothing else may be in the ZIP; directory entries are ignored.

Files are written with the MIME type the repository derives from the file
name. Whatever the repository does with a file at that path — hot-deploying
classes under `/usr/local/classes`, deploying processes under
`/etc/bpm/processes`, serving a Webtop app under `/usr/share/webtop/apps` —
happens for a file a package places, exactly as for a file deployed any other
way.

### The manifest — `package.yml`

```yaml
format: cms-package
formatVersion: 1
id: jp.example.blog              # reverse-DNS, lower case: letters, digits, dots, dashes
version: 1.2.0                   # semantic version; a pre-release such as 1.2.0-beta.1 is allowed
title: Example Blog
description: Posts and pages for the public site.
vendor:
  name: Example Inc.
  url: https://www.example.jp/
requires:
  platform: ">=0.1.30"           # the running cms0 (optional)
  packages:                      # other packages that must be installed (optional)
    jp.example.commons: ">=1.0 <2"
```

`format`, `formatVersion`, `id`, `version` and `title` are required; the
rest is optional. The manifest is kept verbatim in the installation record.
Fields may be added to the format later as optional fields; a required field
is never added to version 1, so every package written against it keeps
installing.

**Version constraints** (`requires.platform`, `requires.packages`) are terms
separated by spaces or commas, each an operator and a version: `>=1.2`,
`>=1.2 <2`, `!=1.3.0`, or a bare version meaning that version exactly. `*`
or an empty constraint accepts every version. Versions compare as semantic
versions: a missing component is zero, a pre-release precedes its release,
and a build suffix (`+…`) is ignored.

### What belongs in a package, and what does not

An upgrade **replaces** the files of the previous version and **removes**
those the new package does not ship. A file a user is meant to edit after
installation therefore does not belong in `deploy/`: it would be overwritten
or removed by the next upgrade. Create such files through a provisioning
descriptor instead — provisioning only creates what is missing, so an
existing, edited file is left alone.

| Content | Where | On upgrade |
| --- | --- | --- |
| The application itself (UI, scripts, routes, processes, classes) | `deploy/` | replaced; files no longer shipped are removed |
| Folders, ACLs, service accounts, namespaces, settings a user edits | `provisioning/` | re-applied; existing items are kept as they are |

## Installation

### What the installer does

1. **Reads** the package once: the manifest, the provisioning descriptors,
   and the name and size of every entry. The ZIP is streamed from the
   repository and never unpacked to disk, so a package's size is bounded by
   the repository, not by the node's temporary directory.
2. **Checks** it (next section). An error finding refuses the package; a
   warning is shown and the installation may go on.
3. Under the cluster lease that content deployment also takes
   (`content-deployment`), so an installation never overlaps a deployment:
   - applies the **provisioning** descriptors (idempotent, as at boot);
   - **places** every file under `deploy/`, streaming it from the ZIP and
     digesting it on the way;
   - **removes** the files of the previous version the package no longer
     ships, and the folders that leaves empty;
   - writes the **installation record**;
   - **commits** all of that in one transaction.

The order is *place, remove, record*. A run that stops halfway leaves old
and new files side by side rather than a workspace without the package, and
running it again converges. A failure rolls the transaction back, so the
previous version stays as it was; the provisioning that already ran is
idempotent and harmless.

### Installation records — `/etc/packages/<id>/`

| File | Content |
| --- | --- |
| `package.yml` | the manifest as it was in the package |
| `MANIFEST` | every file placed, as `sha256  /path` lines (the `sha256sum` format the image seed also records) |
| `install.yml` | `version`, `installedAt`, `installedBy`, `previousVersion`, `source` (the package file name) |

The record is what makes an upgrade exact and what answers "what is
installed in this workspace". `/etc/packages` is writable by administrators
only.

### First installation, upgrade, reinstallation

| Installed version | Package version | Action |
| --- | --- | --- |
| none | any | **install** |
| older | newer | **upgrade**: files placed, files no longer shipped removed |
| same | same | **reinstall**: every file written again, nothing removed |
| newer | older | **refused** (`version.downgrade`) |

A downgrade is refused because the record of the installed version describes
files the older package knows nothing about. To go back, uninstall the
package first (see [Uninstalling](#uninstalling)).

### Installation is per workspace

Every workspace installs its own packages: the Tasks app of a workspace
installs into that workspace, and the records live in that workspace. The
Webtop of each workspace lists the apps under its own
`/usr/share/webtop/apps`, so an app installed into one workspace appears
there only. Provisioned identity (users, groups, roles) is shared by all
workspaces through the `system` workspace, as for every provisioning
descriptor: a package installed into several workspaces has **one** set of
accounts, created by the first installation and reused by the others. This
is by design. The code of an application names its service accounts (a
`runAs` in a process or a route is a fixed name), and one account per role
across the instance is what keeps the accounts of an installation few,
known and reviewable; a set per workspace would multiply accounts that can
never be removed and that nobody watches. Workspaces separate content and
its access control, not the accounts the applications run as. Where the
accounts themselves must be separate, run a separate cms0 instance: that
boundary is complete, and it is the one everybody understands.

## Checks

A package goes through every check in order; all of them run even after one
reported an error, so one inspection lists every problem. Each finding has
a stable code (shown here), a severity, an English message, and where
applicable the package entry or repository path it is about. The confirm
form translates known codes and falls back to the message.

| Check | Finding codes | What it refuses |
| --- | --- | --- |
| Manifest | `manifest.missing`, `manifest.invalid`, `manifest.format`, `manifest.formatVersion`, `manifest.id`, `manifest.version`, `manifest.title`, `manifest.vendor`, `manifest.requires`, `manifest.requires.platform`, `manifest.requires.packages` | a missing or malformed manifest |
| Layout | `layout.path`, `layout.duplicate`, `layout.unexpected`, `layout.provisioning`, `layout.empty` | entries outside `package.yml` / `deploy/` / `provisioning/`, `..` segments, one path placed twice, descriptors not directly under `provisioning/`, a package with nothing to install |
| Platform | `platform.incompatible`; warning `platform.unknown` | a `requires.platform` the running platform does not meet; when the platform does not know its version (outside the container image) the requirement is not checked and a warning says so |
| Dependencies | `dependency.missing`, `dependency.version`, `dependency.self` | a required package that is not installed, or installed in a version the constraint rejects. Nothing is resolved or fetched: install the dependency first |
| Version | `version.downgrade`; warning `version.installedUnknown` | an older version than the installed one |
| Paths | `path.reserved`, `path.bundled`, `path.owned` | files under the areas the platform keeps for itself (`/etc/packages`, `/var/lib/packages`, `/var/seed`, `/var/jobs`, `/home`, `/jcr:system`), over a file the bundled assets of the image own (the applied seed manifest), or over a file another installed package owns |
| Provisioning | `provisioning.invalid`, `provisioning.userPassword`, `provisioning.reservedPath`; warnings `provisioning.empty`, `provisioning.unknownSection` | descriptors that are not mappings of the known sections, entries missing what the provisioner insists on (an id, a password for a non-service user, an absolute node path), nodes under a reserved area |

Two more codes come from reading the package itself: `package.missing`
(no file at the path) and `package.unreadable` (not a ZIP). A check that
fails on its own becomes `check.failed`, so a broken check can never let a
package through.

### Adding a check

Implement `org.mintjams.rt.cms.internal.pkg.PackageCheck` and list it in
`PackageChecks.defaults()`. A check receives an `InspectionContext` — the
package (`getContents()`, `getManifest()`), the workspace session, the
installed package of the same id, the installation records, the platform
version, the paths the seed owns and the paths other packages own — and adds
`Finding`s. It adds nothing when it lacks what it needs (for example no
manifest), and it never throws for a problem in the package. Give new codes
a text in `etc/i18n/packages-forms.*.json` under
`form.system.packages.finding.<code>`.

## Installing from the Tasks app

1. Open the **Tasks** app of the workspace and switch to **Start a process**.
2. Select **Install Package**. Choose the package file (.zip) and continue.
   The form uploads the file to the staging area
   (`/var/lib/packages/incoming/<id>/<file>`) and starts the process. The
   button is shown to administrators only; the server enforces the role
   again at every step.
3. The process reads and checks the package, and a **Confirm Package
   Installation** task appears in your task list. Its form shows the package
   title and version, the workspace, whether this is a new installation, an
   update or a reinstallation, the checks' findings, and under *Details* the
   files it places, the files an update removes and the provisioning
   descriptors it applies. **Install** (or **Update** / **Reinstall**) goes
   ahead; **Cancel** discards the uploaded package. A package with an error
   finding only offers **Close**.
4. On **Install**, the process installs the package and a **Package
   Installation Result** task shows what was done: files created, replaced
   and removed, descriptors applied — or the error when it failed.
   **Complete task** ends the process.

The uploaded package is removed from the staging area on every path:
installed, failed or cancelled.

```
Start form ──▶ Inspect ──▶ Confirm (user task) ──▶ install? ──▶ Install ──▶ Result (user task) ──▶ ●
 (upload)   (asyncBefore)                            │        (asyncBefore)
                 │                                   └── cancel ──▶ Discard ──▶ ●
                 └── unauthorized ──▶ ●
```

Files: `etc/bpm/processes/system/packages/package-install.bpmn`, the
scripts under `etc/bpm/scripts/system/packages/` (`inspect.groovy`,
`install.groovy`, `discard.groovy`), the forms under
`etc/bpm/forms/system/packages/`, the texts in
`etc/i18n/packages-forms.{en,ja}.json`, and `provisioning/packages.yml`.
They are seed assets for every workspace.

### Authorization

- The start form shows its button to administrators only; that is display
  control.
- Each service task runs as `package-service-user` (`runAs`), a service
  account that only needs to read the scripts. Every script validates the
  process **initiator**'s administrator role and throws the
  `packages.install.unauthorized` BPMN error otherwise, which ends the
  process without reading the package.
- `PackageAPI` checks the administrator role once more for the user it acts
  for, then does the work in a service session carrying that user's id. The
  files placed and the installation record therefore name the administrator
  who installed, while the script's own account can do nothing of the sort.
- The staging area `/var/lib/packages` is closed to everyone but
  administrators (an explicit deny, since the root of a workspace grants
  everyone read). `/etc/packages` is writable by administrators only.

## Uninstalling

Uninstalling removes what a package placed, using its record: every file the
`MANIFEST` lists, the folders that leaves empty, and the record itself.
Several packages are uninstalled together, in one transaction, in
dependents-first order (a package before the packages it requires), so no
record ever names a dependency that is already gone.

What stays:

- **Provisioned identity, ACLs and namespaces.** Users, groups and roles are
  shared by every workspace through the `system` workspace, and an ACL or a
  namespace cannot be told apart from one set by hand; none of them is
  touched.
- **Anything the package did not place itself**: the data an application
  wrote, the files its provisioning created. The package format has no
  declaration of such data yet, so nothing of it is removed.
- **A file the image has since taken over.** When a path the package placed
  is now in the applied seed manifest, it belongs to the platform and is left
  in place (reported as a warning).

### Uninstall checks

The same shape as the installation checks: every check runs, an error refuses
the selection, and a new check is added to `UninstallChecks.defaults()` by
implementing `UninstallCheck`. A finding about one package carries that
package's id as its path.

| Check | Finding codes | What it refuses or reports |
| --- | --- | --- |
| Selection | `uninstall.nothingSelected`, `uninstall.notInstalled` | an empty selection; an id that is not installed |
| Required by | `uninstall.requiredBy` | a package that another installed package **outside the selection** requires. Dependents selected along with it are fine: the selection is removed as one |
| Recorded files | warnings `uninstall.noFiles`, `uninstall.bundledPath`, `uninstall.missingFiles` | a record without files (only the record is removed); a file now owned by the platform (left in place); files already gone (skipped) |

### Uninstalling from the Tasks app

1. Open the **Tasks** app of the workspace and switch to **Start a process**.
2. Select **Uninstall Packages**. The form lists the installed packages with
   their version, installation date and the packages that require them.
   Select one or more and continue. The button is shown to administrators
   only; the server enforces the role again at every step.
3. The process checks the selection, and a **Confirm Package
   Uninstallation** task appears in your task list. Its form shows the
   packages, the checks' findings, and under *Details* the files each one
   removes. **Uninstall** goes ahead; **Cancel** ends the process. A
   selection with an error finding only offers **Close**.
4. On **Uninstall**, the process removes the packages and a **Package
   Uninstallation Result** task shows, per package, the files removed, the
   files that were already gone and the files left to the platform — or the
   error when it failed. **Complete task** ends the process.

```
Start form ──▶ Inspect ──▶ Confirm (user task) ──▶ uninstall? ──▶ Uninstall ──▶ Result (user task) ──▶ ●
 (select)   (asyncBefore)                            │           (asyncBefore)
                 │                                   └── cancel ──▶ ●
                 └── unauthorized ──▶ ●
```

Files: `package-uninstall.bpmn`, `uninstall-inspect.groovy`,
`uninstall.groovy`, the `uninstall-*.html` forms, in the same folders as
the installation process. The start form lists the packages through the
GraphQL query below.

### `Query.installedPackages`

```graphql
query {
  installedPackages {
    id title version installedAt installedBy previousVersion sourceFileName
    fileCount
    requires { id constraint }
    requiredBy
  }
}
```

The installation records of the workspace, sorted by id, read as the caller.
`requires` comes from each package's recorded manifest; `requiredBy` lists
the installed packages that require it. A portal app lists installed packages
through the same query.

## One installer, two routes

Every route uses the same installer, so every installation is inspected by
the same checks and recorded in the same place, and "what is installed in
this workspace" has one answer.

**From a script** (BPMN service tasks, EIP routes), `PackageAPI` is in the
binding:

| Method | Returns |
| --- | --- |
| `PackageAPI.inspect(path, userId)` | the inspection as plain data: `ok`, `action` (`install` / `upgrade` / `reinstall` / null), `manifest`, `installedVersion`, `platformVersion`, `findings[]` (`severity`, `code`, `message`, `path`), `fileCount`, `totalSize`, `deployPaths[]`, `removedPaths[]`, `provisioning[]` |
| `PackageAPI.install(path, userId)` | the result: `action`, `id`, `title`, `version`, `previousVersion`, `created`, `updated`, `removed`, `provisioningDescriptors`, `durationMillis`; throws when a check reports an error |
| `PackageAPI.discard(path, userId)` | removes a staged package (under `/var/lib/packages/incoming` only) |
| `PackageAPI.inspectUninstall(ids, userId)` | the inspection as plain data: `ok`, `packages[]` (each record plus `requiredBy[]` and `files[]`), `order[]`, `fileCount`, `findings[]`; `ids` is a collection, an array or one comma-separated string |
| `PackageAPI.uninstall(ids, userId)` | the result: `packages[]` (`id`, `title`, `version`, `removed`, `missing`, `kept`), the totals, `durationMillis`; throws when a check reports an error |
| `PackageAPI.listInstalled()`, `PackageAPI.getInstalled(id)` | the installation records as plain data |
| `PackageAPI.getStagingRoot()`, `PackageAPI.getPlatformVersion()` | the staging area; the running version, or null when unknown |

`userId` is the administrator the call acts for; a user without the
administrator role is refused.

**From Java** (the GraphQL resolvers a portal app will call),
`org.mintjams.rt.cms.internal.pkg.PackageInstaller` on a privileged
`org.mintjams.script.resource.Session` of the target workspace offers the
same `inspect(path)`, `install(path, installedBy)`, `discard(path)`,
`inspectUninstall(ids)` and `uninstall(ids, uninstalledBy)`;
`PackageRecords` reads the records, and `Query.installedPackages` already
lists them. A portal app's resolver uploads or fetches the package into the
staging area, shows its own confirmation from the inspection, and calls the
installer. Exposing the installer's own operations through GraphQL is part
of the portal app work.

## Not yet

- **Data declarations**: a package naming the data it writes outside its
  files (and whether an uninstallation may remove it), so that uninstalling
  can offer to remove it. Today everything a package did not place itself
  stays.
- **Portal app** and its GraphQL operations (a separate project).

Not planned: choosing the names of a package's accounts at installation
time. The accounts are shared across workspaces on purpose (see
[Installation is per workspace](#installation-is-per-workspace)).
