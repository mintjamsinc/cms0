<#
.SYNOPSIS
    Lay down the Webtop in docker/seed before building the Docker image.

.DESCRIPTION
    The seed's assets/ tree is applied to every workspace on every start of
    the container (see docker/README.md). This script fills in the parts that
    are build output rather than sources, from one built Webtop:

      assets/system/deploy/usr/share/webtop/     the Webtop with every app
      assets/workspace/deploy/usr/share/webtop/  the Webtop for the other
                                                 workspaces, without
                                                 $SystemOnlyApps
      assets/workspace/deploy/etc/i18n/          the global message bundles,
                                                 copied from assets/system

    Build the Webtop first (cd webtop; npm run build:prod).

.PARAMETER WebtopDist
    Built Webtop directory. Defaults to "webtop/dist/webtop".

.EXAMPLE
    .\scripts\assemble-seed.ps1
#>

[CmdletBinding()]
param(
    [string]$WebtopDist = "webtop/dist/webtop"
)

$ErrorActionPreference = "Stop"

# Apps that only make sense in the system workspace.
$SystemOnlyApps = @("workspace-manager")

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $RepoRoot

if (-not (Test-Path (Join-Path $WebtopDist "webtop.js"))) {
    Write-Error "No built Webtop at $WebtopDist. Build it first (cd webtop; npm run build:prod)."
    exit 1
}

$SeedAssets = "docker/seed/assets"

# Replaces the destination directory with a copy of the source directory.
function Copy-Tree([string]$Source, [string]$Destination) {
    if (Test-Path $Destination) {
        Remove-Item -Recurse -Force $Destination
    }

    if (Test-Path $Source -PathType Container) {
        New-Item -ItemType Directory -Force $Destination | Out-Null
        Copy-Item -Recurse -Force -Path (Join-Path $Source "*") -Destination $Destination
    } else {
        $parent = Split-Path $Destination -Parent
        if ($parent -and -not (Test-Path $parent)) {
            New-Item -ItemType Directory -Force $parent | Out-Null
        }
        Copy-Item -Force -Path $Source -Destination $Destination
    }
}

Copy-Tree $WebtopDist "$SeedAssets/system/deploy/usr/share/webtop"
Copy-Tree $WebtopDist "$SeedAssets/workspace/deploy/usr/share/webtop"
foreach ($app in $SystemOnlyApps) {
    $appPath = "$SeedAssets/workspace/deploy/usr/share/webtop/apps/$app"
    if (Test-Path $appPath) {
        Remove-Item -Recurse -Force $appPath
    }
}
Copy-Tree "$SeedAssets/system/deploy/etc/i18n" "$SeedAssets/workspace/deploy/etc/i18n"
# webtop
Copy-Tree "$SeedAssets/system/deploy/content/WEB-INF/web.yml" "$SeedAssets/workspace/deploy/content/WEB-INF/web.yml"
Copy-Tree "$SeedAssets/system/provisioning/webtop.yml" "$SeedAssets/workspace/provisioning/webtop.yml"
# content browser app: Server-side assets
Copy-Tree "$SeedAssets/system/deploy/etc/eip/routes/webtop/media-metadata.xml" "$SeedAssets/workspace/deploy/etc/eip/routes/webtop/media-metadata.xml"
# mail app: Server-side assets
Copy-Tree "$SeedAssets/system/deploy/etc/eip/routes/webtop/mail.xml" "$SeedAssets/workspace/deploy/etc/eip/routes/webtop/mail.xml"
Copy-Tree "$SeedAssets/system/deploy/etc/graphql/webtop/mail" "$SeedAssets/workspace/deploy/etc/graphql/webtop/mail"
Copy-Tree "$SeedAssets/system/deploy/usr/local/classes/webtop/mail" "$SeedAssets/workspace/deploy/usr/local/classes/webtop/mail"
Copy-Tree "$SeedAssets/system/provisioning/mail.yml" "$SeedAssets/workspace/provisioning/mail.yml"

$systemFiles = (Get-ChildItem -Recurse -File "$SeedAssets/system").Count
$workspaceFiles = (Get-ChildItem -Recurse -File "$SeedAssets/workspace").Count
Write-Host "OK: seed assets laid down from $WebtopDist"
Write-Host "  system:    $systemFiles files"
Write-Host "  workspace: $workspaceFiles files"
