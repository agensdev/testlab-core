#!/usr/bin/env bash
# Unpacks the CLI distribution (cli/build/cli-package/*) and checks it runs on its bundled runtime:
# --version, --selftest, and (not on Windows) `serve` answering /api/v1/status with its token.
set -euo pipefail
slug="$(sed -n 's/^testlab\.slug=//p' gradle.properties | tr -d '\r')"
pkg=$(ls cli/build/cli-package/"$slug"-cli-* | head -1)
tmp="${RUNNER_TEMP:-/tmp}/cli-smoke"; rm -rf "$tmp"; mkdir -p "$tmp"
case "$pkg" in
  *.zip) if command -v unzip >/dev/null; then (cd "$tmp" && unzip -q "$OLDPWD/$pkg"); else 7z x -bso0 "-o$tmp" "$pkg"; fi ;;
  *) tar xzf "$pkg" -C "$tmp" ;;
esac
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) cli=(cmd //c "$(cygpath -w "$tmp/$slug/bin/$slug.bat")"); windows=1 ;;
  *) cli=("$tmp/$slug/bin/$slug"); windows=0 ;;
esac
"${cli[@]}" --version
st="$tmp/selftest.json"
"${cli[@]}" "--selftest=$(if [ $windows = 1 ]; then cygpath -w "$st"; else echo "$st"; fi)" || true
cat "$st"
grep -Eq '^    "ok": true' "$st"
[ $windows = 1 ] && exit 0

export XDG_DATA_HOME="$tmp/data" XDG_CACHE_HOME="$tmp/cache" HOME="$tmp/home" TESTLAB_VAULT_PASSPHRASE=cli-smoke-only TESTLAB_SERVICE=1
mkdir -p "$HOME"
# macOS keeps the data in ~/Library/Application Support, and Java takes the home folder from the
# account, not $HOME: point it at the temp folder so the runner's real data is never touched.
export JAVA_TOOL_OPTIONS="-Duser.home=$HOME"
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
"${cli[@]}" serve --port "$port" > "$tmp/serve.out" 2> "$tmp/serve.err" &
pid=$!
trap 'kill $pid 2>/dev/null || true' EXIT
control=$(find "$tmp" -name control.json 2>/dev/null | head -1)
for _ in $(seq 1 60); do control=$(find "$tmp" -name control.json 2>/dev/null | head -1); [ -n "$control" ] && break; sleep 0.5; done
[ -n "$control" ] || { cat "$tmp/serve.err"; echo "serve wrote no control.json" >&2; exit 1; }
token=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["token"])' "$control")
curl -sf -H "Authorization: Bearer $token" "http://127.0.0.1:$port/api/v1/status" | tee "$tmp/status.json"; echo
grep -q '"mode":"service"' "$tmp/status.json"
! grep -q "Bearer" "$tmp/serve.out" "$tmp/serve.err" || { echo "the token was printed under TESTLAB_SERVICE=1" >&2; exit 1; }
kill -TERM $pid; wait $pid && echo "serve: clean exit on SIGTERM"
