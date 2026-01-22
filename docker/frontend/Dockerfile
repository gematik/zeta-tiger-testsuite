# ---------- Build stage: warm up Maven repos & validate the project ----------
FROM maven:3.9.12-eclipse-temurin-21-alpine AS build

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
FROM maven:3.9.12-eclipse-temurin-21-alpine AS execute-tests

WORKDIR /app

# Copy the prepared sources as well as the primed ~/.m2 directory from the build stage.
COPY --from=build /app /app
COPY --from=build /root/.m2/ /root/.m2/
COPY docker/frontend/run-tests.sh /app/run-tests.sh
RUN chmod +x /app/run-tests.sh

# Default runtime toggles; can be overridden via `docker run -e ...`.
ENV ZETA_BASE_URL="" \
    ZETA_PROXY_URL="" \
    ZETA_PROXY="no-proxy" \
    CUCUMBER_TAGS="@smoke" \
    TIGER_ENVIRONMENT="cloud" \
    SERENITY_EXPORT_DIR="" \
    CUCUMBER_EXPORT_DIR=""

# Exec-form entrypoint to preserve signals and args.
ENTRYPOINT ["/app/run-tests.sh"]
