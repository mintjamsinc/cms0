<#
.SYNOPSIS
    Build (and optionally push) the MintJams CMS Docker image via buildx.

.DESCRIPTION
    Wraps `docker buildx build` with sensible defaults: derives OCI metadata
    from git, targets linux/amd64 only (arm64 is blocked by a native bundle —
    see docker/README.md), and bootstraps a docker-container buildx builder
    so we are ready to add platforms later without reshaping the workflow.

    The script expects felix-dist/ to already exist in the repo root. Build
    that first via your usual workflow (Eclipse PDE export + manual layout).

.PARAMETER Version
    Image version tag. Defaults to `git describe --tags --always --dirty`,
    falling back to "0.0.0-dev".

.PARAMETER ImageName
    Image name without tag. Defaults to "mintjams/cms".

.PARAMETER Platforms
    Comma-separated platform list. Defaults to "linux/amd64".

.PARAMETER Push
    Push the built image to its registry. Without this flag the image is
    loaded into the local docker daemon (`--load`) for testing.

.PARAMETER Latest
    Also tag the image as ":latest". Use only for clean release builds.

.EXAMPLE
    # Local build for smoke testing
    .\scripts\docker-build.ps1 -Version 1.0.0

.EXAMPLE
    # Release build: tag 1.0.0 + latest and push to DockerHub
    .\scripts\docker-build.ps1 -Version 1.0.0 -Latest -Push
#>

[CmdletBinding()]
param(
    [string]$Version,
    [string]$ImageName = "mintjams/cms",
    [string]$Platforms = "linux/amd64",
    [switch]$Push,
    [switch]$Latest,
    [string]$BuilderName = "cms-builder"
)

$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $RepoRoot

if (-not (Test-Path "felix-dist")) {
    Write-Error "felix-dist/ not found at $RepoRoot. Build it before running this script."
    exit 1
}

if (-not $Version) {
    $Version = (git describe --tags --always --dirty 2>$null)
    if (-not $Version) { $Version = "0.0.0-dev" }
}

$Revision = (git rev-parse HEAD 2>$null)
if (-not $Revision) { $Revision = "unknown" }

$Created = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")

# Bootstrap a docker-container builder so we are multi-platform ready.
docker buildx inspect $BuilderName 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Creating buildx builder '$BuilderName'..."
    docker buildx create --name $BuilderName --driver docker-container --use | Out-Null
} else {
    docker buildx use $BuilderName | Out-Null
}

$buildArgs = @(
    "--platform", $Platforms,
    "--build-arg", "IMAGE_VERSION=$Version",
    "--build-arg", "IMAGE_REVISION=$Revision",
    "--build-arg", "IMAGE_CREATED=$Created",
    "-f", "docker/Dockerfile",
    "--tag", "${ImageName}:${Version}"
)
if ($Latest) {
    $buildArgs += @("--tag", "${ImageName}:latest")
}

if ($Push) {
    $buildArgs += "--push"
} elseif ($Platforms -match ",") {
    # Multi-platform builds cannot --load; fall back to an OCI archive.
    $buildArgs += "--output=type=oci,dest=cms-image.tar"
} else {
    $buildArgs += "--load"
}

$buildArgs += "."

Write-Host ""
Write-Host "Building ${ImageName}:${Version}"
Write-Host "  revision=$Revision"
Write-Host "  platforms=$Platforms"
Write-Host "  push=$Push"
Write-Host "  latest=$Latest"
Write-Host ""

& docker buildx build @buildArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "OK: ${ImageName}:${Version}"
