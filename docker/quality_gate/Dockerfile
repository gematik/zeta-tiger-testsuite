# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e
ARG CI_DEPENDENCY_PROXY_GROUP_IMAGE_PREFIX=docker.io

# ---------- Build stage: package runnable testsuite artifacts ----------
FROM ${CI_DEPENDENCY_PROXY_GROUP_IMAGE_PREFIX}/library/maven:3.9.12-eclipse-temurin-21-alpine@sha256:8b2f036477a5bc9fbeb16cfb7301c484d7fff727b1c4907301ac665526bd7a8e AS build

WORKDIR /app
ARG GITLAB_BASE_URL=https://gitlab.com
ARG TESTPROXY_PROJECT_ID=75362233
ENV MAVEN_ARGS="-Dgitlab.base.url=${GITLAB_BASE_URL} -Dtestproxy.project.id=${TESTPROXY_PROJECT_ID}"

# Cache Maven dependencies independently of source and runtime files.
COPY pom.xml .
COPY .mvn .mvn
COPY .m2/settings.xml .m2/settings.xml
COPY docker/build-common.sh docker/build-common.sh

RUN --mount=type=secret,id=ci_job_token,required=false \
    rm -rf /tmp/.m2/repository/de/gematik/test && \
    . docker/build-common.sh && \
    tiger_export_ci_job_token && \
    mvn -s .m2/settings.xml -B -Pdistribution \
      -Dmaven.repo.local=/tmp/.m2/repository \
      dependency:go-offline \
      dependency:copy@copy-tiger-agent \
      dependency:copy@copy-serenity-cli && \
    tiger_verify_gitlab_testproxy_artifacts

COPY src src
COPY config config

# Package test-jar + test dependencies without executing the tests.
# Skip source-style checks here because CI already runs them separately and they
# are irrelevant for assembling the runtime image.
RUN --mount=type=secret,id=ci_job_token,required=false \
    . docker/build-common.sh && \
    tiger_export_ci_job_token && \
    mvn -s .m2/settings.xml -B -Pdistribution -DskipTests=true -DskipITs=true \
      -Dlicense.skip=true -Dlicense.skipAddThirdParty=true -Dcheckstyle.skip=true \
      -Dmaven.repo.local=/tmp/.m2/repository \
      package && \
    tiger_verify_gitlab_testproxy_artifacts

# Add documentation, configuration and certificate fixtures after packaging.
COPY . .
ARG REQUIRE_TEST_PLAN=false
RUN if [ "${REQUIRE_TEST_PLAN}" = "true" ]; then test -s docs/Testplan_ZETA.html; fi

RUN mkdir -p /runtime/app/agent /runtime/app/docs/asciidoc/tables/source \
      /runtime/app/docs/specifications/2.0.0-cc/derived \
      /runtime/app/src/test /runtime/app/tools /runtime/app/target && \
    if [ -s /app/docs/Testplan_ZETA.html ]; then \
      cp /app/docs/Testplan_ZETA.html /runtime/app/docs/Testplan_ZETA.html; \
    fi && \
    cp /app/target/*-tests.jar /runtime/app/tests.jar && \
    cp -r /app/target/deps /runtime/app/libs && \
    cp -r /app/.cache/zeta-test-certificates /runtime/app/zeta-test-certificates && \
    cp /app/target/agent/tiger-java-agent.jar /runtime/app/agent/tiger-java-agent.jar && \
    cp /app/tiger*.yaml /app/serenity.properties /runtime/app/ && \
    cp -r /app/tiger /runtime/app/tiger && \
    cp /app/docs/asciidoc/tables/source/runtime_failure_annotations.internal.csv \
      /runtime/app/docs/asciidoc/tables/source/ && \
    cp -r /app/docs/asciidoc/afos /runtime/app/docs/asciidoc/afos && \
    cp /app/docs/specifications/2.0.0-cc/derived/gemVZ_Afo_ZETA_Guard_V_2.0.0-0_V1.0.0.xml \
      /runtime/app/docs/specifications/2.0.0-cc/derived/ && \
    cp -r /app/docs/asciidoc/testaspekte /runtime/app/docs/asciidoc/testaspekte && \
    cp -r /app/src/test/resources /runtime/app/src/test/resources && \
    cp /app/docker/run-tests-common.sh /runtime/app/run-tests-common.sh

# ---------- Runtime stage: run testsuite without Maven ----------
FROM ${CI_DEPENDENCY_PROXY_GROUP_IMAGE_PREFIX}/library/eclipse-temurin:21-jre-alpine@sha256:704db3c40204a44f471191446ddd9cda5d60dab40f0e15c6507b815ed897238b AS runtime

WORKDIR /app

# Copy packaged artifacts.
RUN mkdir -p /app/tools /app/target && \
    chown 1000:1000 /app /app/tools /app/target
COPY --chown=1000:1000 --from=build /runtime/app/ /app/
COPY --chown=1000:1000 docker/quality_gate/run-tests.sh /app/run-tests.sh
RUN chmod +x /app/run-tests.sh

# The TLS test tool fixture binary depends on Alpine's C/C++ runtime libraries.
RUN apk add --no-cache libgcc libstdc++

# Install cluster tools only when the base image does not already provide them.
ARG HELM_VERSION
ARG TARGETARCH
RUN . /app/run-tests-common.sh && \
    tiger_ensure_kubectl && \
    tiger_ensure_helm

RUN . /app/run-tests-common.sh && \
    tiger_prepare_nonroot_runtime "zeta" "zeta" "1000" "1000" /app/target

# Default runtime toggles; can be overridden at `docker run`.
ENV ZETA_BASE_URL="" \
    ZETA_PORT_FORWARD_HOST="" \
    ZETA_PROXY_URL="" \
    ZETA_K8S_NAMESPACE="" \
    ALLOW_DEPLOYMENT_MODIFICATION="" \
    ALLOW_PERFORMANCE_TESTS="" \
    ALLOW_LONGRUNNING_TESTS="" \
    OPENSEARCH_URL="" \
    PROMETHEUS_URL="" \
    JAEGER_URL="" \
    ZETA_TLS_TEST_TOOL_SERVICE_URL="" \
    ZETA_CERT_VALIDATION_MOCK_URL="" \
    LOAD_DISPENSER_URL="" \
    ZETA_TEST_CERTIFICATES_DIR="/app/zeta-test-certificates" \
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
