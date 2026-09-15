#!/usr/bin/env bash
# Container entrypoint for the MintJams CMS Felix runtime.
#
# Responsibilities:
#  1. Ensure the persistent state directories exist with correct ownership.
#  2. Fail fast if CMS_PUBLIC_BASE_URL is missing — without it the SP/IdP
#     would bind to http://localhost:8080 which is rarely what you want in a
#     container.
#  3. Place the configuration files of the seed into the repository — only
#     the ones that do not exist yet, so an operator's edits are never
#     overwritten. CMS_CLUSTER_ENABLED selects the standalone or the clustered
#     files and, when set, is passed on to the runtime. (The seed's bundled
#     assets are not copied: the runtime applies them from the seed itself.)
#  4. Lock down the secret-key file so the AES key is not world-readable.
#  5. Hand off to the supplied command (typically `java -jar bin/felix.jar`).

set -euo pipefail

REPOSITORY_DIR="${CMS_REPOSITORY_PATH:-/data/repository}"
SECRET_KEY_PATH="${MINTJAMS_CMS_SECRET_KEY_PATH:-/data/secrets/secret-key.yml}"
SECRETS_DIR="$(dirname "${SECRET_KEY_PATH}")"
SEED_DIR="${MINTJAMS_CMS_SEED_PATH:-/opt/cms/seed}"

mkdir -p "${REPOSITORY_DIR}" "${SECRETS_DIR}"

if [[ -z "${CMS_PUBLIC_BASE_URL:-}" ]]; then
	echo "ERROR: CMS_PUBLIC_BASE_URL is not set." >&2
	echo "       Set it to the external URL of this CMS instance" >&2
	echo "       (e.g. http://localhost:8080) before starting the container." >&2
	exit 1
fi

case "${CMS_CLUSTER_ENABLED:-}" in
	""|false)
		CONFIG_VARIANT=standalone
		;;
	true)
		CONFIG_VARIANT=cluster
		;;
	*)
		echo "ERROR: CMS_CLUSTER_ENABLED must be \"true\" or \"false\" (got \"${CMS_CLUSTER_ENABLED}\")." >&2
		exit 1
		;;
esac

# Copies every file under the given directory into the repository, unless a
# file already exists at the same path.
place_missing_files() {
	local source_dir="$1"
	local relative_path
	if [[ ! -d "${source_dir}" ]]; then
		return 0
	fi

	while IFS= read -r -d '' relative_path; do
		relative_path="${relative_path#./}"
		if [[ ! -e "${REPOSITORY_DIR}/${relative_path}" ]]; then
			mkdir -p "$(dirname "${REPOSITORY_DIR}/${relative_path}")"
			cp "${source_dir}/${relative_path}" "${REPOSITORY_DIR}/${relative_path}"
		fi
	done < <(cd "${source_dir}" && find . -type f -print0)
}

# The variant goes first so that it wins over a common file of the same path.
place_missing_files "${SEED_DIR}/config/${CONFIG_VARIANT}"
place_missing_files "${SEED_DIR}/config/common"

if [[ -n "${CMS_CLUSTER_ENABLED:-}" ]]; then
	export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dorg.mintjams.jcr.cluster.enabled=${CMS_CLUSTER_ENABLED}"
fi

# The secret key file is created on first start by FileSecretKeyProvider.
# We pre-create the directory and tighten permissions defensively so it never
# lives even briefly with a permissive umask.
chmod 0700 "${SECRETS_DIR}" || true
if [[ -f "${SECRET_KEY_PATH}" ]]; then
	chmod 0600 "${SECRET_KEY_PATH}" || true
fi

exec "$@"
