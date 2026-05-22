#!/usr/bin/env bash
# Container entrypoint for the MintJams CMS Felix runtime.
#
# Responsibilities:
#  1. Ensure the persistent state directories exist with correct ownership.
#  2. Fail fast if CMS_PUBLIC_BASE_URL is missing — without it the SP/IdP
#     would bind to http://localhost:8080 which is rarely what you want in a
#     container.
#  3. Lock down the secret-key file so the AES key is not world-readable.
#  4. Hand off to the supplied command (typically `java -jar bin/felix.jar`).

set -euo pipefail

REPOSITORY_DIR="${CMS_REPOSITORY_PATH:-/data/repository}"
SECRET_KEY_PATH="${MINTJAMS_CMS_SECRET_KEY_PATH:-/data/secrets/secret-key.yml}"
SECRETS_DIR="$(dirname "${SECRET_KEY_PATH}")"

mkdir -p "${REPOSITORY_DIR}" "${SECRETS_DIR}"

if [[ -z "${CMS_PUBLIC_BASE_URL:-}" ]]; then
	echo "ERROR: CMS_PUBLIC_BASE_URL is not set." >&2
	echo "       Set it to the external URL of this CMS instance" >&2
	echo "       (e.g. http://localhost:8080) before starting the container." >&2
	exit 1
fi

# The secret key file is created on first start by FileSecretKeyProvider.
# We pre-create the directory and tighten permissions defensively so it never
# lives even briefly with a permissive umask.
chmod 0700 "${SECRETS_DIR}" || true
if [[ -f "${SECRET_KEY_PATH}" ]]; then
	chmod 0600 "${SECRET_KEY_PATH}" || true
fi

exec "$@"
