#!/usr/bin/env python3
"""fake-proxy.py PORT LOG — an HTTP proxy stand-in for the Proxyman e2e: answers every request with
200 and appends its request line to LOG (CONNECT tunnels get 200 and are closed)."""
import socketserver, sys
PORT, LOG = int(sys.argv[1]), sys.argv[2]
class H(socketserver.StreamRequestHandler):
    def handle(self):
        line = self.rfile.readline().decode("latin-1").strip()
        if not line: return
        with open(LOG, "a") as f: f.write(line + "\n")
        body = b"proxied by fake-proxy\n"
        self.wfile.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %d\r\nConnection: close\r\n\r\n" % len(body) + body)
class S(socketserver.ThreadingTCPServer): allow_reuse_address = True; daemon_threads = True
S(("127.0.0.1", PORT), H).serve_forever()
