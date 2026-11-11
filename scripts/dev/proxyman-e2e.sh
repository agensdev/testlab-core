#!/usr/bin/env bash
# proxyman-e2e.sh — Proxyman routing on the dev emulator, over MCP, against fake-proxy.py (the
# proxy port) and the fake Proxyman mcp-server from the test fixtures: handsets_proxy on sets the
# phone's proxy through adb reverse and the phone's traffic arrives; proxyman_* tools are
# mirrored; the report gets flows.har; off restores the direct connection; after kill -9 the
# next start restores it. Sequenced like e2e.sh.
#   scripts/dev/proxyman-e2e.sh [--keep]
set -euo pipefail
cd "$(dirname "$0")/../.."
keep=false; [ "${1:-}" = "--keep" ] && keep=true
B=cli/build/install/testlab/bin/testlab
if ! adb devices 2>/dev/null | grep -q '^emulator-[0-9]*\s*device$'; then
  ./gradlew :cli:installDist --console=plain -q
  ./gradlew --stop >/dev/null 2>&1 || true
  scripts/dev/emu.sh up
fi
export XDG_DATA_HOME=$(mktemp -d) XDG_CACHE_HOME=$(mktemp -d) TESTLAB_VAULT_PASSPHRASE=proxyman-e2e-only
work=$(mktemp -d); pport=8397; plog=$(mktemp)
fakemcp="$work/mcp-server"; cp services/src/testFixtures/resources/proxyman/fake-mcp-server.py "$fakemcp"; chmod +x "$fakemcp"
mkdir -p "$XDG_DATA_HOME/testlab"
scripts/dev/install-example-profile.sh "$XDG_DATA_HOME/testlab" "" "" "{\"workspace\":\"$work\",\"mcp\":{\"confirmWrites\":false},\"proxyman\":{\"enabled\":true,\"port\":$pport,\"mcpServer\":\"$fakemcp\"}}"
python3 scripts/dev/fake-proxy.py "$pport" "$plog" & fp=$!
TOKEN=$("$B" mcp-config | sed -n 's/.*Bearer \([^"]*\)".*/\1/p' | head -1)
srv=
serve() { log=$(mktemp); "$B" serve > /dev/null 2> "$log" & srv=$!; for _ in $(seq 1 60); do grep -q "MCP server on" "$log" && break; sleep 0.5; done; sleep 2; }
trap 'kill $fp ${srv:-} 2>/dev/null || true; adb shell settings put global http_proxy :0 >/dev/null 2>&1; adb reverse --remove-all >/dev/null 2>&1 || true; $keep || scripts/dev/emu.sh down' EXIT
call() { NTL_TOKEN=$TOKEN scripts/dev/mcp-call.py "$@"; }
fails=()
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; fails+=("$1"); fi; }
phoneproxy() { adb shell settings get global http_proxy | tr -d '\r'; }
adb shell settings put global http_proxy :0 >/dev/null

serve
on=$(call handsets_use_device '{}' handsets_proxy '{"action":"on"}')
echo "$on" | tail -1 | cut -c1-200
check "the phone's proxy is 127.0.0.1:$pport" '[ "$(phoneproxy)" = "127.0.0.1:$pport" ]'
check "adb reverse carries the port" 'adb reverse --list | grep -q "tcp:$pport"'
# Traffic from the phone through the reverse port arrives at the proxy (toybox nc on the device).
adb shell "printf 'GET http://example.com/tl-e2e HTTP/1.1\r\nHost: example.com\r\n\r\n' | nc -w 3 127.0.0.1 $pport" > /dev/null 2>&1 || true
# And an app using the system proxy (the browser/WebView, when the image has one).
adb shell am start -a android.intent.action.VIEW -d "http://example.com/tl-e2e-browser" > /dev/null 2>&1 || true
sleep 6
echo "  proxy saw: $(tr '\n' ' ' < "$plog" | cut -c1-200)"
check "the phone's traffic reaches the proxy" 'grep -q "tl-e2e" "$plog" || grep -q "^\(GET\|CONNECT\) " "$plog"'
tools=$(NTL_TOKEN=$TOKEN python3 - <<'PY'
import json, os, urllib.request
u = "http://127.0.0.1:8378/mcp"; h = {"Authorization": "Bearer " + os.environ["NTL_TOKEN"], "Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
def post(body, sid=None):
    hh = dict(h); sid and hh.update({"mcp-session-id": sid})
    r = urllib.request.urlopen(urllib.request.Request(u, json.dumps(body).encode(), hh)); return r.headers.get("mcp-session-id"), r.read().decode()
sid, _ = post({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "e2e", "version": "0"}}})
post({"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
_, t = post({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, sid)
print(" ".join(sorted(x["name"] for x in json.loads(t[t.index("{"):].splitlines()[0] if t.startswith("{") else [l[5:] for l in t.splitlines() if l.startswith("data:")][0])["result"]["tools"] if x["name"].startswith("proxyman_"))))
PY
)
echo "  mirrored: $tools"
check "Proxyman's tools are mirrored as proxyman_*" '[[ "$tools" == *proxyman_get_flows* ]] && [[ "$tools" == *proxyman_create_map_local* ]]'
health=$(call lab_proxyman_health '{}')
check "health: the proxy answers a real request" '[[ "$health" == *"\"healthy\":true"* ]]'
rid=$(call lab_report_start '{"title":"Proxyman e2e"}' | sed -n 's/^report_id: //p')
w=$(call lab_report_write "{\"report_id\":\"$rid\",\"markdown\":\"Nettverk.\"}")
echo "$w" | sed 's/^/  /'
check "the report gets flows.har and a Network log" '[ -s "$work/reports/$rid/flows.har" ] && grep -q "## Network log (Proxyman)" "$work/reports/$rid/report.md" && grep -q "api.example.com" "$work/reports/$rid/index.html"'
call handsets_proxy '{"action":"off"}' > /dev/null
check "off: direct again, reverse removed" '[ "$(phoneproxy)" = ":0" ] && ! adb reverse --list | grep -q "tcp:$pport"'

# A crash while routed: the next start gives the phone its network back.
call handsets_proxy '{"action":"on"}' > /dev/null
check "routed again before the crash" '[ "$(phoneproxy)" = "127.0.0.1:$pport" ]'
kill -9 $srv; sleep 1
serve
sleep 3
check "after kill -9 the next start restores the direct connection" '[ "$(phoneproxy)" = ":0" ]'
echo "problems: ${fails[*]:-none}"
[ ${#fails[@]} = 0 ]
