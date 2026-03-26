# syntax=docker/dockerfile:1.7
# ---------- Build stage: package runnable testsuite artifacts ----------
ARG registry=docker.io
FROM $registry/maven:3.9.12-eclipse-temurin-21-alpine AS build

WORKDIR /app
ARG SERENITY_CLI_VERSION=4.1.13
ARG GITLAB_BASE_URL=https://gitlab.com
ARG TESTPROXY_PROJECT_ID=75362233
ENV MAVEN_ARGS="-Dgitlab.base.url=${GITLAB_BASE_URL} -Dtestproxy.project.id=${TESTPROXY_PROJECT_ID}"

# Bring in the workspace.
COPY . .

# Package test-jar + test dependencies without executing the tests.
# Skip source-style checks here because CI already runs them separately and they
# are irrelevant for assembling the runtime image.
RUN --mount=type=secret,id=ci_job_token,required=false \
    . docker/build-common.sh && \
    tiger_export_ci_job_token && \
    mvn -s .m2/settings.xml -B -DskipTests=true -DskipITs=true \
      -Dlicense.skip=true -Dcheckstyle.skip=true \
      -Dmaven.repo.local=/tmp/.m2/repository \
      -Dtraceability.skip=true -Dtraceability.sync.skip=true -Dserenity.readme.sync.skip=true \
      package \
      org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
      -Dartifact=net.serenity-bdd:serenity-cli:${SERENITY_CLI_VERSION} \
      -DoutputDirectory=/app/serenity-cli

RUN . docker/build-common.sh && \
    tiger_verify_gitlab_testproxy_artifacts

# ---------- Runtime stage: run testsuite without Maven ----------
ARG registry=docker.io
FROM $registry/eclipse-temurin:21-jre-alpine AS runtime
ARG SERENITY_CLI_VERSION=4.1.13
ARG ZETA_UID=1000
ARG ZETA_GID=1000

WORKDIR /app

# Copy packaged artifacts.
RUN mkdir -p /app/tools
COPY --from=build /app/target/*-tests.jar /app/tests.jar
COPY --from=build /app/target/deps /app/libs
COPY --from=build /app/target/agent/tiger-java-agent.jar /app/agent/tiger-java-agent.jar
COPY --from=build /app/tiger.yaml /app/tiger.yaml
COPY --from=build /app/tiger-proxy.yaml /app/tiger-proxy.yaml
COPY --from=build /app/tiger /app/tiger
COPY --from=build /app/docs/asciidoc/tables/source/product_implementation.csv /app/docs/asciidoc/tables/source/product_implementation.csv
COPY --from=build /app/docs/asciidoc/testaspekte /app/docs/asciidoc/testaspekte
COPY --from=build /app/tools/tls-test-tool-1.0.1/TlsTestTool-alpine /app/tools/tls-test-tool-1.0.1/TlsTestTool-alpine
COPY --from=build /app/src/test/resources/mocks /app/src/test/resources/mocks
COPY --from=build /app/src/test/resources/keys /app/src/test/resources/keys
COPY --from=build /app/src/test/resources/schemas /app/src/test/resources/schemas
COPY --from=build /app/serenity-cli/serenity-cli-${SERENITY_CLI_VERSION}.jar /app/tools/serenity-cli.jar
COPY --from=build /app/docker/run-tests-common.sh /app/run-tests-common.sh
COPY docker/quality_gate/run-tests.sh /app/run-tests.sh
RUN chmod +x /app/run-tests.sh

# Install kubectl only when the base image does not already provide it.
RUN . /app/run-tests-common.sh && \
    tiger_ensure_kubectl

RUN . /app/run-tests-common.sh && \
    tiger_prepare_nonroot_runtime "zeta" "zeta" "${ZETA_UID}" "${ZETA_GID}" /app /app/target

# Default runtime toggles; can be overridden at `docker run`.
ENV ZETA_BASE_URL="" \
    ZETA_PROXY_URL="" \
    ALLOW_DEPLOYMENT_MODIFICATION="" \
    OPENSEARCH_URL="" \
    PROMETHEUS_URL="" \
    ZETA_TEST_CERTIFICATES_DIR="" \
    PROFILE="" \
    CUCUMBER_TAGS="@smoke" \
    SERENITY_EXPORT_DIR="" \
    CUCUMBER_EXPORT_DIR="" \
    ALLURE_RESULTS_DIR="" \
    HOME="/home/zeta" \
    KUBECONFIG="/home/zeta/.kube/config"

USER zeta

ENTRYPOINT ["/app/run-tests.sh"]
