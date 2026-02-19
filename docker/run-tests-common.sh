#!/bin/sh

# Shared helpers for the Docker test runners.

tiger_set_defaults() {
  : "${ZETA_BASE_URL:=}"
  : "${ZETA_PROXY_URL:=}"
  : "${OPENSEARCH_URL:=}"
  : "${PROFILE:=}"
  : "${CUCUMBER_TAGS:=@smoke}"
}

tiger_cd_app() {
  app_dir="${1:-/app}"
  cd "${app_dir}" || { echo "Expected ${app_dir} to exist." >&2; exit 1; }
}

tiger_normalize_dir() {
  # Treat file-like paths (ending with .json/.xml/etc.) as a directory by stripping the filename.
  path="$1"
  case "${path}" in
    *.json|*.xml) dirname "$(realpath -m "$(dirname "${path}")")" ;;
    *) realpath -m "${path}" ;;
  esac
}

tiger_maybe_link() {
  target_dir="$1"
  default_dir="$2"
  if [ -z "${target_dir}" ]; then
    mkdir -p "${default_dir}"
    return 0
  fi
  # If the target looks like a file path, use its parent directory.
  target_dir="$(tiger_normalize_dir "${target_dir}")"
  if [ -n "${target_dir}" ] && [ "${target_dir}" != "${default_dir}" ]; then
    mkdir -p "${target_dir}"
    mkdir -p "$(dirname "${default_dir}")"
    rm -rf "${default_dir}"
    ln -s "${target_dir}" "${default_dir}"
  else
    mkdir -p "${default_dir}"
  fi
}

tiger_setup_report_dirs() {
  mode="${1:-direct}"
  serenity_default="${2:-/app/target/site/serenity}"
  cucumber_default="${3:-/app/target/cucumber-parallel}"
  allure_default="${4:-/app/target/allure-results}"

  case "${mode}" in
    symlink)
      serenity_dir="${serenity_default}"
      cucumber_dir="${cucumber_default}"
      allure_dir="${allure_default}"
      tiger_maybe_link "${SERENITY_EXPORT_DIR:-}" "${serenity_dir}"
      tiger_maybe_link "${CUCUMBER_EXPORT_DIR:-}" "${cucumber_dir}"
      tiger_maybe_link "${ALLURE_RESULTS_DIR:-}" "${allure_dir}"
      mkdir -p "${serenity_dir}" "${cucumber_dir}" "${allure_dir}"
      ;;
    direct)
      serenity_dir="${SERENITY_EXPORT_DIR:-${serenity_default}}"
      cucumber_dir="${CUCUMBER_EXPORT_DIR:-${cucumber_default}}"
      allure_dir="${ALLURE_RESULTS_DIR:-${allure_default}}"
      mkdir -p "${serenity_dir}" "${cucumber_dir}" "${allure_dir}"
      ;;
    *)
      echo "Unknown report mode '${mode}'." >&2
      exit 1
      ;;
  esac
}
