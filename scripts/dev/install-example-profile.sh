#!/usr/bin/env bash
# install-example-profile.sh DATA_DIR [GITHUB_API] [JIRA_API] [extra settings JSON]
# Writes the example-shop profile into DATA_DIR/profiles and a settings.json that points the
# GitHub and Jira clients at the local fakes. Used by the e2e scripts.
set -euo pipefail
d="$1"; gh="${2:-}"; jr="${3:-}"; extra="${4:-}"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$d/profiles"
rm -rf "$d/profiles/example-shop"; cp -r "$here/example-profile" "$d/profiles/example-shop"
python3 - "$d/settings.json" "$gh" "$jr" "$extra" <<'PY'
import json, sys
path, gh, jr, extra = sys.argv[1:]
s = {"profile": "example-shop", "connections": {}}
if gh: s["connections"]["githubApiUrl"] = gh
if jr: s["connections"]["jiraApiUrls"] = {"example.atlassian.net": jr}
if extra: s.update(json.loads(extra))
json.dump(s, open(path, "w"), indent=1)
PY
