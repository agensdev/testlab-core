#!/usr/bin/env python3
"""mcp-call.py — a tiny stdlib MCP client for poking a running `testlab serve` from the shell.

    NTL_TOKEN=… scripts/dev/mcp-call.py handsets_ui_dump '{"form":"tree"}' [tool args]…

Each pair of arguments is a tool name and its JSON arguments; the calls share one session and
print the result text with the elapsed time.
"""
import json, os, sys, time, urllib.request

URL = os.environ.get("NTL_MCP_URL", "http://127.0.0.1:8378/mcp")
sid, n = None, 0

def post(body):
    global sid
    h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", "Authorization": "Bearer " + (os.environ.get("TESTLAB_TOKEN") or os.environ["NTL_TOKEN"])}
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

rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "mcp-call", "version": "1"}})
post({"jsonrpc": "2.0", "method": "notifications/initialized"})
args = sys.argv[1:]
for i in range(0, len(args), 2):
    tool, a = args[i], json.loads(args[i + 1]) if i + 1 < len(args) else {}
    if tool == "sleep":
        time.sleep(float(a)); continue
    t = time.time()
    r = rpc("tools/call", {"name": tool, "arguments": a})
    text = "\n".join(c.get("text", "") for c in r["content"] if c["type"] == "text")
    print(f"== {tool} {'ERROR ' if r.get('isError') else ''}{time.time() - t:.2f}s\n{text}")
