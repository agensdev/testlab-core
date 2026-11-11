#!/usr/bin/env bash
# devtest-e2e.sh — a devtest end to end on the dev emulator, against fake-jira.py and
# fake-github.py: the queue shows the cleared ticket, its PR is found from Jira and GitHub, the
# ticket is taken (refused first: someone else has it), the PR build is installed, a recorded
# report linked to the ticket is written, and the devtest comment is previewed, then
# posted with the report page and video attached and the approved label set. Sequenced like e2e.sh.
#   scripts/dev/devtest-e2e.sh [--keep]
set -euo pipefail
cd "$(dirname "$0")/../.."
keep=false; [ "${1:-}" = "--keep" ] && keep=true
B=cli/build/install/testlab/bin/testlab
if ! adb devices 2>/dev/null | grep -q '^emulator-[0-9]*\s*device$'; then
  ./gradlew :cli:installDist --console=plain -q
  ./gradlew --stop >/dev/null 2>&1 || true
  scripts/dev/emu.sh up
fi
[ -x "$B" ] || { echo "build the CLI first (./gradlew :cli:installDist) — not while the emulator runs" >&2; exit 1; }

export XDG_DATA_HOME=$(mktemp -d) XDG_CACHE_HOME=$(mktemp -d) TESTLAB_VAULT_PASSPHRASE=devtest-e2e-only
ghport=8399; jport=8398; ghtoken=ghp_fake_e2e_token_0123456789; jemail=e2e@example.com; jtoken=jira_fake_e2e_token_0123
work=$(mktemp -d); mkdir -p "$XDG_DATA_HOME/testlab"
scripts/dev/install-example-profile.sh "$XDG_DATA_HOME/testlab" "http://127.0.0.1:$ghport" "http://127.0.0.1:$jport" "{\"workspace\":\"$work\",\"mcp\":{\"confirmWrites\":false}}"
echo -n "$ghtoken" | "$B" secrets set github.token
echo -n "$jemail" | "$B" secrets set jira.email
echo -n "$jtoken" | "$B" secrets set jira.token
python3 scripts/dev/fake-github.py "$ghport" "$ghtoken" & gh=$!
python3 scripts/dev/fake-jira.py "$jport" "$jemail" "$jtoken" & jr=$!
srv=
trap 'kill $gh $jr ${srv:-} 2>/dev/null || true; adb uninstall no.agens.testlab.fixture >/dev/null 2>&1 || true; $keep || scripts/dev/emu.sh down' EXIT
sleep 1
adb uninstall no.agens.testlab.fixture >/dev/null 2>&1 || true
fails=()
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; fails+=("$1"); fi; }
state() { curl -s "http://127.0.0.1:$jport/_state"; }

q=$("$B" devtest queue 2>&1) || true; echo "  $q"
check "the queue has the cleared ticket, assigned to the developer" '[[ "$q" == SHOP-42*"Utvikler Utviklersen"*"Fixture-appen viser versjon"* ]]'
prs=$("$B" devtest prs SHOP-42 2>&1) || true; echo "  $prs"
check "its PR is found by the dev panel, the title and the branch" '[[ "$prs" == "#42  open  feature/SHOP-42-ok"*"Jira development panel"*"PR title"*"branch name"* ]]'

TOKEN=$("$B" mcp-config | sed -n 's/.*Bearer \([^"]*\)".*/\1/p' | head -1)
log=$(mktemp); "$B" serve > /dev/null 2> "$log" & srv=$!
for _ in $(seq 1 60); do grep -q "MCP server on" "$log" && break; sleep 0.5; done
call() { NTL_TOKEN=$TOKEN scripts/dev/mcp-call.py "$@"; }

a=$(call handsets_use_device '{}' jira_get_issue '{"key":"SHOP-42"}' jira_linked_prs '{"key":"SHOP-42"}' jira_assign '{"key":"SHOP-42"}' jira_assign '{"key":"SHOP-42","force":true}')
echo "$a" | sed 's/^/  /' | cut -c1-160 | head -30
check "the ticket reads as Markdown" '[[ "$a" == *"- Appen starter og viser **versjonen**"* ]]'
check "one open PR, named" '[[ "$a" == *"\"single_open_pr\":42"* ]]'
check "someone else's ticket is not taken without force" '[[ "$a" == *"assigned to Utvikler Utviklersen: not changed"* ]] && [[ "$a" == *"now assigned to you"* ]]'
check "one assignee write reached Jira: to me" '[ "$(state | python3 -c "import json,sys;print(\",\".join(json.load(sys.stdin)[\"assignee\"]))")" = me-e2e ]'

