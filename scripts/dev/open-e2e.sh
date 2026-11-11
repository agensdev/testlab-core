#!/usr/bin/env bash
# open-e2e.sh — `testlab open profiles` in headless Chromium against fake-github.py: the link
# signs the page in, the profile list loads, and «New from a repo» goes repo → build types →
# preview → «Save and use», after which the new profile is the active one. No device needed.
# Leaves build/open-e2e-profiles.png.
#   scripts/dev/open-e2e.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
B=cli/build/install/testlab/bin/testlab
[ -x "$B" ] || ./gradlew :cli:installDist --console=plain -q
command -v chromium >/dev/null || { echo "needs chromium" >&2; exit 1; }
export XDG_DATA_HOME=$(mktemp -d) XDG_CACHE_HOME=$(mktemp -d) TESTLAB_VAULT_PASSPHRASE=open-e2e-only
gport=8399; gtoken=ghp_fake_e2e_token_0123456789
mkdir -p "$XDG_DATA_HOME/testlab"
printf '{"checkUpdates":false,"connections":{"githubApiUrl":"http://127.0.0.1:%s"}}\n' "$gport" > "$XDG_DATA_HOME/testlab/settings.json"
python3 scripts/dev/fake-github.py "$gport" "$gtoken" >/dev/null 2>&1 & gh=$!
log=$(mktemp); "$B" serve >/dev/null 2>"$log" & srv=$!
trap 'kill $gh $srv 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do grep -q "MCP server on" "$log" && break; sleep 0.5; done
url=$("$B" open profiles --print)
python3 - "$url" "$gtoken" <<'PY'
import sys; sys.path.insert(0, "tools/e2e")
from cdp import Browser
url, token = sys.argv[1], sys.argv[2]
fails = []
def check(name, ok):
    print(("ok    " if ok else "FAIL  ") + name)
    if not ok: fails.append(name)
with Browser() as b:
    b.go(url)
    b.until("location.pathname == '/ui/profiles' && !location.search.includes('t=')", what="the redirect without the ticket")
    b.until("document.querySelector('#profiles .prof')", what="the profile list")
    check("signed in by the link; the ticket left the address bar", "t=" not in b.js("location.href"))
    check("the built-in profile is listed and active", "active" in b.js("document.querySelector('#profiles').innerText"))
    b.js("document.querySelector('#newBtn').click()")
    b.until("!document.querySelector('#tokenForm').hidden", what="the token form")
    b.js(f"document.querySelector('#ghToken').value = '{token}'; document.querySelector('#saveToken').click()")
    b.until("document.querySelector('[data-repo]')", what="the repo list")
    check("the token is saved and the repos load", "example-org/shop-android" in b.js("document.querySelector('#repos').innerText"))
    b.js("{ const s = document.querySelector('#repoSearch'); s.value = 'ios'; s.dispatchEvent(new Event('input')) }")
    check("typing narrows the list", b.js("document.querySelectorAll('[data-repo]').length") == 1)
    b.js("{ const s = document.querySelector('#repoSearch'); s.value = ''; s.dispatchEvent(new Event('input')) }")
    b.js("document.querySelector('[data-repo=\"example-org/shop-android\"]').click()")
    b.until("document.querySelector('#cands input[data-c]')", what="the build candidates")
    check("the repo's build workflow is proposed", "build_feature.yml" in b.js("document.querySelector('#cands').innerText"))
    check("the name comes from the repo", b.js("document.querySelector('#pName').value") == "Shop Android")
    b.js("document.querySelector('#s-builds').scrollIntoView()"); b.screenshot("build/open-e2e-wizard.png")
    b.js("document.querySelector('#preview').click()")
    b.until("document.querySelector('#draft').value.includes('example-org/shop-android')", what="the draft")
    b.js("document.querySelector('#saveNew').click()")
    b.until("document.querySelector('#msg').textContent.includes('Saved')", what="saved")
    b.until("document.querySelector('#profiles').innerText.includes('Shop Android')", what="the new profile in the list")
    check("saved, listed and active", "shop-android" in b.js("[...document.querySelectorAll('.prof')].find(p => p.innerText.includes('active')).innerText"))
    b.js("document.querySelector('[data-edit=\"shop-android\"]').click()")
    b.until("!document.querySelector('#editor').hidden && document.querySelector('#editJson').value.includes('shop-android')", what="the editor")
    b.js("{ const t = document.querySelector('#editJson'); t.value = t.value.replace(/\"artifact\": \"[^\"]*\"/, '\"artifact\": \"(\"'); document.querySelector('#saveEdit').click() }")
    b.until("document.querySelector('#editProblems').innerText.includes('not a valid regex')", what="the problem list")
    check("a broken edit is refused with what to fix", "Not saved" in b.js("document.querySelector('#msg').textContent"))
    b.js("document.querySelector('#closeEdit').click(); window.scrollTo(0, 0)")
    b.screenshot("build/open-e2e-profiles.png")
