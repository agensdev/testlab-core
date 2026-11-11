#!/usr/bin/env bash
# rig-e2e.sh — the rig tools on the dev emulator: lab_health measured from the device, the settings
# snapshot (a change shows in diff, restore puts it back), log capture with an app-style analytics
# line read back by lab_analytics_events, and a report whose plan items must all be accounted for.
# Sequenced like mcp-e2e.sh: build first, stop Gradle, then the emulator.
#   scripts/dev/rig-e2e.sh [--keep]
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

export XDG_DATA_HOME=$(mktemp -d) XDG_CACHE_HOME=$(mktemp -d)
work=$(mktemp -d)
scripts/dev/install-example-profile.sh "$XDG_DATA_HOME/testlab" "" "" "{\"workspace\":\"$work\",\"mcp\":{\"confirmWrites\":false}}"
export TESTLAB_VAULT_PASSPHRASE=rig-e2e-only
TOKEN=$("$B" mcp-config | sed -n 's/.*Bearer \([^"]*\)".*/\1/p' | head -1)
log=$(mktemp)
"$B" serve > /dev/null 2> "$log" &
srv=$!
trap 'kill $srv 2>/dev/null || true; adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true; $keep || scripts/dev/emu.sh down' EXIT
for _ in $(seq 1 60); do grep -q "MCP server on" "$log" && break; kill -0 $srv 2>/dev/null || break; sleep 0.5; done
grep -q "MCP server on" "$log" || { echo "the MCP server did not start:" >&2; cat "$log" >&2; exit 1; }

NTL_TOKEN=$TOKEN python3 - <<'PY'
import json, os, subprocess, sys, time, urllib.request
URL = "http://127.0.0.1:8378/mcp"
sid, n = None, 0

def post(body):
    global sid
    h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", "Authorization": "Bearer " + os.environ["NTL_TOKEN"]}
    if sid: h["Mcp-Session-Id"] = sid
    with urllib.request.urlopen(urllib.request.Request(URL, json.dumps(body).encode(), h), timeout=300) as r:
        sid = r.headers.get("Mcp-Session-Id") or sid
        raw = r.read().decode()
        return json.loads(raw) if raw.strip() else None

def rpc(method, params):
    global n
    n += 1
    resp = post({"jsonrpc": "2.0", "id": n, "method": method, "params": params})
    if "error" in resp: raise SystemExit(f"{method}: {resp['error']}")
    return resp["result"]

def call(tool, **args):
    t = time.time()
    r = rpc("tools/call", {"name": tool, "arguments": args})
    text = "\n".join(c.get("text", "") for c in r["content"] if c["type"] == "text")
    print(f"{tool:24s} {'ERROR' if r.get('isError') else 'ok':5s} {time.time() - t:6.2f}s  {text[:110]!r}")
    return r, text

def adb(*a): return subprocess.run(["adb", "shell", *a], capture_output=True, text=True).stdout.strip()

rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "rig-e2e", "version": "1"}})
post({"jsonrpc": "2.0", "method": "notifications/initialized"})
fails = []
def expect(ok, what):
    if not ok: fails.append(what)

call("handsets_use_device")
r, t = call("lab_health")
h = json.loads(t)
for c in h["checks"]: print(f"    {c['result']:4s} {c['check']}: {c['value']}" + (f"  → {c['fix']}" if c.get('fix') else ""))
checks = {c["check"]: c["result"] for c in h["checks"]}
expect(checks.get("adb") == "pass" and checks.get("network") == "pass" and checks.get("proxy") == "pass", "health: adb, network, proxy pass")
expect(checks.get("internet") in ("pass", "fail"), "health: the device's own internet request was made")

r, t = call("lab_device_state", action="snapshot"); expect("snapshot of emulator-" in t, "snapshot")
adb("settings", "put", "system", "font_scale", "1.3")
r, t = call("lab_device_state"); expect("font_scale: 1.0 → 1.3" in t, "diff shows the font change")
r, t = call("lab_health", quick=False); expect("font scale 1.3" in t, "health flags the leftover setting")
r, t = call("lab_device_state", action="restore"); expect("font_scale: 1.3 → 1.0" in t, "restore")
expect(adb("settings", "get", "system", "font_scale") in ("1.0", "1"), "the font scale is back on the device")

r, t = call("lab_logs_start", preset="all", tags=["TestlabE2E:I"]); expect("capturing" in t, "logs_start")
time.sleep(1)
adb("log", "-t", "TestlabE2E", "Tracked analytics event: e2e_event {k=v}")
adb("log", "-t", "TestlabE2E", "Tracked analytics event: second_event {n=2}")
time.sleep(2)
r, t = call("lab_logs_read", grep="Tracked"); expect(t.count("Tracked analytics event") == 2, "logs_read sees both lines")
r, t = call("lab_analytics_events", pattern=r"Tracked analytics event: (?<name>\S+) (?<params>.*)")
ev = json.loads(t); expect([e["name"] for e in ev["events"]] == ["e2e_event", "second_event"], "analytics events in order")

r, t = call("lab_report_start", title="Rig e2e"); rid = t.splitlines()[0].removeprefix("report_id: ")
call("lab_report_items", report_id=rid, items=[{"id": "1", "text": "Analytics events are sent"}, {"id": "2", "text": "Push opens the app"}])
call("lab_report_screenshot", report_id=rid, caption="Home", item="1")
call("lab_report_item", report_id=rid, item="1", status="tested", result="pass", note="two events in the log")
r, t = call("lab_report_write", report_id=rid, markdown="Rig e2e."); expect(r.get("isError") and "neither tested" in t, "write refused with an open item")
call("lab_report_item", report_id=rid, item="2", status="not_tested", note="no push on the emulator")
r, t = call("lab_logs_stop", report_id=rid); expect("2 line(s)" in t or "line(s)" in t, "logs_stop"); expect("logs/" in t, "log attached")
r, t = call("lab_report_write", report_id=rid, markdown="Rig e2e."); expect("No failures in what was tested. 1 item(s) not tested." in t, "result line from the items")
print("problems:", fails or "none")
sys.exit(1 if fails else 0)
PY
