#!/bin/sh

tiger_export_ci_job_token() {
  export CI_JOB_TOKEN="$(cat /run/secrets/ci_job_token 2>/dev/null || true)"
}

tiger_verify_gitlab_testproxy_artifacts() {
  repo_root="${1:-/tmp/.m2/repository/de/gematik/test}"

  if [ ! -d "${repo_root}" ]; then
    echo "Expected Tiger artifacts in ${repo_root}, but the directory is missing." >&2
    exit 1
  fi

  metadata_count="$(find "${repo_root}" -name _remote.repositories | wc -l | tr -d ' ')"
  if [ "${metadata_count}" = "0" ]; then
    echo "Expected Maven provenance metadata under ${repo_root}, but none was found." >&2
    exit 1
  fi

  offenders="$(find "${repo_root}" -name _remote.repositories -exec grep -H -E '>[^=]+=' {} + | grep -v '>gitlab-testproxy=' || true)"
  if [ -n "${offenders}" ]; then
    echo "Tiger artifacts must resolve from gitlab-testproxy only." >&2
    echo "${offenders}" >&2
    exit 1
  fi
}