print("problems:", ", ".join(fails) or "none")
sys.exit(1 if fails else 0)
PY
# The phone in a tab, when an emulator or phone is connected: frames arrive through the browser host.
if adb devices 2>/dev/null | grep -q "device$"; then
  surl=$("$B" open screen --print)
  python3 - "$surl" <<'PY'
import sys; sys.path.insert(0, "tools/e2e")
from cdp import Browser
with Browser(1000, 900) as b:
    b.go(sys.argv[1])
    b.until("document.querySelector('#screen') && document.querySelector('#screen').naturalWidth > 0", timeout=30, what="the first frame")
    b.until("document.querySelector('#sub').textContent.includes('frames/s')", timeout=10, what="the frame rate")
    b.screenshot("build/open-e2e-screen.png")
    print("ok    the phone in a browser tab:", b.js("document.querySelector('#sub').textContent"))
PY
  # The devices page, a report written on the emulator, and the reports list leading to it.
  TOKEN=$("$B" mcp-config | sed -n 's/.*Bearer \([^"]*\)".*/\1/p' | head -1)
  rid=$(NTL_TOKEN=$TOKEN python3 scripts/dev/mcp-call.py lab_report_start '{"title":"Open e2e report"}' | sed -n 's/^report_id: //p')
  NTL_TOKEN=$TOKEN python3 scripts/dev/mcp-call.py lab_report_screenshot "{\"report_id\":\"$rid\",\"caption\":\"Home\"}" lab_report_write "{\"report_id\":\"$rid\",\"markdown\":\"Home screen.\"}" >/dev/null
  durl=$("$B" open --print)
  python3 - "$durl" <<'PY'
import sys; sys.path.insert(0, "tools/e2e")
from cdp import Browser
with Browser(1200, 900) as b:
    b.go(sys.argv[1])
    b.until("document.querySelector('.dev .dot.ready')", what="a ready device")
    text = b.js("document.querySelector('#devices').innerText")
    assert "emulator" in text and "Show screen" in text, text
    b.screenshot("build/open-e2e-devices.png")
    print("ok    the devices page lists the emulator:", " ".join(text.split()[:6]))
    b.js("document.querySelector('nav a[href=\"/ui/reports\"]').click()")
    b.until("location.pathname == '/ui/reports' && document.querySelector('a.r')", what="the reports list (through the menu)")
    assert "Open e2e report" in b.js("document.querySelector('#list').innerText")
    b.screenshot("build/open-e2e-reports.png")
    b.js("[...document.querySelectorAll('a.r')].find(a => a.innerText.includes('Open e2e report')).click()")
    b.until("location.pathname == '/ui/report' && document.querySelector('#report h1')", timeout=30, what="the report")
    titles = b.js("[...document.querySelectorAll('h1, h2, #status')].filter(e => e.textContent.trim() == 'Open e2e report').length")
    assert titles == 1, f"the title shows {titles} times"
    b.screenshot("build/open-e2e-report.png")
    print("ok    the reports list opens the report, its title once")
