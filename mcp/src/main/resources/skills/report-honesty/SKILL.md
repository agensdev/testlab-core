---
name: report-honesty
description: The rules for an honest test report — every plan item under Tested or Not tested with a reason, a result line that matches coverage, the four verdicts for what you saw, pre-existing versus regression checked against the control, and a preview before any external write. Load it before writing or posting any test report.
---

# Honest reports

## Coverage

Give the report the plan's items (`lab_report_items`), and account for each with
`lab_report_item` as you go: tested with pass, fail or unclear (a fail with its verdict, below), or
not tested with the reason. Pass `item` to `lab_report_screenshot` to file the screenshot as that
item's evidence. `lab_report_write` refuses while an item is unaccounted for, and writes the
Tested and Not tested lists and a result line that follows from them.

- Every plan item appears under **Tested** or **Not tested**. "6 of 8 tested" beats a false 8.
- The result line matches coverage and findings. "All tests passed, no issues found" only when
  Not tested is empty and there are no findings.
- Report only what was exercised. When the user says "all ok" over items you didn't run, ask
  per item.
- State limits plainly: "The push part is not validated."
- Common honest reasons under Not tested: needs a second user or device; no suitable data in the
  environment; the fallback path can't be triggered; mocking failed; dark mode, large text,
  rotation or long-press not controllable; an attachment that couldn't be fetched; the app is
  locked to portrait.

## Four verdicts for something you saw

1. **In-scope bug** (`verdict=bug`): a finding; it goes back to the PR's author.
2. **By design** (`by_design`): not a finding (say where the design says so).
3. **Test environment** (`environment`): not a finding (the backend, the data, the rig).
4. **Existing and unrelated** (`existing`): a separate ticket, not this one's finding.

Unclear → write "unclear" and say what would decide it.

## Pre-existing or regression?

Check the control build (`lab_read_skill('ab-control')`). Keep out-of-scope observations in
their own section, marked "also on main".

## Evidence

One screenshot per item, the caption = item number + what is visible. Re-read the captions
before writing: a caption that describes the wrong screen is worse than none.

## Before anything leaves the machine

- Preview every external write (a Jira comment, a label, a Slack message) and get the user's yes.
  After any edit, preview again. Mark drafts "DRAFT, not posted".
- Jira writes one at a time.
- Never put credentials, national IDs, PINs or tokens in a comment, report or caption.
