## Get started

Testlab lets Claude test your Android and iOS app on real phones, emulators and iOS Simulators,
from your own computer. It runs on **macOS (Apple silicon)**, **Linux (x64)** and **Windows (x64)**, with Java bundled.

**1. Download two files** from the assets below into your Downloads folder:

- `install.sh`
- `testlab-cli-…-macos-arm64.tar.gz` on a Mac, or `testlab-cli-…-linux-x64.tar.gz` on Linux
  (and `SHA256SUMS` if you want the download checked)

**2. Install and set up**, in a terminal:

```bash
bash ~/Downloads/install.sh --setup
```

It installs to `~/.local/opt/testlab`, puts `testlab` on your PATH, clears macOS's download
quarantine, checks `SHA256SUMS` when it's there, and starts `testlab setup`. Setup walks through
what is left, opening a page in your browser for the keys:

| You need | For |
|---|---|
| A GitHub token, classic, «repo» scope ([make one](https://github.com/settings/tokens/new?scopes=repo&description=Testlab)) | Your app's repo, its PRs and CI builds, and Testlab's own upgrades from this repo |
| Your app's repo, or your team's profile (a `.zip`) | Setup lists your repos, apps first, and makes the profile |
| An autopilot: a TypeSafe key, a local Ollama model, or an [OpenAI key](https://platform.openai.com/api-keys) | Picking what to tap toward a goal |
| A phone with USB debugging, or a Mac with Xcode for iOS | The device to test on |
| [Claude Code](https://claude.com/claude-code) (or Claude Desktop) | Asking for tests in plain words |
| Jira e-mail and API token (optional) | Devtests of tickets |

**3. Ask Claude**, in Claude Code: «Show me the phone», «Install the build of PR 123 and check that
the app starts», «Devtest PROJ-123». Setup ends with prompts that use your own project, and
`testlab open guide` explains the rest: [GUIDE.md](https://github.com/agensdev/testlab-core/blob/main/docs/GUIDE.md),
[INSTALL.md](https://github.com/agensdev/testlab-core/blob/main/docs/INSTALL.md).

**Already installed?** `testlab upgrade` (it restarts the background service on the new version;
`testlab upgrade --rollback` goes back). **Windows** (Android only): unzip `testlab-cli-…-windows-x64.zip`
and follow [INSTALL.md → Windows](https://github.com/agensdev/testlab-core/blob/main/docs/INSTALL.md#windows). Something wrong?
`testlab diag` writes a diagnostics zip to send along.

---
