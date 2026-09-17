#!/usr/bin/env bash
# Encrypts a value for a configuration file, so that a password never has to
# be written into a shared jcr.yml / bpm.yml in the clear:
#
#   docker exec -i cms cms-encrypt
#   <the value, on one line>
#   ENC[v1:...]
#
# The value is read from standard input, never from an argument. The secret
# key (MINTJAMS_CMS_SECRET_KEY_PATH) is created when it does not exist yet, so
# this also works before the first start of a new installation — which is how
# a clustered deployment gets an encrypted database password into its shared
# configuration ahead of its first node.

set -euo pipefail

FELIX_HOME="${FELIX_HOME:-/opt/felix}"

classpath=""
for jar in "${FELIX_HOME}"/bundle/org.mintjams.cms_*.jar "${FELIX_HOME}"/bundle/snakeyaml-engine-*.jar; do
	if [[ ! -f "${jar}" ]]; then
		echo "ERROR: ${jar} not found. Is FELIX_HOME (${FELIX_HOME}) correct?" >&2
		exit 1
	fi
	classpath="${classpath:+${classpath}:}${jar}"
done

exec java -cp "${classpath}" org.mintjams.cms.security.EncryptTool "$@"
