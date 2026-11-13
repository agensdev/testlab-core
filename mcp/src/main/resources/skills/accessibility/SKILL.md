---
name: accessibility
description: How to test an Android app's accessibility. Covers TalkBack with real gestures (explore by touch, double-tap, swipe next/previous), reading what TalkBack will say, keyboard (Tab) navigation, large text, and A/B evidence between two builds. Load it for any accessibility, TalkBack, screen-reader, content-description, keyboard or font-size ticket.
---

# Accessibility testing

## Two kinds of input

- The usual tools inject their input: `handsets_tap`, `handsets_swipe`, `handsets_type_text`,
  `handsets_navigate` and `handsets_key`.
  - Injected input skips TalkBack. With TalkBack on, a tap is still one normal click, and
    TalkBack's focus (the green frame) stays where it was.
  - That makes them the way to **navigate** to the screen under test. You can leave TalkBack on.
  - A tap clicks through accessibility only when the selector is a `#resource-id`. Text falls
    back to a tap at the node's coordinates.
- The TalkBack tools are **real gestures**: a virtual touchscreen, which TalkBack takes for a
  finger. Use them for the gesture under test.
  - **`handsets_talkback_touch(selector_or_text | x,y)`** is explore by touch. It holds about 600 ms on
    the node, so TalkBack moves its focus there and speaks it. The selector is resolved on a fresh
    dump right before the touch, because layouts move: the screen shifts when the keyboard closes.
  - **`handsets_talkback_activate()`** is a double-tap. It activates the node TalkBack has in
    focus, wherever that is on screen.
  - **`handsets_talkback_swipe(next | previous)`** is a swipe right or left, to the next or previous
    element in TalkBack's reading order.
  - These tools refuse to run while TalkBack is off, because a double-tap there would click
    whatever is under it.
- System dialogs can ignore injected taps while TalkBack runs, such as the notification-permission
  dialog. A TalkBack touch followed by `_activate` works there.

## TalkBack on and off

- `handsets_talkback(action=on)` enables TalkBack. It also dismisses TalkBack's
  notification-permission dialog and its tutorial.
- `handsets_talkback(action=off)` remembers exactly what it removed, and `on` puts that back.
  Always restore TalkBack after a session.

## What TalkBack will read

- `handsets_ui_dump(form=a11y)` lists text, content description and hint as separate fields,
  with every state flag: clickable, focusable, checked, disabled and so on. TalkBack reads the
  description when one is set, even when the node also shows text.
- The dump doesn't report TalkBack's own focus or live regions. A screenshot shows the focus frame.
- To capture what TalkBack actually said:
  1. On the phone, turn on TalkBack settings → Advanced → Developer settings → "Display speech
     output".
  2. Call `handsets_talkback_touch(..., screenshot=true)`. The returned screen shows the caption.
- Known TalkBack behaviour:
  - When two polite live regions change in the same frame, it reads only one.
  - When an editable field's value changes, it reads a diff ("9 replaced 10"), not the new value.

## Keyboard

- `handsets_key(tab | shift_tab | enter | space | dpad_* …, count)` presses keys. The result says
  whether the on-screen keyboard is up.
- Don't put taps in a keyboard flow. A tap puts the phone back into touch mode, and the focus ring
  disappears.
- One Tab can get lost. Wait for what the screen shows, and use a screenshot to see the focus ring,
  rather than counting Tabs.

## Large text

- `handsets_font_scale(action=set, scale=1.5)` sets the text size, and `reset` puts back exactly
  the size it replaced. Always reset when you're done.
- Some apps need a restart to pick up the new size.

## Evidence, and comparing two builds

- Compare the accessibility tree before and after (`form=a11y`), and record the session
  (`lab_session_start`). Use TalkBack gestures only for the gesture itself.
- For an A/B test, run the same scripted sequence on the control build and the fix build, each from
  a clean app state: force-stop the app, then relaunch it. Interactive runs with pauses don't line
  up for side-by-side video.
- With several phones connected, pass `serial` on every call. A call that names a serial doesn't
  change the active device, but a call that names none uses it. Pass `serial` to
  `lab_report_screenshot` too, so the shot comes from the right phone.

## What to check on every new screen

- Dump it (`form=a11y`): unlabeled tappable groups, and buttons missing from the tree, are
  findings.
- One stop per control, no duplicate stops, and nothing inside a closed sheet reachable.
- After activating a control, focus stays where the user expects (on it, or on what it opened).
- The system's "accessibility enabled" flag can be wrong for some UI toolkits: judge by
  behaviour, not by the flag.
- Elements the autopilot and the tools can't see are invisible to screen-reader users too: note every place
  you needed a coordinate tap.

## Phone state

- After a reboot, a phone with a locked SIM stops on the **SIM PIN** screen before the lock screen.
  `handsets_unlock_device` then refuses, because the device PIN typed there would use up SIM
  attempts. Unlock the SIM on the phone yourself.
