#!/usr/bin/env bash
# Build V8 as a shared/component build (.so) for the native ECMA engine.
#
# Why component build: linking the V8 monolith (.a) into the JNI .so triggers
# TLS relocation errors (R_X86_64_TPOFF32); the shared build is stable.
# See ../README.md sections 1-2.
#
# This is the heavy step (tens of GB, 16GB+ RAM). It is intentionally separate
# from the day-to-day JNI rebuild (build_nativeecma.sh) so you only run it when
# bumping the V8 revision.
#
# Requires depot_tools on PATH (fetch, gclient, gn, autoninja).
#
# Env overrides:
#   TARGET_ARCH x86_64 | arm64 (default: x86_64). arm64 cross-compiles from an
#               x86_64 host (no arm64 hardware needed to build).
#   V8_DIR    V8 checkout dir (default: ~/v8). Created via `fetch v8` if missing.
#   V8_OUT    gn out dir, relative to V8_DIR (default: out/shared for x86_64,
#             out/<arch> otherwise).
#   V8_COMMIT Pin V8 to this commit/tag (recommended for reproducibility).
#   JOBS      autoninja parallelism (default: autoninja default). Set 1 on OOM.

set -euo pipefail

TARGET_ARCH="${TARGET_ARCH:-x86_64}"
case "${TARGET_ARCH}" in
    x86_64) TARGET_CPU="x64";   DEFAULT_V8_OUT="out/shared" ;;
    arm64)  TARGET_CPU="arm64"; DEFAULT_V8_OUT="out/arm64" ;;
    *) echo "ERROR: unsupported TARGET_ARCH '${TARGET_ARCH}' (x86_64|arm64)." >&2; exit 1 ;;
esac

V8_DIR="${V8_DIR:-${HOME}/v8}"
V8_OUT="${V8_OUT:-${DEFAULT_V8_OUT}}"
JOBS="${JOBS:-}"

# GN args for a stable shared build (see README section 2). Override GN_ARGS to
# customize, but keep is_component_build=true.
GN_ARGS="${GN_ARGS:-
  is_debug=false
  target_cpu=\"${TARGET_CPU}\"
  is_component_build=true
  v8_use_external_startup_data=false
  v8_enable_i18n_support=false
  v8_enable_webassembly=false
  use_custom_libcxx=true
  symbol_level=0
}"

usage() {
    cat <<EOF
Usage: $0 [--no-sync]
  --no-sync   Skip 'fetch v8' / 'gclient sync' (use the existing checkout).
  -h, --help  Show this help.

Env: TARGET_ARCH (=${TARGET_ARCH}), V8_DIR (=${V8_DIR}), V8_OUT (=${V8_OUT}), JOBS, GN_ARGS
EOF
}

DO_SYNC=true
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-sync) DO_SYNC=false; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 1 ;;
    esac
done

for tool in gn autoninja; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "ERROR: '$tool' not found on PATH. Install depot_tools first (see ../README.md section 0)." >&2
        exit 1
    }
done

if [[ "${DO_SYNC}" == "true" ]]; then
    # `fetch v8` always lays the checkout down as '<parent>/v8' (and writes a
    # .gclient in the parent that references the solution dir 'v8'), regardless
    # of V8_DIR's basename. Normalize V8_DIR to that real location so every
    # downstream path (gn, sync, the caller's copy steps) matches.
    if [[ "$(basename "${V8_DIR}")" != "v8" ]]; then
        V8_DIR="$(dirname "${V8_DIR}")/v8"
        echo "Normalized V8_DIR to ${V8_DIR} (fetch always creates a 'v8' dir)."
    fi
    if [[ ! -d "${V8_DIR}" ]]; then
        command -v fetch >/dev/null 2>&1 || { echo "ERROR: 'fetch' (depot_tools) not found." >&2; exit 1; }
        echo "Fetching V8 into ${V8_DIR} ..."
        mkdir -p "$(dirname "${V8_DIR}")"
        ( cd "$(dirname "${V8_DIR}")" && fetch v8 )
    fi
    if [[ -n "${V8_COMMIT:-}" ]]; then
        echo "Pinning V8 to ${V8_COMMIT} ..."
        ( cd "${V8_DIR}" && git fetch --tags origin "${V8_COMMIT}" 2>/dev/null || git fetch --tags origin )
        ( cd "${V8_DIR}" && git checkout --detach "${V8_COMMIT}" )
    fi
    echo "Syncing V8 dependencies ..."
    ( cd "${V8_DIR}" && gclient sync -D )
fi

[[ -d "${V8_DIR}" ]] || { echo "ERROR: V8_DIR '${V8_DIR}' does not exist. Run without --no-sync first." >&2; exit 1; }

cd "${V8_DIR}"

# Cross builds need the target sysroot. The Debian sysroots ship with the V8
# checkout; install the one for the target arch (no-op if already present).
if [[ "${TARGET_ARCH}" == "arm64" && -f build/linux/sysroot_scripts/install-sysroot.py ]]; then
    echo "Ensuring arm64 sysroot ..."
    python3 build/linux/sysroot_scripts/install-sysroot.py --arch=arm64 || true
fi

echo "Configuring gn (${V8_OUT}) ..."
gn clean "${V8_OUT}" 2>/dev/null || true
gn gen "${V8_OUT}" --args="${GN_ARGS}"

NINJA_ARGS=(-C "${V8_OUT}")
[[ -n "${JOBS}" ]] && NINJA_ARGS+=(-j "${JOBS}")

echo "Building V8 (v8 v8_libplatform) ..."
autoninja "${NINJA_ARGS[@]}" v8 v8_libplatform

# Record provenance for reproducibility (README section 10).
gn args "${V8_OUT}" --list >"${V8_OUT}/args.used.txt" 2>/dev/null || true
git -C "${V8_DIR}" rev-parse HEAD >"${V8_OUT}/V8_COMMIT.txt" 2>/dev/null || true

echo
echo "OK: V8 shared build at ${V8_DIR}/${V8_OUT}"
echo "    commit: $(cat "${V8_OUT}/V8_COMMIT.txt" 2>/dev/null || echo unknown)"
ls -1 "${V8_OUT}"/*.so 2>/dev/null || true
