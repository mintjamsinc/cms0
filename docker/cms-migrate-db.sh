#!/usr/bin/env bash
# Moves the workspace databases from the embedded H2 files to PostgreSQL,
# into the databases jcr.yml / bpm.yml name. Run it with the CMS stopped:
#
#   docker compose stop cms
#   docker compose run --rm cms cms-migrate-db --dry-run
#   docker compose run --rm cms cms-migrate-db
#
# See documents/database-migration.md.

set -euo pipefail

FELIX_HOME="${FELIX_HOME:-/opt/felix}"
REPOSITORY_DIR="${CMS_REPOSITORY_PATH:-/data/repository}"

classpath=""
for jar in "${FELIX_HOME}"/bundle/org.mintjams.cms_*.jar "${FELIX_HOME}"/bundle/snakeyaml-engine-*.jar; do
	if [[ ! -f "${jar}" ]]; then
		echo "ERROR: ${jar} not found. Is FELIX_HOME (${FELIX_HOME}) correct?" >&2
		exit 1
	fi
	classpath="${classpath:+${classpath}:}${jar}"
done

# The repository path is passed the way the entrypoint passes it to the
# server, so ${secrets.KEY} reads the same etc/secrets.yml.
exec java -cp "${classpath}" -Dorg.mintjams.jcr.repository.rootdir="${REPOSITORY_DIR}" \
	org.mintjams.cms.tools.DatabaseMigrationTool --felix-home "${FELIX_HOME}" "$@"
