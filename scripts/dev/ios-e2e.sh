#!/usr/bin/env bash
# ios-e2e.sh — an iOS Simulator end to end (macOS with Xcode and Node): builds the TestShop app,
# boots an iPhone simulator, `testlab setup ios` (Appium + WebDriverAgent), `testlab serve` with a
# throwaway home and passphrase vault, then a stdlib-Python MCP client drives the app through the
# same tools as a phone: dump, fill, tap, back, a deeplink, a plan, a recording and a report.
# Then the same steps timed twice, with the quick iOS path off and on (TESTLAB_IOS_FAST=0/1): a
# table of medians, also in the GitHub job summary. IOS_BENCH=0 leaves the timing out.
#   scripts/dev/ios-e2e.sh            # needs ./gradlew :cli:installDist first
set -euo pipefail
cd "$(dirname "$0")/../.."
B=$PWD/cli/build/install/testlab/bin/testlab
[ -x "$B" ] || { echo "build the CLI first: ./gradlew :cli:installDist" >&2; exit 1; }

# A throwaway home: data, cache and workspace never touch a real install.
H=$(mktemp -d)
# What the screen showed when something failed (uploaded by the workflow).
ART=${E2E_ARTIFACTS:-$H/artifacts}
mkdir -p "$ART"
export JAVA_OPTS="-Duser.home=$H" TESTLAB_VAULT_PASSPHRASE=ios-e2e-only
DATA="$H/Library/Application Support/Testlab"
GH_PORT=18766
mkdir -p "$DATA/profiles/testshop-ios"
cat > "$DATA/profiles/testshop-ios/profile.json" <<JSON
{"id": "testshop-ios", "name": "Test Shop iOS", "platform": "ios",
 "app": {"defaultPackage": "com.example.testshop", "family": "com.example.testshop", "label": "Test Shop"},
 "github": {"apiUrl": "http://127.0.0.1:$GH_PORT", "repo": "example-org/shop-ios"},
 "builds": [{"id": "simulator", "label": "Simulator", "workflowFile": "build_simulator.yml", "artifact": "TestShop-Simulator",
             "packageName": "com.example.testshop", "default": true}],
 "navigation": {"host": "testshop://", "destinations": {"cart": {"path": "testshop://cart"}}}}
JSON
echo '{"platformProfiles": {"ios": "testshop-ios"}}' > "$DATA/settings.json"
echo -n "ghp_ios_e2e_token" | "$B" secrets set github.token

app=$(tools/testapp-ios/build.sh "$H/app")
echo "built $app"
# The artifact as CI uploads it: the bundle's contents at the zip's root, no TestShop.app folder.
(cd "$app" && ditto -c -k . "$H/artifact.zip")

# A fake GitHub with PR 7 and its simulator build (what handsets_install_branch_build reads).
cat > "$H/fake-gh.py" <<'PYGH'
import json, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
port, zip_path = int(sys.argv[1]), sys.argv[2]
R = "/repos/example-org/shop-ios"
NOW = "2026-10-12T08:00:00Z"
run = {"id": 77, "name": "Build Simulator", "path": ".github/workflows/build_simulator.yml", "head_branch": "feature/IOS-7-cart",
       "head_sha": "cafe7777cafe", "status": "completed", "conclusion": "success", "event": "pull_request", "run_number": 7,
       "created_at": NOW, "html_url": "https://github.com/example-org/shop-ios/actions/runs/77"}
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def out(self, code, body, ctype="application/json"):
        if not isinstance(body, bytes): body = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        u = urlparse(self.path)
        if u.path == R + "/pulls/7":
            return self.out(200, {"number": 7, "title": "IOS-7 Ny kurv", "draft": False, "state": "open", "html_url": "https://github.com/x/pull/7", "head": {"ref": "feature/IOS-7-cart", "sha": "cafe7777cafe"}})
        if u.path == R + "/actions/workflows/build_simulator.yml/runs":
            return self.out(200, {"workflow_runs": [run] if "status=success" in u.query else []})
        if u.path == R + "/actions/runs/77/artifacts":
            import os
            return self.out(200, {"artifacts": [{"id": 901, "name": "TestShop-Simulator", "size_in_bytes": os.path.getsize(zip_path), "expired": False,
                                                 "created_at": NOW, "workflow_run": {"id": 77, "head_branch": "feature/IOS-7-cart", "head_sha": "cafe7777cafe"}}]})
        if u.path == R + "/actions/artifacts/901/zip":
            return self.out(200, open(zip_path, "rb").read(), "application/zip")
        return self.out(404, {"message": "Not Found: " + self.path})
ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
PYGH
python3 "$H/fake-gh.py" "$GH_PORT" "$H/artifact.zip" & gh=$!

