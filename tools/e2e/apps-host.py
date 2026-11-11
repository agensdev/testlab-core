#!/usr/bin/env python3
"""apps-host.py — a minimal MCP Apps host for testing the views in a real browser.

Serves /?v=screen and /?v=report: the page loads that view (resources/read) in an iframe,
answers its ui/initialize, sends tool-input/tool-result for the tool that opens it, forwards the
view's tools/call to the MCP server, exercises the view and POSTs its findings to /report?v=…,
which land in OUT_DIR/<view>.json. Runs until killed.

    NTL_TOKEN=… apps-host.py PORT OUT_DIR
"""
import json, os, sys, threading, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

URL = os.environ.get("NTL_MCP_URL", "http://127.0.0.1:8378/mcp")
port, outdir = int(sys.argv[1]), sys.argv[2]
sid, n, lock = None, 0, threading.Lock()

def post(body):
    global sid
    h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", "Authorization": "Bearer " + os.environ["NTL_TOKEN"]}
    if sid: h["Mcp-Session-Id"] = sid
    with urllib.request.urlopen(urllib.request.Request(URL, json.dumps(body).encode(), h), timeout=120) as r:
        sid = r.headers.get("Mcp-Session-Id") or sid
        raw = r.read().decode()
        return json.loads(raw) if raw.strip() else None

def rpc(method, params):
    global n
    with lock:
        n += 1; i = n
    return post({"jsonrpc": "2.0", "id": i, "method": method, "params": params})

rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {"extensions": {"io.modelcontextprotocol/ui": {"mimeTypes": ["text/html;profile=mcp-app"]}}}, "clientInfo": {"name": "apps-host", "version": "1"}})
post({"jsonrpc": "2.0", "method": "notifications/initialized"})
tools = {t["name"]: t for t in rpc("tools/list", {})["result"]["tools"]}
OPENER = {"screen": "handsets_show_screen", "report": "lab_show_report"}
views = {}
for v, tool in OPENER.items():
    c = rpc("resources/read", {"uri": tools[tool]["_meta"]["ui"]["resourceUri"]})["result"]["contents"][0]
    assert c["mimeType"] == "text/html;profile=mcp-app", c["mimeType"]
    views[v] = c["text"]

COMMON = """
const V = new URLSearchParams(location.search).get('v'), OPENER = %s[V];
const f = document.getElementById('f'); f.src = '/view?v=' + V;
f.addEventListener('load', () => { (seen.loads = seen.loads || []).push(Date.now()); f.contentWindow.addEventListener('error', (e) => seen.errors.push('js: ' + e.message)); });
const seen = {init:false, initialized:false, calls:{}, errors:[]};
const rpc = (method, params) => fetch('/rpc', {method:'POST', body: JSON.stringify({method, params})}).then(r => r.json());
const toView = (m) => f.contentWindow.postMessage(Object.assign({jsonrpc:'2.0'}, m), '*');
window.addEventListener('message', async (e) => {
  const d = e.data; if (!d || d.jsonrpc !== '2.0' || e.source !== f.contentWindow) return;
  if (d.method === 'ui/initialize') { seen.init = true; toView({id:d.id, result:{protocolVersion:'2025-06-18', hostInfo:{name:'apps-host',version:'1'}, hostCapabilities:{serverTools:{}}, hostContext:{theme:'light', displayMode:'inline', availableDisplayModes:['inline']}}}); return; }
  if (d.method === 'ui/notifications/initialized') {
    seen.initialized = true; toView({method:'ui/notifications/tool-input', params:{arguments:{}}});
    const r = await rpc('tools/call', {name:OPENER, arguments:{}}); toView({method:'ui/notifications/tool-result', params:r.result}); return;
  }
  if (d.method === 'tools/call') {
    const name = d.params.name; seen.calls[name] = (seen.calls[name] || 0) + 1;
    if (name === 'handsets_tap_xy') { seen.tap = d.params.arguments; seen.tapAt = Date.now(); }
    const r = await rpc('tools/call', d.params);
    if (r.error) { seen.errors.push(r.error.message); toView({id:d.id, error:r.error}); } else toView({id:d.id, result:r.result});
  }
});
const t0 = Date.now(); const sleep = (ms) => new Promise(r => setTimeout(r, ms));
async function until(cond, ms) { const end = Date.now() + ms; while (Date.now() < end) { try { if (cond()) return true; } catch {} await sleep(250); } return false; }
async function finish() { await fetch('/report?v=' + V, {method:'POST', body: JSON.stringify(seen)}); document.title = 'done'; }
""" % json.dumps(OPENER)

