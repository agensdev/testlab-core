---
name: websocket-capture
description: How to capture and inspect the app's WebSocket traffic (e.g. GraphQL subscriptions over wss) through the user's Proxyman. Load this when realtime / subscription / chat traffic seems missing, "not decrypted", shows only CONNECT tunnels, or you need the actual socket messages.
---

# WebSocket capture (GraphQL subscriptions and other wss traffic)

Realtime features (chat, live scores, notifications…) usually run over a WebSocket next to
ordinary HTTPS calls. The most common shape is **GraphQL over WebSocket**:

| Flavour | Handshake | Messages |
|---------|-----------|----------|
| AWS AppSync | `wss://<host>/graphql/realtime?header=<base64>&payload=<base64>`, `Sec-WebSocket-Protocol: graphql-ws` | `connection_init`, `start`/`data`/`complete`, `ka` keep-alives |
| Apollo legacy (`subscriptions-transport-ws`) | `Sec-WebSocket-Protocol: graphql-ws` | `connection_init`, `start`/`data`/`stop`, `ka` |
| `graphql-ws` library | `Sec-WebSocket-Protocol: graphql-transport-ws` | `connection_init`/`ack`, `subscribe`/`next`/`complete`, `ping`/`pong` |

Queries and mutations normally still go over `POST https://<host>/graphql` and are fully readable
with `proxyman_get_flow_detail`. If the project profile or a project playbook names the realtime
host, use it. Otherwise find it with `proxyman_filter_flows(key="url", value="wss://")` or
`(key="requestHeader", value="websocket")`.

Handshake URLs and first messages often carry **auth tokens** (AppSync's `header=` is base64 JSON
with the Authorization JWT). Treat them as secrets: never paste them into reports or tickets.

## Proxyman CAN decrypt it. Check these things first

1. **Is the phone routed?** `handsets_proxy(status)` must show the phone pointing at Proxyman, and
   HTTPS needs Proxyman's certificate on the phone plus a build that trusts user certificates (see
   `handsets_proxy`). If the phone isn't routed, no flows appear at all.
2. **Is the host excluded?** `proxyman_get_ssl_proxying_list`. If the realtime host (or a wildcard
   matching it) is in `excludeList`, or isn't covered by `includeList`, every connection shows up as
   a bare `CONNECT <host>:443` tunnel and nothing is decrypted. Turn it on with
   `proxyman_toggle_ssl_proxying_domain`: switch the exclude entry off (`enabled=false,
   list="exclude"`), or enable it in the include list. Then force a reconnect (leave and re-open the
   screen, or force-stop and relaunch the app). These are the user's own Proxyman settings: say what
   you changed, and put back anything you only changed temporarily.
3. **Tell the two states apart** with `proxyman_filter_flows(key="host", value="<host>")`:
   - decrypted: `GET wss://<host>/…` with status **101 Switching Protocols** and
     `isIntercepted: true`, often next to `POST /graphql` 200s.
   - not decrypted: `CONNECT https://<host>:443` with `isIntercepted: false`.
   - **certificate pinning**: the handshake fails or the app reports a network error only while
     routed. Pinning is an app/build question, not something Proxyman settings fix.

## Reading the socket messages

`lab_ws_messages(host=…)`: the newest sessions with their last messages, both directions, GraphQL
over WebSocket decoded (type, subscription id, the subscription's operation name), keep-alives
counted rather than listed, and messages a forcing rule changed marked (`script`: edited, dropped,
injected). It works with Proxyman 26.x on macOS, Linux and Windows. An older Proxyman has no
WebSocket message tools: then a decrypted socket shows only as its 101 upgrade with
`responseSize: 0`, **which is expected, not a decryption failure**; ask the user to open the flow in
the Proxyman window (WebSocket tab).

## Known limitation: sockets with a subprotocol fail while decrypted

Proxyman (Linux 26.2.0 checked, 2026-11-02) doesn't pass the client's `Sec-WebSocket-Protocol` on to
the server. GraphQL over WebSocket needs it (AppSync and Apollo `graphql-ws`, `graphql-transport-ws`),
so with the host decrypted the server answers `connection_error NoProtocolError` and closes, and the
app reconnects in a loop. `lab_ws_messages` flags those sessions with `problem`.

- That is the proxy, not the app. **Never report it as an app bug.**
- To use the feature while routed, exclude the host from SSL proxying
  (`proxyman_toggle_ssl_proxying_domain(domain=…, enabled=true, list="exclude")`), say so in the
  report, and remove the exclude afterwards. Its messages can't be read or forced while excluded.
- A scripting rule can't put the header back: Proxyman drops it on the way out.

## Forcing socket states

`lab_proxy_force` with a `ws_*` kind and `url` = the socket's `wss://` URL (wildcards allowed) acts on
the messages from the server, kept to this phone like the HTTP kinds:

| kind | What the app gets | Typical case |
|------|-------------------|--------------|
| `ws_rewrite` + `patches` | the message with those key paths changed (`payload.data.onScore.home`) | a live value the backend won't give you |
| `ws_drop` | nothing: the message never arrives | a missed event, a stale screen |
| `ws_delay` + `delay_ms` | the message late; messages behind it on the same socket wait too | a slow feed, ordering |
| `ws_inject` + `body` | an extra message at the next matching server message (`{{id}}` = its subscription id) | an event the backend can't trigger |
| `ws_close` | the socket closed at the first matching message, every reconnect again | reconnect handling, offline banners |

Narrow it with `graphql_operation` (the subscription's name; the script learns its id from the
app's `start`/`subscribe` message, so create the rule before the screen opens) and `match` (text,
or `/regex/`). Proxyman applies a socket rule to sockets opened after it: make the app reconnect
(leave and re-open the screen, or relaunch). Then check `lab_ws_messages` for the `script` marks
before judging the screen. Remove the rules with `lab_proxy_cleanup` like any other.

## Reading reconnect patterns

Opening a realtime screen usually produces an HTTP call plus a new `wss` 101. Several new sockets
within seconds can be normal per-screen subscriptions. **Repeated reconnects while the user
stays still on one screen** suggest the socket is dropping, which may or may not be caused by
the proxy. To decide:
1. Note the reconnect timestamps from `proxyman_filter_flows`.
2. Check logcat for the HTTP client's WebSocket failure/close codes (okhttp: `onFailure`,
   `onClosing`) around those times.
3. Exclude the host temporarily (`proxyman_toggle_ssl_proxying_domain(domain=…, enabled=true, list="exclude")`)
   and see if the reconnects stop. If they stop, it's a MITM side effect. Undo the exclude
   afterwards and note the finding.
