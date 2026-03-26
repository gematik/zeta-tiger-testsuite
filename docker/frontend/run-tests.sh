#!/bin/sh
set -eu

common_lib="/app/run-tests-common.sh"
if [ ! -f "${common_lib}" ]; then
  common_lib="$(dirname "$0")/../run-tests-common.sh"
fi
# shellcheck source=/app/run-tests-common.sh
. "${common_lib}" || { echo "Failed to load ${common_lib}" >&2; exit 1; }

# GitLab runner sets CWD to /builds/...; ensure Maven sees the POM in /app.
tiger_cd_app "/app"

tiger_set_defaults

tiger_setup_report_dirs "symlink" \
  "/app/target/site/serenity" \
  "/app/target/cucumber-parallel" \
  "/app/target/allure-results"

if [ -z "${PROFILE}" ]; then
  unset PROFILE
fi

common_property_args="$(tiger_common_property_args)"

set +e
mvn -o -B \
  -Dmaven.repo.local=/tmp/.m2/repository \
  -Djava.awt.headless=true \
  -Dlicense.skip=true \
  -Dcheckstyle.skip=true \
  -Dtiger.lib.activateWorkflowUi=false \
  -Dtiger.lib.startBrowser=false \
  -Dtiger.lib.trafficVisualization=false \
  -Dtiger.lib.rbelAnsiColors=false \
  -Dtiger.lib.runTestsOnStart=true \
  -Dfailsafe.testFailureIgnore=false \
  ${common_property_args} \
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
