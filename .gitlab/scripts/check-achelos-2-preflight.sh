#!/bin/sh
set -eu

: "${ZETA_BASE_URL:?ZETA_BASE_URL is required}"

hello_zeta_url="https://${ZETA_BASE_URL}/proxy/achelos_testfachdienst/hellozeta"

# achelos-2 intentionally uses a Let's Encrypt staging certificate. Keep the
# trust exception scoped to this availability probe and still require HTTPS,
# a successful response status, and the exact expected response body.
if ! hello_zeta_response="$(curl --insecure --fail --show-error --silent \
  --connect-timeout 10 \
  --max-time 30 \
  --retry 2 \
  --retry-all-errors \
  "${hello_zeta_url}")"; then
  echo "achelos-2 preflight failed: ${hello_zeta_url} is not reachable." >&2
  exit 1
fi

case "${hello_zeta_response}" in
  *'"message":"Hello ZETA!"'*)
    echo "achelos-2 helloZeta preflight passed."
    ;;
  *)
    echo "achelos-2 preflight returned an unexpected response: ${hello_zeta_response}" >&2
    exit 1
    ;;
esac
