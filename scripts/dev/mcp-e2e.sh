#!/usr/bin/env bash
# mcp-e2e.sh — the MCP server end to end on the dev emulator: `testlab serve` with a throwaway
# passphrase vault, then a stdlib-Python MCP client (Streamable HTTP, JSON responses) drives the
# stock Settings app through the tools, runs the every-step plan through handsets_autopilot_plan (stub
# backend) and checks that no stored secret comes back. Sequenced like e2e.sh: build first, stop
# Gradle, then the emulator.
#   scripts/dev/mcp-e2e.sh [--keep]
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

# A throwaway data folder, so the run never touches a real vault.
export XDG_DATA_HOME=$(mktemp -d) XDG_CACHE_HOME=$(mktemp -d)
work=$(mktemp -d)
scripts/dev/install-example-profile.sh "$XDG_DATA_HOME/testlab" "" "" "{\"workspace\":\"$work\"}"
export TESTLAB_VAULT_PASSPHRASE=mcp-e2e-only
echo -n "7Xq-e2e-secret-value" | "$B" secrets set account.e2e.secret
echo -n "e2e-nnin-0101" | "$B" secrets set account.e2e.nnin
TOKEN=$("$B" mcp-config | sed -n 's/.*Bearer \([^"]*\)".*/\1/p' | head -1)
log=$(mktemp)
"$B" serve > /dev/null 2> "$log" &
srv=$!
trap 'kill $srv 2>/dev/null || true; $keep || scripts/dev/emu.sh down' EXIT
for _ in $(seq 1 60); do grep -q "MCP server on" "$log" && break; kill -0 $srv 2>/dev/null || break; sleep 0.5; done
grep -q "MCP server on" "$log" || { echo "the MCP server did not start:" >&2; cat "$log" >&2; exit 1; }
adb shell input keyevent KEYCODE_HOME

NTL_TOKEN=$TOKEN python3 - tools/e2e/settings-every-step.json <<'PY'
import base64, json, os, sys, time, urllib.request
URL = "http://127.0.0.1:8378/mcp"
SECRETS = ["7Xq-e2e-secret-value", "e2e-nnin-0101", os.environ["NTL_TOKEN"]]
sid, n = None, 0

def post(body):
    global sid
    h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", "Authorization": "Bearer " + os.environ["NTL_TOKEN"]}
    if sid: h["Mcp-Session-Id"] = sid
    with urllib.request.urlopen(urllib.request.Request(URL, json.dumps(body).encode(), h), timeout=900) as r:
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
    name = tool
    t = time.time()
    r = rpc("tools/call", {"name": tool, "arguments": args})
    text = "\n".join(c.get("text", "") for c in r["content"] if c["type"] == "text")
    blob = json.dumps(r)
    for s in SECRETS:
        assert s not in blob, f"{name} leaked a secret"
    status = "ERROR" if r.get("isError") else "ok"
    print(f"{name:28s} {status:5s} {time.time() - t:6.2f}s  {text[:110]!r}")
    return r, text

init = rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "mcp-e2e", "version": "1"}})
post({"jsonrpc": "2.0", "method": "notifications/initialized"})
tools = rpc("tools/list", {})["tools"]
print(f"server {init['serverInfo']['name']} {init['serverInfo']['version']}: {len(tools)} tools, instructions {len(init.get('instructions', ''))} chars")
fails = []
def expect(ok, what):
    if not ok: fails.append(what)

r, t = call("handsets_list_adb_devices"); expect("emulator" in t, "devices")
r, t = call("handsets_use_device"); expect(t.startswith("ready emulator-"), "use_device")
r, t = call("handsets_open_app", package="com.android.settings")
r, t = call("handsets_wait_for", selector_or_text="Network & internet", timeout_s=15); expect(t.startswith("ok"), "wait_for")
r, t = call("handsets_ui_dump"); expect('"Network & internet"' in t, "ui_dump table")
r, t = call("handsets_screenshot", size=720); expect(base64.b64decode(r["content"][0]["data"])[:2] == b"\xff\xd8", "screenshot is a JPEG")
r, t = call("handsets_tap", selector_or_text="Network & internet"); expect("via" in t, "tap by text")
r, t = call("handsets_wait_for", selector_or_text="Internet", timeout_s=10); expect(t.startswith("ok"), "wait after tap")
r, t = call("handsets_navigate", target="back")
r, t = call("handsets_tap", selector_or_text="#search_action_bar"); expect("via" in t, "tap by id")
r, t = call("handsets_wait_for", selector_or_text="#open_search_view_edit_text", timeout_s=10)
r, t = call("handsets_fill", selector="Search settings", value="Blåbær "); expect(t.startswith("ok"), "fill by hint")
r, t = call("handsets_wait_for", selector_or_text="Blåbær", timeout_s=5); expect(t.startswith("ok"), "filled text on screen")
r, t = call("handsets_type_secret", ref="e2e.secret"); expect(t == "ok typed e2e.secret", "type_secret")
r, t = call("handsets_ui_dump"); expect("‹redacted›" in t, "typed secret masked in the dump")
r, t = call("handsets_hide_keyboard")
r, t = call("handsets_navigate", target="back")
r, t = call("handsets_navigate", target="back")
r, t = call("handsets_current_focus"); expect("/" in t, "current_focus")
r, t = call("handsets_info"); expect("Android" in t, "info")
r, t = call("handsets_tap", selector_or_text="No such label here"); expect(r.get("isError"), "tap of a missing label errors")
r, t = call("handsets_autopilot_pick", goal="Open Network & internet", backend="stub"); expect('"choice"' in t, "autopilot_pick")
# The stored secret is typed by reference; the wait checks a prefix of it, so the plan never holds the whole value.
plan = open(sys.argv[1]).read().replace("Blåbær syltetøy ÆØÅ 123", "Blåbær syltetøy ÆØÅ 7Xq")
r, t = call("handsets_autopilot_plan", plan=plan, backend="stub")
res = json.loads(t)
expect(res["status"] in ("completed", "paused"), "autopilot_plan ran")
print(f"  plan: {res['status']} in {res.get('elapsed_s')} s, {res.get('decisions')} decisions" + (f", paused at {res.get('paused_at')}: {res.get('hand_back')}" if res["status"] != "completed" else ""))
r, t = call("lab_save_plan", name="settings-every-step", plan=open(sys.argv[1]).read(), overwrite=True); expect(t.startswith("saved"), "save_plan")
r, t = call("lab_list_plans"); expect("settings-every-step" in t, "list_plans")
r, t = call("lab_list_credentials"); expect('"e2e.secret":"set"' in t, "list_credentials")
r, t = call("lab_status"); expect('"active":"emulator-' in t, "status")
r, t = call("lab_autopilot_stats", since_minutes=10); expect('"decisions"' in t, "autopilot_stats")
print("problems:", fails or "none")
sys.exit(1 if fails else 0)
PY
