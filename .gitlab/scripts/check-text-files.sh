#!/bin/sh
set -u

# Validate tracked text files while leaving binary files untouched.
# Newlines in filenames are not supported; spaces and tabs are handled.
git ls-files | (
  bad=0

  while IFS= read -r file; do
    case "${file}" in
      tools/*) continue ;;
    esac

    # GNU grep -I treats files containing NUL bytes as binary. Empty files are
    # skipped as they cannot contain invalid UTF-8 or CRLF line endings.
    if ! LC_ALL=C grep -Iq . "${file}"; then
      continue
    fi

    if LC_ALL=C grep -q "$(printf '\r')" "${file}" 2>/dev/null; then
      echo "CRLF: ${file}"
      bad=1
    fi

    if ! iconv -f UTF-8 -t UTF-8 "${file}" >/dev/null 2>&1; then
      echo "Non-UTF8: ${file}"
      bad=1
    fi
  done

  exit "${bad}"
)
