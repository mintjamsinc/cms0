# syntax=docker/dockerfile:1.6
#
# MintJams CMS - Apache Felix runtime container
#
# This image expects a pre-built Felix distribution at ./felix-dist/ relative
# to the build context (i.e. the directory you point `docker build` at).
# The "felix-dist" folder must include:
#   - bin/felix.jar
#   - conf/config.properties
#   - bundle/  (auto-deploy bundles, including this project's bundles)
#
# Two named volumes are expected at runtime:
#   /data/repository  - JCR repository, etc/*.yml, *.p12 keystores (persistent)
#   /data/secrets     - secret-key.yml (persistent, restricted permissions)
#
# Two environment variables drive the SAML configuration:
#   CMS_PUBLIC_BASE_URL              - external HTTP URL (e.g. http://localhost:8080)
#   MINTJAMS_CMS_SECRET_KEY_PATH     - path to the encryption key file

ARG JAVA_VERSION=17
FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime

ARG FELIX_USER=felix
ARG FELIX_UID=1000
ARG FELIX_GID=1000

# Image metadata. Override from CI to stamp a release:
#   --build-arg IMAGE_VERSION=$(cat VERSION)
#   --build-arg IMAGE_REVISION=$(git rev-parse HEAD)
#   --build-arg IMAGE_CREATED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
ARG IMAGE_VERSION=0.0.0-dev
ARG IMAGE_REVISION=unknown
ARG IMAGE_CREATED=
ARG IMAGE_SOURCE=https://github.com/mintjamsinc/cms0-dev

LABEL org.opencontainers.image.title="MintJams CMS" \
      org.opencontainers.image.description="MintJams CMS runtime on Apache Felix with zero-configuration SAML." \
      org.opencontainers.image.vendor="MintJams Inc." \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.source="${IMAGE_SOURCE}" \
      org.opencontainers.image.url="${IMAGE_SOURCE}" \
      org.opencontainers.image.documentation="${IMAGE_SOURCE}/blob/main/docker/README.md" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${IMAGE_REVISION}" \
      org.opencontainers.image.created="${IMAGE_CREATED}"

# eclipse-temurin:17-jre ships with a default user at UID/GID 1000 (currently
# named "ubuntu"). Remove anyone occupying our target UID/GID before creating
# felix, so file ownership matches typical host filesystems on bind mounts.
RUN set -e; \
    if getent passwd ${FELIX_UID} > /dev/null; then \
        userdel -r "$(getent passwd ${FELIX_UID} | cut -d: -f1)" 2>/dev/null || true; \
    fi; \
    if getent group ${FELIX_GID} > /dev/null; then \
        groupdel "$(getent group ${FELIX_GID} | cut -d: -f1)" 2>/dev/null || true; \
    fi; \
    groupadd --gid ${FELIX_GID} ${FELIX_USER}; \
    useradd --uid ${FELIX_UID} --gid ${FELIX_GID} --create-home --shell /bin/bash ${FELIX_USER}

ENV FELIX_HOME=/opt/felix \
    CMS_REPOSITORY_PATH=/data/repository \
    MINTJAMS_CMS_SECRET_KEY_PATH=/data/secrets/secret-key.yml \
    JAVA_TOOL_OPTIONS="-Dorg.mintjams.jcr.repository.rootdir=/data/repository \
                        -Dmintjams.cms.secret-key.path=/data/secrets/secret-key.yml \
                        -XX:+UseG1GC \
                        -XX:+UseContainerSupport \
                        -XX:MaxRAMPercentage=75.0"

# /data/secrets is empty in the image; secret-key.yml is created on first run
# by FileSecretKeyProvider. Only the directory needs to exist with felix ownership.
RUN mkdir -p /data/secrets \
 && chown ${FELIX_USER}:${FELIX_USER} /data /data/secrets

# Seed content for /data/repository (initial JCR nodes, keystores, etc.).
# Docker copies this into a named volume only when the volume is first created
# and empty; bind mounts or pre-existing volumes will hide this content.
COPY --chown=${FELIX_USER}:${FELIX_USER} docker/initial-repository/ /data/repository/

# WORKDIR creates ${FELIX_HOME} as root if it doesn't exist; chown so the
# felix user can later create runtime dirs (felix-cache, log, ...) here.
RUN mkdir -p ${FELIX_HOME} && chown ${FELIX_USER}:${FELIX_USER} ${FELIX_HOME}
WORKDIR ${FELIX_HOME}

# Pre-built Felix distribution is laid down by the build context.
COPY --chown=${FELIX_USER}:${FELIX_USER} felix-dist/ ${FELIX_HOME}/

# Third-party license inventory for the JARs shipped under ${FELIX_HOME}/bundle/.
COPY --chown=${FELIX_USER}:${FELIX_USER} docker/THIRD_PARTY_LICENSES.md ${FELIX_HOME}/THIRD_PARTY_LICENSES.md

COPY --chown=root:root docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh

# Volumes for state that must survive container recreation.
VOLUME ["/data/repository", "/data/secrets"]

EXPOSE 8080

USER ${FELIX_USER}

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["java", "-jar", "bin/felix.jar"]
