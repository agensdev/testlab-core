# Parity with jevpick.py

The decision engine (`core/…/jev`) is a port of `jevpick.py`, the Python tool it grew out of (a copy with the core's generic vocabulary is in `tools/golden/`). Two test layers hold it to that:

- **`JevpickPortTest`** — its 27 tests, ported one-to-one with the same names and fixtures.
- **`PythonGoldenTest`** — `tools/golden/gen.py` runs the pinned Python over the test fixtures and 34 emulator dumps. It records parsed nodes, candidates for TypeSafe, Ollama and risky-allowed modes, the request state and question, fingerprints, titles, and 400 step-policy outcomes. The Kotlin engine must reproduce all of it, with JSON key order respected.

Regenerate the goldens after changing the Python copy or the core's vocabulary with `python3 tools/golden/gen.py`.

## Deliberate differences

| Area | jevpick.py | Testlab | Why |
|---|---|---|---|
| `hide_keyboard` | Taps a fixed chevron position (195,2990, Pixel 6 Pro) | Sends BACK only while `dumpsys input_method` says the keyboard is up | A fixed position is wrong on other phones; BACK without a keyboard navigates away. The chevron tap remains as a fallback when no device hook is given, scaled to the screen |
| Pixel constants | Title band 150–700 px, label search 450 px up and 600 px across, tuned on 1440×3120 | Scaled to the device's size; identical at 1440×3120 | Phones differ |
| `diff.added` / `diff.removed` order | Python set iteration order, which differs between processes | Screen order | Deterministic output; the lists are diagnostic only |
| Stub response in traces | `{"stub": true}` | A response-shaped object (`answers`, `usage`) plus `"stub": true` | Replay tools such as `jev-replay.py` read every trace the same way (the Python tool crashes on stub records) |
| Probabilities for unknown keys | `KeyError` in the fallback search → step error | Skipped | A sloppy answer should not crash a plan |
| Missing TypeSafe key message | Points to an environment file | Points to Settings | Local install |
| Daemon-down hint | Matches `no forwarded daemon` | Also matches the app's `no daemon on …` | Same advice for the new transport |
| Screenshot step without a target | Falls back to `JEV_LOG_DIR` | Needs a `ShotStore` (the app always passes one: the open report, else the traces folder) | No global log directory |
| `hasWord` (`[^\W_]`) | Code-point based | Per UTF-16 unit, so letters outside the Basic Multilingual Plane do not count | Shared code has no code-point category API; such letters do not occur in the app's UI |
| `_TREE_FLAGS` `\w` | Python Unicode word class | Java `(?U)\w`, which also counts combining marks | Flags are ASCII words, so no output differs |
| Typing | `hs type` sends key events from the virtual keymap, so æøå and emoji fail | Keymap text as keys; anything else pasted through the clipboard, edge spaces as SPACE keys | Test data in other languages (æøå) |
| UI dump source | `hs ui --tree` text from the CLI | The daemon's `dump_active` JSON rendered by `UiDumpRender` to the same bytes | Byte-identical on 17 captured screens (`UiDumpRenderGoldenTest`) |
| Vocabulary | Norwegian risky/leave words, an app glossary and app-specific examples built in | English base words; the profile's `autopilot` section adds the app's language, glossary and examples | One core for every app |
