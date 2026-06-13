#!/usr/bin/env bash
# Compile and link libnativeecma.so against a V8 shared build.
#
# Uses rpath=$ORIGIN so libnativeecma.so finds its V8 deps in the same folder
# at load time (deploy_natives.sh lays them out together). See ../README.md
# section 4.
#
# This is the single source of truth for the native build; arm64 is produced by
# cross-compiling from an x86_64 host (TARGET_ARCH=arm64) using V8's bundled
# clang and the arm64 sysroot fetched into the V8 checkout.
#
# IMPORTANT — C++ standard library ABI must match V8's:
#   A V8 built with use_custom_libcxx=true exposes std types in the inline
#   namespace __Cr (e.g. std::__Cr::unique_ptr). Its public API includes
#     v8::platform::NewDefaultPlatform(..., std::unique_ptr<TracingController>, ...)
#   which takes a std::unique_ptr BY VALUE, so the namespace is part of the
#   mangled symbol name. Compiling this against the system libstdc++ (plain
#   std::) produces a different name and the .so fails to LOAD with:
#     undefined symbol: v8::platform::NewDefaultPlatform(..., std::unique_ptr<...>, ...)
#   (the link still succeeds because shared libs allow undefined symbols.)
#   So when V8 ships its own libc++ (libc++.so in the out dir) we compile this
#   against V8's own libc++ headers and its __config_site (which carries the
#   ABI knobs, incl. _LIBCPP_ABI_NAMESPACE=__Cr), making our std:: ABI identical.
#
# Env overrides:
#   TARGET_ARCH  x86_64 | arm64 (default: x86_64). arm64 cross-compiles.
#   V8_DIR     V8 checkout dir (default: ~/v8).
#   V8_OUT     gn out dir, relative to V8_DIR (default: out/shared for x86_64,
#              out/<arch> otherwise).
#   SYSROOT    arm64 sysroot (default: auto-detected under V8_DIR/build/linux).
#   V8_LIBCXX_INCLUDE / V8_LIBCXXABI_INCLUDE / V8_LIBCXX_CONFIG
#              Override the auto-detected V8 libc++ header / abi-header /
#              __config_site dirs (only used when V8 ships libc++.so).
#   V8_EMBEDDER_DEFINES
#              Override the V8 build-config macros (pointer compression,
#              sandbox, ...) passed to match V8. Space-separated -D... flags;
#              defaults are read from V8 via gn, else a standard set.
#   JAVA_HOME  JDK providing jni.h (required).
#   TOOLCHAIN  clang++ bin dir (default: V8's bundled LLVM).
#   CXX        compiler (default: ${TOOLCHAIN}/clang++).
#   OUT        output .so path (default: <bundle>/build/<arch>/libnativeecma.so).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_SRC="${BUNDLE_DIR}/native_src"
CPP="${NATIVE_SRC}/org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma.cpp"

TARGET_ARCH="${TARGET_ARCH:-x86_64}"
case "${TARGET_ARCH}" in
    x86_64) DEFAULT_V8_OUT="out/shared" ;;
    arm64)  DEFAULT_V8_OUT="out/arm64" ;;
    *) echo "ERROR: unsupported TARGET_ARCH '${TARGET_ARCH}' (x86_64|arm64)." >&2; exit 1 ;;
esac

V8_DIR="${V8_DIR:-${HOME}/v8}"
V8_OUT_REL="${V8_OUT:-${DEFAULT_V8_OUT}}"
V8_OUT_ABS="${V8_DIR}/${V8_OUT_REL}"
TOOLCHAIN="${TOOLCHAIN:-${V8_DIR}/third_party/llvm-build/Release+Asserts/bin}"
CXX="${CXX:-${TOOLCHAIN}/clang++}"
OUT="${OUT:-${BUNDLE_DIR}/build/${TARGET_ARCH}/libnativeecma.so}"

[[ -f "${CPP}" ]] || { echo "ERROR: native source not found: ${CPP}" >&2; exit 1; }
[[ -n "${JAVA_HOME:-}" ]] || { echo "ERROR: JAVA_HOME is required (for jni.h)." >&2; exit 1; }
[[ -d "${JAVA_HOME}/include" ]] || { echo "ERROR: ${JAVA_HOME}/include not found." >&2; exit 1; }
[[ -d "${V8_DIR}/include" ]] || { echo "ERROR: V8 headers not found at ${V8_DIR}/include. Build V8 first (build_v8_shared.sh)." >&2; exit 1; }
[[ -d "${V8_OUT_ABS}" ]] || { echo "ERROR: V8 build not found at ${V8_OUT_ABS}. Run build_v8_shared.sh (TARGET_ARCH=${TARGET_ARCH})." >&2; exit 1; }
command -v "${CXX}" >/dev/null 2>&1 || { echo "ERROR: compiler not found: ${CXX} (set TOOLCHAIN or CXX)." >&2; exit 1; }

