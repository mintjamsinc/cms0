#!/usr/bin/env bash
# Lay out libnativeecma.so together with the exact set of V8 runtime .so files
# it needs into the matching bundle's native/linux/<arch>/ folder.
#
# The runtime set is derived from the binary itself: starting from
# libnativeecma.so's DT_NEEDED entries, every dependency that exists in the V8
# shared build is copied, transitively. This avoids copying system libs and
# keeps the committed set minimal and correct (no guesswork).
#
# Env overrides:
#   TARGET_ARCH  x86_64 | arm64 (default: x86_64). Selects the destination
#                bundle (org.mintjams.rt.cms.linux.<arch>) and native subdir.
#   V8_DIR    V8 checkout dir (default: ~/v8).
#   V8_OUT    gn out dir, relative to V8_DIR (default: out/shared for x86_64,
#             out/<arch> otherwise).
#   SO        libnativeecma.so to deploy (default: <bundle>/build/<arch>/libnativeecma.so).
#   DEST      target dir (default: <bundles>/org.mintjams.rt.cms.linux.<arch>/native/linux/<arch>).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BUNDLES_DIR="$(cd "${BUNDLE_DIR}/.." && pwd)"

TARGET_ARCH="${TARGET_ARCH:-x86_64}"
case "${TARGET_ARCH}" in
    x86_64) DEFAULT_V8_OUT="out/shared" ;;
    arm64)  DEFAULT_V8_OUT="out/arm64" ;;
    *) echo "ERROR: unsupported TARGET_ARCH '${TARGET_ARCH}' (x86_64|arm64)." >&2; exit 1 ;;
esac

V8_DIR="${V8_DIR:-${HOME}/v8}"
V8_OUT_ABS="${V8_DIR}/${V8_OUT:-${DEFAULT_V8_OUT}}"
SO="${SO:-${BUNDLE_DIR}/build/${TARGET_ARCH}/libnativeecma.so}"
DEST="${DEST:-${BUNDLES_DIR}/org.mintjams.rt.cms.linux.${TARGET_ARCH}/native/linux/${TARGET_ARCH}}"

[[ -f "${SO}" ]] || { echo "ERROR: ${SO} not found. Run build_nativeecma.sh first (TARGET_ARCH=${TARGET_ARCH})." >&2; exit 1; }
[[ -d "${V8_OUT_ABS}" ]] || { echo "ERROR: V8 build not found at ${V8_OUT_ABS}." >&2; exit 1; }
command -v readelf >/dev/null 2>&1 || { echo "ERROR: readelf (binutils) is required." >&2; exit 1; }

needed_of() {
    # Print DT_NEEDED soname entries of an ELF file, one per line.
    readelf -d "$1" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'
}

mkdir -p "${DEST}"

# Transitive closure over the V8 out dir, seeded by libnativeecma.so.
declare -A copied=()
queue=("${SO}")

# Always deploy libnativeecma.so itself.
cp -f "${SO}" "${DEST}/libnativeecma.so"
copied["libnativeecma.so"]=1

while [[ ${#queue[@]} -gt 0 ]]; do
    cur="${queue[0]}"; queue=("${queue[@]:1}")
    while IFS= read -r soname; do
        [[ -z "${soname}" ]] && continue
        [[ -n "${copied[${soname}]:-}" ]] && continue
        src="${V8_OUT_ABS}/${soname}"
        if [[ -f "${src}" ]]; then
            cp -f "${src}" "${DEST}/${soname}"
            copied["${soname}"]=1
            queue+=("${src}")
        fi
    done < <(needed_of "${cur}")
done

echo "Deployed (${TARGET_ARCH}) to ${DEST}:"
for f in "${!copied[@]}"; do echo "  ${f}"; done | sort

echo
if [[ "${TARGET_ARCH}" == "x86_64" ]]; then
    echo "Verifying with ldd (look for 'not found') ..."
    ( cd "${DEST}" && LD_LIBRARY_PATH="${DEST}" ldd ./libnativeecma.so 2>/dev/null | grep -E 'not found' ) && {
        echo "WARNING: unresolved dependencies above. A system lib may be missing on the target." >&2
    } || echo "OK: all dependencies resolve within ${DEST} (plus system libs)."
else
    # ldd can't introspect a foreign-arch ELF on an x86_64 host; check DT_NEEDED
    # against what we deployed instead.
    echo "Cross-arch (${TARGET_ARCH}); skipping ldd. Verifying DT_NEEDED coverage ..."
    missing=0
    while IFS= read -r soname; do
        [[ -z "${soname}" ]] && continue
        case "${soname}" in
            libc.so.*|libm.so.*|libdl.so.*|libpthread.so.*|librt.so.*|ld-linux-*|libgcc_s.so.*|libstdc++.so.*) continue ;;
        esac
        if [[ ! -f "${DEST}/${soname}" ]]; then
            echo "  WARNING: ${soname} is NEEDED but not deployed and not a system lib." >&2
            missing=1
        fi
    done < <(needed_of "${DEST}/libnativeecma.so")
    [[ "${missing}" -eq 0 ]] && echo "OK: all non-system DT_NEEDED libraries are present in ${DEST}."
fi
