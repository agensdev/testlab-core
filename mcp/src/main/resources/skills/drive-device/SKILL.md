---
name: drive-device
description: How to READ the phone screen and INTERACT with it — the core device-driving loop. Use handsets_ui_dump to see every tappable element (text, resource-id, coordinates), then tap / type / swipe / navigate. Load this whenever you need to find what's on screen, click a button, fill a field, or move around the app and a more specific skill hasn't already told you how.
---

# Drive the device (read the screen, then act)

The interaction loop every device task uses, on the phone or emulator attached to this computer.
Call `handsets_use_device` once per session first (device-prep does this).

## The loop: READ → ACT → SYNC

1. **READ the screen — `handsets_ui_dump`.** It returns the accessibility tree as text, not a
   picture. Use it *before every tap* to get exact labels, ids and coordinates; never guess.
2. **ACT** — `handsets_tap` / `handsets_fill` / `handsets_type_text` / `handsets_swipe` /
   `handsets_navigate` / `handsets_go`.
3. **SYNC** — `handsets_wait_for("text")` after an action that loads a new screen, so the next
   `ui_dump` reads the settled screen.

To *show the user* the screen, use `handsets_screenshot` (a JPEG). `ui_dump` is for you to locate
elements; `screenshot` is for the human to look at.

## Reading `handsets_ui_dump`

Default `form="table"`: a verb-led flat list, one node per line.

```
tap  FrameLayout  "Search"  #tab_search  432,2896
-    TextView     "3 items in your cart"  402,1358
```

- **First column:** `tap` = actionable, a valid target for `handsets_tap`; `-` = a label or
  decoration: read it for context, don't tap it.
- Then the **widget type**, the **"label"**, an optional **`#resource-id`** and the **`x,y` centre**.
- `form="tree"` gives the indented hierarchy, `form="json"` the raw dump.

## Acting on what you read

- **`handsets_tap(selector_or_text)`**: prefer the visible text or the `#resource-id`:
  - `"Continue"`: by text: exact, then case-insensitive, then substring; text before content-description.
    A label inside a clickable row taps the row.
  - `"#fragment_games"`: by resource id (the part after `:id/` is enough).
  - CSS-like selectors for anything ambiguous: `Button[text="OK"]`, `EditText[hint~=Email]`,
    `Button:has-text("Sign in")`, `EditText:below(TextView[text=Email])`,
    `Button:near(ImageView[desc~=cart], 200)`; pseudos `:clickable :enabled :visible :focused
    :checked :has-text("x") :text-is("x") :in(SEL) :right-of(SEL)`.
  - A match that is off screen is refused ("swipe to bring it into view"), not tapped at a clipped position.
- **`handsets_tap_xy(x, y)`**: fallback for targets with no stable text or id. Coordinates break
  when the layout changes; text and ids don't.
- **`handsets_fill(selector, value)`**: set a field's text in one action (search, login forms).
  `handsets_type_text(text)` types into the *focused* field (any characters, æøå included) after
  what it already holds; to **change** a field's text use `handsets_fill(selector, value)` or
  `handsets_type_text(text, replace=true)`. Don't rely on a field's clear (×) button: many apps
  show it only while the field has focus, so after `hide_keyboard` a tap there lands in the field.
  `handsets_submit()` presses the keyboard's Done/Search.
- **Secrets**: never type a password or national ID from the chat. Use
  `handsets_type_secret("demo.password")` or `handsets_fill(selector, cred=…)`;
  `lab_list_credentials` shows which references are set. Values never appear in results.
- **`handsets_swipe(up|down|left|right)`**: scroll; read the screen again after it.
- **`handsets_drag(points, duration_ms, hold_ms, repeat)`**: press, move along a path, release,
  for games and canvases (draw, brush, steer, drag-and-drop, sliders). Points are `[[x,y],…]` in
  `handsets_tap_xy` pixels; `duration_ms` is one pass along the path; `hold_ms` ~500 for
  long-press-then-drag; `repeat` scrubs back and forth. Find the points in the dump, or with
  `handsets_jev_find` when there is no tree.
- **`handsets_orientation(get|portrait|landscape|landscape-right)`**: read or turn the screen.
  Coordinates follow the screen as it is turned: read the screen again after turning. An app that
  fixes its own orientation (many games) stays as it is, and the result says so; `portrait` turns
  auto-rotate back on.
- **`handsets_home(close_apps=true)`**: the home screen with a clean state: the app in front and
  the profile's apps are stopped first, so the next `handsets_open_app` starts fresh.
  `close_apps=false` only presses home.
