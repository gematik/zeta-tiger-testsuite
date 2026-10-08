#!/bin/sh
set -u

common_lib="/app/run-tests-common.sh"
if [ ! -f "${common_lib}" ]; then
  common_lib="$(dirname "$0")/../run-tests-common.sh"
fi
# shellcheck source=/app/run-tests-common.sh
. "${common_lib}" || { echo "Failed to load ${common_lib}" >&2; exit 1; }

# GitLab runners reset the working directory to /builds/...; keep JEXL file() paths stable.
tiger_cd_app "/app"

# Default Tiger config when no explicit path is provided (helpful in CI where CWD != /app)
: "${TIGER_TESTENV_CFGFILE:=/app/tiger.yaml}"
export TIGER_TESTENV_CFGFILE

tiger_set_defaults
tiger_setup_report_dirs "direct" "/app/target/site/serenity" "/app/target/cucumber-parallel"

if [ -z "${PROFILE}" ]; then
  unset PROFILE
fi

agent="/app/agent/tiger-java-agent.jar"
[ -f "${agent}" ] || agent="$(find /app/libs -name 'tiger-*-agent*.jar' | head -n1 || true)"
[ -n "${agent}" ] && [ -f "${agent}" ] || { echo "Tiger agent missing" >&2; exit 1; }

tests_jar="$(find /app -maxdepth 1 -name '*-tests.jar' | head -n1 || true)"
[ -n "${tests_jar}" ] || tests_jar="/app/tests.jar"
classpath="${tests_jar}:/app/libs/*"

common_property_args="$(tiger_common_property_args)"
effective_cucumber_tags="${CUCUMBER_FILTER_TAGS:-${CUCUMBER_TAGS:-}}"
run_quality_gate() {
  if [ -n "${effective_cucumber_tags}" ]; then
    # shellcheck disable=SC2086 # Shared helper intentionally returns a list of -D args.
    java -Dserenity.outputDirectory="${serenity_dir}" \
      "-Dzeta.cucumber.outputDirectory=${cucumber_dir}" \
      "-Dallure.results.directory=${allure_dir}" \
      -Djava.net.preferIPv4Stack=true \
      ${common_property_args} \
      "-Dcucumber.filter.tags=${effective_cucumber_tags}" \
      -javaagent:"${agent}" \
      -cp "${classpath}" \
      de.gematik.zeta.TigerTestsuiteMain "$@"
  else
    # shellcheck disable=SC2086 # Shared helper intentionally returns a list of -D args.
    java -Dserenity.outputDirectory="${serenity_dir}" \
      "-Dzeta.cucumber.outputDirectory=${cucumber_dir}" \
      "-Dallure.results.directory=${allure_dir}" \
      -Djava.net.preferIPv4Stack=true \
      ${common_property_args} \
      -javaagent:"${agent}" \
      -cp "${classpath}" \
      de.gematik.zeta.TigerTestsuiteMain "$@"
  fi
}

set +e
run_quality_gate "$@"
rc=$?
set -e

report_rc=0
echo "Generating Serenity reports..."
java -cp "${classpath}" \
  de.gematik.zeta.reporting.SerenityExtendedReportsMain \
  "${serenity_dir}" "${serenity_dir}" \
  "/app/src/test/resources/features" || report_rc=$?

for expected_report in index.html serenity-summary.html serenity-summary.json; do
  if [ ! -s "${serenity_dir}/${expected_report}" ]; then
    echo "Missing generated Serenity report: ${serenity_dir}/${expected_report}" >&2
    report_rc=1
  fi
done

if [ "${rc}" -eq 0 ] && [ "${report_rc}" -ne 0 ]; then
  rc="${report_rc}"
fi

exit ${rc}
