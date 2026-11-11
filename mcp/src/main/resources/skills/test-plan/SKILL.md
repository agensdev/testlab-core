---
name: test-plan
description: Write a devtest (peer test) plan from the change itself — read the ticket and every comment, read the diff and confirm it covers the whole ticket, then pick only the test categories a changed line can break, with how to reach each state and the control build. Load it before testing someone else's PR or ticket, and whenever a plan starts to look like a checklist.
---

# Write a devtest plan

A devtest is a second pair of eyes on someone else's change. It brings knowledge of the diff and
the product, not a tester's full coverage. Its most valuable output is the side effects and
regression risks the diff alone doesn't reveal, so spend time on the code around the change.
Never test a change the user wrote themselves; if they ask anyway, call it self-verification.

## 1. Understand before touching the device

- Read the description and every comment (`jira_get_issue`). Sections the developer wrote for the
  tester ("to test", "test info", acceptance criteria) weigh most. Earlier test reports in the
  comments are results, not instructions.
- Read the diff (`github_pr_files`, `github_pr_diff`), then confirm in the code that it covers the
  whole ticket: PRs often fix only part of it. Every acceptance criterion is a test item.
- Read the PR's own head, not your checkout: `github_read_file(path, ref=<branch>)`.
- See the screens it changes: `lab_pr_screens(pr)` gives the screenshot-test images the PR adds or
  changes, before and after (`show=N` to look at one). Each changed image is a screen and state
  to reach on the phone and compare with it; a screen in the diff without one has no test, so it
  needs your eyes all the more. They come from tests, not a phone: don't report a difference in
  fonts or data between the image and the device as a bug.
- A mismatch between what the ticket asks and what the code does is a high-value item, or a
  question for the developer.
- A spec you can't read blocks you ("see attachment" that won't download): ask, don't guess.
- Follow the whole journey the ticket names, end to end, not only the screen that changed.
- A sibling ticket on the other platform is a strong signal: read its plan and its bugs.

## 2. Pick the categories — name the line

For every category you include, name the line in the diff that could break it. No changed
colour, string, modifier, request or route → the category stays out. "It touches a UI component"
does not qualify. Count only changed lines: a line removed and added with the same content was
reformatted.

Most tickets need two or three categories; a refactor often one. A plan covering most of the menu
is a checklist and teaches the tester to skim. The menu:

- Happy path (always)
- Error handling (network errors, empty data, invalid input)
- Feature toggle on and off, and the switch between them
- User states (logged in/out, new user, many items)
- Network contract (request and response shape)
- Navigation, deeplinks and the back stack
- Dark mode — only if colours change
- Text size and accessibility — only if text, modifiers or content descriptions change
- Screen sizes and rotation
- Process death (`lab_read_skill('lifecycle-and-config')`)
- Copy, in every language the app ships
- Regression and side effects — when you find ripple risk

Ripple heuristics: a shared or core module → list the affected screens beyond the ticket's; a
shared state stream → every consumer; a serialized model → "test on a minified build"; gated
behaviour → both toggle states.

## 3. Each item

- What to do, what to expect, and how to reach the state: a proxy rule
  (`lab_read_skill('proxy-forcing')`), a toggle, a deeplink, a test user, a device setting. Never
  tell the tester to skip an item because the state is hard to reach.
- Mark items that came from the developer; under regression, say why each item is at risk.
- Plan the control too (`lab_read_skill('ab-control')`): which build shows the old behaviour.
- No test guidance on the ticket → say so and build the plan from the code. List only the sources
  you actually checked.

Show the plan to the user and ask whether to run all of it or a part.
