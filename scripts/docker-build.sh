#!/usr/bin/env bash
# Build (and optionally push) the MintJams CMS Docker image via buildx.
#
# Wraps `docker buildx build` with sensible defaults: derives OCI metadata
# from git, targets linux/amd64 only (arm64 is blocked by a native bundle —
# see docker/README.md), and bootstraps a docker-container buildx builder
# so we are ready to add platforms later without reshaping the workflow.
#
# Expects felix-dist/ to already exist in the repo root. Build that first
# via your usual workflow (Eclipse PDE export + manual layout).

set -euo pipefail

IMAGE_NAME="${IMAGE_NAME:-mintjams/cms}"
PLATFORMS="${PLATFORMS:-linux/amd64}"
BUILDER_NAME="${BUILDER_NAME:-cms-builder}"
VERSION=""
PUSH=false
LATEST=false

usage() {
    cat <<EOF
Usage: $0 [options]
  -v, --version VERSION    Image version tag. Defaults to git describe.
  -n, --name NAME          Image name (default: ${IMAGE_NAME}).
  -p, --platforms LIST     Comma-separated platforms (default: ${PLATFORMS}).
      --push               Push to registry. Default loads into local daemon.
      --latest             Also tag the image as :latest.
  -h, --help               Show this help.

Examples:
  $0 -v 1.0.0                       # local build for smoke testing
  $0 -v 1.0.0 --latest --push       # release build, tag :1.0.0 + :latest, push
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -v|--version)   VERSION="$2"; shift 2 ;;
        -n|--name)      IMAGE_NAME="$2"; shift 2 ;;
        -p|--platforms) PLATFORMS="$2"; shift 2 ;;
        --push)         PUSH=true; shift ;;
        --latest)       LATEST=true; shift ;;
        -h|--help)      usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

if [[ ! -d "felix-dist" ]]; then
    echo "ERROR: felix-dist/ not found at ${REPO_ROOT}. Build it before running this script." >&2
    exit 1
fi

if [[ -z "${VERSION}" ]]; then
    VERSION="$(git describe --tags --always --dirty 2>/dev/null || echo "0.0.0-dev")"
fi

REVISION="$(git rev-parse HEAD 2>/dev/null || echo "unknown")"
CREATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# Bootstrap a docker-container builder so we are multi-platform ready.
if ! docker buildx inspect "${BUILDER_NAME}" >/dev/null 2>&1; then
    echo "Creating buildx builder '${BUILDER_NAME}'..."
    docker buildx create --name "${BUILDER_NAME}" --driver docker-container --use >/dev/null
else
    docker buildx use "${BUILDER_NAME}" >/dev/null
fi

BUILD_ARGS=(
    --platform "${PLATFORMS}"
    --build-arg "IMAGE_VERSION=${VERSION}"
    --build-arg "IMAGE_REVISION=${REVISION}"
    --build-arg "IMAGE_CREATED=${CREATED}"
    -f docker/Dockerfile
    --tag "${IMAGE_NAME}:${VERSION}"
)
if [[ "${LATEST}" == "true" ]]; then
    BUILD_ARGS+=(--tag "${IMAGE_NAME}:latest")
fi

if [[ "${PUSH}" == "true" ]]; then
    BUILD_ARGS+=(--push)
elif [[ "${PLATFORMS}" == *,* ]]; then
    # Multi-platform builds cannot --load; fall back to an OCI archive.
    BUILD_ARGS+=(--output=type=oci,dest=cms-image.tar)
else
    BUILD_ARGS+=(--load)
fi

BUILD_ARGS+=(.)

echo
echo "Building ${IMAGE_NAME}:${VERSION}"
echo "  revision=${REVISION}"
echo "  platforms=${PLATFORMS}"
echo "  push=${PUSH}"
echo "  latest=${LATEST}"
echo

docker buildx build "${BUILD_ARGS[@]}"

echo
echo "OK: ${IMAGE_NAME}:${VERSION}"
