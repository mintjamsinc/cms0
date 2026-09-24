#!/usr/bin/env bash
# Lay down the Webtop in docker/seed before building the Docker image.
#
# The seed's assets/ tree is applied to every workspace on every start of the
# container (see docker/README.md). This script fills in the parts that are
# build output rather than sources, from one built Webtop:
#   assets/system/deploy/usr/share/webtop/     the Webtop with every app
#   assets/workspace/deploy/usr/share/webtop/  the Webtop for the other workspaces,
#                                              without SYSTEM_ONLY_APPS
#   assets/workspace/deploy/etc/i18n/          the global message bundles, copied
#                                              from assets/system
#
# Build the Webtop first (cd webtop && npm run build:prod).

set -euo pipefail

# Apps that only make sense in the system workspace.
SYSTEM_ONLY_APPS=(workspace-manager)

WEBTOP_DIST="webtop/dist/webtop"

usage() {
    cat <<EOF
Usage: $0 [options]
  -w, --webtop-dist DIR    Built Webtop (default: ${WEBTOP_DIST}).
  -h, --help               Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -w|--webtop-dist) WEBTOP_DIST="$2"; shift 2 ;;
        -h|--help)        usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

if [[ ! -f "${WEBTOP_DIST}/webtop.js" ]]; then
    echo "ERROR: no built Webtop at ${WEBTOP_DIST}. Build it first (cd webtop && npm run build:prod)." >&2
    exit 1
fi

SEED_ASSETS="docker/seed/assets"

# Replaces the destination directory with a copy of the source directory.
copy_tree() {
    rm -rf "$2"

    if [ -d "$1" ]; then
        mkdir -p "$2"
        cp -R "$1/." "$2/"
    else
        mkdir -p "$(dirname "$2")"
        cp "$1" "$2"
    fi
}

copy_tree "${WEBTOP_DIST}" "${SEED_ASSETS}/system/deploy/usr/share/webtop"
copy_tree "${WEBTOP_DIST}" "${SEED_ASSETS}/workspace/deploy/usr/share/webtop"
for app in "${SYSTEM_ONLY_APPS[@]}"; do
    rm -rf "${SEED_ASSETS}/workspace/deploy/usr/share/webtop/apps/${app}"
done
copy_tree "${SEED_ASSETS}/system/deploy/etc/i18n" "${SEED_ASSETS}/workspace/deploy/etc/i18n"
# webtop
copy_tree "${SEED_ASSETS}/system/deploy/content/WEB-INF/web.yml" "${SEED_ASSETS}/workspace/deploy/content/WEB-INF/web.yml"
copy_tree "${SEED_ASSETS}/system/provisioning/webtop.yml" "${SEED_ASSETS}/workspace/provisioning/webtop.yml"
# content browser app: Server-side assets
copy_tree "${SEED_ASSETS}/system/deploy/etc/eip/routes/webtop/media-metadata.xml" "${SEED_ASSETS}/workspace/deploy/etc/eip/routes/webtop/media-metadata.xml"
# mail app: Server-side assets
copy_tree "${SEED_ASSETS}/system/deploy/etc/eip/routes/webtop/mail.xml" "${SEED_ASSETS}/workspace/deploy/etc/eip/routes/webtop/mail.xml"
copy_tree "${SEED_ASSETS}/system/deploy/etc/graphql/webtop/mail" "${SEED_ASSETS}/workspace/deploy/etc/graphql/webtop/mail"
copy_tree "${SEED_ASSETS}/system/deploy/usr/local/classes/webtop/mail" "${SEED_ASSETS}/workspace/deploy/usr/local/classes/webtop/mail"
copy_tree "${SEED_ASSETS}/system/provisioning/mail.yml" "${SEED_ASSETS}/workspace/provisioning/mail.yml"

echo "OK: seed assets laid down from ${WEBTOP_DIST}"
echo "  system:    $(find "${SEED_ASSETS}/system" -type f | wc -l) files"
echo "  workspace: $(find "${SEED_ASSETS}/workspace" -type f | wc -l) files"
