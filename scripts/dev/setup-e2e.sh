#!/usr/bin/env bash
# setup-e2e.sh — «Set up a project» against fake-github.py, no device needed: `testlab profile init`
# scans the repo's workflows and artifacts, downloads the default build to read its package and
# deeplink hosts, and saves a profile that `profile check` accepts and that installs builds.
#   scripts/dev/setup-e2e.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
B=cli/build/install/testlab/bin/testlab
[ -x "$B" ] || ./gradlew :cli:installDist --console=plain -q
export XDG_DATA_HOME=$(mktemp -d) XDG_CACHE_HOME=$(mktemp -d) TESTLAB_VAULT_PASSPHRASE=setup-e2e-only
port=8399; token=ghp_fake_e2e_token_0123456789
mkdir -p "$XDG_DATA_HOME/testlab"
printf '{"connections":{"githubApiUrl":"http://127.0.0.1:%s"}}\n' "$port" > "$XDG_DATA_HOME/testlab/settings.json"
echo -n "$token" | "$B" secrets set github.token >/dev/null
python3 scripts/dev/fake-github.py "$port" "$token" & gh=$!
trap 'kill $gh 2>/dev/null || true' EXIT
sleep 1
fails=()
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; fails+=("$1"); fi; }
dry=$("$B" profile init --repo example-org/shop-android --name "Shop" --read-packages --dry-run 2>&1)
echo "$dry" | head -40 | sed 's/^/  /'
p() { echo "$dry" | python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
check "the workflow becomes the default build type" '[ "$(p "d[\"builds\"][0][\"workflowFile\"]")" = build_feature.yml ] && [ "$(p "d[\"builds\"][0][\"default\"]")" = True ]'
check "the artifact pattern matches the artifact" '[ "$(p "d[\"builds\"][0][\"artifact\"]")" = "app-feature-firebase\\.apk" ]'
check "the branch prefixes come from the workflow" '[ "$(p "\" \".join(d[\"builds\"][0][\"autoPrefixes\"])")" = "feature/ fix/" ]'
check "the dispatch input and its choices" '[ "$(p "\",\".join(d[\"builds\"][0][\"dispatchInputs\"][\"environment\"][\"choices\"])")" = "prod,stage" ]'
check "the package was read from the APK" '[ "$(p "d[\"app\"][\"defaultPackage\"]")" = no.agens.testlab.fixture ]'
check "the deeplink host was read from the manifest" '[ "$(p "d[\"navigation\"][\"host\"]")" = https://www.example.com ]'
repos=$("$B" repos 2>&1); echo "$repos" | sed 's/^/  /'
check "repos lists what the token can read, newest first" '[ "$(echo "$repos" | head -1 | cut -d" " -f1)" = example-org/shop-android ]'
check "archived repos are hidden by default" '! echo "$repos" | grep -q old-app && "$B" repos --all 2>/dev/null | grep -q old-app'
check "an SSO-hidden organisation is reported" 'echo "$repos" | grep -q "hide their repos until the token is authorized"'
out=$("$B" profile init --repo example-org/shop-android --name "Shop" --read-packages 2>&1); echo "  $out"
check "saved and active" '"$B" profile list | grep -q "^\* shop "'
check "the saved profile passes the checks" '"$B" profile check "$XDG_DATA_HOME/testlab/profiles/shop/profile.json" >/dev/null'
b=$("$B" builds --refresh 2>&1 || true); echo "  $b"
check "the new profile lists the repo's builds" '[[ "$b" == feature*feature/SHOP-4* ]]'
echo "problems: ${fails[*]:-none}"
[ ${#fails[@]} = 0 ]
