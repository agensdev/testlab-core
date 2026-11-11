---
name: rig-health
description: Tell rig problems from app bugs — a phone stuck on the SIM PIN screen, Wi-Fi dropped, a hung or stale proxy, a missing proxy CA on a new device, the wrong device addressed, a simulator routed through the Mac's proxy. Load it when requests fail, the app says it can't reach the server, a device behaves oddly, or before a long session on a shared rig.
---

# Rig health

Network failures on the rig look like backend failures ("can't reach the server", "something went
wrong") while the device itself still answers. Check the rig before reporting the app.

## Before a session

- `lab_health` (with `serial`): the rig measured from the phone. adb, SIM PIN and lock screen, the
  active network, the phone's proxy against what Testlab set, a real request from the phone through
  that proxy, Proxyman forwarding, active rules, the SSL exclude list, the phone's trust in the CA,
  the app's builds on the phone, leftover settings, runaway recordings. Each check says pass, warn
  or fail and the fix. `lab_session_start` runs it and lists the problems.
- `lab_status` and `handsets_list_adb_devices`: which devices are there, which is active. A phone
  connected twice (USB and network) is marked `same_phone_as`: use one serial.
- With several devices, pass `serial` on every call. On a phone reached over the network, use the
  serial the device list shows for it.
- Phones with weeks of uptime: reboot them before a heavy session.
- A phone that locks while charging pauses: keep it awake (`svc power stayon usb`), off afterwards.

## The SIM PIN trap

After a reboot, a phone with a locked SIM stops on the SIM PIN screen, before the lock screen.
Never unlock or type until a screenshot shows it is past that screen: a device PIN typed there
uses up SIM attempts (three wrong = PUK). Ask a person to unlock the SIM.

## When requests fail

Run `lab_health` first: it measures from the phone, where the problem is. Its failed checks say
which of these it is.

1. `lab_proxyman_health`: is the proxy up and answering through itself? A hung proxy takes every
   phone that uses it offline. Restart it, or take one phone off it meanwhile with
   `handsets_proxy(off)`.
2. Is the phone's proxy address still the proxy's address? A proxy pinned to an address that has
   since changed fails every request. Compare the phone's proxy setting with the proxy host's
   current address.
3. Is the phone on Wi-Fi at all? A dropped Wi-Fi looks the same from the app.
4. HTTPS errors on a new device (`HTTPS_CLIENT_ERROR`, not intercepted): the proxy's CA isn't
   installed on it yet.

## Shared proxy

- Both your phones and other testers' may share one proxy: scope rules to your device
  (`lab_read_skill('proxy-forcing')`).
- Some traffic stays excluded from decryption on purpose (platform services, pinned logins).
  Don't include it without asking: it breaks push and sign-in.

## iOS Simulators

A simulator has no proxy of its own: routing it means setting the whole Mac's system proxy. Don't
do that by default. Ask first, turn it off right after, and prefer an Android phone for traffic
rewrites.
