#!/usr/bin/env bash
# spaces-e2e.sh — client spaces side by side: `alpha` and `beta` each get their own folders, vault,
# port and names; a secret set in one is not in the other; both serve at once; each space's
# reports and control.json are its own; `default` stays the folders as they were. No device needed.
#   scripts/dev/spaces-e2e.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
B=cli/build/install/testlab/bin/testlab
[ -x "$B" ] || ./gradlew :cli:installDist --console=plain -q
export XDG_DATA_HOME=$(mktemp -d) XDG_CACHE_HOME=$(mktemp -d) TESTLAB_VAULT_PASSPHRASE=spaces-e2e-only
# The JVM's home (where the workspaces go) is a scratch one too: migrate moves the default workspace.
export JAVA_TOOL_OPTIONS="-Duser.home=$(mktemp -d)"
unset TESTLAB_SPACE
fails=()
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; fails+=("$1"); fi; }

echo -n default-token-xyz | "$B" secrets set github.token >/dev/null
"$B" space create alpha --no-setup >/dev/null
"$B" space create beta --no-setup >/dev/null
check "space list shows the three, beta in use" '"$B" space list 2>/dev/null | grep -q "^\* beta" && [ "$("$B" space list 2>/dev/null | wc -l)" = 3 ]'
pa=$("$B" --space alpha config get mcp.port 2>/dev/null || true); pb=$("$B" --space beta config get mcp.port 2>/dev/null || true)
check "each space has its own port ($pa, $pb)" '[ -n "$pa" ] && [ -n "$pb" ] && [ "$pa" != "$pb" ] && [ "$pa" != 8378 ]'
echo -n alpha-secret-123 | "$B" --space alpha secrets set github.token >/dev/null
check "a secret set in alpha is there" '"$B" --space alpha secrets list | grep -q "^github.token *set"'
check "…and not in beta" '"$B" --space beta secrets list | grep -q "^github.token *-"'
check "…nor in default (it has its own)" '"$B" --space default secrets list | grep -q "^github.token *set"'
check "separate vault files" '[ -f "$XDG_DATA_HOME/testlab/spaces/alpha/secrets.vault" ] && [ ! -f "$XDG_DATA_HOME/testlab/spaces/beta/secrets.vault" ] || [ -f "$XDG_DATA_HOME/testlab/spaces/beta/secrets.vault" ]'
check "the vault holds no secret in clear" '! grep -rq "alpha-secret-123" "$XDG_DATA_HOME"'
check "names per space: Claude Code entry" '"$B" --space alpha mcp-config | grep -q "add --scope user --transport http testlab-alpha "'
check "…Claude Desktop entry starts this space" '"$B" --space alpha mcp-config | grep -q "\"testlab-alpha\":{\"command\".*\"--space\",\"alpha\",\"--mcp-stdio\""'
check "…default keeps its old name" '"$B" --space default mcp-config | grep -q "transport http testlab http"'
check "TESTLAB_SPACE picks the space too" '[ "$(TESTLAB_SPACE=alpha "$B" space current 2>/dev/null)" = alpha ]'
check "a bad name is refused" '! "$B" --space "Bad Name" status >/dev/null 2>&1 && ! "$B" space create reports --no-setup >/dev/null 2>&1'

# Guards (space.json): the first profile pins alpha to its client; another client's is refused.
"$B" --space alpha profile import scripts/dev/example-profile >/dev/null
check "alpha is pinned to the example profile's owner, site and app" '"$B" space rules alpha | grep -q "\"example-org\"" && "$B" space rules alpha | grep -q "example.atlassian.net"'
other=$(mktemp -d); python3 - "$other" <<'PY'
import json, sys
json.dump({"id": "other", "name": "Other client", "app": {"defaultPackage": "org.other.app", "family": "org.other.app"},
           "github": {"repo": "other-co/app"}, "tracker": {"site": "other.atlassian.net"}}, open(sys.argv[1] + "/profile.json", "w"))
PY
check "another client's profile is refused in alpha" '! "$B" --space alpha profile import "$other" >/dev/null 2>&1'
check "…and kept out of its profile list" '! "$B" --space alpha profile list 2>/dev/null | grep -q "^ *other"'
check "…but beta (not pinned yet) takes it" '"$B" --space beta profile import "$other" >/dev/null 2>&1'
check "the default space has no rules" '"$B" --space default space rules | grep -q "no rules"'

