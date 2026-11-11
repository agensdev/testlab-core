#!/usr/bin/env bash
# e2e.sh — device end-to-end runs in the dev container, sequenced for its limits:
# build first, stop the Gradle daemons, then boot the emulator and run. A Gradle build next to
# the emulator starves its vCPU threads (the emulator's watchdog aborts after 15 s) and pushes
# the container to its 6 GB cap.
#   scripts/dev/e2e.sh [--keep]     # --keep leaves the emulator running afterwards
set -euo pipefail
cd "$(dirname "$0")/../.."
keep=false; [ "${1:-}" = "--keep" ] && keep=true

if ! adb devices 2>/dev/null | grep -q '^emulator-[0-9]*\s*device$'; then
  ./gradlew :cli:installDist --console=plain -q
  ./gradlew --stop >/dev/null 2>&1 || true
  scripts/dev/emu.sh up
fi
B=cli/build/install/testlab/bin/testlab
[ -x "$B" ] || { echo "build the CLI first (./gradlew :cli:installDist) — not while the emulator runs" >&2; exit 1; }

shots=$(mktemp -d)
adb shell input keyevent KEYCODE_HOME
NTL_CRED_E2E_SECRET=123 "$B" plan --file tools/e2e/settings-every-step.json --stub --shots "$shots" > "$shots/result.json"
python3 - "$shots/result.json" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))
bad = [(s["i"], s["status"], s.get("error", "")) for s in d["steps"] if s["status"] not in ("ok", "acted", "goal_done")]
print(f"settings-every-step: {d['status']} in {d['elapsed_s']} s, {d['decisions']} decisions, problems: {bad or 'none'}")
sys.exit(0 if d["status"] == "completed" else 1)
EOF
$keep || scripts/dev/emu.sh down
