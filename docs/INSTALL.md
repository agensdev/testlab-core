# Installing Testlab

Testlab runs on your own computer and drives your own phones. Nothing runs on a server: your
tokens stay in an encrypted vault on this machine, and Claude talks to Testlab over `127.0.0.1`.
It is a command, `testlab`, with a background service and a few local web pages; there is no
desktop app. Plan on 15 minutes the first time. [GUIDE.md](GUIDE.md) walks through what comes after.

You need:

- macOS 13+ on Apple silicon, a 64-bit Linux, or 64-bit Windows (see [Windows](#windows) below:
  no `install.sh` and no `testlab upgrade` there yet)
- an Android phone or emulator, and a USB data cable or (Android 11+) the same Wi-Fi network;
  for iOS, a Mac with Xcode
- a project profile from your team (a `.zip`), or the details to make one: the app's GitHub repo,
  its Jira site and project
- Claude Code (or Claude Desktop, or Codex) for the AI part. Everything else works without it

## 1. Install the command

Download `testlab-cli-…` for your system from the newest release at
<https://github.com/agensdev/testlab-core/releases>. Java is bundled. You can check a download
against `SHA256SUMS` with `shasum -a 256 FILE`.

The quick way: download `install.sh` from the same release, next to the package, and run

```bash
bash ~/Downloads/install.sh          # add --setup to go straight on to step 2
```

It finds the newest package for this machine in its own folder, the current folder or `~/Downloads`
(or takes a path), checks it against `SHA256SUMS` when that sits beside it, unpacks it to
`~/.local/opt/testlab`, links `~/.local/bin/testlab` and adds `~/.local/bin` to your shell's `PATH`
(`--no-modify-path` leaves the profile alone). Run on an existing install, it does
`testlab upgrade --from <package>` instead. By hand, the same steps are:

```bash
mkdir -p ~/.local/opt && tar xzf testlab-cli-*.tar.gz -C ~/.local/opt   # → ~/.local/opt/testlab
xattr -dr com.apple.quarantine ~/.local/opt/testlab                      # macOS only, once per version
mkdir -p ~/.local/bin && ln -sf ~/.local/opt/testlab/bin/testlab ~/.local/bin/testlab
testlab version
```

`~/.local/bin` must be on your `PATH` (add `export PATH="$HOME/.local/bin:$PATH"` to your shell's
profile if `testlab version` isn't found).

The vault key goes into the OS keychain: the macOS Keychain (choose **Always Allow** when macOS asks
whether Testlab may use its item), or the Secret Service on Linux (GNOME Keyring, KWallet). Where
there is none, Testlab asks for a passphrase; for a service, set `TESTLAB_VAULT_PASSPHRASE`.

### adb

Testlab uses the Android Debug Bridge (adb) you already have, which Android Studio installs. If it
finds none, `testlab setup` offers Google's copy (after you accept Google's terms), or run
`testlab tools --download-adb --accept-android-sdk-terms`. `testlab tools` shows which adb is in use.

## 2. Set it up

```bash
testlab setup
```

It walks through what isn't done yet, checking each step, and skips what is: run it again
whenever you like. On a desktop it first offers the **setup page** in your browser for the GitHub
token, Jira, the autopilot key, test accounts and the Apple team (so nothing is pasted into the
terminal); `--no-web` keeps it all in the terminal. Then it does the devices, Claude and the
background service.

| What | Where to get it | Needed for |
|---|---|---|
| GitHub token | <https://github.com/settings/tokens/new?scopes=repo>: a **classic** token with `repo`. If your organisation uses SSO, also **Configure SSO → Authorize** it | PR builds, devtests |
| Jira e-mail + API token | <https://id.atlassian.com/manage-profile/security/api-tokens> → **Create API token** (not «with scopes»), with your work e-mail | Devtests |
| TypeSafe Jev key | from the Jev account owner | The autopilot (or choose a local Ollama model, or OpenAI's Decisions API, instead) |
| OpenAI API key (optional) | <https://platform.openai.com/api-keys> («Create new secret key», copy it at once: it is shown once; the API needs credit on the account, billed apart from ChatGPT): `testlab secrets set openai.key`, or `OPENAI_API_KEY` | The autopilot on OpenAI's [Decisions API](https://developers.openai.com/api/docs/guides/decisions) (`jev.backend = openai`, model `jev.openaiModel`, default `gpt-6-luna`) |
| Test accounts | the test users your team uses, with the fields the profile names | Logging in, plans that log in |
| Device PIN | your phone's lock PIN: `testlab secrets set device_pin` | Unlocking the phone |

Values go into the vault and never leave it: tools use them by name, and results, logs and reports
are scrubbed of them. From a `.env` file: `testlab secrets import-env FILE`.

The **project** is a profile. Import your team's on the setup page or with
`testlab profile import team.zip`; otherwise setup makes one from a repo you pick
(`testlab open profiles` to edit it).

## 3. Connect a phone

1. On the phone: **Settings → About phone → tap «Build number» seven times**, then
   **Settings → System → Developer options → USB debugging** on.
2. Plug it in with a data cable. Unlock it and accept **Allow USB debugging?**, ticking
   «Always allow from this computer».
3. `testlab devices` lists it (or `testlab open devices`).

Per OS:

- **macOS** needs nothing. If the phone stays invisible, allow the accessory under System Settings → Privacy & Security and replug.
- **Linux** needs udev rules:
  - Install them with `sudo apt install android-sdk-platform-tools-common` (Debian/Ubuntu) or the `android-udev-rules` package (Fedora/Arch).
  - Add yourself to `plugdev`, log out and in, then replug.
  - «no permissions» in `testlab devices` means this step is missing.
- **Xiaomi, OPPO and vivo** also need «Install via USB» and «USB debugging (Security settings)» in Developer options, or builds won't install.

**Without a cable** (Android 11+): on the phone, Developer options → **Wireless debugging** →
**Pair device with pairing code**; then `testlab setup` (answer «wifi») or the devices page pairs
it. It is paired once; later connects need only the address. Guest and company Wi-Fi often isolate
devices from each other.

**Emulators** from Android Studio show up by themselves.

### iOS (macOS with Xcode)

When the project covers iOS, `testlab setup` sets it up in the same run as Android: Xcode, AXe,
Node.js, Appium, WebDriverAgent and real iPhones. `testlab setup ios` runs only those steps.

**Real iPhones and iPads** (iOS 17 or newer) show up once they are paired: connect one by cable,
unlock it and trust this Mac. They take device builds (`.ipa`) and app starts right away. To read
and tap the screen too, as on a simulator:

1. Sign in to Xcode with your Apple ID (Xcode → Settings → Accounts). A free personal team works,
   but what it signs lasts only 7 days.
2. On the phone, turn on Settings → Privacy & Security → **Developer Mode** (the phone restarts),
   then Settings → Developer → **Enable UI Automation**.
3. Give Testlab your Apple team id (the 10 characters under Membership on developer.apple.com, or
   in Xcode → Settings → Accounts): on the setup page, or `testlab setup --team <TEAM ID>`.
   The first connection builds WebDriverAgent and installs it on the phone, which takes a few minutes.

Keep the phone unlocked and on the cable while it is tested (Auto-Lock → Never helps). Recording
a real iPhone isn't supported yet; screenshots are.

## 4. Connect Claude

`testlab setup` installs the Claude Code plugin and adds Testlab to Claude Desktop. By hand,
`testlab mcp-config` prints the commands for this computer, with your token filled in:

- **Claude Code:** the `claude mcp add --scope user --transport http testlab http://127.0.0.1:8378/mcp --header "Authorization: Bearer …"` line, once; check it with `claude mcp get testlab`.
- **Claude Desktop:** the JSON for `claude_desktop_config.json`. It starts Testlab's stdio bridge, which forwards to the running server, or serves the tools itself.
- **Codex:** the TOML for `~/.codex/config.toml`.

Then ask, for example, "install the build of PR 1234 and open search". The playbooks are prompts:

| Prompt | Does |
|---|---|
| `/mcp__testlab__drive-device` | The loop for driving the phone |
| `/mcp__testlab__write-plan` | Writes, checks and saves an autopilot plan, then runs it |
| `/mcp__testlab__device-login` | Logs in with a stored test account |
| `/mcp__testlab__devtest PROJ-123` | A whole devtest |

If your team has its own devtest command (the profile names it), it works in the app's checkout,
with Testlab running the steps on the phone.

The **background service** keeps the server running from login, so Claude reaches the phones at
any time: `testlab service install` (setup offers it). The server listens on `127.0.0.1` only, and
every request needs the token. Uninstalls, CI builds and every Jira write wait for your yes: a
desktop notification, then `testlab open approvals`, `testlab approve ID`, or Claude's own dialog.
A shared phone rig for a team works the same way; see [SERVICE.md](SERVICE.md).

## 5. Proxyman (optional)

To see and mock the phone's HTTP(S) traffic with Proxyman:

1. Install [Proxyman](https://proxyman.com) (macOS, Windows or Linux) and start it.
2. `testlab open settings` → **Proxyman**: turn it on, then **Check Proxyman**. It should say
   «the proxy answers · its MCP server: connected». The default port is 9090; change it if
   Proxyman uses another. If the mcp-server isn't found, paste its path.
3. Route the phone through it: the devices page (**Through Proxyman**), or ask Claude
   (`handsets_proxy on`).
   - USB phones and emulators go through `adb reverse` to 127.0.0.1.
   - Wi-Fi phones use this computer's address on their network. Proxyman must accept connections
     from the network, and the firewall must allow its port.
4. For HTTPS, open `http://proxy.man/ssl` on the phone while it is routed, download the
   certificate, and install it under Settings → Security → Encryption & credentials →
   Install a certificate → CA certificate.
   - Builds that trust user-installed CAs (debug-overrides in `network_security_config.xml`,
     usual for debug builds) show their HTTPS. A profile can say which of its builds do
     (`texts["proxyman.cert"]`).
   - Release builds usually don't, so their HTTPS stays opaque.

While a phone is routed, quitting Proxyman takes its network away. Disconnecting the device or
stopping Testlab restores the direct connection; after a crash it is restored at the next start.
Claude reaches Proxyman's tools through Testlab as `proxyman_*`, so it needs no separate Proxyman
MCP setup.

## Windows

There is a package for 64-bit Windows, `testlab-cli-<version>-windows-x64.zip`, with Java bundled.
It is built and self-tested for every release, but it is the least used of the three: expect
rough edges, and tell us about them.

1. Unzip it where you want it, for example `%LOCALAPPDATA%\Programs\testlab`.
2. Add its `bin` folder to your `PATH` (or run `bin\testlab.bat` with its full path).
3. `testlab setup`, as on the other systems.

What is different: Android only (iOS needs a Mac); no `install.sh`; `testlab upgrade` and
automatic updates don't work yet, so to update, stop the service (`testlab service stop`), unzip
the new package over the old one and start it again. The background service is a scheduled task
at logon ([SERVICE.md](SERVICE.md)).

## Updating

`testlab status` says when a newer release exists: the running server looks once a day and also
tells you with a desktop notification. Settings, the vault, profiles, plans and reports are kept in
every update.

- `testlab upgrade`: the newest release. `testlab upgrade --ci`: the newest package build (Actions →
  package). `testlab upgrade --from ~/Downloads/dist-macos`: a downloaded artifact folder, its zip,
  or the CLI's tar.gz. The background service is stopped around the swap and started again, and the
  Claude Code plugin is updated with it.
- Back to the previous version: `testlab upgrade --rollback`.
- Automatic: turn on **Install updates automatically** on the settings page (`testlab open
  settings`; `autoUpdate` in settings.json). The background service then installs a new release
  itself once nothing has run on it for ten minutes, and restarts. A version you rolled back from
  is not installed again. Not on Windows, and not for a server started by hand (`testlab serve`).

Reading releases needs no GitHub token when the Testlab repo is public; for a private copy of it,
the token must be able to read that repo. The paths stay the same, so the
background service, Claude Desktop's config and the `testlab` link in your PATH keep working. Start
a new chat in Claude Desktop to get the new tools.

## Where things are

`testlab dirs` prints them.

| | macOS | Linux |
|---|---|---|
| Settings, vault, traces, logs | `~/Library/Application Support/Testlab` | `~/.local/share/testlab` |
| Builds, daemon, downloaded adb | `~/Library/Caches/Testlab` | `~/.cache/testlab` |
| Plans and reports | `~/Documents/Testlab` | `~/testlab` |

`testlab config set workspace PATH` moves plans and reports elsewhere. To uninstall:
`testlab service uninstall`, delete `~/.local/opt/testlab` and these folders. The vault key also
lives in the OS keychain under «Testlab».

## Troubleshooting

Start with `testlab status`: it checks the version, the profile, the GitHub and Jira tokens, the
server and the background service, Claude Code and Claude Desktop's entry, Proxyman, adb and the
phones, and says what to do about each gap. Settings can be changed there too (`testlab config
list`, `config set KEY VALUE`, or `testlab open settings`); a running service or Claude Desktop
picks the change up within a couple of seconds.

| Symptom | Fix |
|---|---|
| Phone shows «unauthorized» | Unlock it and accept the USB debugging prompt. No prompt: Developer options → Revoke USB debugging authorisations, then replug |
| «offline» | Replug, or `adb kill-server`. Only one adb should run: Android Studio's and Testlab's must be the same (`testlab tools` shows which one is used) |
| «Another automation tool holds the phone» | Maestro, Appium, `uiautomator dump` or `hs` is connected. Close it, or `testlab connect --take-over` |
| Screenshots black on PIN and login screens | Those screens block capture (FLAG_SECURE). Taps and typing still work |
| Install fails «signed with another key» | The phone has the same app from another signer. Uninstalling it (Claude asks first) deletes its login and data |
| Install fails on Xiaomi/OPPO/vivo | Turn on «Install via USB» in Developer options |
| GitHub: «not authorized for SSO» | Open the link Testlab shows and authorize the token for your organisation |
| Jira: «wrong token type» | Create a plain API token (not «with scopes») with your work e-mail |
| «PKIX path building failed» / certificate errors | Your network inspects TLS with a company root that is not in the OS store. Ask IT for the root certificate (PEM) and put it in the `trust` folder inside the data folder (`testlab dirs` shows where), or set `TESTLAB_CA_FILE`. Then restart the service |
| Test environments or test logins don't load | Corporate network or VPN rules. Try another network, or ask IT to allow the test hosts |
| macOS asks for the keychain on every start | Choose **Always Allow** (not «Allow») |
| The phone has no network while routed through Proxyman | Proxyman isn't running or is hung. Start or restart it, or turn the phone's proxy off (devices page, or `handsets_proxy off`). **Check Proxyman** on the settings page tells which it is |
| A write never happens | It waits for your yes: `testlab open approvals` (no answer within two minutes is a no) |
| Claude Code says the server is unreachable | `testlab service status`, then `testlab service start`; `claude mcp get testlab` shows what Claude sees |

Still stuck? `testlab diag` writes a zip with versions, tools, devices, settings, the log and recent
traces. Secrets and e-mail addresses are removed, and screenshots and recordings are never
included. Send it along with what you did.