# Cross-compilation flags for non-host targets (empty for x86_64).
CROSS_FLAGS=()
if [[ "${TARGET_ARCH}" == "arm64" ]]; then
    SYSROOT="${SYSROOT:-$(ls -d "${V8_DIR}"/build/linux/*arm64-sysroot 2>/dev/null | head -1)}"
    [[ -n "${SYSROOT}" && -d "${SYSROOT}" ]] || {
        echo "ERROR: arm64 sysroot not found under ${V8_DIR}/build/linux." >&2
        echo "       Set SYSROOT, or run in the V8 checkout:" >&2
        echo "         python3 build/linux/sysroot_scripts/install-sysroot.py --arch=arm64" >&2
        exit 1
    }
    CROSS_FLAGS=( --target=aarch64-linux-gnu --sysroot="${SYSROOT}" )
fi

# Match V8's C++ standard library. When V8 ships its own libc++ (libc++.so in
# the out dir), compile this against V8's libc++ headers AND its __config_site
# (which defines _LIBCPP_ABI_NAMESPACE=__Cr and the other ABI knobs), so our
# std:: ABI is byte-for-byte identical to libv8's — see the header note above.
# Override the auto-detected dirs with V8_LIBCXX_INCLUDE / V8_LIBCXXABI_INCLUDE
# / V8_LIBCXX_CONFIG if your V8 layout differs.
STDLIB_FLAGS=()
if [[ -f "${V8_OUT_ABS}/libc++.so" ]]; then
    libcxx_inc=""
    for d in "${V8_LIBCXX_INCLUDE:-}" \
             "${V8_DIR}/third_party/libc++/src/include" \
             "${V8_DIR}/buildtools/third_party/libc++/trunk/include"; do
        [[ -n "$d" && -f "$d/__config" ]] && { libcxx_inc="$d"; break; }
    done
    libcxxabi_inc=""
    for d in "${V8_LIBCXXABI_INCLUDE:-}" \
             "${V8_DIR}/third_party/libc++abi/src/include" \
             "${V8_DIR}/buildtools/third_party/libc++abi/trunk/include"; do
        [[ -n "$d" && -f "$d/cxxabi.h" ]] && { libcxxabi_inc="$d"; break; }
    done
    # __config_site carries V8's ABI knobs incl. _LIBCPP_ABI_NAMESPACE=__Cr.
    config_site_dir=""
    for d in "${V8_LIBCXX_CONFIG:-}" \
             "${V8_DIR}/buildtools/third_party/libc++" \
             "${V8_DIR}/third_party/libc++/src/include" \
             "${V8_DIR}/third_party/libc++"; do
        [[ -n "$d" && -f "$d/__config_site" ]] && { config_site_dir="$d"; break; }
    done

    [[ -n "${libcxx_inc}" ]] || {
        echo "ERROR: V8 ships a custom libc++ (libc++.so present) but its libc++ headers" >&2
        echo "       were not found. Set V8_LIBCXX_INCLUDE to the dir containing <__config>" >&2
        echo "       (e.g. \$V8_DIR/third_party/libc++/src/include), or rebuild V8 with" >&2
        echo "       use_custom_libcxx=false to drop libc++ entirely." >&2
        exit 1
    }

    STDLIB_FLAGS=( -nostdinc++ )
    [[ -n "${config_site_dir}" ]] && STDLIB_FLAGS+=( -isystem "${config_site_dir}" )
    STDLIB_FLAGS+=( -isystem "${libcxx_inc}" )
    [[ -n "${libcxxabi_inc}" ]] && STDLIB_FLAGS+=( -isystem "${libcxxabi_inc}" )

    # __config_site supplies the consumer-facing ABI knobs (namespace __Cr, ABI
    # version, ...). The one extra knob V8 sets via -D on the command line — and
    # that <__config> errors without — is the hardening mode. It does NOT affect
    # the ABI or mangled names (only runtime asserts), and NONE/FAST/EXTENSIVE
    # share identical layouts, so a fixed default is safe and lets us avoid
    # scanning the (huge) V8 out tree. Override LIBCPP_HARDENING_MODE to match
    # V8 exactly if you prefer (e.g. _LIBCPP_HARDENING_MODE_EXTENSIVE).
    HARDENING="${LIBCPP_HARDENING_MODE:-_LIBCPP_HARDENING_MODE_NONE}"
    STDLIB_FLAGS+=( "-D_LIBCPP_HARDENING_MODE=${HARDENING}" )
    # If no __config_site was found, force the ABI namespace V8 uses so the
    # mangled std:: names line up.
    [[ -z "${config_site_dir}" ]] && STDLIB_FLAGS+=( -D_LIBCPP_ABI_NAMESPACE="${LIBCPP_ABI_NAMESPACE:-__Cr}" )
    STDLIB_FLAGS+=( -stdlib=libc++ )

    echo "Matching V8 custom libc++ ABI:"
    echo "  headers:     ${libcxx_inc}"
    [[ -n "${libcxxabi_inc}" ]] && echo "  abi headers: ${libcxxabi_inc}"
    [[ -n "${config_site_dir}" ]] && echo "  __config_site: ${config_site_dir}"
    echo "  hardening:   ${HARDENING}"
