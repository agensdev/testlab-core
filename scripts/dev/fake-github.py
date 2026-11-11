#!/usr/bin/env python3
"""fake-github.py — just enough of api.github.com for the builds end-to-end run.

    scripts/dev/fake-github.py PORT TOKEN

PR 42 (feature/SHOP-42-ok) has a Build Feature artifact with fixture-v1.apk, PR 43
(feature/SHOP-43-clash) one with fixture-otherkey.apk (another signing key), and the latest
release carries fixture-v2.apk. API calls need "Authorization: Bearer TOKEN". Artifact
downloads redirect to /storage/…, which refuses any Authorization header, like Azure's signed
URLs: a client that keeps the token on the redirect fails here.
"""
import io, json, sys, time, zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT, TOKEN = int(sys.argv[1]), sys.argv[2]
REPO = "/repos/example-org/shop-android"
FIX = "tools/testapp/"
NOW = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 3600))
EXP = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 20 * 86400))
PRS = {42: ("feature/SHOP-42-ok", "4242aaaa42", 900, 502, "fixture-v1.apk"), 43: ("feature/SHOP-43-clash", "4343bbbb43", 901, 503, "fixture-otherkey.apk")}
BASE = f"http://127.0.0.1:{PORT}"

def zipped(name):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        z.write(FIX + name, "app-feature-firebase.apk")
    return b.getvalue()

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body=b"", ctype="application/json", headers=()):
        if isinstance(body, (dict, list)): body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("x-ratelimit-remaining", "4999")
        for k, v in headers: self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query); p = u.path
        if p.startswith("/storage/"):
            if self.headers.get("Authorization"):
                return self.send(400, {"message": "storage: extra Authorization header (the token followed the redirect)"})
            return self.send(200, STORE[p], "application/zip")
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            return self.send(401, {"message": "Bad credentials"})
        # Who the token is (the token check), with the classic token's scopes.
        if p == "/user":
            return self.send(200, {"login": "e2e-tester"}, headers=[("X-OAuth-Scopes", "repo, read:org")])
        # The repos the token can read (the repo picker), one organisation hidden behind SSO.
        if p == "/user/repos":
            repos = [{"full_name": REPO[len("/repos/"):], "private": True, "description": "the shop's Android app", "language": "Kotlin", "pushed_at": NOW, "archived": False, "fork": False},
                     {"full_name": "example-org/shop-ios", "private": True, "description": "the shop's iOS app", "language": "Swift", "pushed_at": "2026-09-30T10:00:00Z", "archived": False, "fork": False},
                     {"full_name": "example-org/old-app", "private": False, "description": "", "language": "Java", "pushed_at": "2024-01-01T10:00:00Z", "archived": True, "fork": False}]
            return self.send(200, repos if q.get("page", ["1"]) == ["1"] else [], headers=[("X-GitHub-SSO", "partial-results; organizations=1234")])
        # Project set-up (setup-e2e.sh): the repo, its workflows, the newest run and the workflow file.
        if p == REPO:
            return self.send(200, {"full_name": REPO[len("/repos/"):], "default_branch": "main", "private": True, "html_url": f"{BASE}/repo"})
        if p == f"{REPO}/actions/runs":  # the token check: may it read the builds?
            return self.send(200, {"total_count": 1, "workflow_runs": []})
        if p == f"{REPO}/actions/workflows":
            return self.send(200, {"workflows": [{"id": 1, "name": "Build Feature", "path": ".github/workflows/build_feature.yml", "state": "active"}]})
        if p == f"{REPO}/actions/workflows/build_feature.yml/runs" and "branch" not in q:
            ref, sha, run, art, apk = PRS[42]
            return self.send(200, {"workflow_runs": [{"id": run, "name": "Build Feature", "path": ".github/workflows/build_feature.yml", "head_branch": ref, "head_sha": sha,
                                                      "status": "completed", "conclusion": "success", "event": "pull_request", "run_number": 1, "created_at": NOW, "html_url": f"{BASE}/runs/{run}"}]})
        if p == f"{REPO}/contents/.github/workflows/build_feature.yml":
            return self.send(200, WORKFLOW_YAML.encode(), "text/plain")
        for n, (ref, sha, run, art, apk) in PRS.items():
            if p == f"{REPO}/pulls/{n}":
                return self.send(200, {"number": n, "title": f"SHOP-{n} e2e", "draft": False, "state": "open", "html_url": f"{BASE}/pull/{n}", "head": {"ref": ref, "sha": sha}})
            if p == f"{REPO}/actions/workflows/build_feature.yml/runs" and q.get("branch") == [ref]:
                runs = [{"id": run, "name": "Build Feature", "path": ".github/workflows/build_feature.yml", "head_branch": ref, "head_sha": sha, "status": "completed",
                         "conclusion": "success", "event": "pull_request", "run_number": run - 800, "created_at": NOW, "html_url": f"{BASE}/runs/{run}"}]
                return self.send(200, {"workflow_runs": runs if q.get("status") == ["success"] else []})
            if p == f"{REPO}/actions/runs/{run}/artifacts":
                return self.send(200, {"artifacts": [{"id": art, "name": "app-feature-firebase.apk", "size_in_bytes": len(STORE[f"/storage/{art}.zip"]), "expired": False,
                                                       "created_at": NOW, "expires_at": EXP, "workflow_run": {"id": run, "head_branch": ref, "head_sha": sha}}]})
            if p == f"{REPO}/actions/artifacts/{art}/zip":
                return self.send(302, b"", headers=[("Location", f"{BASE}/storage/{art}.zip?sig=signed")])
        # PR lookup for a devtest (devtest-e2e.sh): title search and branch-name prefixes.
        if p == "/search/issues":
            terms = q.get("q", [""])[0]
            items = [{"number": n, "title": f"SHOP-{n} e2e", "state": "open", "draft": False, "user": {"login": "dev"}, "updated_at": NOW,
                      "html_url": f"{BASE}/pull/{n}", "pull_request": {}} for n in PRS if f'"SHOP-{n}"' in terms]
            return self.send(200, {"items": items})
        if p.startswith(f"{REPO}/git/matching-refs/heads/"):
            prefix = p[len(f"{REPO}/git/matching-refs/heads/"):]
            return self.send(200, [{"ref": f"refs/heads/{ref}"} for (ref, *_r) in PRS.values() if ref.startswith(prefix)])
        if p == f"{REPO}/pulls" and q.get("head"):
            head = q["head"][0].split(":", 1)[-1]
            return self.send(200, [{"number": n, "title": f"SHOP-{n} e2e", "state": "open", "draft": False, "user": {"login": "dev"}, "updated_at": NOW,
                                    "html_url": f"{BASE}/pull/{n}", "merged_at": None} for n, (ref, *_r) in PRS.items() if ref == head])
        if p == f"{REPO}/releases":
            return self.send(200, [{"tag_name": "9.9.9-rc1", "name": "9.9.9", "published_at": NOW, "prerelease": False,
                                    "assets": [{"id": 71, "name": "app-releaseDebug-minifiedDebug.apk", "size": len(V2), "url": f"{BASE}{REPO}/releases/assets/71"}]}])
        if p == f"{REPO}/releases/assets/71":
            return self.send(200, V2, "application/vnd.android.package-archive")
        if p == f"{REPO}/actions/artifacts":
            arts = [{"id": art, "name": "app-feature-firebase.apk", "size_in_bytes": len(STORE[f"/storage/{art}.zip"]), "expired": False, "created_at": NOW,
                     "expires_at": EXP, "workflow_run": {"id": run, "head_branch": ref, "head_sha": sha}} for (ref, sha, run, art, _) in PRS.values()]
            arts.sort(key=lambda a: -a["id"])
            return self.send(200, {"total_count": len(arts), "artifacts": arts if q.get("page", ["1"]) == ["1"] else []})
        return self.send(404, {"message": f"Not Found: {p}"})

WORKFLOW_YAML = """name: Build Feature
on:
  pull_request:
  workflow_dispatch:
    inputs:
      environment:
        description: Default environment
        default: prod
        type: choice
        options:
          - prod
          - stage
jobs:
  build:
    if: startsWith(github.head_ref, 'feature/') || startsWith(github.head_ref, 'fix/')
"""
STORE = {f"/storage/{art}.zip": zipped(apk) for (_, _, _, art, apk) in PRS.values()}
V2 = open(FIX + "fixture-v2.apk", "rb").read()
ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
