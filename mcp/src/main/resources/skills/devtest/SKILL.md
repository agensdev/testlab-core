---
name: devtest
description: Devtest (peer-test) a Jira ticket end to end on the attached phone — read the ticket and the PR, write a test plan, install the PR build, run the plan against a control with evidence, clean up, and post an honest report comment in the profile's format after the user approves. Use when asked to devtest, peer-test or test PROJ-123 and the team's own devtest command is not available.
---

# Devtest a ticket

When Claude Code runs in the app's checkout and the team has its own devtest command (the server
instructions name it), follow that command and use these tools only as the hands (device, builds,
evidence). Otherwise this playbook is the whole flow. The user approves every Jira write in the
app's window: show what you will post before asking.

A devtest is a second pair of eyes on someone else's change: it brings knowledge of the diff and
the product, not a tester's full coverage, and its most valuable output is the side effects the
diff alone doesn't reveal. Never devtest a PR the user wrote (call it self-verification if they
ask anyway). The loop: understand → reproduce (control) → verify (fix) → report → clean up.

The steps below lean on these playbooks (`lab_read_skill(name)`): `test-plan`, `ab-control`,
`proxy-forcing`, `lifecycle-and-config`, `accessibility`, `autopilot-usage`, `evidence-and-media`,
`rig-health`, `rig-cleanup` and `report-honesty`.

## 1. The ticket

1. `jira_get_issue(key)`. Read the description (acceptance criteria), the comments (the newest
   say what is ready, on which environment, and what the developer wants checked) and the links.
2. Check clearance: the result's «Devtest:» line warns when the ticket has none of the profile's
   queue labels or already has its approved label. Tell the user and ask before going on.
3. Assignee: if it is not the user, say who has it. Take it only when the user says so:
   `jira_assign(key, "me")` (someone else's needs `force=true`).

## 2. The change

1. `jira_linked_prs(key)`. Exactly one open PR → use it. Several, or none → ask the user. Never guess.
2. `github_get_pr(n)`, `github_pr_files(n)`, then `github_pr_diff(n, path=…)` for the files that
   matter. `github_read_file(path, ref=<branch>)` for the code around a change (callers, the screen
   the ViewModel feeds, feature toggles, strings).
3. Look for what the diff does not say: other screens using a changed component, error and empty
   states, toggles, deeplinks, dark mode, font scaling, small screens, translated texts.

## 3. The plan

Follow `test-plan`: a short, numbered plan in the profile's report language (`lab_status` →
profile) with only the categories a changed line can break (name the line), how to reach each
state, and the control build (`ab-control`). Show it to the user and ask whether to run all of
it or a part.

## 4. Run it on the phone

1. Device prep (`lab_read_skill('device-prep')`; `rig-health` if anything looks off): `handsets_use_device` on a device of the
   ticket's platform (the queue's `platform`: an Android phone for the Android project, an iOS
   Simulator for the iOS one; `handsets_list_adb_devices` shows each device's platform), then
   `lab_session_start` (records the whole run, snapshots the phone's settings and lists any rig
   problem `lab_health` found: fix those first, they look like app bugs later).
2. Build: `handsets_install_branch_build("<PR>")`. Check `latest_commit`; if a newer commit is
   still building, say so. A signature clash: ask before `handsets_uninstall_app`.
3. `lab_report_start("Devtest <key>", ticket="<key>")`, then `lab_report_items(report_id, items)`
   with the plan's numbered items: the report then has to account for each one.
4. Log in when needed: `handsets_login`.
5. For each plan item: navigate (`handsets_go`, `handsets_autopilot_do`, or a `handsets_autopilot_plan` for a
   repeated flow), check what the screen shows with `handsets_ui_dump`, and capture evidence with
   `lab_report_screenshot(report_id, "<item>: <what it shows>", item=<id>)`, then
   `lab_report_item(report_id, id, status, result|note)`. `lab_session_note` marks moments in the
   video. Record what you SAW. If something can't be checked on this device or environment, it is
   `not_tested` with the reason. Need logs or analytics? `lab_logs_start` before reproducing.
6. Clean up before writing (`rig-cleanup`): `lab_device_state(restore)`, `lab_logs_stop`,
   proxy rules (`lab_report_write` removes this session's own and checks).
7. `lab_report_write(report_id, markdown, serial)`: your findings and context; the tool adds the
   Tested / Not tested lists, the result line and the «Rig» section (`report-honesty`).

## Several devices: devtests side by side

With an Android device and an iOS Simulator both free (or two phones), devtest several tickets at
once, one per device:

1. `jira_devtest_queue` shows each ticket's `platform` and, when a device has it, `testing_on`.
2. For each free device: `jira_devtest_next(serial)` gives it the next ticket for its platform and
   marks it as that device's, so the device beside it gets another one.
3. Do steps 1–3 for every ticket, then show the user all the plans together and ask once.
4. Run them in parallel when you can (in Claude Code: one subagent per device, each given its
   serial, its ticket and this playbook). Pass `serial` on EVERY device, build, report and plan call:
   the tools then use that device's platform (its repo, builds, login and destinations), however
   the calls interleave. GitHub tools take the ticket key or `platform`.
5. Report each ticket on its own (step 5); a ticket's report covers only its own device.

## 5. Report on the ticket

Follow `report-honesty`: every plan item under tested or not tested, a result line that matches,
and a verdict for each finding (in-scope bug, by design, test environment, existing and
unrelated).

1. `jira_post_devtest_report(key, tested=[…], not_tested=[…], result="…", env="<env>",
   outcome=…, report_id=…, preview=true)` and show the user the preview.
   - `tested`: one line per item: «✅ …», «❌ … (what happened)» or «⚠️ …».
   - `outcome`: `approved` only when every item passed and the user agrees; `changes` sends the
     ticket back to the developer; `none` posts the comment only.
2. When the user agrees, call it again without `preview`. The app asks for approval of the
   exact payload, attaches the report page and the video, and applies the outcome.
3. Give the user the comment link.

Never invent a result, never post without the user's go-ahead, and never put credentials,
national IDs or tokens in a comment.
