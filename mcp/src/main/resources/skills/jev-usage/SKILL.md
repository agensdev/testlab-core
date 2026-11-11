---
name: jev-usage
description: When and how to use Jev, the decision model that picks the next tap — goals versus single steps versus scripted taps, reading its confidence, what it can't do (type, launch apps, see unlabeled elements, tap purchase words), and using plans and traces. Load it before handing a flow to handsets_jev_do or handsets_jev_plan, or when Jev picked the wrong element.
---

# Using Jev well

Jev reads the screen as numbered candidates (only actionable, labelled elements), gives each a
probability, and acts on the best one above `min_confidence` (0.5 by default, Settings → Jev).
A decision takes a few hundred milliseconds.

## Which tool

- `handsets_go(destination)`: a deeplink. Best when the destination exists.
- `handsets_jev_do(goal)`: a short goal ("open the cart"); Jev loops until the screen shows it.
- `handsets_jev_plan(plan)`: a whole flow in one call (`lab_read_skill('write-plan')`).
- `handsets_tap(label | #id | selector)`: an element you already know.
- `handsets_tap_xy(x, y)` with coordinates from the dump: last resort. Say why in the report;
  the reason is usually an accessibility finding (the element isn't in the tree).
- `handsets_jev_find(goal)`: Jev looks at a **picture** of the screen (TypeSafe Box) when there
  is no tree to read: games, canvases, Unity, maps, video. See below.

## Pictures: jev_find or the tree?

- The tree first. If `handsets_ui_dump` shows the target, `handsets_tap` / `handsets_jev_pick`
  are exact and cheaper. Use `handsets_jev_find` when the dump is empty or only a single big
  view (a game engine, a canvas, a WebGL page), or when the thing is only drawn (an icon in a
  picture).
- It returns `found`, `present`, `confidence`, `box` and `x, y` (the box's centre in
  `handsets_tap_xy` pixels). **Trust `found`**, not the box: Box always draws one, even for
  something that isn't there; `found` is Jev's separate «is it on screen» (present ≥ 0.5).
- Then act: `handsets_tap_xy(x, y)`, or `handsets_drag` from it (drag a piece, brush, steer).
- Describe what it looks like and where: "the round back arrow in the top left", "the white
  tooth in the bottom row". 0.2–0.5 s a call; check the screen after acting.
- An element only `jev_find` can see is invisible to screen readers: say so in an accessibility
  report.

## Writing goals

- Use words that are on the screen ("Tap Settings", not "open preferences").
- One goal per thing to reach. A long goal invites a confident wrong turn.

## Reading confidence

- Near the threshold Jev can be confidently wrong: a goal judged "already done" at 0.52, or the
  wrong tile at 0.34. Treat decisions under about 0.6 as suspect and check the screen.
- `handsets_jev_pick(goal)` shows the top candidates without acting: use it when unsure.

## What it can't do

- It never types and never launches apps: use `handsets_type_text`, `handsets_fill`,
  `handsets_type_secret` and `handsets_open_app` (or plan steps `type`, `open`).
- It filters purchase, payment and delete words unless the step says `allow_risky`; scripted
  `tap` steps ignore `allow_risky` because they don't decide.
- Unlabeled or tree-missing elements are invisible to the tree tools: find them in the picture
  (`handsets_jev_find`), or script them (a `tap` step with coordinates), and record the gap as an
  accessibility finding.
- Noise dialogs the profile lists (`dismiss`, e.g. a test-build service asking to sign in) are
  dismissed before Jev reads the screen: they are never a step.
- On a login or PIN screen it may pick "continue without logging in": log in first
  (`lab_read_skill('device-login')`).

## Plans and traces

- Save flows you repeat (`lab_save_plan`): login, a purchase, opening a key screen.
- Every decision is written to a trace (candidates, probabilities, latency, what changed after
  the tap); `lab_jev_stats` sums them up. Read the trace when Jev went wrong before blaming it.
