#!/usr/bin/env python3
"""cdp.py — just enough Chrome DevTools Protocol to drive a page from a test: open headless
Chromium, navigate, evaluate JavaScript, wait for a condition, take a screenshot.

    with Browser() as b:
        b.go(url); b.until("document.title == 'x'"); b.js("document.querySelector('#b').click()")
"""
import base64, json, os, shutil, subprocess, tempfile, time, urllib.request
import websocket

class Browser:
    def __init__(self, width=1280, height=900):
        self.profile = tempfile.mkdtemp()
        self.proc = subprocess.Popen(["chromium", "--headless", "--no-sandbox", "--disable-gpu", f"--window-size={width},{height}",
                                      "--remote-debugging-port=0", f"--user-data-dir={self.profile}", "about:blank"],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        port_file = os.path.join(self.profile, "DevToolsActivePort")
        for _ in range(100):
            if os.path.exists(port_file) and open(port_file).read().strip(): break
            time.sleep(0.1)
        port = open(port_file).read().split()[0]
        tabs = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json"))
        page = next(t for t in tabs if t["type"] == "page")
        self.ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=30, suppress_origin=True)
        self.n = 0
        self.send("Page.enable"); self.send("Runtime.enable")

    def send(self, method, **params):
        self.n += 1; i = self.n
        self.ws.send(json.dumps({"id": i, "method": method, "params": params}))
        while True:
            m = json.loads(self.ws.recv())
            if m.get("id") == i:
                if "error" in m: raise RuntimeError(f"{method}: {m['error']}")
                return m.get("result", {})

    def go(self, url):
        self.send("Page.navigate", url=url)

    def js(self, expr):
        r = self.send("Runtime.evaluate", expression=expr, awaitPromise=True, returnByValue=True)
        if "exceptionDetails" in r: raise RuntimeError(f"js: {r['exceptionDetails'].get('exception', {}).get('description') or r['exceptionDetails']}")
        return r.get("result", {}).get("value")

    def until(self, expr, timeout=20, what=None):
        end = time.time() + timeout
        while time.time() < end:
            try:
                if self.js(f"!!({expr})"): return True
            except RuntimeError: pass
            time.sleep(0.2)
        raise TimeoutError(f"timed out waiting for {what or expr}")

    def screenshot(self, path):
        open(path, "wb").write(base64.b64decode(self.send("Page.captureScreenshot", format="png", captureBeyondViewport=True)["data"]))

    def __enter__(self): return self
    def __exit__(self, *a):
        try: self.ws.close()
        finally:
            self.proc.kill(); self.proc.wait(); shutil.rmtree(self.profile, ignore_errors=True)