b=$(call handsets_install_branch_build '{"branch_or_pr":"42"}' lab_session_start '{}' lab_report_start '{"title":"Devtest SHOP-42","ticket":"SHOP-42"}')
echo "$b" | sed 's/^/  /' | cut -c1-160
rid=$(echo "$b" | sed -n 's/^report_id: //p')
check "the PR build is installed" '[[ "$b" == *"\"install\":\"Success\""* ]]'
call handsets_open_app '{"package":"no.agens.testlab.fixture"}' sleep 3 lab_report_screenshot "{\"report_id\":\"$rid\",\"caption\":\"Appen viser versjonen\"}" sleep 2 > /dev/null
w=$(call lab_report_write "{\"report_id\":\"$rid\",\"markdown\":\"## Devtest SHOP-42\\n\\n![Appen viser versjonen](01-appen-viser-versjonen.jpg)\"}")
echo "$w" | sed 's/^/  /'
check "the report is written with the recording" '[[ "$w" == *"recording: session.mp4"* ]]'

args="{\"key\":\"SHOP-42\",\"tested\":[\"✅ Appen starter og viser versjonen (skjermbilde 01)\"],\"not_tested\":[\"Mørk modus: ikke relevant\"],\"result\":\"Godkjent.\",\"outcome\":\"approved\",\"report_id\":\"$rid\""
pv=$(call jira_post_devtest_report "$args,\"preview\":true}")
echo "$pv" | sed 's/^/  /'
check "the preview has the profile's template and the installed build" '[[ "$pv" == *PREVIEW*"## Devtest "*"**Build:** 1.0-fixture"*"**Branch:** \`feature/SHOP-42-ok\`"*"### Not tested"*"🤖 Written by Claude, approved by E2E Testesen"* ]]'
check "the preview wrote nothing" '[ "$(state | python3 -c "import json,sys;print(len(json.load(sys.stdin)[\"comments\"]))")" = 0 ]'
po=$(call jira_post_devtest_report "$args}")
echo "$po" | sed 's/^/  /'
st=$(state)
check "the comment is ADF with the heading and the build line" 'echo "$st" | python3 -c "
import json,sys; s=json.load(sys.stdin); c=s[\"comments\"][0]; t=json.dumps(c, ensure_ascii=False)
assert c[\"type\"]==\"doc\" and c[\"content\"][0][\"type\"]==\"heading\" and \"Devtest\" in t and \"1.0-fixture\" in t and \"Written by Claude\" in t, t"'
check "the report page and the video are attached" 'echo "$st" | python3 -c "
import json,sys; a={x[\"filename\"]:x for x in json.load(sys.stdin)[\"attachments\"]}
assert a[\"report-standalone.html\"][\"size\"]>1000 and a[\"session.mp4\"][\"size\"]>10000 and a[\"session.mp4\"][\"mimeType\"]==\"video/mp4\", a"'
check "approved: the label is added, nothing removed" '[[ "$st" == *"\"labels\": [{\"add\": \"devtest-approved\"}]"* ]]'
q2=$("$B" devtest queue 2>&1) || true
check "the approved ticket leaves the queue" '[ -z "$q2" ]'
# The control API (host apps): install by PR, follow the job, start a devtest with a report.
ctl="$XDG_DATA_HOME/testlab/control.json"
check "control.json is written while the server runs" '[ -s "$ctl" ]'
api=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['api'])" "$ctl"); tok=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['token'])" "$ctl")
adb uninstall no.agens.testlab.fixture >/dev/null 2>&1 || true
job=$(curl -s -H "Authorization: Bearer $tok" -d '{"ref":"42"}' "$api/builds/install" | python3 -c "import json,sys;print(json.load(sys.stdin)['job'])")
for _ in $(seq 1 60); do ph=$(curl -s -H "Authorization: Bearer $tok" "$api/jobs/$job" | python3 -c "import json,sys;print(json.load(sys.stdin).get('phase',''))"); [ "$ph" = done ] || [ "$ph" = failed ] && break; sleep 1; done
echo "  control api job $job: $ph"
check "control API: the PR build installs as a job" '[ "$ph" = done ] && adb shell pm list packages | grep -q no.agens.testlab.fixture'
dv=$(curl -s -H "Authorization: Bearer $tok" -d '{"key":"SHOP-42","pr":42,"start":"report"}' "$api/devtest")
echo "  $dv" | cut -c1-200
check "control API: a devtest with a report starts" '[[ "$dv" == *"\"key\":\"SHOP-42\""* ]] && [[ "$dv" == *"\"report_id\":\"20"* ]]'
check "control API: refuses without the token" '[ "$(curl -s -o /dev/null -w "%{http_code}" "$api/status")" = 401 ]'
echo "problems: ${fails[*]:-none}"
[ ${#fails[@]} = 0 ]
