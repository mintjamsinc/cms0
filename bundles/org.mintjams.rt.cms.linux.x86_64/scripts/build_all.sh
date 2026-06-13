#!/usr/bin/env bash
# One-shot orchestration for rebuilding the native ECMA engine.
#
# Default flow (assumes V8 is already built once via build_v8_shared.sh):
#   1. gen_jni_header.sh   (only matters when native signatures changed)
#   2. build_nativeecma.sh (compile + link libnativeecma.so)
#   3. deploy_natives.sh   (lay out .so set into native/linux/<arch>)
#
# Use --with-v8 to also run the heavy V8 build first.
# Use --skip-header to skip header regeneration (e.g. no Java toolchain handy;
# the committed header already matches the current signatures).
#
# Set TARGET_ARCH=arm64 to cross-build the arm64 bundle from an x86_64 host
# (the JNI header is arch-independent, so --skip-header is fine there). All
# step-specific env vars (TARGET_ARCH, V8_DIR, V8_OUT, JAVA_HOME, CLASSPATH, ...)
# are passed straight through to the sub-scripts.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

WITH_V8=false
SKIP_HEADER=false

usage() {
    cat <<EOF
Usage: $0 [--with-v8] [--skip-header]
  --with-v8       Build V8 (build_v8_shared.sh) before the JNI build. Slow.
  --skip-header   Skip JNI header regeneration (gen_jni_header.sh).
  -h, --help      Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --with-v8)     WITH_V8=true; shift ;;
        --skip-header) SKIP_HEADER=true; shift ;;
        -h|--help)     usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 1 ;;
    esac
done

if [[ "${WITH_V8}" == "true" ]]; then
    echo "==> [1/4] Building V8 (shared)"
    "${SCRIPT_DIR}/build_v8_shared.sh"
fi

if [[ "${SKIP_HEADER}" == "true" ]]; then
    echo "==> [skip] JNI header regeneration"
else
    echo "==> [2/4] Regenerating JNI header"
    "${SCRIPT_DIR}/gen_jni_header.sh"
fi

echo "==> [3/4] Building libnativeecma.so"
"${SCRIPT_DIR}/build_nativeecma.sh"

echo "==> [4/4] Deploying native libraries"
"${SCRIPT_DIR}/deploy_natives.sh"

echo
echo "OK: native ECMA engine rebuilt and deployed."
echo "    Commit the updated native/linux/x86_64/*.so to publish the fix."
