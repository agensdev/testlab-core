---
name: install-build
description: Put the right build of the app on the phone — the CI build of a PR or branch, another build type, or a release — check it is from the newest commit, and deal with a signature clash. Load this before a devtest of a PR, or whenever a task needs a specific build installed.
---

# Install a build

Builds come from the profile's GitHub repo (its Actions artifacts and Releases), through the
user's own GitHub token (Settings → GitHub). The profile's `builds` say which workflow makes
which APK artifact and which package it installs as; `lab_status` lists the build types and the
default one (what a PR number means). Different packages live side by side on the phone.

## A PR's build (the devtest case)

1. `handsets_list_branch_builds("1234")` (PR number, PR link or branch name). Check:
   - `latest_commit: true`: the build is from the PR's newest commit. If false and `building` is
     set, a newer build is running: wait for it and look again.
   - `artifacts` has the build type's APK with `expired: false`.
   - `behind_default_branch`: a branch far behind the default branch shows old behaviour that
     isn't the PR's; ask its author for a rebase rather than reporting the default branch's bugs.
2. `handsets_install_branch_build("1234")`: downloads once (then cached) and runs
   `adb install -r -d`, so the app's data and login survive. `type=` picks another build type.
3. Use the result's `branch`, `head_sha` and `versionName` for the build line of the report.
   `handsets_installed_builds` tells the same later: the phone itself can't tell builds of one
   package apart.

No build? The error says why: a draft PR, a branch prefix the workflow skips, or a build older
than GitHub's 30 days. `handsets_start_branch_build` starts one by hand (workflow_dispatch): it
asks the user first, because it takes a CI runner for minutes. Never mark a draft PR ready to get
a build: that is its author's call.

## The control build

`handsets_control_build(pr)`: the PR's merge-base, whether CI built that commit (`install=true`
installs it), and what the newest release changed in the PR's files since then
(`lab_read_skill('ab-control')`).

## A local build

`handsets_install_apk(path)` installs an APK from this computer (a working-tree build).

## Older over newer

Installing an older build over a newer one's data can hang the app at start. Every install checks
the version codes first and, after a yes, uninstalls the newer copy (its data and login go with it:
log in again). The result's `downgrade` says when that happened.

## Releases

`handsets_list_builds` lists the releases with an APK; `handsets_install_build("latest")` or a tag.

## When the install fails

The result carries `problem` and `advice`:

- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: the installed copy is signed with another key (a local
  Android Studio build). `handsets_uninstall_app(package)` deletes it **and its data, login
  included**: tell the user before; the uninstall asks for approval in the app window. Then
  install again.
- `INSTALL_FAILED_USER_RESTRICTED` / `INSTALL_FAILED_ABORTED`: the phone blocks or asks about USB
  installs (Xiaomi, OPPO, OnePlus, Vivo): the advice names the setting. Ask the user to change it
  or confirm on the phone.
- Out of storage, wrong CPU, too old Android: the advice says which.
