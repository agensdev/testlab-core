---
name: proxy-forcing
description: Force app states through the proxy — Map Local (rewrite a response), Map Remote, blocking, breakpoints and scripted delays — match the real response shape, scope rules to one device, verify the rule took effect, and remove every rule afterwards. Load it when a test needs an error, an empty list, a toggle or a slow response the backend won't give you.
---

# Force states with the proxy

The phones send their traffic through Proxyman (`lab_proxyman_health` says whether it is up;
`lab_proxyman_call` runs its tools). A rule changes what the app receives without changing the
backend.

## The quick way: `lab_proxy_force`

One call for the common recipes, each made the way that works on the Proxyman in use, as a rule of
this session (named `[tl <session>] …`):

- `kind=rewrite`, `url`, `patches` `{"features.0.enabled": false, "count": 3}`: each live response
  of that URL with those key paths changed, by a scripting rule (every OS). The usual choice: the
  app gets its real shape. If Proxyman refuses the script, the result says why and serves a
  patched copy of the latest captured response with Map Local (exact URL only).
- `kind=status`, `status` (and `body`): a real error status, by a scripting rule (every OS). An
  error status gets an error body instead of the real data unless you pass `body` or
  `keep_body=true`: a 500 that still carries the real data looks, in the app, as if the error was
  ignored. If the Proxyman in use refuses the script, the result says why and what the app gets
  instead.
- `graphql_operation` (e.g. `GetAddressQuery`) keeps any kind to one GraphQL operation on a shared
  endpoint. Use it whenever the case names one query: without it every operation on the URL is hit.
- `kind=delay`, `delay_ms`: hold the response.
- `kind=block`: the request fails.

Which phones a rule hits: `status`, `delay` and `rewrite` are kept to this device (`serial`, else the active
one) whenever Proxyman can tell it apart, by the address its traffic comes from. The result says
«Applies to only 198.51.100.21 (serial)», or that it applies to every device and why.

`lab_proxy_rules` lists the rules (marked Testlab's or not) and the proxy changes made, with times.

## Choosing the rule by hand

- **Map Local** replaces a response body. Match the real response shape exactly: capture the
  real response first and rewrite it, rather than writing one from scratch.
- Map Local may ignore the status code and content type and serve 200. For a real error status,
  use **Map Remote** to an endpoint that returns that error, or **block** the request. An error
  body served with 200 is itself a useful probe: it shows how the app treats a body it can't use.
- A rule's `method` may be ignored, and `ANY` may never match. Pass an explicit method and check
  that it matched (below).
- **Breakpoints** widen a race window. Latency profiles don't slow keep-alive connections.
- A **scripted delay** (`await sleep(ms)`) holds one response. A scripted error doesn't stop the
  server carrying out the request.
- Wildcards containing `&` never match: use a regex.
- One GraphQL endpoint for every operation: pick the operation (`graphql_operation` in
  `lab_proxy_force`, `graphql_query_name` on Proxyman's own rules), or mock it with a body that
  answers all of them.

## On a shared proxy

One Proxyman can serve several phones at once (a rig, several devtests in parallel). Keep each
test's rules to its own phone:

- `lab_proxy_force` does it for `status`, `delay` and `rewrite`: the script checks the sender's address
  (`context.flow.clientIpAddress`) and lets every other phone's traffic through unchanged.
- That works when the phone uses the proxy over the network (its proxy is the rig's address).
  Through `adb reverse`, and from simulators, every device arrives as 127.0.0.1: then the rule hits
  all of them. The result says so; route the phone over Wi-Fi for a rule of its own, or pass
  `only_device` (a User-Agent fragment) if the app's User-Agent tells the phones apart.
- Map Local (by hand, or a `rewrite` fallback) and `block` can't be kept to one phone: say so in the report, and don't run
  them while someone else tests the same endpoint. A rule by hand: scripting, with the same address
  check, is the way to scope it.
- The report's network log keeps to the report's own phones when Proxyman can tell them apart; the
  log says whose requests it shows.
- Decrypting traffic has side effects: pinned or system-CA-only traffic breaks (some logins,
  push, Google services). Say what you changed in the proxy setup in the report.

## Verify, then judge

1. Make the app fetch again for real (pull to refresh, reopen the screen).
2. Check the flow: it shows the rule matched (`matchedTools`, the served size and status).
3. Only then judge the UI. Report what was served, not what the rule was meant to serve.

## Clean up

- `lab_proxy_cleanup` removes this session's rules, lists again and asks the mocked URLs once more
  through the proxy: a rule can survive its delete under a new id. `lab_report_write` does this by
  itself and lists every proxy change in the report's «Rig» section. `lab_reset_proxyman` clears
  every rule, other people's too: only on a proxy that is yours alone.
  A forgotten rule ("zombie") ruins the next person's test.
- Never paste tokens from captured URLs into reports.
