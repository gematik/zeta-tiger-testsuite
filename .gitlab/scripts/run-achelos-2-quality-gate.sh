#!/bin/sh
set -eu

: "${CI_PROJECT_DIR:?CI_PROJECT_DIR is required}"
: "${ZETA_BASE_URL:?ZETA_BASE_URL is required}"
: "${ZETA_K8S_NAMESPACE:?ZETA_K8S_NAMESPACE is required}"
: "${ZETA_PROXY_URL:?ZETA_PROXY_URL is required}"
: "${OPENSEARCH_URL:?OPENSEARCH_URL is required}"
: "${PROMETHEUS_URL:?PROMETHEUS_URL is required}"
: "${ZETA_TLS_TEST_TOOL_SERVICE_URL:?ZETA_TLS_TEST_TOOL_SERVICE_URL is required}"

kube_dir="${CI_PROJECT_DIR}/.kube"
kubeconfig="${kube_dir}/config"

cleanup() {
  for pid_file in "${kube_dir}"/port-forward-*.pid; do
    [ -f "${pid_file}" ] || continue
    kill "$(cat "${pid_file}")" 2>/dev/null || true
  done
  rm -f "${kube_dir}"/port-forward-*.pid
  rm -f "${kube_dir}"/port-forward-*.log
  rm -f "${kubeconfig}"
}
on_signal() {
  exit 1
}
trap cleanup 0
trap on_signal 1 2 15

if [ -z "${KUBECONFIG_B64:-}" ]; then
  echo "KUBECONFIG_B64 is required for the achelos-2 quality gate." >&2
  exit 1
fi

umask 077
mkdir -p "${kube_dir}"
if ! printf '%s' "${KUBECONFIG_B64}" | base64 -d > "${kubeconfig}"; then
  echo "KUBECONFIG_B64 is not valid base64." >&2
  exit 1
fi
export KUBECONFIG="${kubeconfig}"

kubectl --namespace "${ZETA_K8S_NAMESPACE}" get pods --request-timeout=30s > /dev/null

start_port_forward() {
  service_name="$1"
  local_port="$2"
  remote_port="$3"
  log_file="${kube_dir}/port-forward-${service_name}.log"
  pid_file="${kube_dir}/port-forward-${service_name}.pid"

  kubectl --namespace "${ZETA_K8S_NAMESPACE}" port-forward \
    --address 127.0.0.1 \
    "service/${service_name}" \
    "${local_port}:${remote_port}" > "${log_file}" 2>&1 &
  port_forward_pid=$!
  printf '%s\n' "${port_forward_pid}" > "${pid_file}"

  attempt=0
  while ! nc -z 127.0.0.1 "${local_port}"; do
    attempt=$((attempt + 1))
    if ! kill -0 "${port_forward_pid}" 2>/dev/null || [ "${attempt}" -ge 30 ]; then
      echo "Failed to establish port-forward for service/${service_name} on port ${local_port}." >&2
      cat "${log_file}" >&2
      exit 1
    fi
    sleep 1
  done
}

start_port_forward tiger-proxy 9999 9999
start_port_forward opensearch 9200 9200
start_port_forward prometheus 9090 9090
start_port_forward zeta-tls-test-tool-service 9012 80

# shellcheck disable=SC2086 # Maven and Tiger options are intentionally word-split.
mvn ${MAVEN_CLI_OPTS} -Pproxy \
  "-Dcucumber.filter.tags=@critical" \
  -Dtiger.lib.activateWorkflowUi=false \
  -Dtiger.lib.startBrowser=false \
  -Dtiger.lib.trafficVisualization=false \
  -Dtiger.lib.runTestsOnStart=true \
  -Dtiger.lib.rbelAnsiColors=false \
  "-Dzeta_base_url=${ZETA_BASE_URL}" \
  "-Dzeta_k8s_namespace=${ZETA_K8S_NAMESPACE}" \
  "-Dzeta_proxy_url=${ZETA_PROXY_URL}" \
  "-Dopensearch_url=${OPENSEARCH_URL}" \
  "-Dprometheus_url=${PROMETHEUS_URL}" \
  "-Dzeta_tls_test_tool_service_url=${ZETA_TLS_TEST_TOOL_SERVICE_URL}" \
  verify