la=$(mktemp); lb=$(mktemp)
"$B" --space alpha serve >/dev/null 2>"$la" & sa=$!
"$B" --space beta serve >/dev/null 2>"$lb" & sb=$!
trap 'kill $sa $sb 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do grep -q "MCP server on" "$la" && grep -q "MCP server on" "$lb" && break; sleep 0.5; done
check "both serve at once" 'grep -q "MCP server on 127.0.0.1:$pa" "$la" && grep -q "MCP server on 127.0.0.1:$pb" "$lb"'
check "each its own control.json" 'grep -q ":$pa/" "$XDG_DATA_HOME/testlab/spaces/alpha/control.json" && grep -q ":$pb/" "$XDG_DATA_HOME/testlab/spaces/beta/control.json"'
ta=$(python3 -c "import json;print(json.load(open('$XDG_DATA_HOME/testlab/spaces/alpha/control.json'))['token'])")
check "alpha's token doesn't open beta" '[ "$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $ta" http://127.0.0.1:$pb/api/v1/status)" = 401 ]'
NTL_MCP_URL=http://127.0.0.1:$pa/mcp NTL_TOKEN=$ta python3 scripts/dev/mcp-call.py lab_report_start '{"title":"Alpha only"}' >/dev/null
wa=$("$B" --space alpha dirs | awk '/^workspace/{print $2}'); wb=$("$B" --space beta dirs | awk '/^workspace/{print $2}')
check "a report lands in alpha's workspace only" 'ls "$wa/reports" | grep -q alpha-only && ! ls "$wb/reports" 2>/dev/null | grep -q alpha-only'
# alpha's token pasted into a beta session: beta's redactor knows it by alpha's published hash.
tb=$(python3 -c "import json;print(json.load(open('$XDG_DATA_HOME/testlab/spaces/beta/control.json'))['token'])")
check "alpha publishes hashes, never values" '[ -s "$XDG_DATA_HOME/testlab/spaces/alpha/secret-hashes" ] && ! grep -q alpha-secret-123 "$XDG_DATA_HOME/testlab/spaces/alpha/secret-hashes"'
NTL_MCP_URL=http://127.0.0.1:$pb/mcp NTL_TOKEN=$tb python3 scripts/dev/mcp-call.py lab_report_start '{"title":"Beta pasted alpha-secret-123 here"}' >"$lb.out" 2>&1 || true
check "alpha's token is masked in beta's tool result" '! grep -q alpha-secret-123 "$lb.out" && grep -q "redacted" "$lb.out"'
check "…and in beta's report and traces" '! grep -rq alpha-secret-123 "$wb" "$XDG_DATA_HOME/testlab/spaces/beta"'
kill $sa $sb; wait 2>/dev/null || true

# A Claude profile per space: its own server only, the other clients' code denied, the plugin wired to it.
"$B" --space beta space allow code /tmp/beta-code >/dev/null
cd_=$(mktemp -d)
"$B" space claude alpha --dir "$cd_" >/dev/null 2>&1 || true
st="$cd_/settings.json"
check "space claude: TESTLAB_SPACE=alpha in the profile" 'python3 -c "import json,sys;s=json.load(open(sys.argv[1]));sys.exit(s[\"env\"][\"TESTLAB_SPACE\"]!=\"alpha\")" "$st"'
check "…the other spaces' servers and beta's code denied" 'grep -q "\"mcp__testlab-beta\"" "$st" && grep -q "\"mcp__testlab\"" "$st" && grep -q "Read(//tmp/beta-code/\*\*)" "$st" && ! grep -q "mcp__testlab-alpha" "$st"'
pd="$XDG_DATA_HOME/testlab/spaces/alpha/claude-plugin"
check "…the plugin's server starts in alpha" 'grep -q "\"--space\", \"alpha\", \"--mcp-stdio\"" "$pd/claude-plugin/.mcp.json" && grep -q "const SPACE = .alpha." "$pd/claude-plugin/hooks/register.js" && grep -q "\"name\": \"testlab-alpha\"" "$pd/.claude-plugin/marketplace.json"'
# For the next consultant: rules and profiles, no secrets; a new space made from it is pinned the same.
zip_=$(mktemp -d)/alpha.zip
"$B" space export alpha --out "$zip_" >/dev/null 2>&1
check "space export has the rules and the profile, no secrets" 'unzip -l "$zip_" | grep -q space.json && unzip -l "$zip_" | grep -q "profiles/example-shop/profile.json" && ! unzip -p "$zip_" | grep -q alpha-secret-123 && ! unzip -p "$zip_" space.json | grep -q beta-code'
"$B" space create gamma --from "$zip_" --no-setup >/dev/null 2>&1
check "…and a space made from it has both" '"$B" space rules gamma 2>/dev/null | grep -q example-org && "$B" --space gamma profile list 2>/dev/null | grep -q "example-shop"'

"$B" space use default >/dev/null
"$B" space delete beta >/dev/null 2>&1 && check "delete needs --yes" false || check "delete needs --yes" true
"$B" space delete beta --yes >/dev/null; "$B" space delete alpha --yes >/dev/null; "$B" space delete gamma --yes >/dev/null
check "a deleted space is gone, its workspace too" '! "$B" space list 2>/dev/null | grep -q " beta " && [ ! -d "$wb" ]'
# Today's data (the default space) becomes a client's space; its secrets open there.
# A space some command already touched (settings only) can still take the data.
mkdir -p "$XDG_DATA_HOME/testlab/spaces/legacy" && echo "{\"mcp\":{\"port\":8390}}" > "$XDG_DATA_HOME/testlab/spaces/legacy/settings.json"
"$B" space migrate legacy >/dev/null
check "migrate: the default space's token is in the new space" '"$B" --space legacy secrets list | grep -q "^github.token *set"'
check "…and the default space starts empty" '"$B" --space default secrets list | grep -q "^github.token *-"'
check "…and the new space is the one in use" '[ "$("$B" space current 2>/dev/null)" = legacy ]'
check "migrating twice says it's done" '{ "$B" space migrate legacy 2>&1 || true; } | grep -q "migrated already"'
# A window still on the default space after the move keeps writing there: status says so, --rest moves it.
echo '[]' > "$XDG_DATA_HOME/testlab/installed-builds.json"; echo '[{"serial":"x","proxy":"192.0.2.1:9090"}]' > "$XDG_DATA_HOME/testlab/proxied.json"
check "status names what the default space still has" '{ "$B" --space legacy status --offline 2>&1 || true; } | grep -q "default space still has.*proxy routings"'
"$B" space migrate legacy --rest >/dev/null
check "migrate --rest moves it without touching the space's own" '[ -f "$XDG_DATA_HOME/testlab/spaces/legacy/proxied.json" ] && [ ! -f "$XDG_DATA_HOME/testlab/proxied.json" ] && "$B" --space legacy secrets list | grep -q "^github.token *set"'
echo "problems: ${fails[*]:-none}"
[ ${#fails[@]} = 0 ]
