# Testlab

Test your Android and iOS apps from your own computer, with Claude doing the clicking. Testlab is
a command, `testlab`, with a background service and a few local web pages, for macOS and Linux:

- it drives phones, emulators, iOS Simulators and real iPhones with an autopilot that picks the
  right button from the screen
- it installs your CI builds by PR number from GitHub Actions
- it runs devtests of Jira tickets end to end: the build, the plan, the recording, the report and the comment
- it gives your own Claude Code (or Claude Desktop, or Codex) all of that through a local MCP
  server, with skills that teach Claude how a good test round goes

Nothing runs on a server: your tokens stay in an encrypted vault on your machine.

Testlab knows nothing about your app until you give it a **profile**: which app, which GitHub repo
and builds, which Jira project and labels, which deeplinks, which test-account fields, and how the
devtest comment looks. Teams share a profile as a zip; see [docs/PROFILES.md](docs/PROFILES.md) and
the [example](docs/examples/example-shop).

**Start here:** download `install.sh` and the package for your computer from the
[latest release](https://github.com/agensdev/testlab-core/releases/latest), then run
`bash ~/Downloads/install.sh --setup`. The [guide](docs/GUIDE.md) (also `testlab open guide`) explains
the rest; [docs/INSTALL.md](docs/INSTALL.md) has the details.

## Getting started

1. Install the CLI: `install.sh` and the package from the [latest release](https://github.com/agensdev/testlab-core/releases/latest),
   then `bash ~/Downloads/install.sh` (Java is bundled; [INSTALL.md](docs/INSTALL.md) has the details).
2. Run **`testlab setup`**. It walks through everything that isn't done yet, checking each step as
   it goes, and skips what is done; run it again whenever you like. On a desktop it opens the
   **setup page** in your browser for the tokens and test accounts, so nothing secret is typed into
   the terminal.
   - a GitHub token, then the project: your team's profile (a .zip or folder), or your repo picked
     from a list, which becomes the profile (it reads the repo's Actions builds)
   - Jira (e-mail and API token)
   - the TypeSafe autopilot key, a local Ollama model, or an OpenAI key (Decisions API)
   - test accounts, and for iOS the Apple team that signs WebDriverAgent for real iPhones
   - the devices for the platforms the project covers: an Android phone (cable or Wi-Fi pairing),
     and on a Mac the iOS Simulators and iPhones
   - the Claude Code plugin, Claude Desktop's entry, and the background service
3. Ask Claude, for example: "Install the build of PR 1234, log in with the demo account and open
   search". `/testlab-screen` in Claude Code shows the phone while it works.

A few things are easier to see than to type. **`testlab open PAGE`** shows them in your browser,
served by the local server and signed in by the link. The link goes to `http://testlab.localhost:8378`
(browsers send `*.localhost` to this computer); `--ip` gives a `127.0.0.1` link for one that doesn't:

- `setup`: the keys, Jira, the autopilot, test accounts and the Apple team, each with its state
- `devices` (the default): what adb reaches (USB, Wi-Fi, IP, emulators), phones nearby with
  Wireless debugging on, pairing with a code, and the proxy per phone
- `screen [serial]`: the phone live: tap, swipe, and rewind to any action
- `reports`, `report [id]`: every report, and one with its recording and chapters
- `profiles`: make a profile from a repo (builds, Jira, queue labels), edit, import, export or switch
- `approvals`: writes waiting for your yes (Jira comments, uninstalls, CI builds), with the exact payload
- `handback`: a plan that stopped where the autopilot wasn't sure: click what it should have
  tapped, or mark the step as done by hand, and the plan goes on
- `settings`: Proxyman, the server, the phones and everything else, plus what's still missing
- `guide`: [the guide](docs/GUIDE.md)

`testlab status` shows the setup checklist without changing anything. A team can share its
profile: `testlab profile export`, then `testlab profile import team.zip`.

## Several clients: spaces

Working for more than one client on one computer? Give each one a **space**. A space has its own:

- folders, vault and Keychain item, so one client's tokens and test accounts never reach another's
- settings, profiles, reports and caches
- port and background service
- names in Claude Code, Claude Desktop and Codex (`testlab-acme`, `testlab-globex`)

```bash
testlab space create acme --from acme.testlab-profile.zip    # a new client: its profile, then setup
testlab --space globex status                              # any command in another space (or TESTLAB_SPACE=globex)
testlab space use acme                                     # the space later commands use
testlab space list                                       # the spaces and their ports
testlab space migrate acme                                 # what you had before spaces becomes the acme space
```

Until you create one, everything is the `default` space: the folders as they were.

A space also keeps its client's work in, not just apart:

- **Rules.** The first profile a space gets pins its GitHub owners, Jira sites and app families into `space.json`. After that, another client's profile is refused, on import, save and activate. So are GitHub and Jira calls for anything else. Use `testlab space rules` to see them and `testlab space allow github OWNER | jira SITE | app FAMILY` to widen them.
- **Masking across spaces.** Each space publishes salted hashes of its secrets, never the values. Every space masks the others' too: one client's token pasted into another client's session is `‹redacted›` in that session's results, traces and reports. This covers values of 8 or more characters without spaces, so not PINs.
- **Diagnostics.** The support bundle holds only the current space's files and key names.
- **Shared phones.** A phone belongs to one space at a time. The space that drives it first holds it. Other spaces see it as "in use by another client" and can't run anything on it, whether over adb, from a tool or from the CLI.
  - `testlab devices release [SERIAL]` frees a phone after a **hand-over**, which undoes what that client left: its apps' data is cleared (or the apps uninstalled, per `space.json` `handover`), the proxy is removed, and TalkBack and font size are put back.
  - `testlab devices lease SERIAL` holds a phone until it is released.
  - A plain hold ends when that space's processes stop. The next space then takes the phone, and the hand-over runs first.
- **Claude per client.** `testlab space claude acme` writes a Claude Code profile in `~/.claude-profiles/acme`. Start it with `CLAUDE_CONFIG_DIR=~/.claude-profiles/acme claude`.
  - It sets `TESTLAB_SPACE=acme` and installs the plugin wired to the acme space.
  - It denies the other spaces' Testlab tools and their code roots (`testlab --space globex space allow code ~/code/globex`).
  - `--sandbox` also turns on Claude Code's sandbox, so Bash can't read those roots either.
  - Run it again after adding a space or a code root.
- **Sharing a set-up.** `testlab space export acme` writes the space's rules and profiles, never secrets or local paths. A colleague runs `testlab space create acme --from acme.testlab-space.zip`.

## What it does

- **Devices**: Android phones and emulators over adb, iOS Simulators, and real iPhones and iPads
  (through WebDriverAgent once an Apple team is set). One device per session, or several side by side.
- **Plans**: JSON lists of goals, taps, typed credentials (by reference), waits and screenshots, run
  in one call. When a step is unsure it hands back: click the element on the hand-back page and the
  plan goes on. `lab_compare_backends` runs a plan per decision backend and writes a side-by-side report.
- **Builds**: the newest CI build of every branch per build type, and the releases; a PR number or
  link finds that PR's build and says whether it is from the newest commit.
- **Devtests**: the tickets waiting for one (the profile's labels); for a ticket, its PR, the build,
  a recorded report, and the comment in the profile's format with the report and video attached.
- **Reports**: recorded sessions with screenshots, notes and chapters. They open offline in any
  browser, and the standalone page plus the video can go straight into Jira.
- **Skills**: playbooks Claude loads as it needs them: writing a test plan from the diff, choosing a
  control build, forcing states through Proxyman, lifecycle and accessibility checks, honest
  reports, cleaning up the rig. A profile adds its team's own.

Safety rails:
- Uninstalls, CI builds and every Jira write wait for your yes: a desktop notification, then the
  approvals page, `testlab approve`, or Claude's own dialog.
- Purchase-worded taps are refused on the production app unless `device.allowRiskyOnProd` allows them.
- Credentials are only used by name (`demo.password`), and every result is scrubbed of stored values.

## The MCP tools

| Prefix | Covers |
|---|---|
| `handsets_*` | Drives the device (screen, tap, type, fill, swipe, wait, deeplinks, unlock, login), installs builds, and runs the autopilot (`handsets_autopilot_pick`, `_step`, `_do`, `_plan`) |
| `lab_*` | Plans, recordings, reports (with plan items each report must account for), playbooks, credentials and status; the rig's health from the phone (`lab_health`), the phone's settings across a session (`lab_device_state`), device logs and analytics events (`lab_logs_*`, `lab_analytics_events`), and Proxyman rules with a lifecycle (`lab_proxy_force`, `_rules`, `_cleanup`) |
| `jira_*` | Reads, and after approval writes, your Jira |
| `github_*` | Reads the repo's PRs, diffs and files |
| `proxyman_*` | Proxyman's own tools, when Proxyman is enabled. For a Proxyman on another machine, set `proxyman.host` to it and `proxyman.mcpServer` to its MCP bridge's URL (`http://HOST:7900/mcp`): Testlab speaks to it directly, no adapter |

The playbooks are also MCP prompts:
- `/mcp__testlab__drive-device`
- `/mcp__testlab__write-plan`
- `/mcp__testlab__device-login`
- `/mcp__testlab__devtest PROJ-123`

A profile can replace them or add its own.

## Watch the phone from Claude

`handsets_show_screen` shows the phone's screen live while Claude works: tap and swipe on it, press
back/home, and rewind to the moment of any action. `lab_show_report` shows a written report inline,
with a chapter per action to jump through the recording.

- **Claude Desktop** (and other hosts with MCP Apps) draws both in the conversation. Ask it to show
  the screen.
- **Claude Code** draws them as panes in the terminal with the Testlab plugin. Real pixels appear in
  kitty and Ghostty; other terminals get half-block cells. The plugin is inside the CLI, and it also
  starts the MCP server, so the `testlab` CLI must be on your `PATH`:

  ```bash
  testlab claude-plugin        # installs (or, after an upgrade, updates) it; then restart Claude Code
  ```

  With access to this repository, `/plugin marketplace add agensdev/testlab-core` and then
  `/plugin install testlab@testlab` work too. `/testlab-screen` opens the phone, `/testlab-report [id]` a report. Both also open when Claude calls the tools. Keys: `b` back, `h` home, `r` apps, `u`/`d`
  scroll, `j`/`k` back and forward in time, `l` live, `p` pause, `i` pixels or cells, `1`–`6` the
  screen at that action. If you added Testlab with `claude mcp add` before, remove that entry
  (`claude mcp remove testlab`), so the tools aren't there twice. See
  [claude-plugin/README.md](claude-plugin/README.md).

## From the terminal

Everything Claude does through the tools also works from the terminal:

```bash
testlab status                     # what is set up and what is missing, with what to do
testlab config set proxyman.host 192.0.2.20     # change a setting (config list shows them all)
testlab serve                      # the MCP server, the control API and the pages, in this terminal
testlab service install            # …as a background service (systemd or launchd)
testlab approvals                  # writes waiting for a yes (approve ID / deny ID)
testlab profile import team.zip    # a project profile (profile list | check | use | export)
testlab install --pr 1234          # the PR's CI build onto the connected phone (or --release latest, --apk FILE)
testlab builds                     # the newest CI build per branch
testlab devtest queue              # tickets waiting for a devtest (devtest prs PROJ-123: its PRs)
testlab jira issue PROJ-123        # a ticket as Markdown (jira me, jira search JQL)
testlab mcp-config                 # connect snippets for Claude Code, Claude Desktop and Codex
testlab diag                       # a diagnostics zip to send when something is wrong (secrets removed)
testlab plan --file plan.json      # run a plan from the terminal
testlab --help
```

The background service runs the server from login, or at boot on a shared phone rig; see
[docs/SERVICE.md](docs/SERVICE.md). Other apps on the computer (a team's own desktop tool,
scripts) can drive Testlab through the local **control API**: install a PR's build, start a
devtest on a ticket (with the prompt for their own Claude), and follow progress as events. See
[docs/CONTROL_API.md](docs/CONTROL_API.md).

Claude Desktop starts Testlab with `--mcp-stdio`. That forwards to the running server, or serves
the tools itself when none runs (and then asks for approvals in Claude's own dialog).

## Modules

| Module | What lives there |
|---|---|
| `core` | Pure logic shared by everything (Kotlin Multiplatform, no IO): the profile model, plans, the autopilot engine, dump renderers and parsers, selectors |
| `device` | adb, the handsets daemon client, tool discovery |
| `services` | The runtime, profiles, decision backends, GitHub and Jira, recordings and reports, connection checks, the secret vault, settings |
| `mcp` | The local MCP server: tools, playbooks, auth, the stdio bridge, the control API and the web pages |
| `cli` | `testlab`: setup, the server, the background service and every command |

## Build

JDK 21 is required.

```bash
./gradlew check                     # all tests
./gradlew :cli:installDist          # cli/build/install/testlab/bin/testlab
./gradlew :cli:packageCli           # the release package for this OS (Java bundled)
```

A team's branded build (its own name, ids and bundled profiles) comes from the same source: see
[docs/DEV.md](docs/DEV.md#branded-builds). Contributing: [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence and credits

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Device control uses [handsets](https://github.com/elliotgao2/handsets) by Elliot Gao (MIT): the
bundled on-device daemon, and ports of its dump renderer, tap-by-text and selector engine. Decisions use
[TypeSafe](https://typesafe.ai)'s Jev, a local [Ollama](https://ollama.com) model, or OpenAI's
[Decisions API](https://developers.openai.com/api/docs/guides/decisions).
