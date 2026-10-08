#!/bin/sh
set -eu

: "${CERT_REPO_DIR:?CERT_REPO_DIR is required}"
: "${CERT_REPO_GIT_CACHE_DIR:?CERT_REPO_GIT_CACHE_DIR is required}"
: "${CERT_REPO_REF:?CERT_REPO_REF is required}"

if [ -z "${CERT_REPO_URL:-}" ]; then
  : "${CI_SERVER_URL:?CI_SERVER_URL is required when CERT_REPO_URL is unset}"
  : "${CI_PROJECT_NAMESPACE:?CI_PROJECT_NAMESPACE is required when CERT_REPO_URL is unset}"
  : "${CI_JOB_TOKEN:?CI_JOB_TOKEN is required when CERT_REPO_URL is unset}"
  CERT_REPO_URL="https://gitlab-ci-token:${CI_JOB_TOKEN}@${CI_SERVER_URL#https://}/${CI_PROJECT_NAMESPACE}/zeta-test-certificates.git"
fi

mkdir -p "$(dirname "${CERT_REPO_GIT_CACHE_DIR}")"
if [ ! -d "${CERT_REPO_GIT_CACHE_DIR}/objects" ]; then
  rm -rf "${CERT_REPO_GIT_CACHE_DIR}"
  git init --bare "${CERT_REPO_GIT_CACHE_DIR}"
fi

git -C "${CERT_REPO_GIT_CACHE_DIR}" fetch --depth 1 "${CERT_REPO_URL}" "${CERT_REPO_REF}"
cert_repo_sha="$(git -C "${CERT_REPO_GIT_CACHE_DIR}" rev-parse FETCH_HEAD)"
git -C "${CERT_REPO_GIT_CACHE_DIR}" update-ref refs/heads/cache "${cert_repo_sha}"
git -C "${CERT_REPO_GIT_CACHE_DIR}" symbolic-ref HEAD refs/heads/cache
rm -f "${CERT_REPO_GIT_CACHE_DIR}/FETCH_HEAD"

rm -rf "${CERT_REPO_DIR}"
git clone --reference-if-able "${CERT_REPO_GIT_CACHE_DIR}" \
  --no-checkout \
  "${CERT_REPO_GIT_CACHE_DIR}" \
  "${CERT_REPO_DIR}"
git -C "${CERT_REPO_DIR}" sparse-checkout init --cone
git -C "${CERT_REPO_DIR}" sparse-checkout set keystores manifest
git -C "${CERT_REPO_DIR}" -c advice.detachedHead=false checkout "${cert_repo_sha}"

test -f "${CERT_REPO_DIR}/manifest/cert-manifest.tsv"
test -f "${CERT_REPO_DIR}/manifest/truststore-manifest.tsv"
test -f "${CERT_REPO_DIR}/manifest/keystore-manifest.tsv"
rm -rf "${CERT_REPO_DIR}/.git"