- **`handsets_navigate(back|home|recents|menu|power)`**: hardware keys. `handsets_hide_keyboard`
  closes the keyboard without navigating away.

## Navigating the app — prefer `handsets_go`

`handsets_go(destination)` jumps by deeplink (or taps a tab) to the destinations the profile names
(`handsets_go("list")`; the server instructions list them too). Some take a subpage:
`handsets_go("<name>", subpage="<subpage>")`. Any other page: `handsets_open_url(path-or-url)`.
Tap through the UI only where no deeplink exists.

## Jev autopilot — let a decision model pick the button

For short in-app navigation with **no deeplink**, Jev (TypeSafe AI's System One model, or a local
Ollama model) gets the tappable nodes as numbered options and answers with one pick plus probabilities.

- **`handsets_jev_do("Open the Sport tab")`**: read → pick → tap → verify the screen changed →
  repeat until `goal_done`. `max_steps` default 12, 90 s deadline.
- **`handsets_jev_pick(goal)`**: one decision, *no action*: `action.hs` (the exact tap), `top`
  (five best with probabilities) and `dropped_risky`. Then `handsets_tap_xy`.
- **`handsets_jev_step(goal)`**: pick + one action + verification.
- **`handsets_jev_plan(plan)`**: a whole flow in one call, returning only on a hand-back. See
  the **write-plan** skill. Saved plans run by name with `handsets_jev_plan_file`.
- **Phrase goals right**: *state* goals for `goal`/`jev_do` ("Open Search", "Open the Profile tab"),
  *action* goals for `step`/`jev_step` ("Press Next", "Choose the Tirsdag 50 kr quickpick"). Name
  the element the way the screen labels it.

| status | meaning | you do |
|---|---|---|
| `goal_done` | the screen already shows the goal | carry on |
| `needs_decision` | confidence < `min_confidence` (0.5); **nothing executed** | pick from `candidates`/`top` yourself (`handsets_tap_xy`) |
| `blocked` | nothing on screen helps, or it needs typing or paying | do that step by hand |
| `stuck` | two actions changed nothing, or the same screen 3× | `handsets_screenshot`, then drive manually |
| `error` | empty dump (FLAG_SECURE screen), daemon down (`handsets_use_device`), or a key/API problem | fix the cause |

Limits by design: Jev only sees nodes on the current screen plus scroll/back: no typing, no app
launches. Nodes worded *kjøp/betal/bekreft/slett/delete/pay/buy* are **filtered out** unless
`allow_risky=true`, and that is refused on the profile's production app.

## Quick recipe

```
handsets_use_device                      # once per session
handsets_go "search"                     # jump to a screen
handsets_wait_for "Search"               # let it settle
handsets_ui_dump                         # READ: find the button or field
handsets_tap "#some_id"  (or "Some text")# ACT
handsets_wait_for "Next screen text"     # SYNC
handsets_screenshot                      # show the user
```

## Pixels
- `handsets_screenshot` says how its picture maps to tap pixels ("tap pixels = picture × F"):
  a position measured on the picture × F is what `handsets_tap_xy` and `handsets_drag` take.
  Screenshots are upright as the screen is turned (a real iPad's portrait-native picture is
  turned for you).

## iOS differences
- **Typing**: `handsets_type_text(replace=true)` can answer "no focused input field" (the iOS
  tree rarely marks the focused field). Use `handsets_fill(class=EditText, value)` (or the field's
  label) instead, and `handsets_type_text("\n")` for Return.
- **`handsets_key`** is Android's keyboard (keycodes, Tab focus order): on iOS use
  `handsets_type_text("\n")`, `handsets_submit()` and `handsets_navigate(back|home)`.
- **A real iPhone/iPad can't be unlocked from here** (`handsets_unlock_device` says so): unlock it
  by hand and set Settings → Display & Brightness → Auto-Lock to Never on a test device. A black
  screenshot means it went to sleep.
- **Device logs** (`lab_logs_start`) work on simulators, not on a real iPhone/iPad: use Console.app
  on the Mac. Recordings (`lab_session_start`) work on both (a real device needs ffmpeg).

## Gotchas
- Read the screen right before a tap: it may have changed since the last dump.
- A node missing from the dump is off screen (`handsets_swipe`) or not loaded yet (`handsets_wait_for`).
- FLAG_SECURE screens (PIN entry, some login pages) give an empty dump and no screenshot, but
  `handsets_type_text` and `handsets_tap_xy` still work by coordinates.
- `handsets_use_device` fails if Maestro, Appium, `uiautomator dump` or upstream `hs` holds the
  phone's accessibility connection: close that tool, or pass `take_over=true`.
