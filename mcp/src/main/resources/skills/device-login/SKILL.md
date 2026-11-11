---
name: device-login
description: Log a test user into the app on the attached device with the active profile's login plan (credentials from the vault by reference). Load this when a task needs a logged-in user.
---

# Device login

Logs a test user into the app. Run **device-prep** first. A project profile can replace this
playbook with one that knows its login screens (a `device-login` skill in the profile).

> Pick the test account by what the test needs: `lab_list_credentials` gives each account's
> `note` (what it is for), `login_on` (the platforms it has every login field for) and
> `in_use_on` (a device already logged in with it). Ask the user when no note fits. Logins that
> need a real person (a production identity, a code sent to a phone) can't be automated: hand
> those steps to the user.
>
> Without `account`, `handsets_login` takes one complete for the device's platform that no other
> device is logged in with, so two devices testing side by side get different accounts. The
> result says which (`account`), and warns when a named account is in use on another device.

## Fast path: `handsets_login`

```
handsets_login(account="<account>")          # env="<env>" when the profile lists environments
```

It runs the profile's login plan (`accounts.loginPlan` in its `plans/`), filled in with the
account, environment and package. It is usually written for a **fresh install**; on another
state it hands back at the first step that doesn't fit: continue with the remaining steps
yourself or with `handsets_jev_plan`. Without a login plan the tool says so: log in by hand.

Credentials come from the vault by reference: `<account>.<field>` for the fields the profile
names (Settings → Test accounts; `lab_list_credentials` shows which are set). Type them with
`handsets_type_secret(ref)` or `handsets_fill(selector, cred=ref)`, never as plain text.

## The manual walk

1. Open the app (`handsets_open_app`), find the login entry with `handsets_ui_dump`.
2. For each field: focus it, `handsets_type_secret("<account>.<field>")`, `handsets_hide_keyboard`,
   then tap the screen's Next/Continue. In a web view (Custom Tab), don't press BACK: it navigates.
3. Handle first-run prompts (PIN, biometrics, notifications) the way the user wants.

## Gotchas
- Several builds of the app installed can all claim the login's return link, so a chooser may send
  the login to the wrong build. With `accounts.isolateDuringLogin` the tool disables the other
  builds of `app.family` during the login and enables them again afterwards.
- FLAG_SECURE screens (PIN pads, some login pages) give an empty dump and no screenshot, but taps
  and typing still work.

## Verify
A screen that only a logged-in user sees (profile, account); `handsets_screenshot` to show it.
