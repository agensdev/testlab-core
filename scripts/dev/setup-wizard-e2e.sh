#!/usr/bin/env bash
# setup-wizard-e2e.sh — `testlab setup` from an empty data folder against fake-github.py and
# fake-jira.py, answers piped in: token → repo picked by filtering → Jira site, e-mail, token →
# autopilot skipped → phone skipped → service declined. Then runs it again: every done step is
# skipped and nothing is asked twice. No device needed.
#   scripts/dev/setup-wizard-e2e.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
B=cli/build/install/testlab/bin/testlab
[ -x "$B" ] || ./gradlew :cli:installDist --console=plain -q
export XDG_DATA_HOME=$(mktemp -d) XDG_CACHE_HOME=$(mktemp -d) TESTLAB_VAULT_PASSPHRASE=wizard-e2e-only
gport=8399; jport=8398; gtoken=ghp_fake_e2e_token_0123456789; jemail=e2e@example.com; jtoken=jira_fake_e2e_token_0123
mkdir -p "$XDG_DATA_HOME/testlab"
printf '{"checkUpdates":false,"connections":{"githubApiUrl":"http://127.0.0.1:%s","jiraApiUrls":{"example.atlassian.net":"http://127.0.0.1:%s"}}}\n' "$gport" "$jport" > "$XDG_DATA_HOME/testlab/settings.json"
python3 scripts/dev/fake-github.py "$gport" "$gtoken" >/dev/null 2>&1 & gh=$!
python3 scripts/dev/fake-jira.py "$jport" "$jemail" "$jtoken" >/dev/null 2>&1 & jr=$!
trap 'kill $gh $jr 2>/dev/null || true' EXIT
sleep 1
fails=()
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; fails+=("$1"); fi; }

first=$(printf '%s\n' "$gtoken" "" "shop" "1" "example.atlassian.net" "$jemail" "$jtoken" "" "skip" "n" | "$B" setup 2>&1 || true)
echo "$first" | sed 's/^/  | /'
check "the token is saved and checked" 'echo "$first" | grep -q "✓ Connected to GitHub as"'
check "the repo list narrows to what was typed" 'echo "$first" | grep -q "1. example-org/shop-android"'
check "a profile is made from the picked repo" '"$B" profile list | grep -q "^\* shop-android "'
check "Jira is checked with the site token" 'echo "$first" | grep -q "✓ Connected to Jira as"'
check "the profile has the Jira site" 'grep -q "example.atlassian.net" "$XDG_DATA_HOME/testlab/profiles/shop-android/profile.json"'
check "the token never shows" '! echo "$first" | grep -q "$gtoken\|$jtoken"'
check "it ends with what is left" 'echo "$first" | grep -q "Still to do:"'

again=$(printf '%s\n' "" "skip" "n" | "$B" setup 2>&1 || true)
echo "$again" | sed 's/^/  | /'
check "a second run skips what is done" 'echo "$again" | grep -q "✓ shop-android · example-org/shop-android" && ! echo "$again" | grep -q "GitHub token"'
check "no Jira question the second time" '! echo "$again" | grep -q "Jira e-mail"'
# A team's profile instead of a repo: a fresh data folder, the profile exported above imported.
zip=$(mktemp -d)/team.zip
"$B" profile export shop-android --out "$zip" >/dev/null
export XDG_DATA_HOME=$(mktemp -d) && mkdir -p "$XDG_DATA_HOME/testlab" && mv "$zip" "$XDG_DATA_HOME/team.zip"
printf '{"checkUpdates":false,"connections":{"githubApiUrl":"http://127.0.0.1:%s","jiraApiUrls":{"example.atlassian.net":"http://127.0.0.1:%s"}}}\n' "$gport" "$jport" > "$XDG_DATA_HOME/testlab/settings.json"
team=$(printf '%s\n' "$gtoken" "$XDG_DATA_HOME/team.zip" "$jemail" "$jtoken" "" "skip" "n" | "$B" setup 2>&1 || true)
check "a team profile can be imported instead" 'echo "$team" | grep -q "✓ imported shop-android" && echo "$team" | grep -q "✓ Connected to Jira as"'
echo "problems: ${fails[*]:-none}"
[ ${#fails[@]} = 0 ]
