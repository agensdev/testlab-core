---
name: rig-cleanup
description: The cleanup checklist to run before writing the report — proxy rules, device settings, recordings, test data, temporary releases — so the next session starts from a known state. Load it at the end of every test round, and whenever you changed a device or proxy setting.
---

# Clean up the rig

Run this before `lab_report_write`, and note in the report what state the rig was left in.
`lab_session_start` snapshots the phone's settings and starts a proxy-rule session;
`lab_report_write` then removes that session's rules (and checks they are gone) and lists in a
«Rig» section the proxy changes made and the settings that still differ. Fix those before the
report, not after it.

- [ ] `lab_device_state(action=diff)`, then `restore`: font size, dark mode, TalkBack,
      Don't keep activities, navigation mode, rotation, stay-awake, animations, show taps, demo
      mode, analytics debug properties and disabled packages back as at the session's start.
- [ ] Proxy rules you made gone: `lab_proxy_cleanup` deletes them, lists again and asks the mocked
      URLs through the proxy (a rule can survive its delete). Rules others made stay; on your own
      Proxyman, `lab_reset_proxyman` clears everything.
- [ ] Changes to the proxy's SSL include/exclude lists reverted.
- [ ] Analytics debug properties reset.
- [ ] "Don't keep activities" off.
- [ ] Text size back (`handsets_font_scale(action=reset)`).
- [ ] Dark mode, TalkBack (`handsets_talkback(action=on|off)` as found), navigation mode as found.
- [ ] Rotation back to portrait.
- [ ] Stay-awake off (`svc power stayon false` if you turned it on).
- [ ] Demo mode / status-bar overrides off (`xcrun simctl status_bar <udid> clear`).
- [ ] The Mac's system proxy off if a simulator was routed through it.
- [ ] Log captures and recordings stopped (`lab_logs_stop`, `lab_session_status`;
      `lab_session_discard` for one no report will use).
- [ ] Temporary files deleted; temporary releases and tags you created deleted.
- [ ] Sibling app builds you disabled enabled again.
- [ ] Test data you created on the server deleted, where the app allows it.
- [ ] Any limits or blocks you set on a test user reset.
