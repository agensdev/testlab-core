# Testlab as a background service

`testlab serve` runs the MCP server, the [control API](CONTROL_API.md) and the local web pages.
Installed as a service it starts at login (or at boot on a phone rig), and Claude Code, Claude
Desktop and host apps reach the phones at any time.

It is the `testlab` command itself ([INSTALL.md](INSTALL.md)): `testlab-cli-<version>-<os>-<arch>.tar.gz`
from the release, Java bundled. Unpack it anywhere, e.g. `/opt/testlab`, and link `bin/testlab`
into your PATH. On macOS, clear the download quarantine first: `xattr -dr com.apple.quarantine testlab`.

## On your own computer

```bash
testlab service install      # starts now and at every login (Linux: --boot also starts it at boot)
testlab service status       # installed? running? what does the server say?
testlab service logs         # how to follow its log
testlab service stop | start
testlab service uninstall
```

| OS | What gets installed |
|---|---|
| Linux | a systemd user unit, `~/.config/systemd/user/testlab.service` |
| macOS | a launchd agent, `~/Library/LaunchAgents/<app id>.service.plist` (the login keychain stays reachable) |
| Windows | a scheduled task at logon, «Testlab service». A Windows service would run as SYSTEM, without your Credential Manager or adb keys |

After a restart of the computer it starts again by itself: on macOS and Windows when you log in, on
Linux when you log in, or at boot with `testlab service install --boot` (it turns on lingering,
`loginctl enable-linger $USER`; if your system asks for root, run that with sudo). `testlab service
status` says which. A crash restarts it; `testlab service stop` keeps it stopped until the next login
(or `testlab service start`). A `--system` service (below) starts at boot.

`testlab upgrade` (and `--rollback`) stops every background service that is running (each space's,
and a Linux `--system` one), swaps the files, starts them again with the new version and checks the
version each one reports. A `--system` service needs root to be stopped and started: run the upgrade
with sudo, or restart it yourself afterwards (`sudo systemctl restart testlab`).

## A shared phone rig

A Linux box with phones on USB, used by the whole team over a tailnet or a trusted LAN:

```bash
sudo useradd -m lab                                    # the account that owns the phones' adb keys
sudo install -d -m 700 /etc/testlab                    # root only; systemd hands the file to the service
sudo sh -c 'umask 077; read -rsp "vault passphrase: " p; echo; echo "$p" > /etc/testlab/vault-passphrase'
sudo -u lab -i testlab secrets set github.token        # asks for that passphrase, then the token; same for the other keys
sudo testlab service install --system --user lab --passphrase-file /etc/testlab/vault-passphrase \
  --listen 0.0.0.0 --allow-host rig.example.ts.net,203.0.113.7
```

- `--system` writes `/etc/systemd/system/testlab.service` with `User=lab`. Its logs go to the journal:
  `journalctl -u testlab -f`.
- `--passphrase-file` hands the vault passphrase over with systemd's `LoadCredential=`. It never
  appears in the unit or the environment. A server has no keyring, so the vault needs it.
- `--listen` is the address the server binds. `0.0.0.0` means all addresses; a tailnet IP binds
  only that one.
- `--allow-host` lists every name and IP clients use. Requests with any other `Host` are refused,
  which is the DNS-rebinding guard. A specific `--listen` IP is allowed by itself.
- The same settings live in `mcp.listen` and `mcp.allowedHosts` (`testlab config set`) for `serve`
  without flags.

Clients connect with the rig's URL and token. On the rig, `sudo -u lab testlab mcp-config` prints
the connect commands, which carry `127.0.0.1`; replace that with the rig's name:

```bash
claude mcp add --scope user --transport http testlab http://rig.example.ts.net:8378/mcp --header "Authorization: Bearer …"
```

**The connection is plain HTTP with a bearer token.** A tailnet encrypts it. On a LAN, put TLS in
front, e.g. `tailscale serve --https=443 http://127.0.0.1:8378`, or a reverse proxy that passes the
`Host` on. Everyone with the token can drive the phones. Treat it like a password, and rotate it
with `testlab secrets delete mcp.token` (a new one is made at the next start).

## Approvals

Writes wait for a yes:
- Jira comments, labels, assignments and the devtest report
- uninstalls and clearing app data
- starting CI builds

A desktop notification says when one waits. The approvals page (`testlab open approvals`), a host
app (`/api/v1/approvals`, see [CONTROL_API.md](CONTROL_API.md#approvals)) and the CLI all answer the
same queue. The first answer wins.

```bash
testlab approvals            # what waits, with the exact payload
testlab approvals --watch    # and keep showing new ones
testlab approve ap_k3x9q2mfvr    # or: testlab deny ap_k3x9q2mfvr, approve --all
```

The service also logs every waiting approval with the command to answer it. No answer within
`mcp.approvalTimeoutSeconds` (120 by default) is a no. Claude then
gets «Not approved». The CLI finds the server through `control.json` in the data folder. For a
service running as another account, pass that account's file:
`sudo testlab approvals --control /home/lab/.local/share/testlab/control.json`.

## What a service never does

- It never prints the token to its log. Under a service manager (`TESTLAB_SERVICE=1`), `serve`
  points to `mcp-config` instead.
- It doesn't run Claude itself. `start: "claude"` in `POST /devtest` hands the host the prompt to
  start its own Claude with.
- A stop (SIGTERM) is a clean exit: systemd records success, and launchd doesn't restart it.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `service status` says running, but nothing answers | `testlab service logs`. A vault that can't be unlocked stops it at start: give `--passphrase-file`, or log in once so the keyring is unlocked |
| «could not listen on …: Address already in use» | Another `serve` (or an old Testlab app) has the port. `testlab service status` |
| «listening on 0.0.0.0 needs the names clients use» | Add `--allow-host` with every name and IP clients use |
| Clients get 403 «Host '…' is not allowed» | The name they use isn't in `allowedHosts` |
| systemd user unit: «Failed to connect to bus» | No user session (SSH without lingering, `su`): `sudo loginctl enable-linger $USER`, log in again, or use `--system` |
| Windows: the task doesn't start | `schtasks /Query /TN "Testlab service" /V /FO LIST`; the log is `logs\` in the data folder |
