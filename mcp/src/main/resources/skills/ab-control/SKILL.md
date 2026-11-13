---
name: ab-control
description: Prove a fix with a control — reproduce the bug deterministically, choose the control build on purpose (merge-base, previous fix, last release), keep the A/B symmetric, validate the method, add negative controls, and make a side-by-side video. Load it when a ticket fixes a bug, or a result has to show before/after.
---

# Reproduce, then prove the fix against a control

The loop is understand → reproduce (control) → verify (fix) → report → clean up. A fix counts as
proven only once you have seen it fail without the fix.

## Reproduce deterministically

- Deterministic beats flaky. Force the state with a proxy rule, a deeplink or a direct screen
  start, or a device setting, rather than waiting for it to happen.
- Script tight sequences instead of tapping by hand: a plan (`handsets_autopilot_plan` with scripted
  `tap` steps) runs the same every time.
- Reproduce with real data before trusting injected data. A mock built to show a difference can
  "prove" a fix for a bug that never occurs in practice: the real bug may be on another screen.
- Check that the bug still exists before testing a fix for an old ticket.
- Some races can't be hit from outside. Say so, and point to unit tests.
- A crash nobody can reproduce: start from the crash reporter's breadcrumbs, rebuild the steps,
  then try them on the device.

## Choose the control on purpose

- The PR's merge-base, for "did this PR fix it": `handsets_control_build(pr)` finds it, says
  whether CI built it and installs it (`install=true`). Its `changed_since_release` says whether
  the control also stands for the last release in the PR's files.
- The previous fix's build, when branches are stacked.
- The last release, for "does this work for users today" and as a third baseline.
- Identify builds by commit, not by version code (`handsets_installed_builds`).
- A branch far behind main shows old behaviour that isn't the PR's: ask for a rebase.

## Keep it fair

- The build is the only variable: same device, same user, same data, same steps, each run from a
  clean app state (force-stop, then launch).
- Validate the method on a case where you know the outcome.
- Negative controls mark where the fix ends: a case the fix should not change.

## Side-by-side evidence

- Record both runs (`lab_session_start`, one recording per run) with the same scripted steps,
  so they line up.
- Put the two recordings side by side with ffmpeg (`hstack`, a `drawtext` label per side),
  control left, fix right, and time each step from the recording, not by feel.
  (`lab_compare_backends` makes side-by-side clips too, but it compares decision backends on one
  build, not two builds.)
- Name the builds in the report: commit, variant, where it came from.
