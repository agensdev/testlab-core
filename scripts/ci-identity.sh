#!/usr/bin/env bash
# CI: exports VERSION (the argument, else gradle.properties), NAME and SLUG (gradle.properties)
# to the following steps, so the per-OS jobs name files the same way for a branded build too.
set -euo pipefail
prop() { sed -n "s/^testlab\.$1=//p" gradle.properties | tr -d '\r'; }
version="${1:-$(prop version)}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "not MAJOR.MINOR.PATCH: $version" >&2; exit 1; }
{
  echo "VERSION=$version"
  echo "NAME=$(prop name)"
  echo "SLUG=$(prop slug)"
} | tee -a "${GITHUB_ENV:-/dev/null}"
