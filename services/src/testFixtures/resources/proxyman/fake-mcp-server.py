#!/usr/bin/env python3
"""A stand-in for Proxyman's bundled stdio mcp-server: a few of its tools, its "xxx result:" text
prefix, and every call appended to $FAKE_PX_LOG as JSON (name + arguments) for the tests."""
import json, os, sys

LOG = os.environ.get("FAKE_PX_LOG")
FLOWS = [
    {"id": "f1", "method": "GET", "statusCode": 200, "host": "api.example.com", "path": "/api/v1/config", "remoteDeviceIP": "198.51.100.21"},
    {"id": "f2", "method": "POST", "statusCode": 401, "host": "api.example.com", "path": "/api/v1/login|x", "remoteDeviceIP": "198.51.100.22"},
]
RULES = [{"id": "r1", "type": "maplocal"}, {"id": "r2", "type": "breakpoint"}]
TOOLS = [
    {"name": "get_flows", "description": "List captured flows", "inputSchema": {"type": "object", "properties": {"limit": {"type": "integer"}}}},
    {"name": "export_flows", "description": "Export flows", "inputSchema": {"type": "object", "properties": {"format": {"type": "string"}, "save_path": {"type": "string"}, "flow_ids": {"type": "array"}}, "required": ["format"]}},
    {"name": "create_map_local", "description": "Create a Map Local rule", "inputSchema": {"type": "object", "additionalProperties": False, "properties": {"url": {"type": "string"}, "method": {"type": "string"}, "name": {"type": "string"}, "response_body": {"type": "string"}, "use_regex": {"type": "boolean"}}, "required": ["url"]}},
    {"name": "create_scripting_rule", "description": "Create a scripting rule", "inputSchema": {"type": "object", "additionalProperties": False, "properties": {"url": {"type": "string"}, "method": {"type": "string"}, "name": {"type": "string"}, "script_content": {"type": "string"}, "graphql_query_name": {"type": ["string", "null"]}, "enable_request": {"type": "boolean"}, "enable_response": {"type": "boolean"}, "modify_websocket_messages": {"type": "boolean"}}, "required": ["url", "script_content"]}},
    {"name": "list_websocket_sessions", "description": "List captured WebSocket sessions", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "get_websocket_messages", "description": "Read WebSocket messages", "inputSchema": {"type": "object", "properties": {"flow_id": {"type": "string"}}, "required": ["flow_id"]}},
    {"name": "list_rules", "description": "List rules", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "delete_rule", "description": "Delete a rule", "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}, "rule_type": {"type": "string"}}}},
    {"name": "list_reverse_proxies", "description": "", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "list_network_conditions", "description": "", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "list_dns_spoofing", "description": "", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "clear_session", "description": "Clear flows", "inputSchema": {"type": "object", "properties": {}}},
]

if os.environ.get("FAKE_PX_NOGQL"):
    for t in TOOLS: t["inputSchema"].get("properties", {}).pop("graphql_query_name", None)

def wsmsg(i, d, text, action=None):
    m = {"index": i, "direction": d, "opcode": "text", "created_at": "2026-11-02T14:00:0%dZ" % i, "payload_preview": text}
    if action: m["script_action"] = action
    return m
WS = {
    "w2": [wsmsg(0, "client", '{"type":"connection_init"}'), wsmsg(1, "server", '{"type":"connection_ack"}'),
           wsmsg(2, "client", '{"id":"s-1","type":"start","payload":{"data":"{\\"query\\":\\"subscription OnScore { onScore { home } }\\"}"}}'),
           wsmsg(3, "server", '{"type":"ka"}'), wsmsg(4, "server", '{"id":"s-1","type":"data","payload":{"data":{"onScore":{"home":3}}}}', "edited")],
    "w1": [wsmsg(0, "client", '{"type":"connection_init"}'), wsmsg(1, "server", '{"type":"connection_error","payload":{"errors":[{"message":"NoProtocolError","errorCode":400}]}}')],
}

def text(s, err=False):
    return {"content": [{"type": "text", "text": s}], "isError": err}

def call(name, a):
    if LOG:
        with open(LOG, "a") as f: f.write(json.dumps({"name": name, "args": a}) + "\n")
    if name == "get_flows":
        flows = FLOWS
        if a.get("host_filter"):
            # $FAKE_PX_TLS: "fail" = the phone rejects the CA (CONNECT 999), "ok" = decrypted traffic
            tls = os.environ.get("FAKE_PX_TLS")
            if tls == "fail": flows = [{"id": "c1", "method": "CONNECT", "statusCode": 999, "host": "api.example.com", "path": "", "isSSL": True, "isIntercepted": False}]
            elif tls == "ok": flows = [{"id": "c2", "method": "GET", "statusCode": 200, "host": "api.example.com", "path": "/v1", "isSSL": True, "isIntercepted": True}]
            else: flows = []
        return text("get_flows result: " + json.dumps({"flows": flows[: a.get("limit", 100)]}))
    if name == "export_flows":
        with open(a["save_path"], "w") as f:
            json.dump({"log": {"version": "1.2", "entries": [{"request": {"url": "https://" + x["host"] + x["path"]}} for x in FLOWS if x["id"] in a.get("flow_ids", [])]}}, f)
        return text("export_flows result: saved")
    if name == "create_map_local":
        # Proxyman on Linux stores the body as a file named after the rule: a "/" in the name fails.
        if "/" in a.get("name", ""): return text("Failed to create Map Local rule: ENOENT: no such file or directory, open '/root/.config/Proxyman/map-local/" + a["name"] + "_1.json'", True)
        RULES.append({"id": "r%d" % (len(RULES) + 9), "type": "maplocal", "name": a.get("name", ""), "url": a.get("url", "")})
        return text("create_map_local result: " + json.dumps({"id": "r9", "method": a.get("method")}))
    if name == "create_scripting_rule":
        # $FAKE_PX_NOSCRIPT: this Proxyman refuses scripting rules
        if os.environ.get("FAKE_PX_NOSCRIPT"): return text("Scripting is not available in this build", True)
        RULES.append({"id": "s%d" % (len(RULES) + 9), "type": "scripting", "name": a.get("name", ""), "url": a.get("url", "")})
        return text("create_scripting_rule result: " + json.dumps({"id": "s9", "message": "Scripting rule created: " + a.get("name", "")}))
    if name == "list_websocket_sessions":
        return text("list_websocket_sessions result: " + json.dumps({"total_count": 2, "sessions": [
            {"flow_id": "w2", "url": "wss://rt.example.com/graphql/realtime?header=e30=", "host": "rt.example.com", "message_count": 5, "started_at": 1793629514564},
            {"flow_id": "w1", "url": "wss://rt.example.com/graphql/realtime?header=e30=", "host": "rt.example.com", "message_count": 2, "started_at": 1793629500000}]}))
    if name == "get_websocket_messages":
        return text("get_websocket_messages result: " + json.dumps({"flow_id": a["flow_id"], "messages": WS.get(a["flow_id"], [])}))
    if name == "list_rules": return text("list_rules result: " + json.dumps({"rules": RULES}))
    if name == "delete_rule":
        # $FAKE_PX_ZOMBIE: deleted rules come back (Proxyman reassigning ids)
        if not os.environ.get("FAKE_PX_ZOMBIE"): RULES[:] = [r for r in RULES if r["id"] != a.get("id")]
        return text("delete_rule result: ok")
    if name == "list_reverse_proxies": return text("list_reverse_proxies result: " + json.dumps({"reverseProxies": [{"id": "rp1"}]}))
    if name in ("list_network_conditions", "list_dns_spoofing"): return text(name + " result: " + json.dumps({"items": []}))
    if name == "clear_session": return text(name + " result: ok")
    return text("unknown tool " + name, True)

for line in sys.stdin:
    try: m = json.loads(line)
    except Exception: continue
    if "id" not in m: continue
    meth, p = m.get("method"), m.get("params") or {}
    if meth == "initialize": res = {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}}, "serverInfo": {"name": "proxyman-fake", "version": "0"}}
    elif meth == "tools/list": res = {"tools": TOOLS}
    elif meth == "tools/call": res = call(p.get("name"), p.get("arguments") or {})
    else:
        sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": m["id"], "error": {"code": -32601, "message": "no " + str(meth)}}) + "\n"); sys.stdout.flush(); continue
    sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": m["id"], "result": res}) + "\n"); sys.stdout.flush()
