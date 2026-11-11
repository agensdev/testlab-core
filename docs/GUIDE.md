# The Testlab guide

Testlab lets Claude (or you, from a terminal) drive real phones and simulators: read the screen,
tap and type, install the build of a pull request, log in with a test account, record what
happened and write a report. Everything runs on your own computer. Tokens and passwords stay in
an encrypted vault on it, and Claude reaches Testlab over `127.0.0.1`.

You work with Testlab in three places:

- **The terminal**: the `testlab` command sets things up, shows what is missing and runs the
  server.
- **Claude Code** (or Claude Desktop, or Codex): you ask in plain words ("show me the phone",
  "devtest PROJ-123") and Claude uses Testlab's tools.
- **A few local web pages**: `testlab open PAGE` signs you in and opens one in the browser. They
  are for the things a terminal is bad at: pasting tokens, watching the phone, reading reports,
  answering approvals.

## 1. Install

You need macOS on Apple silicon or a 64-bit Linux, an Android phone (USB data cable, or Wi-Fi on
Android 11+) and, for iOS, a Mac with Xcode.

1. From the [latest release](https://github.com/agensdev/testlab-core/releases/latest), download
   `install.sh` and the package for your computer (`testlab-cli-…-macos-arm64.tar.gz` or
   `…-linux-x64.tar.gz`) into Downloads.
2. In a terminal: `bash ~/Downloads/install.sh --setup`. It installs to `~/.local/opt/testlab`,
   puts `testlab` on your `PATH`, clears macOS's download flag, checks `SHA256SUMS` when you
   downloaded it too, and goes straight on to section 2. Without `--setup` it only installs.

By hand instead: unpack the package somewhere permanent, run `xattr -dr com.apple.quarantine` on it
on macOS, and put its `bin` on your `PATH`. Later versions: `testlab upgrade` (and
`testlab upgrade --rollback` if one misbehaves).

## 2. Set up

Run:

```bash
testlab setup
```

It walks through what isn't done yet, in order, and skips what is. Run it again whenever you like.

- **On a desktop**, it first offers the **setup page** in your browser, for the GitHub token, Jira,
  the autopilot key, test accounts and (for iOS) the Apple team. Paste them there. The page saves
  each step as you go and shows a green tick when the service accepted it. Then press Enter in the
  terminal. Prefer the terminal for everything? `testlab setup --no-web`.
- **The project** is a profile: the app's repo, its build types, Jira, the login and the
  destinations to navigate to. Your team usually has one; import it on the setup page, or with
  `testlab profile import team-profile.zip`. Otherwise setup makes one from a repo you pick: first
  the account or organisation, then the repo (the ones that look like an app come first, marked
  Android?, iOS?, Flutter?). Testlab reads that repo's pull requests, diffs and CI builds, and never
  writes to it. An app with Android and iOS in separate repos gets one project each: setup asks for
  the second, and the device you use picks which one applies. `testlab open profiles` changes them later.
- **Devices** follow the platforms the project covers: an Android phone (USB, or Wi-Fi pairing),
  and on a Mac the iOS Simulators and real iPhones (see section 6). No Android phone at hand? Type
  `emulator` at that step: Testlab gets Google's Android CLI, which downloads the emulator and a
  system image (a few GB) and makes a `medium_phone` emulator. Later: `testlab emulator create`,
  `testlab emulator start NAME`, or ask Claude. An emulator suits flows and layouts; camera, bank-ID
  logins, NFC, notifications and performance need a real phone.
- **Claude**: setup installs the Claude Code plugin and adds Testlab to Claude Desktop.
- **The background service** keeps Testlab's server running for Claude, starts at login and
  needs no window. `testlab service status` and `testlab service logs` show how it is doing.
  On a Mac it has an icon in the menu bar (on Windows, in the taskbar's notification area): the
  phones it sees, approvals waiting for your yes (a dot on the icon), the pages, and restart or
  quit. It never shows in the Dock. Don't want the icon? Untick it under Settings.

`testlab status` shows the same checklist without changing anything. `testlab open setup` brings
the setup page back at any time.

Several clients on one computer? Give each its own space (`testlab space create NAME`), with its
own keys, profiles, reports and port. See the README.

## 3. First steps with Claude

Start Claude Code and ask in plain words. `testlab setup` ends with prompts that use your project's
own screens and Jira, and the setup page lists them too (click one to copy it). A start:

| To | Ask |
|---|---|
| See the phone | "Show me the phone" (a live pane in Claude Code, or `testlab open screen`) |
| Check the rig | "Is the phone ready to test? Check its network, the proxy and leftover settings" |
| Log in | "Open the app and log in with a test account" |
| Get a PR's build | "Install the build of PR 123 and check that the app starts" |
| Go somewhere | "Go to the cart and take a screenshot" |
| Explore | "Tap through the checkout, look for anything broken, and write a report with screenshots" |
| Devtest a ticket | "Devtest PROJ-123", or "Which tickets are waiting for a devtest?" |
| Save a flow | "Write a plan that logs in and opens the cart, run it, and save it as a plan" |
| Network evidence | "Record the session with Proxyman and show me the requests the app made" |
| Logs | "Capture the log while I reproduce the crash, then show me the stack trace" |
| iOS | "Do the same on the iOS Simulator" |

Claude learns how through **skills**, playbooks it loads as it needs them
(`lab_list_skills` lists them). You don't have to name them; asking for the task is enough.

## 4. A devtest round

A devtest (peer test) is a second pair of eyes on a colleague's change. Ask Claude:
"devtest PROJ-123". It follows the `devtest` skill:

1. **The ticket and the change**: the description, every comment, the linked PR and its diff.
2. **The plan**: only what a changed line can break, how to reach each state, and a control build
   to compare with (`test-plan`, `ab-control`). Claude shows you the plan first.
3. **The run**: the PR's build on a device, the session recorded, a screenshot per item, states
   forced through the proxy when needed (`proxy-forcing`, `lifecycle-and-config`).
4. **Clean up**: proxy rules, device settings and recordings back as they were (`rig-cleanup`).
5. **The report**: every plan item under Tested or Not tested (the report refuses to be written
   while one is unaccounted for), a result line that follows from them, a verdict for each finding,
   and a «Rig» section with any proxy rule or phone setting changed during the session
   (`report-honesty`). Claude shows a preview; nothing is posted until you approve.

**Crashes in the field.** When the profile names the app's Firebase apps (`crashlytics` in the
profile) and you have signed in with `testlab android-cli login` (a Google account that can see the
app in Firebase), Claude looks up a crash from the test run in Crashlytics: whether real users get
it, how many, since which version and on which devices. It names the issue in the report, as a lead
it has checked against the issue's stack trace.

Several devices? Claude can test one ticket per device at the same time.

### Several phones on one Proxyman

One Proxyman can serve several phones, such as a shared rig or a devtest per phone in parallel.
Testlab keeps each test's forced states to its own phone where Proxyman can tell the phones apart:

- **Tell apart by address.** A phone that uses the proxy over the network (its proxy setting is the
  rig's address, as on a shared rig) arrives with its own address. Error statuses, delays and
  rewritten responses that Claude forces (`lab_proxy_force`) then apply only to that phone: the script checks the sender's
  address and lets the other phones' traffic through unchanged. The report's network log shows only
  that phone's requests.
- **Not apart.** A phone connected with `adb reverse` (a USB phone on this computer's own Proxyman)
  and iOS Simulators all arrive as 127.0.0.1, so a rule applies to all of them. Claude says so when
  it makes the rule. Route the phone over Wi-Fi for a rule of its own.
- **Always for everyone.** Blocked requests, and Map Local rules (made by hand, or the fallback when
  Proxyman refuses a script), can't be kept to one phone. Claude says so, and the report names them.

Scripting rules work through Proxyman's MCP on Linux and Windows too, from Proxyman 3.16.1 on.

### WebSockets

Claude reads the app's socket traffic (`lab_ws_messages`: GraphQL subscriptions decoded) and can
force socket states (`lab_proxy_force` `ws_rewrite`, `ws_drop`, `ws_delay`, `ws_inject`,
`ws_close`), kept to one phone like the HTTP rules. Needs Proxyman 26.x. Proxyman doesn't pass a
socket's subprotocol on, so GraphQL subscriptions that need one (AWS AppSync, Apollo) fail while
their host is decrypted. Claude recognises that and says it is the proxy, not the app.

## 5. Approvals

Writes that leave your computer or change a device wait for your yes: Jira comments and labels,
uninstalling an app, starting a CI build. When one waits:

- you get a desktop notification;
- `testlab open approvals` shows exactly what will be sent, with Approve and Decline;
- or in the terminal: `testlab approvals`, then `testlab approve ID` or `testlab deny ID`;
- when Claude Code started Testlab itself, it asks in its own dialog.

Nobody answering within two minutes counts as no.

## 6. iOS

On a Mac with Xcode, `testlab setup` does the iOS steps when the project covers iOS
(`testlab setup ios` runs only those).

- **Simulators**: AXe reads and taps the screen; Appium's WebDriverAgent is built once. No
  iPhone simulator yet? Setup offers to make one (`testlab simulator create`, or ask Claude); with
  no iOS runtime installed it downloads that first (several GB).
- **Crashes and performance in the field** (Xcode 27): Claude reads the App Store's and
  TestFlight's crash reports and its hang and launch-time reports through Xcode's own MCP server,
  as the Apple account signed in to Xcode. Setup offers to turn on its headless mode, so it works
  without an Xcode window (`testlab xcode enable`, asks for your Mac password). The first time
  Claude uses it, Xcode waits for your approval: run `testlab xcode approve`. Testlab's Java isn't
  signed with a developer team, so Xcode grants it for 24 hours at a time; approve again the next
  day. `testlab xcode status` shows where it stands.
- **Real iPhones and iPads** (iOS 17+): connect one by cable, unlock it and trust the Mac. They
  take device builds and app starts at once. To read and tap the screen too:
  1. sign in to Xcode with your Apple ID (Xcode → Settings → Accounts);
  2. on the phone, turn on Developer Mode and Settings → Developer → Enable UI Automation;
  3. give Testlab your Apple team id on the setup page, or `testlab setup --team ABCDE12345`.

  The first connection builds WebDriverAgent and installs it on the phone, which takes a few
  minutes. Keep the phone unlocked and on the cable while it is tested.

## 7. The pages

`testlab open PAGE` (the link works once, within a minute; `--print` prints it for another
computer). Claude opens them too: ask it to "open the report" and it shows the page in your default
browser (the one set for web links, also on a Mac where another app owns `.html` files):

| Page | What for |
|---|---|
| `setup` | Keys, Jira, autopilot, test accounts, Apple team |
| `devices` | What is connected, Wi-Fi pairing, the proxy per device |
| `screen` | The phone live; tap and type on it |
| `reports`, `report` | Saved reports, with screenshots, recording and network log |
| `profiles` | Make a profile from a repo, edit, import, export |
| `handback` | A plan that needs a person: tap what the autopilot couldn't pick |
| `approvals` | Writes waiting for your yes |
| `settings` | Everything else |
| `guide` | This guide |

## 8. When something is wrong

1. `testlab status`: what is missing, and the command that fixes it.
2. The phone isn't found: `testlab devices` says why (unauthorized, offline) and what to do.
3. Requests fail on the phone, or the app says it can't reach the server: ask Claude for
   `lab_health`. It checks from the phone itself (its network, its proxy, a request through the
   proxy, Proxyman's rules) and says what to fix. Meanwhile the devices page can turn the phone's
   proxy off.
4. Claude says the server is unreachable: `testlab service status`, then `testlab service start`.
5. Still stuck: `testlab diag` writes a zip with versions, devices, settings and the log (no
   secrets, screenshots or recordings). Send it along with what you did.

More detail: [INSTALL.md](INSTALL.md) (installing and troubleshooting), [PROFILES.md](PROFILES.md)
(project profiles), [SERVICE.md](SERVICE.md) (the background service).