SCREEN = """
(async () => {
  const doc = () => f.contentDocument;
  await until(() => (seen.calls['handsets_view_frame'] || 0) >= 3 && doc().getElementById('screen').naturalWidth, 30000);
  const img = doc().getElementById('screen'), r = img.getBoundingClientRect();
  seen.img = {w: img.naturalWidth, h: img.naturalHeight};
  for (const type of ['pointerdown', 'pointerup']) img.dispatchEvent(new PointerEvent(type, {clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, bubbles: true}));
  // Rewind: wait for this tap in the activity list, click it, the view shows that moment.
  const rw = () => f.contentWindow.__rewind;
  const mine = () => rw().calls().findIndex(c => c.tool === 'handsets_tap_xy' && c.at >= seen.tapAt - 1000);
  seen.listed = await until(() => seen.tapAt && mine() >= 0 && doc().querySelector('li.act[data-i="' + mine() + '"]'), 10000);
  await sleep(1500);
  const li = doc().querySelector('li.act[data-i="' + mine() + '"]');
  seen.liFound = !!li;
  if (li) li.click();
  seen.whenAtClick = doc().getElementById('when').textContent;
  await sleep(300);
  seen.rewound = doc().querySelector('.phone').classList.contains('past');
  seen.when = doc().getElementById('when').textContent;
  seen.marks = doc().querySelectorAll('.mark').length;
  doc().getElementById('live').click(); await sleep(300);
  seen.backLive = !doc().querySelector('.phone').classList.contains('past');
  seen.msg = doc().getElementById('msg').textContent;
  await finish();
})();
"""

REPORT = """
(async () => {
  const doc = () => f.contentDocument;
  const ok = await until(() => { const v = doc().querySelector('#report video'); return v && v.src.startsWith('blob:') && v.readyState >= 1; }, 60000);
  const v = doc().querySelector('#report video');
  seen.video = ok ? {duration: v.duration, w: v.videoWidth, h: v.videoHeight} : null;
  seen.shots = doc().querySelectorAll('#report img[src^="data:"]').length;
  const chaps = doc().querySelectorAll('.vp-chap'); seen.chapters = [...chaps].map(c => c.textContent);
  if (chaps.length) { chaps[chaps.length - 1].click(); await sleep(800); seen.seekedTo = v.currentTime; v.pause(); }
  seen.status = doc().getElementById('status').textContent;
  seen.statusError = doc().getElementById('status').className === 'err';
  await finish();
})();
"""

PAGE = """<!doctype html><meta charset=utf-8><title>apps-host</title>
<body style="margin:0;background:#ddd"><iframe id=f sandbox="allow-scripts allow-same-origin" style="width:900px;height:860px;border:0;background:#fff"></iframe>
<script>%s
if (V === 'screen') { %s } else { %s }
</script>"""

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, body, ctype):
        b = body.encode(); self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        u = urlparse(self.path); v = parse_qs(u.query).get("v", ["screen"])[0]
        if u.path == "/view": self.send(views[v], "text/html; charset=utf-8")
        else: self.send(PAGE % (COMMON, SCREEN, REPORT), "text/html; charset=utf-8")
    def do_POST(self):
        u = urlparse(self.path)
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if u.path == "/report":
            v = parse_qs(u.query)["v"][0]
            json.dump(body, open(os.path.join(outdir, v + ".json"), "w"), indent=1); self.send("{}", "application/json")
        else:
            self.send(json.dumps(rpc(body["method"], body["params"])), "application/json")

ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
