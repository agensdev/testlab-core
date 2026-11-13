# Development

Testlab grew out of a private Android test lab whose tools were Python scripts (an MCP server,
`autopilot.py`, report and recording helpers). Comments that mention «the lab» refer to those
scripts: the Kotlin code was ported from them, and the parity tests (`PythonPortTest`,
`PythonGoldenTest` with `tools/golden/autopilot.py`) keep the decision engine identical.

You need JDK 21 (Temurin), Python 3, `ffmpeg`, and for the end-to-end runs the Android SDK
(platform-tools, emulator, one x86_64 Google APIs image) and KVM. `scripts/dev/setup-android-sdk.sh`
installs the SDK parts on Debian/Ubuntu.

```bash
./gradlew check                      # every unit and integration test (no device, no network)
./gradlew :cli:installDist           # cli/build/install/testlab/bin/testlab
cli/build/install/testlab/bin/testlab open setup   # the local web pages (mcp/src/main/resources/ui/*.html)
bash scripts/scrub-audit.sh          # no organisation-specific values in the core (CI runs it)
```

The scrub audit checks two word lists: a generic one in the script (private network addresses,
tailnet host names) and a private one that is never committed, with the names of the organisations,
apps and machines a maintainer works with. Put yours in `.scrub-private` in the repo root (one
`grep -E` expression; git ignores the file) or in `$SCRUB_EXTRA_PATTERN`. CI reads the repository
secret `SCRUB_EXTRA_PATTERN`; a fork without it checks the generic list only.

## Binaries in the repo

A few built files are committed so that a build needs neither the Android SDK nor a network:

