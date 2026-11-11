---
name: evidence-and-media
description: Collect evidence a reviewer can trust — network flows, analytics events, crash logs, lifecycle state, screenshots with honest captions, session recordings with notes, clean status bars and timings. Load it when planning what to capture for a test round, or when a result needs proof beyond "it looked fine".
---

# Evidence and media

## What to capture, by channel

- **Network**: the flows in the proxy (body, order, timestamps). Keep flow id, method, path,
  status, size and duration in the report; never tokens or personal data. `lab_report_flows`
  attaches the session's flows to a report.
- **Logs**: `lab_logs_start` BEFORE reproducing (preset `app`, `crash`, `analytics`, or `all`
  with the app's own `tags`), `lab_logs_read(grep)` while it runs, `lab_logs_stop(report_id)`
  attaches the file and lists crashes (a screen recorder's own crash is marked as the rig's).
- **Analytics**: `lab_logs_start(preset=analytics, firebase_debug=true)` logs each Firebase event as
  it is sent; `lab_analytics_events` lists them (or the app's own analytics lines, with `pattern`).
  The ingest request in the proxy is the other source. Report the exact event names, parameters
  and counts, not "events fired".
- **Crashes**: the fatal exception and stack trace from the log capture. When the profile names
  the app's Firebase apps, `lab_crashlytics_issues(from_log=true)` finds the Crashlytics issues
  that share that crash's exception and the app's own frames; read the best one's stack with
  `lab_crashlytics_issue` before calling it the same crash. In the report: the issue id, users and
  events in the window, the versions it hits, and whether it is older than the PR (then the PR
  didn't cause it). No match is evidence too: "not in Crashlytics for the last 7 days". Not signed
  in or no access: say so, never guess. On iOS the same comes from Apple through Xcode:
  `lab_ios_crashes(match=<the crash>)`, then `lab_ios_crash(signature)` for its logs;
  `lab_ios_performance(kind=hangs|launches)` for hangs and slow launches in the field.
- **Lifecycle**: on Android, `dumpsys activity top` (fragment `mState`).
- **Keyboard state**: `dumpsys input_method | grep mInputShown`.
- **Accessibility text**: `handsets_ui_dump(form=a11y)`; say "checked in the accessibility
  tree, not by listening".

## Screenshots

- `lab_report_screenshot(report_id, caption, serial)`: one per plan item, the caption = item
  number + what is visible. Pair the PR build with the control.
- Pass `serial` when several devices are connected, so the shot (and the report's recording) come
  from the right device.
- Coordinates come from the dump (device pixels), never from screenshot pixels.

## Recordings

- `lab_session_start` records the whole session; `lab_session_note(report_id, text)` puts a
  caption at that moment, so the video explains itself. `lab_report_write` stops the recordings
  of the devices the report shows and embeds them.
- Check `lab_session_status` before starting: a recording can survive a session.
- One report finalizes the recording: one report per batch of work.
- Full-resolution takes when needed: Android `adb shell screenrecord` (up to 180 s); the
  session recording of a phone reached over the network can be lower resolution.

## Clean status bars

- iOS Simulator: `xcrun simctl status_bar <udid> override --time 9:41 …`; Android: System UI
  demo mode. Reset afterwards (`lab_read_skill('rig-cleanup')`).

## Timing

Take times from the recording's timestamps (total and per phase), not from memory.
