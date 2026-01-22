#!/bin/sh
set -eu

# GitLab runner sets CWD to /builds/...; ensure Maven sees the POM in /app.
cd /app || { echo "Expected /app to exist in the frontend image." >&2; exit 1; }

serenity_dir="/app/target/site/serenity"
cucumber_dir="/app/target/cucumber-parallel"

normalize_dir() {
  # Treat file-like paths (ending with .json/.xml/etc.) as a directory by stripping the filename.
  path="$1"
  case "${path}" in
    *.json|*.xml) dirname "$(realpath -m "$(dirname "${path}")")" ;;
    *) realpath -m "${path}" ;;
  esac
}

maybe_link() {
  target_dir="$1"; default_dir="$2"
  # If the target looks like a file path, use its parent directory.
  target_dir="$(normalize_dir "${target_dir}")"
  if [ -n "${target_dir}" ] && [ "${target_dir}" != "${default_dir}" ]; then
    mkdir -p "${target_dir}"
    mkdir -p "$(dirname "${default_dir}")"
    rm -rf "${default_dir}"
    ln -s "${target_dir}" "${default_dir}"
  else
    mkdir -p "${default_dir}"
  fi
}

maybe_link "${SERENITY_EXPORT_DIR:-}" "${serenity_dir}"
maybe_link "${CUCUMBER_EXPORT_DIR:-}" "${cucumber_dir}"
mkdir -p "${serenity_dir}" "${cucumber_dir}"

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
