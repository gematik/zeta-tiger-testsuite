#!/bin/sh
set -eu

: "${CI_PROJECT_DIR:?CI_PROJECT_DIR is required}"
: "${ZETA_K8S_NAMESPACE:?ZETA_K8S_NAMESPACE is required}"
: "${KUBECONFIG_B64:?KUBECONFIG_B64 is required}"

kube_dir="${CI_PROJECT_DIR}/.kube"
kubeconfig="${kube_dir}/config"
pods_json="${kube_dir}/pods.json"
image_inventory="${CI_PROJECT_DIR}/deployed-images.tsv"

cleanup() {
  rm -f "${kubeconfig}" "${pods_json}"
}
trap cleanup 0

umask 077
mkdir -p "${kube_dir}"
if ! printf '%s' "${KUBECONFIG_B64}" | base64 -d > "${kubeconfig}"; then
  echo "KUBECONFIG_B64 is not valid base64." >&2
  exit 1
fi
export KUBECONFIG="${kubeconfig}"

kubectl --namespace "${ZETA_K8S_NAMESPACE}" get pods \
  --request-timeout=30s \
  --output=json > "${pods_json}"

jq --raw-output '
  ["pod", "container", "declared_image", "resolved_image_id"],
  (
    .items[] as $pod
    | (($pod.spec.initContainers // []) + ($pod.spec.containers // []))[] as $container
    | (
        (($pod.status.initContainerStatuses // []) + ($pod.status.containerStatuses // []))
        | map(select(.name == $container.name))
        | .[0].imageID // "not-resolved"
      ) as $image_id
    | [$pod.metadata.name, $container.name, $container.image, $image_id]
  )
  | @tsv
' "${pods_json}" > "${image_inventory}"

cat "${image_inventory}"
