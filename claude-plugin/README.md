# Testlab for Claude Code

A Claude Code plugin with a mod. It draws two panes in the terminal and starts the Testlab MCP
server (`testlab --mcp-stdio`, from `.mcp.json`).

- **`/testlab-screen [serial]`**: the phone's screen live, about 4 frames a second. Use `b` back,
  `h` home, `r` apps, and `u`/`d` to scroll. `j`/`k` step back and forward through the last ~200
  frames and `l` returns to live. `1`–`6` show the screen just before that action in the list.
  `p` pauses and `i` switches between pixels and cells.
- **`/testlab-report [id]`**: a written report, chapter by chapter. The picture is the screenshot
  when the chapter is one, else the recording's frame at that time (needs ffmpeg). The report's
  text and a link to the full page sit beside it.

Both panes also open when Claude calls `handsets_show_screen` or `lab_show_report`.

**Pictures.** kitty and Ghostty draw real pixels: the server writes PNG frames to a ring of files
in its cache, and the terminal reads them itself. Every other terminal gets half-block cells, two
pixels a cell, from small raw RGB frames.

**Permissions.** The panes' own calls (frames, activity, the report and the pane's keys) go to
Testlab's local control API when the background service or `testlab serve` runs (`control.json` in its data
folder), so they aren't Claude's tool calls and need no permission. That matters where an
organization caps MCP tools at "ask", which a mod can't loosen. Without the control API they go
over MCP, and the plugin allows its own calls there. Claude's calls go through your usual
permissions either way.

Needs Claude Code 2.1.287 or later (mods) and the `testlab` CLI on your `PATH`. Tested with
2.1.295. Install with `testlab claude-plugin`. It writes this folder from the CLI to Testlab's data
folder and adds it as a local marketplace, so it needs no access to the repository. Run it again
after an upgrade.

```bash
claude plugin validate .     # what Claude Code reads from the mod
claude plugin test           # the pane tests (no session, no network)
claude --plugin-dir .        # develop: reloads on save
```
