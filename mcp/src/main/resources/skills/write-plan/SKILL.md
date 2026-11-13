---
name: write-plan
description: How to write an autopilot plan — a JSON list of steps that handsets_autopilot_plan runs in one call (state goals, single actions, scripted taps, typed credentials by reference, waits, screenshots). Load this when a device flow has more than a few steps, will be repeated, or should be saved for colleagues.
---

# Write a plan

A plan runs a whole device flow in **one tool call** and only comes back when it is done or a step
needs you (a *hand-back*). A 55-step login + search + checkout flow takes about a minute
with scripted taps and about 70 s with the autopilot deciding the buttons; driving it turn by turn takes minutes.

## Steps

```json
[
  {"note": "what the plan does and from which state"},
  {"open": "com.example.shop"},
  {"wait_for": "Home", "timeout": 20},
  {"goal": "Open Search"},
  {"step": "Choose the first product in the list"},
  {"tap": "#tab_cart"},
  {"type": {"cred": "demo.username"}},
  {"hide_keyboard": true},
  {"key": "back"},
  {"sleep": 2},
  {"screenshot": "cart"}
]
```

| step | does | use it for |
|---|---|---|
| `{"goal": …}` | the autopilot loops until the screen shows the goal (`max_steps` 5) | *state* goals: "Open Search", "Open the Profile tab" |
| `{"step": …}` | one autopilot action, done when the screen changed | *action* goals: "Press Next", "Tick the gift-wrap checkbox" |
| `{"tap": "#id" \| "Label" \| "x,y"}` | scripted tap, no model (`"nth": 1` = second match) | elements that are always the same; fastest |
| `{"type": "text"}` / `{"type": {"cred": "acct.field"}}` | types into the focused field | credentials **always** by reference |
| `{"hide_keyboard": true}` | hides the keyboard when it is up | before tapping a button the keyboard covers |
| `{"wait_for": "text or #id", "timeout": 20}` | waits until it is on screen | after every screen transition |
| `{"open": "pkg"}` `{"key": "back"}` `{"sleep": 2}` | launch, hardware key, pause | |
| `{"swipe": "up"}` | scrolls (the finger moves up: the content goes further down) | reaching something below the fold before a `tap`; re-read with a `wait_for` after |
| `{"screenshot": "caption"}` | saves a screenshot (into the report with `report_id`) | evidence for a devtest |
| `{"note": "…"}` | nothing | documentation |

Any step can carry `"if_present": "text or #id"`: it runs only when that is on screen, else it is
`skipped` (an age gate accepted earlier, a prompt that comes only sometimes). A `goal`/`step` with
`"optional": true` is `skipped` instead of handing back when it can't be done. Run a plan with
`isolate=true` when the app's other builds would claim a return link (a sign-in or payment provider).

Purchase/delete-worded steps need `"allow_risky": true` on that step, run only at confidence ≥ 0.5,
and are refused on the production app.

## Writing good plans

- **Start from a known state** (`open` the app, `wait_for` its first screen) and say in the first
  `note` which state the plan expects (fresh install, logged in…).
- **`wait_for` after every transition** (login, purchase, a new screen), with a timeout that fits.
- **Scripted `tap` for fixed elements** (bottom tabs by `#id`, a field you focus), **`goal`/`step`
  for anything whose position or wording varies**. Mixing both is the fast and robust middle.
- **Name elements the way the screen does** ("Betal 75 kr", "Ikke nå"); quote the visible label.
- **Credentials by reference only**: `lab_list_credentials` lists what is set. A plan that needs a
  missing reference is refused before it starts.
- **Screenshots where a reviewer needs evidence**: after a purchase, on a receipt, on an error.

## Running, resuming, saving

1. `lab_save_plan(name, plan)` validates and stores it (every mistake is listed at once).
2. `handsets_autopilot_plan_file(name)` (or `handsets_autopilot_plan(plan)` inline) runs it.
3. On `"status": "paused"`, read `hand_back` (candidates, top, reason), do that step yourself or fix
   its wording, then call again with `start = paused_at + 1`.
4. Each step result has `timing` (read/decide/act/verify/wait ms) and the run has `timing_total`
   and `usage_total`: tell the user where the time went when it matters.
