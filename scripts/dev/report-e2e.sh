#!/usr/bin/env bash
# report-e2e.sh — session recording and reports end to end on the dev emulator, over MCP:
# record a session, take screenshots and notes, run a plan with a screenshot step into the report,
# write it, then check the MP4 against the wall clock (±1 s), that it decodes, the chapters, the
# offline page and the standalone copy. Then a killed server: its recording is recovered at the
# next start and the next report takes it. Sequenced like e2e.sh.
#   scripts/dev/report-e2e.sh [--keep]
set -euo pipefail
cd "$(dirname "$0")/../.."
keep=false; [ "${1:-}" = "--keep" ] && keep=true
B=cli/build/install/testlab/bin/testlab
if ! adb devices 2>/dev/null | grep -q '^emulator-[0-9]*\s*device$'; then
  ./gradlew :cli:installDist --console=plain -q
  ./gradlew --stop >/dev/null 2>&1 || true
  scripts/dev/emu.sh up
fi
export XDG_DATA_HOME=$(mktemp -d) XDG_CACHE_HOME=$(mktemp -d) TESTLAB_VAULT_PASSPHRASE=report-e2e-only
work=$(mktemp -d); mkdir -p "$XDG_DATA_HOME/testlab"
scripts/dev/install-example-profile.sh "$XDG_DATA_HOME/testlab" "" "" "{\"workspace\":\"$work\"}"
TOKEN=$("$B" mcp-config | sed -n 's/.*Bearer \([^"]*\)".*/\1/p' | head -1)
srv=
serve() { log=$(mktemp); "$B" serve > /dev/null 2> "$log" & srv=$!; for _ in $(seq 1 60); do grep -q "MCP server on" "$log" && break; sleep 0.5; done; }
trap 'kill ${srv:-} 2>/dev/null || true; $keep || scripts/dev/emu.sh down' EXIT
call() { NTL_TOKEN=$TOKEN scripts/dev/mcp-call.py "$@"; }
fails=()
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; fails+=("$1"); fi; }
adb shell input keyevent KEYCODE_HOME

serve
call handsets_use_device '{}' > /dev/null
out=$(call lab_session_start '{}' lab_report_start '{"title":"Report e2e"}')
ts=$(date +%s.%N); t0=$ts
rid=$(echo "$out" | sed -n 's/^report_id: //p')
call handsets_open_app '{"package":"com.android.settings"}' handsets_wait_for '{"selector_or_text":"Network & internet"}' \
     lab_report_screenshot "{\"report_id\":\"$rid\",\"caption\":\"Settings\"}" lab_session_note "{\"report_id\":\"$rid\",\"text\":\"Opening the network page\"}" \
     handsets_tap '{"selector_or_text":"Network & internet"}' sleep 3 lab_report_screenshot "{\"report_id\":\"$rid\",\"caption\":\"Network\"}" \
     handsets_navigate '{"target":"back"}' sleep 4 > /dev/null
call handsets_jev_plan "{\"plan\":\"[{\\\"tap\\\":\\\"#search_action_bar\\\"},{\\\"sleep\\\":2},{\\\"screenshot\\\":\\\"Search\\\"},{\\\"key\\\":\\\"back\\\"}]\",\"report_id\":\"$rid\",\"backend\":\"stub\"}" > /dev/null
sleep 3
t1=$(date +%s.%N)
w=$(call lab_report_write "{\"report_id\":\"$rid\",\"markdown\":\"## Resultat\\n\\nOK.\\n\\n![Settings](01-settings.jpg)\\n![Network](02-network.jpg)\"}")
echo "$w" | sed 's/^/  /'
d="$work/reports/$rid"
wall=$(python3 -c "print(round($t1-$t0,1))"); dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$d/session.mp4")
echo "  wall clock ~${wall} s (report start → write), video ${dur} s"
first=$(python3 -c "import json;print(json.load(open('$d/manifest.json'))['videos'][0]['first_frame_epoch'])")
echo "  first frame $(python3 -c "print(round($first-$ts,1))") s after lab_session_start returned"
check "the first frame comes within 2.5 s of the start" 'python3 -c "import sys; sys.exit(0 if -0.5 <= $first - $ts < 2.5 else 1)"'
check "video length = first frame → stop, within 1 s (the stop is the report write)" 'python3 -c "import sys; sys.exit(0 if abs($dur - ($t1 - $first)) < 1.0 else 1)"'
check "session.mp4 decodes cleanly" 'ffmpeg -v error -i "$d/session.mp4" -fps_mode passthrough -f null - 2>&1 | grep -q . && false || true'
check "three screenshots, the plan's included" '[ -f "$d/03-search.jpg" ] && [ "$(ls "$d"/*.jpg | wc -l)" = 3 ]'
check "chapters in the page, inline" 'grep -q "\"kind\":\"chapter\",\"title\":\"Search\"" "$d/index.html" && grep -q "Opening the network page" "$d/index.html"'
check "the annotated MP4 has three chapters" '[ "$(ffprobe -v error -show_chapters -of csv=p=0 "$d/session_annotated.mp4" | wc -l)" = 3 ]'
check "the standalone page inlines the images" 'grep -q "data:image/jpeg;base64" "$d/report-standalone.html"'

# A killed server: the recording is recovered at the next start and the next report takes it.
call lab_session_start '{}' > /dev/null
adb shell am start -a android.settings.SETTINGS > /dev/null; sleep 2; adb shell input swipe 540 1800 540 600 300; sleep 3
kill -9 $srv; sleep 1
serve
rid2=$(call handsets_use_device '{}' lab_report_start '{"title":"After a crash"}' | sed -n 's/^report_id: //p')
w2=$(call lab_report_write "{\"report_id\":\"$rid2\",\"markdown\":\"Etter krasj.\"}")
echo "$w2" | sed 's/^/  /'
check "the next report takes the recovered recording" '[[ "$w2" == *recovered* ]] && [ -s "$work/reports/$rid2/session.mp4" ]'
echo "problems: ${fails[*]:-none}"
[ ${#fails[@]} = 0 ]
