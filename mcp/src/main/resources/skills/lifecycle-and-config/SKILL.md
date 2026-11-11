---
name: lifecycle-and-config
description: Test what happens when the system interrupts the app — "Don't keep activities", process death, dark mode, text size, predictive back, storage migrations on upgrade and downgrade — with the commands to set each state and put it back. Load it when a plan has process-death, configuration, upgrade or back-gesture items.
---

# Lifecycle, process death and configuration

These states come from the system, not the app, so they need device settings. On Android the
commands below run with `adb shell` from a terminal that reaches the phone; iOS Simulators use
`xcrun simctl`. Put every setting back afterwards (`lab_read_skill('rig-cleanup')`).

## Activities destroyed in the background

- Turn on Developer options → **Don't keep activities** (in the settings screen; the global
  setting alone isn't always honoured). Reach the state under test → Home → reopen the app →
  finish the flow. Turn it off afterwards.
- Lifecycle state of a screen: `dumpsys activity top` shows each fragment's `mState`
  (5 = STARTED, 7 = RESUMED). A screen stuck below RESUMED can drop events.

## Process death

- With the app in the background: `am kill <package>`, then reopen it from Recents. The app must
  restore the screen and its state, not crash or start over.
- A sheet or payment in progress is the interesting case: background the app in the middle.

## Dark mode and text size

- Dark mode: `cmd uimode night yes|no` (Android), `xcrun simctl ui <udid> appearance dark|light`.
- Text size: `handsets_font_scale(action=set, scale=1.5)`, and `reset` puts back exactly what it
  replaced. Some apps need a restart to pick it up.
- Test only what the diff touches: dark mode when colours change, text size when text or layout
  modifiers change.

## Predictive back (Android)

Hold a partial back gesture with `input motionevent DOWN/MOVE …`, take a screenshot of the peek,
then `UP` (commit) or `CANCEL`.

## Upgrades, downgrades and storage

- Upgrade over old data: install the old build, create the data, then install the new one with
  `adb install -r -d` (keeps data). Measure what survives.
- Downgrading over newer data can wedge the app: uninstall first.
- Clearing app data (`pm clear`) logs out and resets the app's environment choice.
