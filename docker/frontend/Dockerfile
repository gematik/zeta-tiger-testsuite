# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e
ARG CI_DEPENDENCY_PROXY_GROUP_IMAGE_PREFIX=docker.io

# ---------- Build stage: warm up Maven repos & validate the project ----------
FROM ${CI_DEPENDENCY_PROXY_GROUP_IMAGE_PREFIX}/library/maven:3.9.12-eclipse-temurin-21-alpine@sha256:8b2f036477a5bc9fbeb16cfb7301c484d7fff727b1c4907301ac665526bd7a8e AS build

WORKDIR /app
ARG GITLAB_BASE_URL=https://gitlab.com
ARG TESTPROXY_PROJECT_ID=75362233
ENV MAVEN_ARGS="-Dgitlab.base.url=${GITLAB_BASE_URL} -Dtestproxy.project.id=${TESTPROXY_PROJECT_ID}"

# Copy the main POM to leverage the Docker layer cache for dependency resolution.
COPY pom.xml .
COPY .mvn .mvn
COPY .m2/settings.xml .m2/settings.xml
COPY docker/build-common.sh docker/build-common.sh

# Download all dependencies/plugins once so later runs can stay offline.
#   -DincludeParents=true                   -> fetch parent POMs to avoid re-resolution when modules inherit from them
#   -DincludeDependencies=true              -> pull regular compile/test dependencies into the cache
#   -Dmaven.repo.local=/tmp/.m2/repository  -> set local m2 repository path
#   -DincludePluginDependencies=true        -> ensure Maven plugins bring their own dependency trees along (e.g. surefire)
#   -DincludePlugins=true                   -> download the plugin artifacts themselves, not just their dependencies
#   -DincludeReports=true                   -> include reporting plugins (Surefire/Failsafe reports, etc.) used later in CI
RUN --mount=type=secret,id=ci_job_token,required=false \
    . docker/build-common.sh && \
    tiger_export_ci_job_token && \
    mvn -s .m2/settings.xml -B dependency:go-offline \
      -DincludeParents=true \
      -DincludeDependencies=true \
      -Dmaven.repo.local=/tmp/.m2/repository \
      -DincludePluginDependencies=true \
      -DincludePlugins=true \
      -DincludeReports=true

# Bring in the rest of the workspace (features, configs, docs, etc.).
COPY . .

# Run a dependency-only build (tests off) to prime the target directory & plugins.
# Skip source-style checks here because CI already runs them separately and they
# are irrelevant for assembling the runtime image.
RUN --mount=type=secret,id=ci_job_token,required=false \
    . docker/build-common.sh && \
    tiger_export_ci_job_token && \
    mvn -s .m2/settings.xml -B -DskipTests=true -DskipITs=true \
      -Dmaven.repo.local=/tmp/.m2/repository \
      verify

RUN . docker/build-common.sh && \
    tiger_verify_gitlab_testproxy_artifacts

# ---------- Runtime stage: executes Maven with the warmed-up repository ----------
FROM ${CI_DEPENDENCY_PROXY_GROUP_IMAGE_PREFIX}/library/maven:3.9.12-eclipse-temurin-21-alpine@sha256:8b2f036477a5bc9fbeb16cfb7301c484d7fff727b1c4907301ac665526bd7a8e AS execute-tests

WORKDIR /app
ARG GITLAB_BASE_URL=https://gitlab.com
ARG TESTPROXY_PROJECT_ID=75362233
ARG ZETA_UID=1000
ARG ZETA_GID=1000
ENV MAVEN_ARGS="-Dgitlab.base.url=${GITLAB_BASE_URL} -Dtestproxy.project.id=${TESTPROXY_PROJECT_ID}"

# Copy the prepared sources as well as the primed ~/.m2 directory from the build stage.
COPY --from=build /app /app
COPY --from=build /tmp/.m2/ /tmp/.m2/
COPY --from=build /app/docker/run-tests-common.sh /app/run-tests-common.sh
COPY docker/frontend/run-tests.sh /app/run-tests.sh
RUN chmod +x /app/run-tests.sh

# Install kubectl only when the base image does not already provide it.
RUN . /app/run-tests-common.sh && \
    tiger_ensure_kubectl

RUN . /app/run-tests-common.sh && \
    tiger_prepare_nonroot_runtime "zeta" "zeta" "${ZETA_UID}" "${ZETA_GID}" /app /tmp/.m2 /app/target

# Default runtime toggles; can be overridden via `docker run -e ...`.
ENV ZETA_BASE_URL="" \
    ZETA_PROXY_URL="" \
    ALLOW_DEPLOYMENT_MODIFICATION="" \
    OPENSEARCH_URL="" \
    PROMETHEUS_URL="" \
    ZETA_TEST_CERTIFICATES_DIR="" \
    PROFILE="" \
    CUCUMBER_TAGS="" \
    TPM_ENVIRONMENT="" \
    SERENITY_EXPORT_DIR="" \
    CUCUMBER_EXPORT_DIR="" \
    ALLURE_RESULTS_DIR="" \
    HOME="/home/zeta" \
    KUBECONFIG="/home/zeta/.kube/config"

ARG CI_COMMIT_SHA=unknown
LABEL org.opencontainers.image.revision="${CI_COMMIT_SHA}"

USER zeta

ENTRYPOINT ["/app/run-tests.sh"]