# The newest iPhone simulator, booted.
udid=$(xcrun simctl list devices available -j | python3 -c '
import json, sys, re
d = json.load(sys.stdin)["devices"]
rt = sorted((k for k in d if "iOS" in k), key=lambda k: [int(x) for x in re.findall(r"\d+", k)])
for k in reversed(rt):
    for dev in d[k]:
        if dev["name"].startswith("iPhone"): print(dev["udid"]); sys.exit()')
[ -n "$udid" ] || { echo "no iPhone simulator" >&2; exit 1; }
xcrun simctl boot "$udid" 2>/dev/null || true
xcrun simctl bootstatus "$udid" -b
xcrun simctl uninstall "$udid" com.example.testshop 2>/dev/null || true
echo "simulator $udid"

"$B" setup ios
log=$(mktemp)
start_server() {
  : > "$log"
  "$B" serve > /dev/null 2> "$log" &
  srv=$!
  for _ in $(seq 1 120); do grep -q "MCP server on" "$log" && break; kill -0 $srv 2>/dev/null || break; sleep 0.5; done
  grep -q "MCP server on" "$log" || { echo "the MCP server did not start:" >&2; cat "$log" >&2; exit 1; }
}
start_server
finish() {
  code=$?
  kill $srv $gh 2>/dev/null || true
  if [ $code -ne 0 ]; then
    for f in "$log" "$DATA"/logs/*.log; do [ -f "$f" ] && { echo "== $f"; tail -150 "$f"; cp "$f" "$ART/" 2>/dev/null || true; }; done
    xcrun simctl io "$udid" screenshot "$ART/simulator-at-exit.png" 2>/dev/null || true
  fi
  xcrun simctl shutdown "$udid" 2>/dev/null || true
}
trap finish EXIT
TOKEN=$("$B" mcp-config | sed -n 's/.*Bearer \([^"]*\)".*/\1/p' | head -1)

cat > "$H/mcp_client.py" <<'PY'
import json, os, time, urllib.request
URL = "http://127.0.0.1:8378/mcp"
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

def init():
    rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "ios-e2e", "version": "1"}})
    post({"jsonrpc": "2.0", "method": "notifications/initialized"})

def timed(tool, **args):
    t = time.time()
    r = rpc("tools/call", {"name": tool, "arguments": args})
    return time.time() - t, r
PY

NTL_TOKEN=$TOKEN UDID=$udid ART="$ART" REPORTS="$H/Documents/Testlab/reports" python3 - <<'PY'
import base64, json, os, sys, time, urllib.request, glob
URL = "http://127.0.0.1:8378/mcp"
UDID = os.environ["UDID"]
sid, n, problems = None, 0, []

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
    t = time.time()
    r = rpc("tools/call", {"name": tool, "arguments": args})
    text = "\n".join(c.get("text", "") for c in r["content"] if c["type"] == "text")
    images = [c for c in r["content"] if c["type"] == "image"]
    ok = not r.get("isError")
    print(f"{tool:30} {'ok ' if ok else 'ERR'} {time.time() - t:6.2f}s  {text[:110]!r}")
    if not ok:
        problems.append(f"{tool}: {text[:300]}")
        if tool == "handsets_wait_for": show_screen(f"{len(problems)}-{tool}")
    return text, images

def show_screen(name):
    """What was on the screen when a wait failed: the dump in the log, the screenshot as an artifact."""
    d = rpc("tools/call", {"name": "handsets_ui_dump", "arguments": {}})
    print("    screen then:\n" + "\n".join("      " + l for l in d["content"][0].get("text", "").splitlines()[:40]))
    s = rpc("tools/call", {"name": "handsets_screenshot", "arguments": {}})
    for c in s["content"]:
        if c["type"] == "image": open(os.path.join(os.environ["ART"], name + ".jpg"), "wb").write(base64.b64decode(c["data"]))

def expect(cond, what):
    print(f"  {'ok' if cond else 'FAIL'}  {what}")
    if not cond: problems.append(what)

rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "ios-e2e", "version": "1"}})
post({"jsonrpc": "2.0", "method": "notifications/initialized"})