fi

# V8 build-configuration macros that the EMBEDDER must match, or V8::Initialize
# aborts with "Embedder-vs-V8 build configuration mismatch" (pointer
# compression, sandbox, SMI size, cppgc heap cage, ...). v8.h reads these at
# compile time; if we don't define what V8 was built with, the build configs
# disagree. Read V8's own values via gn (clean 'defines' output, run from the
# V8 root); fall back to a standard component-build set; full override via
# V8_EMBEDDER_DEFINES (space-separated -D... flags).
V8CFG_FLAGS=()
if [[ -n "${V8_EMBEDDER_DEFINES:-}" ]]; then
    # shellcheck disable=SC2206
    V8CFG_FLAGS=( ${V8_EMBEDDER_DEFINES} )
else
    if command -v gn >/dev/null 2>&1; then
        _v8d=()
        mapfile -t _v8d < <(
            ( cd "${V8_DIR}" && gn desc "${V8_OUT_REL}" //:v8 defines 2>/dev/null ) \
            | grep -E '^(V8_COMPRESS_POINTERS|V8_31BIT_SMIS_ON_64BIT_ARCH|V8_ENABLE_SANDBOX|V8_EXTERNAL_CODE_SPACE|V8_ENABLE_LEAPTIERING|CPPGC_)'
        )
        for d in "${_v8d[@]}"; do V8CFG_FLAGS+=( "-D${d}" ); done
    fi
    if [[ ${#V8CFG_FLAGS[@]} -eq 0 ]]; then
        # Defaults for a standard x64/arm64 component build (build_v8_shared.sh
        # does not override these): pointer compression + shared cage + sandbox
        # + external code space + leaptiering + cppgc caged heap are all on.
        V8CFG_FLAGS=(
            -DV8_COMPRESS_POINTERS
            -DV8_COMPRESS_POINTERS_IN_SHARED_CAGE
            -DV8_31BIT_SMIS_ON_64BIT_ARCH
            -DV8_ENABLE_SANDBOX
            -DV8_EXTERNAL_CODE_SPACE
            -DV8_ENABLE_LEAPTIERING
            -DCPPGC_CAGED_HEAP
            -DCPPGC_YOUNG_GENERATION
            -DCPPGC_POINTER_COMPRESSION
            -DCPPGC_ENABLE_LARGER_CAGE
            -DCPPGC_SLIM_WRITE_BARRIER
        )
        echo "NOTE: gn unavailable/empty; using default V8 embedder defines. If V8::Initialize" >&2
        echo "      still reports a build-config mismatch, set V8_EMBEDDER_DEFINES to match args.gn." >&2
    fi
fi
echo "V8 build-config defines: ${V8CFG_FLAGS[*]}"

mkdir -p "$(dirname "${OUT}")"

echo "Compiling ${OUT}"
echo "  arch: ${TARGET_ARCH}"
echo "  V8:   ${V8_OUT_ABS}"
echo "  CXX:  ${CXX}"
[[ "${TARGET_ARCH}" == "arm64" ]] && echo "  sysroot: ${SYSROOT}"

"${CXX}" -std=c++20 -fPIC \
    ${CROSS_FLAGS[@]+"${CROSS_FLAGS[@]}"} \
    ${STDLIB_FLAGS[@]+"${STDLIB_FLAGS[@]}"} \
    ${V8CFG_FLAGS[@]+"${V8CFG_FLAGS[@]}"} \
    -I"${V8_DIR}/include" \
    -I"${JAVA_HOME}/include" \
    -I"${JAVA_HOME}/include/linux" \
    -I"${NATIVE_SRC}" \
    -shared "${CPP}" \
    -L"${V8_OUT_ABS}" \
    -lv8 -lv8_libplatform -lv8_libbase \
    -pthread -ldl -fuse-ld=lld \
    -Wl,-rpath,'$ORIGIN' -Wl,-z,origin \
    -o "${OUT}"

echo
echo "OK: ${OUT}"
readelf -d "${OUT}" | grep -E 'RPATH|RUNPATH|NEEDED' || true

# Guard against the exact failure this matching prevents: any remaining
# undefined V8 symbol means the ABI still doesn't line up.
if command -v nm >/dev/null 2>&1; then
    if nm -D -u "${OUT}" 2>/dev/null | grep -q 'NewDefaultPlatform'; then
        und="$(nm -D -u "${OUT}" | grep 'NewDefaultPlatform' | head -1)"
        prov="$(nm -D --defined-only "${V8_OUT_ABS}/libv8_libplatform.so" 2>/dev/null | grep 'NewDefaultPlatform' | head -1)"
        if [[ "${und#*NewDefaultPlatform}" != "${prov#*NewDefaultPlatform}" ]]; then
            echo "WARNING: NewDefaultPlatform symbol does not match libv8_libplatform.so —" >&2
            echo "         the C++ stdlib ABI is still mismatched (would fail to load)." >&2
        fi
    fi
fi
