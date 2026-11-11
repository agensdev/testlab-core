#!/usr/bin/env bash
# spaces-device-e2e.sh — shared phones between client spaces, on the dev emulator: `alpha` holds it,
# `beta` is refused (CLI and MCP), `alpha`'s release runs the hand-over (clears its app family's
# data, puts the font size back), then `beta` gets it free; a stale lease is taken with a hand-over.
#   scripts/dev/spaces-device-e2e.sh [--keep]     # build first (./gradlew :cli:installDist)
set -euo pipefail
cd "$(dirname "$0")/../.."
keep=false; [ "${1:-}" = --keep ] && keep=true
B=cli/build/install/testlab/bin/testlab
[ -x "$B" ] || { echo "build the CLI first (./gradlew :cli:installDist) — not while the emulator runs" >&2; exit 1; }
adb devices 2>/dev/null | grep -q '^emulator-[0-9]*\s*device$' || scripts/dev/emu.sh up
E=$(adb devices | awk '$2 == "device" && $1 ~ /^emulator-/ {print $1; exit}')
[ -n "$E" ] || { echo "no emulator" >&2; exit 1; }
export XDG_DATA_HOME=$(mktemp -d) XDG_CACHE_HOME=$(mktemp -d) TESTLAB_VAULT_PASSPHRASE=spaces-e2e-only
export JAVA_TOOL_OPTIONS="-Duser.home=$(mktemp -d) -Xshare:auto" ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export PATH=$PATH:$ANDROID_HOME/platform-tools
unset TESTLAB_SPACE
fails=()
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; fails+=("$1"); fi; }
q() { "$@" 2>&1 | grep -v "^Picked up JAVA_TOOL_OPTIONS"; }

"$B" space create alpha --no-setup >/dev/null 2>&1; "$B" space create beta --no-setup >/dev/null 2>&1
# alpha's client app: the emulator's calendar provider stands in (its data is safe to clear here).
PKG=com.android.providers.calendar
cat > "$XDG_DATA_HOME/testlab/spaces/alpha/space.json" <<J
{"client":"alpha","githubOwners":["example-org"],"appFamilies":["$PKG"]}
J
adb -s "$E" shell settings put system font_scale 1.0
check "alpha leases the emulator" 'q "$B" --space alpha devices lease "$E" | grep -q "held for the space alpha"'
# alpha made the text bigger (as a large-text test would), remembered in its own state.
echo "{\"$E\":\"1.0\"}" > "$XDG_DATA_HOME/testlab/spaces/alpha/font-scale.json"; adb -s "$E" shell settings put system font_scale 1.3
check "beta sees it as another client's" 'q "$B" --space beta devices | grep "$E" | grep -q "in use by another client"'
check "beta's CLI can't drive it" '! "$B" --space beta connect --serial "$E" >/dev/null 2>&1 && { "$B" --space beta connect --serial "$E" 2>&1 || true; } | grep -q "another client"'
pb=$("$B" --space beta config get mcp.port 2>/dev/null); lb=$(mktemp)
"$B" --space beta serve >/dev/null 2>"$lb" & sb=$!
trap 'kill $sb 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do grep -q "MCP server on" "$lb" && break; sleep 0.5; done
tb=$(python3 -c "import json;print(json.load(open('$XDG_DATA_HOME/testlab/spaces/beta/control.json'))['token'])")
out=$(NTL_MCP_URL=http://127.0.0.1:$pb/mcp NTL_TOKEN=$tb python3 scripts/dev/mcp-call.py handsets_tap_xy "{\"x\":10,\"y\":10,\"serial\":\"$E\"}" 2>&1 || true)
check "beta's handsets_tap_xy is refused" 'echo "$out" | grep -q "another client" && ! echo "$out" | grep -q alpha'
kill $sb; wait $sb 2>/dev/null || true
rel=$(q "$B" --space alpha devices release "$E")
echo "$rel" | sed 's/^/      /'
check "alpha's release cleared its app's data" 'echo "$rel" | grep -q "cleared $PKG"'
check "…and put the font size back" '[ "$(adb -s "$E" shell settings get system font_scale | tr -d "\r")" = 1.0 ]'
check "beta now sees it free" '! q "$B" --space beta devices | grep "$E" | grep -q "another client"'
check "beta drives it" 'q "$B" --space beta connect --serial "$E" | grep -q "connected $E"'
# beta's connect left a plain lease of a process that ended: alpha takes it, with beta's hand-over.
check "a stale lease is taken over" 'q "$B" --space alpha connect --serial "$E" | grep -q "handed over from another client"'
q "$B" --space alpha devices release "$E" >/dev/null
echo "problems: ${fails[*]:-none}"
$keep || scripts/dev/emu.sh down
[ ${#fails[@]} = 0 ]
