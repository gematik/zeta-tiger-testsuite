# ---------- Build stage: warm up Maven repos & validate the project ----------
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build

WORKDIR /app

# Copy the main POM to leverage the Docker layer cache for dependency resolution.
COPY pom.xml .

# Download all dependencies/plugins once so later runs can stay offline.
#   -DincludeParents=true            -> fetch parent POMs to avoid re-resolution when modules inherit from them
#   -DincludeDependencies=true       -> pull regular compile/test dependencies into the cache
#   -DincludePluginDependencies=true -> ensure Maven plugins bring their own dependency trees along (e.g. surefire)
#   -DincludePlugins=true            -> download the plugin artifacts themselves, not just their dependencies
#   -DincludeReports=true            -> include reporting plugins (Surefire/Failsafe reports, etc.) used later in CI
RUN mvn -B dependency:go-offline \
      -DincludeParents=true \
      -DincludeDependencies=true \
      -DincludePluginDependencies=true \
      -DincludePlugins=true \
      -DincludeReports=true

# Bring in the rest of the workspace (features, configs, docs, etc.).
COPY . .

# Run a dependency-only build (tests off) to prime the target directory & plugins.
RUN mvn -B -DskipTests=true -DskipITs=true \
    -Dtraceability.skip=true -Dtraceability.sync.skip=true -Dserenity.readme.sync.skip=true \
    verify

# ---------- Runtime stage: executes Maven with the warmed-up repository ----------
FROM maven:3.9.11-eclipse-temurin-21-alpine AS execute-tests

WORKDIR /app

# Copy the prepared sources as well as the primed ~/.m2 directory from the build stage.
COPY --from=build /app /app
COPY --from=build /root/.m2/ /root/.m2/

# Default runtime toggles; can be overridden via `docker run -e ...`.
ENV ZETA_BASE_URL="" \
    ZETA_PROXY_URL="" \
    ZETA_PROXY="no-proxy" \
    CUCUMBER_TAGS="@smoke" \
    TIGER_ENVIRONMENT="cloud" \
    SERENITY_EXPORT_DIR="" \
    FAILSAFE_EXPORT_DIR=""

# Provide a reusable wrapper so CI runners can simply call `/usr/local/bin/run-tests.sh`.
RUN cat <<'EOF' >/usr/local/bin/run-tests.sh && chmod +x /usr/local/bin/run-tests.sh
#!/bin/sh
set -eu

DEFAULT_SERENITY_DIR="/app/target/site/serenity"
DEFAULT_SERENITY_PARENT="$(dirname "${DEFAULT_SERENITY_DIR}")"
DEFAULT_FAILSAFE_DIR="/app/target/failsafe-reports"
DEFAULT_FAILSAFE_PARENT="$(dirname "${DEFAULT_FAILSAFE_DIR}")"

# If SERENITY_EXPORT_DIR is set (e.g. to /builds/.../target/site/serenity inside GitLab CI),
# replace the default output directory with a symlink so Maven writes directly there.
if [ -n "${SERENITY_EXPORT_DIR:-}" ] && [ "${SERENITY_EXPORT_DIR}" != "${DEFAULT_SERENITY_DIR}" ]; then
  mkdir -p "${SERENITY_EXPORT_DIR}"
  mkdir -p "${DEFAULT_SERENITY_PARENT}"
  rm -rf "${DEFAULT_SERENITY_DIR}"
  ln -s "${SERENITY_EXPORT_DIR}" "${DEFAULT_SERENITY_DIR}"
else
  mkdir -p "${DEFAULT_SERENITY_DIR}"
fi

# Do the same for Failsafe reports so CI can collect TEST-*.xml from a mounted path.
if [ -n "${FAILSAFE_EXPORT_DIR:-}" ] && [ "${FAILSAFE_EXPORT_DIR}" != "${DEFAULT_FAILSAFE_DIR}" ]; then
  mkdir -p "${FAILSAFE_EXPORT_DIR}"
  mkdir -p "${DEFAULT_FAILSAFE_PARENT}"
  rm -rf "${DEFAULT_FAILSAFE_DIR}"
  ln -s "${FAILSAFE_EXPORT_DIR}" "${DEFAULT_FAILSAFE_DIR}"
else
  mkdir -p "${DEFAULT_FAILSAFE_DIR}"
fi

set +e
mvn -o -B \
  -Djava.awt.headless=true \
  -Dtiger.lib.activateWorkflowUi=false \
  -Dtiger.lib.startBrowser=false \
  -Dtiger.lib.trafficVisualization=false \
  -Dtiger.lib.rbelAnsiColors=false \
  -Dtiger.lib.runTestsOnStart=true \
  -Dfailsafe.testFailureIgnore=false \
  "-Denvironment=${TIGER_ENVIRONMENT}" \
  "-Dzeta_base_url=${ZETA_BASE_URL}" \
  "-Dzeta_proxy_url=${ZETA_PROXY_URL}" \
  "-Dzeta_proxy=${ZETA_PROXY}" \
  "-Dcucumber.filter.tags=${CUCUMBER_TAGS}" \
  verify
MVN_RESULT=$?
set -e

FAILSAFE_SUMMARY="/app/target/failsafe-reports/failsafe-summary.xml"
if [ ${MVN_RESULT} -eq 0 ] && [ -f "${FAILSAFE_SUMMARY}" ]; then
  if grep -Eq '<failures>[1-9]' "${FAILSAFE_SUMMARY}" || grep -Eq '<errors>[1-9]' "${FAILSAFE_SUMMARY}"; then
    echo "Integration tests failed according to ${FAILSAFE_SUMMARY}." >&2
    exit 1
  fi
fi

exit ${MVN_RESULT}
EOF

# Use /bin/sh -c so custom commands passed to `docker run` still work; default CMD invokes the wrapper.
ENTRYPOINT ["/bin/sh", "-c"]
CMD ["/usr/local/bin/run-tests.sh"]
