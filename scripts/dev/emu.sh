#!/usr/bin/env bash
# emu.sh up|down|status — the dev container's one headless emulator (AVD tl36).
# The host is short on RAM: `up` stops the Gradle daemons first and refuses to start
# with less than 3 GB available. Stop it again when done.
set -euo pipefail
AVD=${AVD:-tl36}
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
LOG=/tmp/emulator-$AVD.log

running() { adb devices 2>/dev/null | grep -q '^emulator-[0-9]*\s*device$'; }

case "${1:-status}" in
  up)
    if running; then echo "emulator already running: $(adb devices | grep '^emulator' | awk '{print $1}')"; exit 0; fi
    (cd "$(dirname "$0")/../.." && ./gradlew --stop >/dev/null 2>&1) || true
    avail_kb=$(awk '/MemAvailable/ {print $2}' /proc/meminfo)
    if [ "$avail_kb" -lt $((3 * 1024 * 1024)) ]; then
      echo "only $((avail_kb / 1024)) MB available; need 3 GB for the emulator" >&2; exit 1
    fi
    nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
      -accel on -memory 2048 -camera-back none -camera-front none >"$LOG" 2>&1 &
    adb wait-for-device
    for _ in $(seq 1 180); do
      [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
      sleep 2
    done
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] || { echo "boot timed out; see $LOG" >&2; exit 1; }
    # Deterministic UI for captures and tests: no animations, stay awake while plugged in.
    adb shell settings put global window_animation_scale 0
    adb shell settings put global transition_animation_scale 0
    adb shell settings put global animator_duration_scale 0
    adb shell svc power stayon true
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    echo "emulator up: $(adb devices | grep '^emulator' | awk '{print $1}')"
    ;;
  down)
    if running; then adb emu kill >/dev/null 2>&1 || true; fi
    for _ in $(seq 1 30); do running || break; sleep 1; done
    echo "emulator down"
    ;;
  status)
    if running; then adb devices -l | grep '^emulator'; else echo "no emulator running"; fi
    awk '/MemAvailable/ {printf "MemAvailable: %d MB\n", $2/1024}' /proc/meminfo
    ;;
  *) echo "usage: $0 up|down|status" >&2; exit 2 ;;
esac
