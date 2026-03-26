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

set +e
java -Dserenity.outputDirectory="${serenity_dir}" \
  "-Dzeta.cucumber.outputDirectory=${cucumber_dir}" \
  -Djava.net.preferIPv4Stack=true \
  ${common_property_args} \
  "-Dcucumber.filter.tags=${CUCUMBER_TAGS}" \
  -javaagent:"${agent}" \
  -cp "${classpath}" \
  de.gematik.zeta.TigerTestsuiteMain "$@"
rc=$?
set -e

cli="/app/tools/serenity-cli.jar"
if [ ! -f "${cli}" ]; then
  cli="$(find /app/tools -name 'serenity-cli*.jar' | head -n1 || true)"
fi

if [ -n "${cli}" ] && [ -f "${cli}" ]; then
  echo "Aggregating Serenity report via serenity-cli..."
  java -jar "${cli}" --source "${serenity_dir}" --destination "${serenity_dir}" || true
fi

exit ${rc}
