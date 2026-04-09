#!/bin/sh

# Shared helpers for the Docker test runners.

tiger_ensure_kubectl() {
  if command -v kubectl >/dev/null 2>&1; then
    kubectl version --client
  else
    : "${KUBECTL_VERSION:=1.34.2}"
    apk add --no-cache "kubectl=~${KUBECTL_VERSION}"
  fi
}

tiger_set_defaults() {
  : "${ZETA_BASE_URL:=}"
  : "${ZETA_PROXY_URL:=}"
  : "${ZETA_K8S_NAMESPACE:=}"
  : "${ALLOW_DEPLOYMENT_MODIFICATION:=}"
  : "${OPENSEARCH_URL:=}"
  : "${ZETA_TLS_TEST_TOOL_SERVICE_URL:=}"
  : "${ZETA_TEST_CERTIFICATES_DIR:=}"
  : "${PROFILE:=}"
  : "${CUCUMBER_TAGS:=@smoke}"
}

tiger_cd_app() {
  app_dir="${1:-/app}"
  cd "${app_dir}" || { echo "Expected ${app_dir} to exist." >&2; exit 1; }
}

tiger_property_arg_from_env() {
  env_key="$1"
  prop_key="$2"
  eval "value=\${${env_key}:-}"
  if [ -n "${value}" ]; then
    printf -- "-D%s=%s" "${prop_key}" "${value}"
  fi
}

tiger_common_property_args() {
  args=""
  profile_arg="$(tiger_property_arg_from_env PROFILE PROFILE)"
  zeta_base_arg="$(tiger_property_arg_from_env ZETA_BASE_URL zeta_base_url)"
  zeta_proxy_arg="$(tiger_property_arg_from_env ZETA_PROXY_URL zeta_proxy_url)"
  zeta_k8s_namespace_arg="$(tiger_property_arg_from_env ZETA_K8S_NAMESPACE zeta_k8s_namespace)"
  allow_deployment_modification_arg="$(tiger_property_arg_from_env ALLOW_DEPLOYMENT_MODIFICATION allow_deployment_modification)"
  opensearch_arg="$(tiger_property_arg_from_env OPENSEARCH_URL opensearch_url)"
  zeta_tls_test_tool_service_url_arg="$(tiger_property_arg_from_env ZETA_TLS_TEST_TOOL_SERVICE_URL zeta_tls_test_tool_service_url)"
  test_certificates_dir_arg="$(tiger_property_arg_from_env ZETA_TEST_CERTIFICATES_DIR testCertificates.dir)"

  [ -n "${profile_arg}" ] && args="${args} ${profile_arg}"
  [ -n "${zeta_base_arg}" ] && args="${args} ${zeta_base_arg}"
  [ -n "${zeta_proxy_arg}" ] && args="${args} ${zeta_proxy_arg}"
  [ -n "${zeta_k8s_namespace_arg}" ] && args="${args} ${zeta_k8s_namespace_arg}"
  [ -n "${allow_deployment_modification_arg}" ] && args="${args} ${allow_deployment_modification_arg}"
  [ -n "${opensearch_arg}" ] && args="${args} ${opensearch_arg}"
  [ -n "${zeta_tls_test_tool_service_url_arg}" ] && args="${args} ${zeta_tls_test_tool_service_url_arg}"
  [ -n "${test_certificates_dir_arg}" ] && args="${args} ${test_certificates_dir_arg}"

  printf -- "%s" "${args}"
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

tiger_lookup_group_by_name() {
  group_name="$1"
  awk -F: -v group="${group_name}" '$1 == group { print $3; exit }' /etc/group
}

tiger_lookup_group_by_id() {
  group_id="$1"
  awk -F: -v gid="${group_id}" '$3 == gid { print $1; exit }' /etc/group
}

tiger_lookup_user_by_name() {
  user_name="$1"
  awk -F: -v user="${user_name}" '$1 == user { print $3 ":" $4 ":" $6; exit }' /etc/passwd
}

tiger_lookup_user_by_id() {
  user_id="$1"
  awk -F: -v uid="${user_id}" '$3 == uid { print $1; exit }' /etc/passwd
}

tiger_insert_after_first_line() {
  line="$1"
  file="$2"
  tmp_file="$(mktemp)"

  if [ -s "${file}" ]; then
    awk -v line="${line}" 'NR == 1 { print; print line; next } { print }' "${file}" > "${tmp_file}"
  else
    printf -- "%s\n" "${line}" > "${tmp_file}"
  fi

  cat "${tmp_file}" > "${file}"
  rm -f "${tmp_file}"
}

tiger_prepare_nonroot_runtime() {
  user_name="${1:-zeta}"
  group_name="${2:-zeta}"
  user_id="${3:-1000}"
  group_id="${4:-1000}"
  shift 4
  user_home="/home/${user_name}"
  group_added_manually=0
  user_added_manually=0

  existing_group_id="$(tiger_lookup_group_by_name "${group_name}")"
  existing_group_name="$(tiger_lookup_group_by_id "${group_id}")"
  if [ -n "${existing_group_id}" ]; then
    if [ "${existing_group_id}" != "${group_id}" ]; then
      echo "Group '${group_name}' already exists with GID '${existing_group_id}', expected '${group_id}'." >&2
      exit 1
    fi
  elif [ -n "${existing_group_name}" ]; then
    tiger_insert_after_first_line "${group_name}:x:${group_id}:" /etc/group
    group_added_manually=1
  else
    addgroup -g "${group_id}" -S "${group_name}"
  fi

  existing_user_entry="$(tiger_lookup_user_by_name "${user_name}")"
  existing_user_name="$(tiger_lookup_user_by_id "${user_id}")"
  if [ -n "${existing_user_entry}" ]; then
    existing_user_id="$(printf -- "%s" "${existing_user_entry}" | cut -d: -f1)"
    existing_user_group_id="$(printf -- "%s" "${existing_user_entry}" | cut -d: -f2)"
    existing_user_home="$(printf -- "%s" "${existing_user_entry}" | cut -d: -f3)"
    if [ "${existing_user_id}" != "${user_id}" ] || [ "${existing_user_group_id}" != "${group_id}" ]; then
      echo "User '${user_name}' already exists with UID/GID '${existing_user_id}:${existing_user_group_id}', expected '${user_id}:${group_id}'." >&2
      exit 1
    fi
    user_home="${existing_user_home}"
  elif [ -n "${existing_user_name}" ] || [ "${group_added_manually}" -eq 1 ]; then
    tiger_insert_after_first_line "${user_name}:!:${user_id}:${group_id}::${user_home}:/sbin/nologin" /etc/passwd
    user_added_manually=1
  else
    adduser -u "${user_id}" -S -D -h "${user_home}" -G "${group_name}" "${user_name}"
  fi

  user_home="$(awk -F: -v user="${user_name}" '$1 == user { print $6 }' /etc/passwd)"
  if [ -z "${user_home}" ]; then
    echo "Unable to resolve home directory for user '${user_name}'." >&2
    exit 1
  fi

  for path in "$@"; do
    [ -n "${path}" ] || continue
    mkdir -p "${path}"
  done

  mkdir -p "${user_home}/.kube"

  if [ "${user_added_manually}" -eq 1 ] || [ "${group_added_manually}" -eq 1 ]; then
    chown -R "${user_id}:${group_id}" "${user_home}" "$@"
  else
    chown -R "${user_name}:${group_name}" "${user_home}" "$@"
  fi
}
