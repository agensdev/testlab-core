#!/usr/bin/env bash
# builds-e2e.sh — PR builds end to end on the dev emulator, against scripts/dev/fake-github.py:
# a PR number finds its build, the artifact download drops the token on the storage redirect,
# the APK is installed and recorded, a release updates it, a build signed with another key is
# refused with advice, and over MCP an approved uninstall clears the way. Sequenced like e2e.sh.
#   scripts/dev/builds-e2e.sh [--keep]
set -euo pipefail
cd "$(dirname "$0")/../.."
keep=false; [ "${1:-}" = "--keep" ] && keep=true
B=cli/build/install/testlab/bin/testlab
if ! adb devices 2>/dev/null | grep -q '^emulator-[0-9]*\s*device$'; then
  ./gradlew :cli:installDist --console=plain -q
  ./gradlew --stop >/dev/null 2>&1 || true
  scripts/dev/emu.sh up
fi
[ -x "$B" ] || { echo "build the CLI first (./gradlew :cli:installDist) — not while the emulator runs" >&2; exit 1; }

export XDG_DATA_HOME=$(mktemp -d) XDG_CACHE_HOME=$(mktemp -d) TESTLAB_VAULT_PASSPHRASE=builds-e2e-only
port=8399; token=ghp_fake_e2e_token_0123456789
mkdir -p "$XDG_DATA_HOME/testlab"
scripts/dev/install-example-profile.sh "$XDG_DATA_HOME/testlab" "http://127.0.0.1:$port" "" "{\"workspace\":\"$(mktemp -d)\",\"mcp\":{\"confirmWrites\":false}}"
echo -n "$token" | "$B" secrets set github.token
python3 scripts/dev/fake-github.py "$port" "$token" & gh=$!
srv=
trap 'kill $gh ${srv:-} 2>/dev/null || true; adb uninstall no.agens.testlab.fixture >/dev/null 2>&1 || true; $keep || scripts/dev/emu.sh down' EXIT
sleep 1
adb uninstall no.agens.testlab.fixture >/dev/null 2>&1 || true
fails=()
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; fails+=("$1"); fi; }
version() { adb shell dumpsys package no.agens.testlab.fixture | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1 | tr -d '\r'; }

out=$("$B" install --pr 42 2>&1) || true; echo "  $out"
check "PR 42 installs its build" '[[ "$out" == installed\ Feature\ feature/SHOP-42-ok* ]] && [ "$(version)" = 1.0-fixture ]'
out=$("$B" install --pr 42 2>&1) || true
check "the second install comes from the download cache" '[ "$(ls "$XDG_CACHE_HOME/testlab/builds" | grep -c "^a502$")" = 1 ]'
out=$("$B" install --release latest 2>&1) || true; echo "  $out"
check "the latest release updates it" '[ "$(version)" = 2.0-fixture ]'
out=$("$B" install --pr 43 2>&1) && rc=0 || rc=$?; echo "  $out"
check "another signing key is refused with advice" '[ $rc = 1 ] && [[ "$out" == *"signed with another key"* ]] && [ "$(version)" = 2.0-fixture ]'
pm=$(adb shell pm disable-user --user 0 no.agens.testlab.fixture | tr -d '\r'); pe=$(adb shell pm enable no.agens.testlab.fixture | tr -d '\r')
check "pm disable-user/enable answer as isolation expects ($pm / $pe)" '[[ "$pm" == *disabled* ]] && [[ "$pe" == *enabled* ]]'

TOKEN=$("$B" mcp-config | sed -n 's/.*Bearer \([^"]*\)".*/\1/p' | head -1)
log=$(mktemp); "$B" serve > /dev/null 2> "$log" & srv=$!
for _ in $(seq 1 60); do grep -q "MCP server on" "$log" && break; sleep 0.5; done
mcp=$(NTL_TOKEN=$TOKEN scripts/dev/mcp-call.py handsets_use_device '{}' handsets_installed_builds '{}' handsets_list_branch_builds '{"branch_or_pr":"43"}' \
  handsets_install_branch_build '{"branch_or_pr":"43"}' handsets_uninstall_app '{"package":"no.agens.testlab.fixture"}' handsets_install_branch_build '{"branch_or_pr":"43"}' handsets_installed_builds '{}')
echo "$mcp" | sed 's/^/  /' | cut -c1-200
check "MCP: the installed build is the release" '[[ "$mcp" == *"\"tag\":\"9.9.9-rc1\""* ]]'
check "MCP: the clash names the fix" '[[ "$mcp" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]] && [[ "$mcp" == *handsets_uninstall_app* ]]'
check "MCP: after the uninstall PR 43 installs" '[ "$(version)" = 3.0-otherkey ] && [[ "$mcp" == *"\"branch\":\"feature/SHOP-43-clash\""* ]]'
echo "problems: ${fails[*]:-none}"
[ ${#fails[@]} = 0 ]
