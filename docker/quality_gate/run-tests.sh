#!/bin/sh
set -u

# GitLab runners reset the working directory to /builds/...; keep JEXL file() paths stable.
cd /app || { echo "Expected /app to exist in the quality gate image." >&2; exit 1; }

# Default Tiger config when no explicit path is provided (helpful in CI where CWD != /app)
: "${TIGER_TESTENV_CFGFILE:=/app/tiger.yaml}"
export TIGER_TESTENV_CFGFILE

serenity_dir="${SERENITY_EXPORT_DIR:-/app/target/site/serenity}"
cucumber_dir="${CUCUMBER_EXPORT_DIR:-/app/target/cucumber-parallel}"

mkdir -p "${serenity_dir}" "${cucumber_dir}" || {
  echo "Failed to ensure report directories exist: ${serenity_dir}, ${cucumber_dir}" >&2
  exit 1
}

agent="/app/agent/tiger-java-agent.jar"
[ -f "${agent}" ] || agent="$(find /app/libs -name 'tiger-*-agent*.jar' | head -n1 || true)"
[ -n "${agent}" ] && [ -f "${agent}" ] || { echo "Tiger agent missing" >&2; exit 1; }

tests_jar="$(find /app -maxdepth 1 -name '*-tests.jar' | head -n1 || true)"
[ -n "${tests_jar}" ] || tests_jar="/app/tests.jar"
classpath="${tests_jar}:/app/libs/*"

set +e
java -Dserenity.outputDirectory="${serenity_dir}" \
  "-Dzeta.cucumber.outputDirectory=${cucumber_dir}" \
  "-Denvironment=${TIGER_ENVIRONMENT:-cloud}" \
  "-Dzeta_base_url=${ZETA_BASE_URL:-}" \
  "-Dzeta_proxy_url=${ZETA_PROXY_URL:-}" \
  "-Dzeta_proxy=${ZETA_PROXY:-no-proxy}" \
  "-Dcucumber.filter.tags=${CUCUMBER_TAGS:-@smoke}" \
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
