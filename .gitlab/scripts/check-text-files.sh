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

    # Preserve the pinned upstream schema's original bytes and checksum, and
    # respect explicit CRLF checkout rules for Windows scripts. Read raw bytes
    # so Git for Windows grep does not silently strip carriage returns.
    if [ "${file}" != "docs/specifications/2.0.0-cc/schemas/upstream/afoListeSteckbrief_v2.xsd" ] &&
        LC_ALL=C grep -Uq "$(printf '\r')" "${file}" 2>/dev/null; then
      case "$(git check-attr eol -- "${file}")" in
        *": eol: crlf") ;;
        *)
          echo "CRLF: ${file}"
          bad=1
          ;;
      esac
    fi

    if ! iconv -f UTF-8 -t UTF-8 "${file}" >/dev/null 2>&1; then
      echo "Non-UTF8: ${file}"
      bad=1
    fi
  done

  exit "${bad}"
)