devs, _ = call("handsets_list_adb_devices")
expect(f'"serial":"{UDID}"' in devs and '"platform":"ios"' in devs, "the simulator is listed as an iOS device")
call("handsets_use_device", serial=UDID)
inst, _ = call("handsets_install_branch_build", branch_or_pr="7", serial=UDID)
expect('"package":"com.example.testshop"' in inst and '"install":"Success"' in inst and '"versionName":"1.2.0"' in inst, "PR 7's simulator build installs from the fake GitHub")
builds, _ = call("handsets_installed_builds", serial=UDID)
expect('"branch":"feature/IOS-7-cart"' in builds, "and is recorded with its branch")
call("handsets_open_app", package="com.example.testshop")
call("handsets_wait_for", selector_or_text="Log in", timeout_s=30)
dump, _ = call("handsets_ui_dump")
expect("#email" in dump and "#password" in dump and "#login" in dump, "the dump shows the form's fields and button by id")
call("handsets_fill", selector="E-mail", value="kari@example.com")
call("handsets_fill", selector="Password", value="hemmelig")
call("handsets_hide_keyboard")
call("lab_session_start")
call("handsets_tap", selector_or_text="#login")
call("handsets_wait_for", selector_or_text="Welcome, kari@example.com", timeout_s=15)
focus, _ = call("handsets_current_focus")
expect("com.example.testshop/Welcome" in focus, "the screen's «activity» is the app and its title")
dump, _ = call("handsets_ui_dump")
expect("Blåbærsyltetøy" in dump, "non-ASCII text reads back")
call("handsets_navigate", target="back")
call("handsets_wait_for", selector_or_text="Remember me", timeout_s=15)
call("handsets_open_url", url="testshop://cart")
call("handsets_wait_for", selector_or_text="Your cart is empty", timeout_s=15)
plan, _ = call("handsets_jev_plan", plan='[{"tap":"Home"},{"wait_for":"Remember me"}]', backend="stub")
expect('"completed"' in plan, "a plan runs on the simulator")
_, imgs = call("handsets_screenshot")
expect(len(imgs) == 1, "a screenshot comes back as an image")
fs, _ = call("handsets_font_scale", action="set", scale=1.3)
call("handsets_font_scale", action="reset")
r = rpc("tools/call", {"name": "handsets_talkback", "arguments": {}})
expect(r.get("isError") and "not available on iOS" in r["content"][0]["text"], "TalkBack says it is Android only")
start, _ = call("lab_report_start", title="iOS e2e")
rid = start.splitlines()[0].replace("report_id: ", "").strip()
call("lab_report_write", report_id=rid, markdown="Logged in on the simulator.")
mp4 = glob.glob(os.path.join(os.environ["REPORTS"], "**", "*.mp4"), recursive=True)
expect(any(os.path.getsize(f) > 10_000 for f in mp4), f"the report has the simulator's recording ({mp4})")

print("problems: " + ("none" if not problems else ""))
for p in problems: print("  - " + p)
sys.exit(1 if problems else 0)
PY

[ "${IOS_BENCH:-1}" = "0" ] && exit 0

# The same steps timed with the quick iOS path off, then on: each server opens its own session.
cat > "$H/bench.py" <<'PY'
import json, os, statistics, sys
sys.path.insert(0, os.path.dirname(__file__))
import mcp_client as m
UDID, N = os.environ["UDID"], int(os.environ.get("BENCH_N", "15"))
m.init()
m.timed("handsets_use_device", serial=UDID)
m.timed("handsets_open_app", package="com.example.testshop")
m.timed("handsets_wait_for", selector_or_text="Remember me", timeout_s=30)
steps = {
    "ui_dump": lambda: m.timed("handsets_ui_dump"),
    "screenshot": lambda: m.timed("handsets_screenshot"),
    "current_focus": lambda: m.timed("handsets_current_focus"),
    "tap (by text)": lambda: m.timed("handsets_tap", selector_or_text="Remember me"),
    "plan, 4 taps": lambda: m.timed("handsets_jev_plan", plan='[{"tap":"Remember me"},{"tap":"Remember me"},{"tap":"Remember me"},{"tap":"Remember me"}]', backend="stub"),
    "plan, 4 tab switches": lambda: m.timed("handsets_jev_plan", plan='[{"tap":"Cart"},{"tap":"Home"},{"tap":"Cart"},{"tap":"Home"}]', backend="stub"),
}
out = {}
for name, f in steps.items():
    f(); f()  # warm-up
    times = []
    for _ in range(N):
        t, r = f()
        text = r["content"][0].get("text", "") if r.get("content") else ""
        if r.get("isError") or ("plan" in name and '"completed"' not in text): raise SystemExit(f"{name}: {text[:300]}")
        times.append(t)
    out[name] = {"median": statistics.median(times), "p90": sorted(times)[int(len(times) * 0.9) - 1]}
    print(f"  {name:16} median {out[name]['median']:.3f}s  p90 {out[name]['p90']:.3f}s", file=sys.stderr)
print(json.dumps(out))
PY
for mode in 0 1; do
  kill $srv 2>/dev/null || true; wait $srv 2>/dev/null || true
  echo "== timing with TESTLAB_IOS_FAST=$mode"
  TESTLAB_IOS_FAST=$mode start_server
  NTL_TOKEN=$TOKEN UDID=$udid python3 "$H/bench.py" > "$H/bench-$mode.json"
done
python3 - "$H/bench-0.json" "$H/bench-1.json" <<'PY'
import json, os, sys
before, after = (json.load(open(f)) for f in sys.argv[1:3])
rows = ["| step | before (median) | after (median) | faster |", "|---|---:|---:|---:|"]
for k in before:
    b, a = before[k]["median"], after[k]["median"]
    rows.append(f"| {k} | {b * 1000:.0f} ms | {a * 1000:.0f} ms | {b / a:.1f}× |")
table = f"### iOS quick path ({os.environ.get('TESTLAB_IOS_DRIVER') or 'default'} driver)\n\n" + "\n".join(rows) + "\n"
print(table)
if os.environ.get("GITHUB_STEP_SUMMARY"): open(os.environ["GITHUB_STEP_SUMMARY"], "a").write(table)
PY
