#!/usr/bin/env python3
"""fake-jira.py — just enough of a Jira Cloud site for the devtest end-to-end run.

    scripts/dev/fake-jira.py PORT EMAIL TOKEN

SHOP-42 is cleared for a devtest (label devtest), assigned to a developer, and its
development panel links PR 42 (feature/SHOP-42-ok, served by fake-github.py). Requests need
Basic EMAIL:TOKEN. Writes are recorded; GET /_state returns them for the checks: comments
(the ADF bodies), attachments (file name, type, size), label updates and assignee changes.
"""
import base64, json, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

PORT, EMAIL, TOKEN = int(sys.argv[1]), sys.argv[2], sys.argv[3]
AUTH = "Basic " + base64.b64encode(f"{EMAIL}:{TOKEN}".encode()).decode()
ME = {"accountId": "me-e2e", "displayName": "E2E Testesen", "emailAddress": EMAIL}
DEV = {"accountId": "dev-e2e", "displayName": "Utvikler Utviklersen"}
ISSUE = {"id": "77042", "key": "SHOP-42", "fields": {
    "summary": "Fixture-appen viser versjon", "issuetype": {"name": "Story"},
    "status": {"name": "Til test", "statusCategory": {"key": "indeterminate"}}, "priority": {"name": "Medium"},
    "assignee": DEV, "reporter": {"accountId": "po", "displayName": "Produkt Eier"},
    "labels": ["devtest"], "components": [], "fixVersions": [], "created": "2026-10-01T09:00:00.000+0200", "updated": "2026-10-02T09:00:00.000+0200",
    "description": {"type": "doc", "version": 1, "content": [
        {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "Akseptansekriterier"}]},
        {"type": "bulletList", "content": [{"type": "listItem", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Appen starter og viser "}, {"type": "text", "text": "versjonen", "marks": [{"type": "strong"}]}]}]}]}]},
    "comment": {"total": 1, "comments": [{"id": "1", "author": DEV, "created": "2026-10-02T09:00:00.000+0200", "body": {"type": "doc", "version": 1, "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Klar for test"}]}]}}]},
    "attachment": [], "issuelinks": []}}
STATE = {"comments": [], "attachments": [], "labels": [], "assignee": []}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body=None):
        b = json.dumps(body).encode() if body is not None else b""
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def authed(self):
        if self.headers.get("Authorization") != AUTH:
            self.send(401, {"errorMessages": ["Client must be authenticated to access this resource."]})
            return False
        return True

    def do_GET(self):
        p = urlparse(self.path).path
        if p == "/_state": return self.send(200, STATE)
        if not self.authed(): return
        if p == "/rest/api/3/myself": return self.send(200, ME)
        if p == "/rest/api/3/issue/SHOP-42": return self.send(200, ISSUE)
        if p == "/rest/api/3/attachment/meta": return self.send(200, {"enabled": True, "uploadLimit": 100 * 1024 * 1024})
        if p == "/rest/dev-status/latest/issue/summary":
            return self.send(200, {"summary": {"pullrequest": {"byInstanceType": {"GitHub": {"count": 1}}}}})
        if p == "/rest/dev-status/latest/issue/detail":
            return self.send(200, {"detail": [{"pullRequests": [{"id": "#42", "name": "SHOP-42 e2e", "url": "https://github.com/example-org/shop-android/pull/42",
                                                                 "status": "OPEN", "source": {"branch": "feature/SHOP-42-ok"}}]}]})
        return self.send(404, {"errorMessages": [f"Issue does not exist or you do not have permission to see it. ({p})"]})

    def do_POST(self):
        p = urlparse(self.path).path
        raw = self.body()
        if not self.authed(): return
        if p == "/rest/api/3/search/jql":
            jql = json.loads(raw)["jql"]
            hit = "devtest" in jql and "devtest-approved" not in ISSUE["fields"]["labels"]
            return self.send(200, {"issues": [ISSUE] if hit else [], "isLast": True})
        if p == "/rest/api/3/issue/SHOP-42/comment":
            STATE["comments"].append(json.loads(raw)["body"])
            return self.send(201, {"id": str(10000 + len(STATE["comments"]))})
        if p == "/rest/api/3/issue/SHOP-42/attachments":
            if self.headers.get("X-Atlassian-Token") != "no-check":
                return self.send(403, {"errorMessages": ["XSRF check failed"]})
            boundary = self.headers.get("Content-Type", "").split("boundary=")[-1].strip('"').encode()
            out = []
            for part in raw.split(b"--" + boundary):
                if b"filename=" not in part: continue
                head, _, data = part.partition(b"\r\n\r\n")
                name = head.split(b'filename="')[1].split(b'"')[0].decode()
                ctype = next((l.split(b":", 1)[1].strip().decode() for l in head.split(b"\r\n") if l.lower().startswith(b"content-type")), "")
                out.append({"id": str(20000 + len(STATE["attachments"]) + len(out)), "filename": name, "mimeType": ctype, "size": len(data.rstrip(b"\r\n")),
                            "created": "2026-10-02T10:00:00.000+0200", "author": ME})
            STATE["attachments"].extend(out)
            return self.send(200, out)
        return self.send(404, {"errorMessages": [p]})

    def do_PUT(self):
        p = urlparse(self.path).path
        raw = self.body()
        if not self.authed(): return
        if p == "/rest/api/3/issue/SHOP-42":
            for op in json.loads(raw)["update"]["labels"]:
                STATE["labels"].append(op)
                if "add" in op: ISSUE["fields"]["labels"].append(op["add"])
                if "remove" in op and op["remove"] in ISSUE["fields"]["labels"]: ISSUE["fields"]["labels"].remove(op["remove"])
            return self.send(204)
        if p == "/rest/api/3/issue/SHOP-42/assignee":
            acc = json.loads(raw)["accountId"]
            STATE["assignee"].append(acc)
            ISSUE["fields"]["assignee"] = ME if acc == ME["accountId"] else ({"accountId": acc, "displayName": acc} if acc else None)
            return self.send(204)
        return self.send(404, {"errorMessages": [p]})

ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
