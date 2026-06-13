#!/usr/bin/env bash
# Regenerate the JNI header for NativeEcma into native_src/.
#
# Only needed when the native method signatures in NativeEcma.java change.
# The header is machine-generated and committed; this script keeps it in sync.
#
# `javac -h` must compile NativeEcma.java, so its dependencies have to be on the
# classpath: the rt.cms and tools bundle output dirs plus the OSGi framework
# (Bundle/BundleContext). Provide them via the CLASSPATH env var, or let the
# script auto-discover the common Eclipse/PDE locations.
#
# Env overrides:
#   JAVA_HOME   JDK to use (default: from PATH). 'javac -h' must be available.
#   CLASSPATH   Full classpath for compilation (auto-discovered if unset).
#   RT_CMS_BIN  org.mintjams.rt.cms compiled output (default: <bundles>/org.mintjams.rt.cms/bin)
#   TOOLS_BIN   org.mintjams.tools compiled output (auto-discovered)
#   OSGI_JARS   Colon-separated extra jars (felix.jar, org.osgi.*). Auto-discovered.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"          # .../org.mintjams.rt.cms.linux.x86_64
BUNDLES_DIR="$(cd "${BUNDLE_DIR}/.." && pwd)"          # .../bundles
NATIVE_SRC="${BUNDLE_DIR}/native_src"
JAVA_SRC="${BUNDLES_DIR}/org.mintjams.rt.cms/src/org/mintjams/rt/cms/internal/script/engine/nativeecma/NativeEcma.java"

JAVAC="javac"
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVAC="${JAVA_HOME}/bin/javac"
fi
command -v "${JAVAC}" >/dev/null 2>&1 || { echo "ERROR: javac not found (set JAVA_HOME)." >&2; exit 1; }
[[ -f "${JAVA_SRC}" ]] || { echo "ERROR: source not found: ${JAVA_SRC}" >&2; exit 1; }

# --- Build the classpath ----------------------------------------------------
if [[ -z "${CLASSPATH:-}" ]]; then
    CP_PARTS=()

    RT_CMS_BIN="${RT_CMS_BIN:-${BUNDLES_DIR}/org.mintjams.rt.cms/bin}"
    [[ -d "${RT_CMS_BIN}" ]] && CP_PARTS+=("${RT_CMS_BIN}")

    if [[ -z "${TOOLS_BIN:-}" ]]; then
        # org.mintjams.tools usually lives next to the cms bundles.
        for cand in \
            "${BUNDLES_DIR}/org.mintjams.tools/bin" \
            "${BUNDLES_DIR}/../org.mintjams.tools/bin" \
            "${BUNDLES_DIR}/../../org.mintjams.tools/bin"; do
            [[ -d "${cand}" ]] && { TOOLS_BIN="${cand}"; break; }
        done
    fi
    [[ -n "${TOOLS_BIN:-}" && -d "${TOOLS_BIN}" ]] && CP_PARTS+=("${TOOLS_BIN}")

    if [[ -n "${OSGI_JARS:-}" ]]; then
        CP_PARTS+=("${OSGI_JARS}")
    else
        # Try to locate felix.jar / org.osgi.* under common roots.
        for root in "${FELIX_HOME:-}" "${BUNDLES_DIR}/.." "${HOME}/felix" "${HOME}/.p2"; do
            [[ -n "${root}" && -d "${root}" ]] || continue
            while IFS= read -r jar; do
                CP_PARTS+=("${jar}")
            done < <(find "${root}" -maxdepth 6 \( -name 'felix*.jar' -o -name 'org.osgi.framework*.jar' -o -name 'org.osgi.core*.jar' \) 2>/dev/null | head -5)
            [[ ${#CP_PARTS[@]} -gt 0 ]] && break
        done
    fi

    CLASSPATH="$(IFS=:; echo "${CP_PARTS[*]}")"
fi

if [[ -z "${CLASSPATH}" ]]; then
    cat >&2 <<EOF
ERROR: Could not assemble a classpath for javac -h.
NativeEcma.java references CmsService/Systems (org.mintjams.rt.cms),
IOs (org.mintjams.tools) and Bundle/BundleContext (OSGi framework).

Provide one explicitly, e.g.:
  CLASSPATH="/path/org.mintjams.rt.cms/bin:/path/org.mintjams.tools/bin:/path/felix.jar" \\
    $0
EOF
    exit 1
fi

echo "Using classpath:"
tr ':' '\n' <<<"${CLASSPATH}" | sed 's/^/  /'

# --- Generate header into a temp dir, then sync into native_src --------------
TMP_OUT="$(mktemp -d)"
trap 'rm -rf "${TMP_OUT}"' EXIT

echo "Running javac -h ..."
"${JAVAC}" -h "${TMP_OUT}" -d "${TMP_OUT}" -cp "${CLASSPATH}" "${JAVA_SRC}"

GENERATED="${TMP_OUT}/org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma.h"
[[ -f "${GENERATED}" ]] || { echo "ERROR: header was not generated." >&2; exit 1; }

DEST="${NATIVE_SRC}/org_mintjams_rt_cms_internal_script_engine_nativeecma_NativeEcma.h"
if cmp -s "${GENERATED}" "${DEST}"; then
    echo "OK: header already up to date (${DEST})"
else
    cp "${GENERATED}" "${DEST}"
    echo "OK: header updated (${DEST})"
fi
