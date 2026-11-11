#!/usr/bin/env bash
# apps-e2e.sh — the MCP Apps live-screen view in a real browser: `testlab serve` on the dev
# emulator, a minimal MCP Apps host (tools/e2e/apps-host.py) loading the view in headless
# Chromium; passes when the view polls frames and a click on it taps the phone at the right spot.
# Leaves build/apps-e2e.png (the view as rendered).
#   scripts/dev/apps-e2e.sh [--keep]
set -euo pipefail
cd "$(dirname "$0")/../.."
keep=false; [ "${1:-}" = "--keep" ] && keep=true
command -v chromium >/dev/null || { echo "needs chromium (apt-get install chromium)" >&2; exit 1; }

B=cli/build/install/testlab/bin/testlab
if ! adb devices 2>/dev/null | grep -q '^emulator-[0-9]*\s*device$'; then
  ./gradlew :cli:installDist --console=plain -q
  ./gradlew --stop >/dev/null 2>&1 || true
  scripts/dev/emu.sh up
fi
export XDG_DATA_HOME=$(mktemp -d) XDG_CACHE_HOME=$(mktemp -d) TESTLAB_VAULT_PASSPHRASE=apps-e2e-only
scripts/dev/install-example-profile.sh "$XDG_DATA_HOME/testlab" "" "" '{}'
TOKEN=$("$B" mcp-config | sed -n 's/.*Bearer \([^"]*\)".*/\1/p' | head -1)
log=$(mktemp); report=$(mktemp)
"$B" serve > /dev/null 2> "$log" &
srv=$!
trap 'kill $srv ${br:-} 2>/dev/null || true; $keep || scripts/dev/emu.sh down' EXIT
for _ in $(seq 1 60); do grep -q "MCP server on" "$log" && break; kill -0 $srv 2>/dev/null || break; sleep 0.5; done
grep -q "MCP server on" "$log" || { echo "the MCP server did not start:" >&2; cat "$log" >&2; exit 1; }
adb shell input keyevent KEYCODE_HOME
adb logcat -c

# A real report to show: recorded on the emulator, a chapter per action and a screenshot.
NTL_TOKEN=$TOKEN python3 - <<'PY'
import json, os, re, subprocess, sys
def call(*pairs):
    out = subprocess.run([sys.executable, "scripts/dev/mcp-call.py", *pairs], capture_output=True, text=True, env=os.environ, check=True).stdout
    return out
call("handsets_use_device", "{}", "lab_session_start", "{}")
rid = re.search(r"report_id: (\S+)", call("lab_report_start", json.dumps({"title": "Apps e2e"}))).group(1)
call("lab_session_note", json.dumps({"report_id": rid, "text": "Open Settings", "kind": "chapter"}),
     "handsets_open_app", json.dumps({"package": "com.android.settings", "why": "start from the Settings home screen"}), "sleep", "2",
     "lab_report_screenshot", json.dumps({"report_id": rid, "caption": "Settings"}),
     "lab_session_note", json.dumps({"report_id": rid, "text": "Scroll down", "kind": "chapter"}),
     "handsets_swipe", json.dumps({"direction": "up", "why": "scroll to the entries further down"}), "sleep", "2",
     "handsets_navigate", json.dumps({"target": "home"}), "sleep", "1",
     "lab_report_write", json.dumps({"report_id": rid, "markdown": "Settings opened and scrolled.\n\n![Settings](01-settings.jpg)"}))
print("report", rid)
PY

out=$(mktemp -d)
NTL_TOKEN=$TOKEN python3 tools/e2e/apps-host.py 8411 "$out" & host=$!
trap 'kill $srv $host 2>/dev/null || true; $keep || scripts/dev/emu.sh down' EXIT
sleep 3
for v in screen report; do
  timeout 120 chromium --headless --no-sandbox --disable-gpu --window-size=900,860 --remote-debugging-port=0 "http://127.0.0.1:8411/?v=$v" >/dev/null 2>&1 & drv=$!
  for _ in $(seq 1 200); do [ -s "$out/$v.json" ] && break; sleep 0.5; done
  kill $drv 2>/dev/null || true; wait $drv 2>/dev/null || true
  timeout 90 chromium --headless --no-sandbox --disable-gpu --window-size=920,880 --virtual-time-budget=10000 --screenshot="$PWD/build/apps-e2e-$v.png" "http://127.0.0.1:8411/?v=$v" >/dev/null 2>&1 || true
done
size=$(adb shell wm size | sed -n 's/.*: \([0-9]*x[0-9]*\).*/\1/p' | tail -1)
python3 - "$out" "$size" <<'PY'
import json, os, sys
out, (w, h) = sys.argv[1], map(int, sys.argv[2].split("x"))
problems = []
def load(v):
    p = os.path.join(out, v + ".json")
    if not os.path.isfile(p): problems.append(f"the {v} view never reported"); return {}
    r = json.load(open(p)); print(v, json.dumps(r, indent=1))
    if not (r["init"] and r["initialized"]): problems.append(f"{v}: no ui/initialize handshake")
    return r
s = load("screen")
if s:
    if s["calls"].get("handsets_view_frame", 0) < 3: problems.append("screen: fewer than 3 frames")
    if s.get("msg"): problems.append("screen: shows an error: " + s["msg"])
    t = s.get("tap")
    if not t: problems.append("screen: the click did not tap")
    elif abs(t["x"] - w / 2) > w * 0.04 or abs(t["y"] - h / 2) > h * 0.04: problems.append(f"screen: tap at {t['x']},{t['y']}, not the center of {w}x{h}")
    if not (s.get("rewound") and "ago" in s.get("when", "") and s.get("backLive")): problems.append("screen: clicking the action did not rewind (or Live did not return)")
r = load("report")
if r:
    if not r.get("video") or not r["video"]["duration"] > 0: problems.append("report: the recording did not load")
    if r.get("shots", 0) < 1: problems.append("report: no screenshot")
    if len(r.get("chapters", [])) < 2: problems.append("report: fewer than 2 chapters")
    elif not r.get("seekedTo", 0) > 0: problems.append("report: a chapter click did not seek")
    if r.get("statusError"): problems.append("report: " + r.get("status", ""))
print("PASS" if not problems else "FAIL: " + "; ".join(problems))
sys.exit(1 if problems else 0)
PY
