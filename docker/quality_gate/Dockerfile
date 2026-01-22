# ---------- Build stage: package runnable testsuite artifacts ----------
FROM maven:3.9.12-eclipse-temurin-21-alpine AS build

WORKDIR /app
ARG SERENITY_CLI_VERSION=4.1.13

# Bring in the workspace.
COPY . .

# Package test-jar + test dependencies without executing the tests.
RUN mvn -B -DskipTests=true -DskipITs=true \
    -Dtraceability.skip=true -Dtraceability.sync.skip=true -Dserenity.readme.sync.skip=true \
    package \
    org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
    -Dartifact=net.serenity-bdd:serenity-cli:${SERENITY_CLI_VERSION} \
    -DoutputDirectory=/app/serenity-cli

# ---------- Runtime stage: run testsuite without Maven ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
ARG SERENITY_CLI_VERSION=4.1.13

WORKDIR /app

# Copy packaged artifacts.
RUN mkdir -p /app/tools
COPY --from=build /app/target/*-tests.jar /app/tests.jar
COPY --from=build /app/target/deps /app/libs
COPY --from=build /app/target/agent/tiger-java-agent.jar /app/agent/tiger-java-agent.jar
COPY --from=build /app/tiger.yaml /app/tiger.yaml
COPY --from=build /app/tiger /app/tiger
COPY --from=build /app/src/test/resources/mocks /app/src/test/resources/mocks
COPY --from=build /app/src/test/resources/keys /app/src/test/resources/keys
COPY --from=build /app/src/test/resources/schemas /app/src/test/resources/schemas
COPY --from=build /app/serenity-cli/serenity-cli-${SERENITY_CLI_VERSION}.jar /app/tools/serenity-cli.jar
COPY docker/quality_gate/run-tests.sh /app/run-tests.sh
RUN chmod +x /app/run-tests.sh

# Default runtime toggles; can be overridden at `docker run`.
ENV ZETA_BASE_URL="" \
    ZETA_PROXY_URL="" \
    ZETA_PROXY="no-proxy" \
    CUCUMBER_TAGS="@smoke" \
    TIGER_ENVIRONMENT="cloud" \
    SERENITY_EXPORT_DIR="" \
    CUCUMBER_EXPORT_DIR=""

ENTRYPOINT ["/app/run-tests.sh"]