| File | What it is | How to rebuild |
|---|---|---|
| `device/src/main/resources/handsets/hs.jar` | The on-device daemon of [handsets](https://github.com/elliotgao2/handsets) (MIT, licence beside it), unchanged; `VERSION` names the release | Download that release's `hs.jar` and update `VERSION` |
| `device/src/main/resources/touch/tl-touch.dex` | Testlab's own touch injector, from `tools/touch/Touch.java` | `tools/touch/build.sh` (Android SDK: a platform and build-tools) |
| `tools/testapp/fixture-*.apk` | Resource-only apps for the install tests, signed with throwaway keys | `tools/testapp/build-fixture.sh` |
| `gradle/wrapper/gradle-wrapper.jar` | The Gradle wrapper | `./gradlew wrapper` |

`testlab.id` in `gradle.properties` still carries the name of the repo the project started in. It
names the vault's keychain entry, the macOS service label and the WebDriverAgent bundle, so
changing it would lock existing installs out of their saved keys.

Organisation-specific values (repos, Jira projects, labels, deeplinks, login plans, report
formats) belong in a **profile** (see [PROFILES.md](PROFILES.md)), never in the core. The tests use
the made-up *Example Shop* profile (`services/src/testFixtures/…/profile/ExampleProfile.kt`, and
`scripts/dev/example-profile/` for the end-to-end runs).

Keep memory in mind on small machines: stop the Gradle daemons (`./gradlew --stop`) before starting
the emulator, and run one emulator at a time.

## Emulator and end-to-end runs

`scripts/dev/emu.sh` manages one headless emulator (AVD `tl36`, Android 16 x86_64). Don't run Gradle while it is up on a small machine: the emulator's watchdog kills it when a build starves its vCPUs.

```bash
scripts/dev/emu.sh up|down|status   # stops Gradle first, refuses below 3 GB free
scripts/dev/e2e.sh [--keep]         # every plan step type through the CLI, stub backend
scripts/dev/mcp-e2e.sh [--keep]     # the MCP server end to end: `serve` plus a stdlib-Python client
                                    # driving Settings through the tools, a plan run, a secret-leak check
scripts/dev/builds-e2e.sh [--keep]  # PR builds against scripts/dev/fake-github.py: PR → build → token-free
                                    # download → install, release update, signature clash, MCP uninstall
scripts/dev/report-e2e.sh [--keep]  # recording + report over MCP: duration vs wall clock, decode, chapters,
                                    # offline page, standalone copy, recovery after kill -9
scripts/dev/devtest-e2e.sh [--keep] # a devtest against fake-jira.py + fake-github.py: queue, PR candidates,
                                    # take (refused, then forced), PR build, recorded report linked to the
                                    # ticket, comment preview, then ADF comment + attachments + approved label
scripts/dev/proxyman-e2e.sh [--keep] # Proxyman routing over MCP against fake-proxy.py + the fake mcp-server:
                                    # proxy + adb reverse set, the phone's traffic arrives, proxyman_* mirrored,
                                    # flows.har in the report, off restores, kill -9 → next start restores
scripts/dev/rig-e2e.sh [--keep]     # the rig tools on the emulator: lab_health from the device, the settings
                                    # snapshot (diff, restore), log capture + analytics events, plan items a
                                    # report must account for
scripts/dev/setup-e2e.sh            # «Set up a project» against fake-github.py (no device): workflows →
                                    # build types, the APK's package and deeplink host, a working profile
scripts/dev/service-e2e.sh          # (root + systemd) the CLI download as a --system service for a throwaway
                                    # user: LoadCredential passphrase, --listen/--allow-host, approvals, stop/start
scripts/smoke-cli.sh                # the packaged CLI unpacked: --selftest, serve answers, clean SIGTERM
scripts/dev/capture-goldens.sh      # new renderer goldens from the emulator
```

The e2e scripts build first, stop Gradle, then boot the emulator, and shut it down afterwards unless `--keep`. They use throwaway data folders, so they never touch a real vault.

## Poking the MCP server

`scripts/dev/mcp-call.py` is a stdlib MCP client for a running `testlab serve`: pairs of tool name and JSON arguments share one session.

```bash
export TESTLAB_TOKEN=$(testlab mcp-config | sed -n 's/.*Bearer \([^"]*\)".*/\1/p' | head -1)
scripts/dev/mcp-call.py handsets_use_device '{}' handsets_ui_dump '{"form":"tree"}' sleep 1 handsets_screenshot '{}'
```

Stop a background server by PID (`pgrep -f 'cli[.]MainKt serve'`): `pkill -f` matches its own command line.

## The web pages

The pages `testlab open PAGE` serves are plain HTML with inline script in
`mcp/src/main/resources/ui/` (setup, devices, screen, reports, report, profiles, handback,
approvals, settings); `UiPages.kt` serves them and their `/api/v1` routes, and `/ui/guide` renders
`docs/GUIDE.md` (bundled at build time). A page signs in with a one-time ticket that becomes an
HttpOnly cookie, so the token never reaches the browser. No build step: edit the HTML, rebuild the
CLI, open the page. `node --check` on a page's script catches syntax errors without a browser.

## Fixture APKs

`tools/testapp/build-fixture.sh` builds three tiny resource-only APKs with the SDK's `aapt2`, `zipalign` and `apksigner`: `fixture-v1.apk`, `fixture-v2.apk` (an update, same key) and `fixture-otherkey.apk` (another key, for the signature-clash path). They are committed; the manifest reader's tests and the builds end-to-end run use them. `scripts/dev/fake-github.py` serves them as PR artifacts and a release, behind a token check, with storage that refuses the token like Azure's signed URLs.

## A fake for Proxyman

- `services/src/testFixtures/resources/proxyman/fake-mcp-server.py` stands in for Proxyman's stdio `mcp-server`: a few tools, its `xxx result:` prefix, calls logged to `$FAKE_PX_LOG`.

## Releases

The packages are built by `.github/workflows/build.yml`, a reusable workflow with one job per OS.
Each packages the CLI (`:cli:packageCli`, Java bundled) and checks it with `scripts/smoke-cli.sh`
(unpacked: `--version`, `--selftest`, `serve` answers with its token, clean SIGTERM):

| Job | Runner | Files |
|---|---|---|
| `test` | ubuntu | — (scrub audit, `./gradlew check`) |
| `linux` | ubuntu | `testlab-cli-<v>-linux-x64.tar.gz` |
| `macos` | macos (arm64) | `testlab-cli-<v>-macos-arm64.tar.gz` |
| `windows` | windows, only with `all-platforms: true` | `testlab-cli-<v>-windows-x64.zip` |

The OS jobs start once `test` passes (macOS minutes count ×10 and Windows ×2 on a private repo)
and run in parallel. File names follow `testlab.name` / `testlab.slug`, so a branded build names
its files after itself.

`release.yml` runs it on a pushed tag `vMAJOR.MINOR.PATCH`, checks the macOS and Linux packages
are there, and publishes them with `SHA256SUMS` on a GitHub Release. 0.x tags are pre-releases.
The release notes start with `docs/release-notes-header.md`. The version comes from the tag
(`-Ptestlab.version`); `gradle.properties` holds the default for local builds. `package.yml` runs
the same jobs by hand for any commit and keeps the packages as workflow artifacts for 14 days.

```bash
git tag v0.9.0 && git push origin v0.9.0
```

`testlab status` and `testlab upgrade` read the release list of `testlab.repo` (not
`releases/latest`, which skips pre-releases) and take the highest non-draft version, with the
user's GitHub token.

## Texts

Everything is in English. Texts for people go through `tr("…", args)` (`{0}`… placeholders,
`{app}` for the app's name, so a branded build names itself). Build plurals as two texts
(`1 screenshot` / `{0} screenshots`), never by appending "s". Texts for Claude (tool descriptions
and results) are plain strings.

## Branded builds

A team can ship its own build — name, ids, update repo, bundled profiles — from this source
without forking it:

```bash
./gradlew :cli:packageCli -Ptestlab.name="Acme Testlab" -Ptestlab.slug=acme-testlab \
  -Ptestlab.id=com.acme.testlab -Ptestlab.repo=acme/testlab-dist -Ptestlab.profilesDir=/path/to/profiles
```

`testlab.profilesDir` holds `<id>/profile.json` (+ `skills/`, `plans/`) per profile; they are bundled
into the build and the first one is active on a fresh install. Everything in `gradle.properties`
under `testlab.*` can be overridden this way.
