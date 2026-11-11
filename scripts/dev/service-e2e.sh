#!/usr/bin/env bash
# The CLI distribution as a machine-wide systemd service, end to end: unpack the packaged CLI,
# `service install --system` for a throwaway user with a vault passphrase file (LoadCredential=),
# reach it over a non-loopback address with --allow-host, list/answer approvals, stop/start,
# uninstall. Needs root and systemd (a dev container or VM); everything it creates is removed at the end.
set -euo pipefail
cd "$(dirname "$0")/../.."
[ "$(id -u)" = 0 ] && [ "$(ps -p 1 -o comm=)" = systemd ] || { echo "skip: needs root and systemd"; exit 0; }
slug=$(sed -n 's/^testlab\.slug=//p' gradle.properties)
user=tlsvc-e2e
port=8412
ip=$(hostname -I | awk '{print $1}')
dist=/opt/$slug-cli-e2e
cred=/etc/$slug-e2e
fail=0
ok()  { echo "ok    $*"; }
bad() { echo "FAIL  $*"; fail=1; }

cleanup() {
  "$dist/$slug/bin/$slug" service uninstall --system >/dev/null 2>&1 || true
  userdel -r "$user" >/dev/null 2>&1 || true
  rm -rf "$dist" "$cred"
}
trap cleanup EXIT
cleanup

rm -f cli/build/cli-package/"$slug"-cli-*.tar.gz  # one package, so the globs below name one file
./gradlew -q :cli:packageCli
mkdir -p "$dist" && tar xzf cli/build/cli-package/"$slug"-cli-*.tar.gz -C "$dist"
cli="$dist/$slug/bin/$slug"
useradd -m -s /bin/bash "$user"
mkdir -p "$cred" && (umask 077; head -c 24 /dev/urandom | base64 > "$cred/pass")

out=$("$cli" service install --system --user "$user" --passphrase-file "$cred/pass" --port "$port" --listen 0.0.0.0 --allow-host "$ip,rig.example.test")
echo "$out" | sed 's/^/      /'
unit=/etc/systemd/system/$slug.service
grep -q "^User=$user$" "$unit" && grep -q "^LoadCredential=vault-passphrase:$cred/pass$" "$unit" && ok "unit: User= and LoadCredential=" || bad "unit content"
grep -q -- "serve --port $port --listen 0.0.0.0 --allow-host $ip,rig.example.test" "$unit" && ok "unit: serve flags" || bad "unit: serve flags"

control=/home/$user/.local/share/$slug/control.json
for _ in $(seq 1 60); do [ -s "$control" ] && break; sleep 0.5; done
[ -s "$control" ] && ok "the service wrote control.json" || { bad "no control.json"; journalctl -u "$slug" -n 40 --no-pager; exit 1; }
[ "$(stat -c %U:%a "$control")" = "$user:600" ] && ok "control.json is $user's, 0600" || bad "control.json owner/mode $(stat -c %U:%a "$control")"
token=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['token'])" "$control")
mode=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['mode'])" "$control")
[ "$mode" = service ] && ok "control.json mode=service" || bad "mode $mode"

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
[ "$(code -H "Authorization: Bearer $token" "http://127.0.0.1:$port/api/v1/status")" = 200 ] && ok "status on loopback" || bad "status on loopback"
[ "$(code -H "Authorization: Bearer $token" "http://$ip:$port/api/v1/status")" = 200 ] && ok "status on $ip (allowed host)" || bad "status on $ip"
[ "$(code -H "Authorization: Bearer $token" -H "Host: rig.example.test:$port" "http://$ip:$port/api/v1/status")" = 200 ] && ok "status as rig.example.test" || bad "status as rig.example.test"
[ "$(code -H "Authorization: Bearer $token" -H "Host: evil.example:$port" "http://$ip:$port/api/v1/status")" = 403 ] && ok "other Host refused" || bad "other Host not refused"
[ "$(code "http://$ip:$port/api/v1/status")" = 401 ] && ok "no token refused" || bad "no token not refused"
curl -s -H "Authorization: Bearer $token" "http://$ip:$port/api/v1/status" | grep -q '"mode":"service"' && ok "status mode=service" || bad "status mode"

"$cli" approvals --control "$control" | grep -q "no approvals waiting" && ok "approvals (none waiting)" || bad "approvals"
"$cli" approve --all --control "$control" | grep -q "no approvals waiting" && ok "approve --all with nothing waiting" || bad "approve --all"
r=$(curl -s -H "Authorization: Bearer $token" -X POST -d '{"approve":true}' "http://127.0.0.1:$port/api/v1/approvals/ap_nope")
echo "$r" | grep -q "no pending approval" && ok "unknown approval: 404 JSON" || bad "unknown approval: $r"

st=$("$cli" service status --system --control "$control"); echo "$st" | sed 's/^/      /'
echo "$st" | grep -q "service: running" && echo "$st" | grep -q "mode service" && ok "service status: running, server answers" || bad "service status"
"$cli" service stop --system >/dev/null && sleep 1
"$cli" service status --system | grep -q "service: stopped" && ok "service stop" || bad "service stop"
[ "$(systemctl show -p Result --value "$slug")" = success ] && ok "a stop is a clean exit (Result=success)" || bad "stop result $(systemctl show -p Result --value "$slug")"
journalctl -u "$slug" --no-pager | grep -q "Bearer " && bad "the token is in the journal" || ok "no token in the journal"
[ ! -e "$control" ] && ok "control.json removed on stop" || bad "control.json left after stop"
"$cli" service start --system >/dev/null
for _ in $(seq 1 60); do [ -s "$control" ] && break; sleep 0.5; done
[ -s "$control" ] && ok "service start: serving again" || bad "service start"
st=$("$cli" service status --system); echo "$st" | grep -q "starts by itself: at boot" && ok "a --system service starts at boot" || bad "starts-at line: $st"

# An upgrade stops the running service, swaps the files and starts it again.
pid=$(systemctl show -p MainPID --value "$slug")
up=$("$cli" upgrade --from cli/build/cli-package/"$slug"-cli-*.tar.gz 2>&1); echo "$up" | sed 's/^/      /'
echo "$up" | grep -q "system service: stopped" && echo "$up" | grep -q "system service: started again" && ok "upgrade stops and restarts the system service" || bad "upgrade output"
for _ in $(seq 1 60); do [ -s "$control" ] && break; sleep 0.5; done
pid2=$(systemctl show -p MainPID --value "$slug")
systemctl is-active -q "$slug" && [ "$pid2" != "$pid" ] && [ "$pid2" != 0 ] && ok "the service runs again, as a new process" || bad "after upgrade: active=$(systemctl is-active "$slug") pid $pid → $pid2"
"$cli" service uninstall --system | grep -q "removed" && [ ! -e "$unit" ] && ok "uninstall" || bad "uninstall"
systemctl is-active -q "$slug" && bad "still active after uninstall" || ok "inactive after uninstall"

[ $fail = 0 ] && echo "problems: none" || { echo "problems: see FAIL lines"; journalctl -u "$slug" -n 30 --no-pager || true; exit 1; }
