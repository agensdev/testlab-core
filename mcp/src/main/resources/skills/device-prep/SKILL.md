---
name: device-prep
description: Get the attached Android device ready before any other task — connect, unlock with the stored PIN, make sure the app is installed, launch it and clear first-run screens. Load this first when a task needs the app open and ready.
---

# Device prep (get the phone ready)

The foundation most other skills build on.

## Steps
1. **Connect**: `handsets_list_adb_devices` lists every device adb reaches: USB, Wi-Fi (wireless
   debugging), IP or emulator. A phone that should be on the network but isn't listed: ask the user
   for the address on its Developer options → Wireless debugging screen and call
   `handsets_connect_device(address)` (the first time with `pairing_code` and `pairing_address`
   from «Pair device with pairing code»); `nearby` in the list may already show it. Then
   `handsets_use_device` (pass `serial=` when more than one device is attached). If it names another automation tool holding the phone, ask the
   user to close it or call `handsets_use_device(take_over=true)`.
   Then `lab_session_start` to **record the session** (one per device for a two-device task): the
   recording is attached to the report automatically; `lab_session_discard` deletes it when the
   user wants no report. It also snapshots the phone's settings (`lab_device_state`) and runs
   `lab_health`: act on what it flags (lock screen, proxy, leftover settings) before testing.
2. **Unlock if locked**: `handsets_unlock_device` (wakes the screen and enters the stored device PIN;
   does nothing when the phone is not locked). A **real iPhone/iPad** can't be unlocked from
   here: when its screenshot is black, ask the user to unlock it and to set Settings → Display &
   Brightness → **Auto-Lock to Never** on the test device, so it stays awake for the test.
3. **Check the app**: `handsets_installed_builds` shows what the app installed;
   `handsets_list_apps(third_party_only=true)` everything (`lab_status` shows the profile's default package). Missing, or a devtest needs a PR's build: see **install-build**
   (`handsets_install_branch_build(PR number)`, `handsets_install_build("latest")`).
4. **Launch**: `handsets_open_app("<package>")`, then `handsets_wait_for` a front-page text or
   `handsets_current_focus`.
5. **First-run screens**: dismiss onboarding or consent screens the way the user wants (a profile's
   own device-prep skill can name them). Dialogs the profile lists under `dismiss` (e.g. a
   test-build service asking to sign in) are dismissed by `handsets_open_app` with their named
   button; the result says so. **Never sign in** to such a service on a test device. For a clean
   start, `handsets_home()` first stops the app and the profile's other builds. **Chrome** on a phone where it was never opened shows its
   own first-run screens (welcome, the EU search-engine choice, sign-in) when a link opens in the
   browser; `handsets_open_url` says so. Go through them once by hand or with `handsets_autopilot_do`
   before a test that leaves the app for the browser.
6. **Show it**: `handsets_screenshot` so the user sees the ready state.

## Notes
- An error ending in "call handsets_use_device(serial) first" means the daemon stopped (phone
  replugged, app restarted): call it again and retry.
- This skill does not log a user in: see **device-login**. To read the screen and click around,
  see **drive-device**.