PY
  # A plan that stops (no such button), resolved from the hand-back page.
  NTL_TOKEN=$TOKEN python3 scripts/dev/mcp-call.py handsets_navigate '{"target":"home"}' handsets_jev_plan '{"plan":"[{\"tap\":\"No such button\"},{\"note\":\"after\"}]","backend":"stub"}' >/dev/null
  hurl=$("$B" open handback --print)
  python3 - "$hurl" <<'PY'
import sys; sys.path.insert(0, "tools/e2e")
from cdp import Browser
with Browser(1200, 950) as b:
    b.go(sys.argv[1])
    b.until("!document.querySelector('#detail').hidden && document.querySelector('#shot').naturalWidth > 0", timeout=30, what="the paused step")
    b.until("document.querySelector('#cands').children.length > 0", timeout=15, what="the candidates")
    step = b.js("document.querySelector('#step').textContent")
    b.screenshot("build/open-e2e-handback.png")
    b.js("document.querySelector('#skip').click()")
    b.until("document.querySelector('#msg').textContent.includes('rest of the plan')", what="resolved")
    b.until("!document.querySelector('#none').hidden", timeout=15, what="nothing waiting")
    print("ok    the hand-back page shows the step (" + step + ") and resolves it:", b.js("document.querySelector('#msg').textContent"))
PY
else echo "skip  the phone in a tab (no device connected)"; fi
# Settings: the link goes by name (testlab.localhost), Proxyman is turned on and tested against a fake proxy.
python3 scripts/dev/fake-proxy.py 18899 "$(mktemp)" >/dev/null 2>&1 & fp=$!; sleep 1
surl=$("$B" open settings --print)
case "$surl" in http://testlab.localhost:*) echo "ok    the link goes by name: ${surl%%\?*}";; *) echo "FAIL  the link goes by name: $surl"; kill $fp; exit 1;; esac
python3 - "$surl" <<'PY'
import sys; sys.path.insert(0, "tools/e2e")
from cdp import Browser
with Browser(1200, 1000) as b:
    b.go(sys.argv[1])
    b.until("document.querySelector('[data-path=\"proxyman.enabled\"]') && document.querySelector('#all').value.includes('proxyman')", what="the settings page")
    b.js("document.querySelector('[data-path=\"proxyman.enabled\"]').checked = true; document.querySelector('[data-path=\"proxyman.port\"]').value = '18899'; document.querySelector('[data-save=\"proxyman\"]').click()")
    b.until("document.querySelector('#msg').textContent == 'Saved'", what="saved")
    b.js("document.querySelector('#pmTest').click()")
    b.until("document.querySelector('#pmRes').textContent.includes('the proxy answers')", timeout=30, what="the Proxyman test")
    res = b.js("document.querySelector('#pmRes').textContent")
    assert "✓ the proxy answers" in res, res
    b.js("document.querySelector('#proxyman').scrollIntoView()")
    b.screenshot("build/open-e2e-settings.png")
    print("ok    settings: Proxyman turned on and tested:", res.splitlines()[0])
PY
kill $fp 2>/dev/null || true
grep -q '"enabled": true' "$XDG_DATA_HOME/testlab/settings.json" && echo "ok    saved to settings.json" || { echo "FAIL  saved to settings.json"; exit 1; }
grep -q '"repo": "example-org/shop-android"' "$XDG_DATA_HOME/testlab/profiles/shop-android/profile.json" && echo "ok    the profile file is on disk" || { echo "FAIL  the profile file is on disk"; exit 1; }
[ "$("$B" profile list | grep '^\*' | awk '{print $2}')" = shop-android ] && echo "ok    the CLI sees it active" || { echo "FAIL  the CLI sees it active"; exit 1; }
