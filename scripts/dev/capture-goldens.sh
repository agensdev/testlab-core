#!/usr/bin/env bash
# Captures renderer goldens from the upstream handsets CLI (the oracle) on the dev emulator.
# For each screen it saves dump_active JSON plus `hs ui --tree` and `hs ui` text, and keeps
# the capture only when the JSON taken before and after the text dumps is identical
# (otherwise the screen changed in between and the trio would not belong together).
#   scripts/dev/capture-goldens.sh OUT_DIR          # the default screen list below
#   scripts/dev/capture-goldens.sh OUT_DIR NAME     # whatever is on screen now
set -euo pipefail
out="${1:?usage: capture-goldens.sh OUT_DIR [NAME]}"
mkdir -p "$out"
export PATH=$PATH:/opt/handsets:/opt/android-sdk/platform-tools
hs use >/dev/null

capture() {
  local name=$1 tmp
  tmp=$(mktemp -d)
  for attempt in 1 2 3; do
    hs see "$tmp/a.json" >/dev/null 2>&1
    hs ui --tree >"$tmp/tree"
    hs ui >"$tmp/table"
    hs see "$tmp/b.json" >/dev/null 2>&1
    # The dump starts with a capture timestamp {"ts":…,"root":…}; compare without it.
    if cmp -s <(sed -E 's/^\{"ts":[0-9]+,/{/' "$tmp/a.json") <(sed -E 's/^\{"ts":[0-9]+,/{/' "$tmp/b.json"); then
      cp "$tmp/a.json" "$out/$name.json"; cp "$tmp/tree" "$out/$name.tree"; cp "$tmp/table" "$out/$name.table"
      echo "captured $name ($(wc -l <"$out/$name.tree") tree lines, $(wc -l <"$out/$name.table") table rows)"
      rm -rf "$tmp"; return 0
    fi
    sleep 2
  done
  echo "SKIPPED $name: screen kept changing" >&2
  rm -rf "$tmp"
}

if [ $# -ge 2 ]; then capture "$2"; exit 0; fi

# name|intent arguments for `am start`
screens=(
  "settings-home|-a android.settings.SETTINGS"
  "settings-network|-a android.settings.WIRELESS_SETTINGS"
  "settings-bluetooth|-a android.settings.BLUETOOTH_SETTINGS"
  "settings-apps|-a android.settings.APPLICATION_SETTINGS"
  "settings-display|-a android.settings.DISPLAY_SETTINGS"
  "settings-sound|-a android.settings.SOUND_SETTINGS"
  "settings-storage|-a android.settings.INTERNAL_STORAGE_SETTINGS"
  "settings-about|-a android.settings.DEVICE_INFO_SETTINGS"
  "settings-location|-a android.settings.LOCATION_SOURCE_SETTINGS"
  "settings-security|-a android.settings.SECURITY_SETTINGS"
  "settings-date|-a android.settings.DATE_SETTINGS"
  "settings-locale|-a android.settings.LOCALE_SETTINGS"
  "settings-accessibility|-a android.settings.ACCESSIBILITY_SETTINGS"
  "settings-developer|-a android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
  "launcher-home|-a android.intent.action.MAIN -c android.intent.category.HOME"
  "contacts|-n com.android.contacts/.activities.PeopleActivity"
  "dialer|-a android.intent.action.DIAL"
  "clock|-n com.android.deskclock/.DeskClock"
  "chrome|-n com.android.chrome/com.google.android.apps.chrome.Main"
)
for s in "${screens[@]}"; do
  name=${s%%|*}; args=${s#*|}
  # shellcheck disable=SC2086
  adb shell am start -W $args >/dev/null 2>&1 || { echo "no app for $name" >&2; continue; }
  sleep 3
  capture "$name"
done
adb shell input keyevent KEYCODE_HOME
