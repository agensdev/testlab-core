# Driving Testlab from another app

Testlab can be the hands of another tool, for example a team's own desktop app with a «devtest
this ticket» button. The tool talks to the **control API** on `http://127.0.0.1:8378/api/v1`, the
same port and token as the MCP server. The spec is [control-api.yaml](control-api.yaml) (OpenAPI 3.1).

## Find the API

While Testlab runs, `control.json` in its data folder holds the URL and the token. Only the user
can read the file.

| OS | File |
|---|---|
| macOS | `~/Library/Application Support/Testlab/control.json` |
| Windows | `%APPDATA%\Testlab\control.json` |
| Linux | `~/.local/share/testlab/control.json` |

A branded build uses its own name for the folder. When the file is missing, Testlab isn't running
or the API is off (`mcp.controlApi`). The file's `mode` is always `service` now (it was `window`
when Testlab had a desktop app).

## From a shell

```bash
c=~/.local/share/testlab/control.json
api=$(jq -r .api "$c"); tok=$(jq -r .token "$c")
curl -s -H "Authorization: Bearer $tok" "$api/status"
curl -s -H "Authorization: Bearer $tok" -d '{"ref":"1234"}' "$api/builds/install"
curl -s -H "Authorization: Bearer $tok" -d '{"key":"PROJ-123","pr":1234,"start":"claude"}' "$api/devtest"
curl -sN -H "Authorization: Bearer $tok" "$api/events"
```

## From Swift (macOS)

```swift
struct Control: Decodable { let api: String; let token: String }

func testlab() throws -> Control {
    let url = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Application Support/Testlab/control.json")
    return try JSONDecoder().decode(Control.self, from: Data(contentsOf: url))
}

func startDevtest(key: String, pr: Int?) async throws -> Data {
    let c = try testlab()
    var req = URLRequest(url: URL(string: c.api + "/devtest")!)
    req.httpMethod = "POST"
    req.setValue("Bearer \(c.token)", forHTTPHeaderField: "Authorization")
    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
    req.httpBody = try JSONSerialization.data(withJSONObject: ["key": key, "pr": pr as Any, "start": "claude"])
    let (data, response) = try await URLSession.shared.data(for: req)
    guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
    return data
}
```

`start: "claude"` starts a recorded report and answers with `claude_prompt`: the devtest command
to start Claude with in your own terminal or agent (the team's own command, if the profile names
one and the work folder has it). Use `start: "report"` to leave Claude out, or `"none"` just to
note the PR. Install a build first with `POST /builds/install` and poll
`GET /jobs/{id}`, or follow `GET /events`.

## Approvals

When Claude wants to write (a Jira comment or label, an uninstall, starting a CI build), the
write waits for a yes. The person gets a desktop notification and can answer on the approvals page
(`testlab open approvals`) or with `testlab approve`; a host app answers here:

```bash
curl -s -H "Authorization: Bearer $tok" "$api/approvals"          # {"pending":[{"id":"ap_…","action":…,"detail":…}]}
curl -s -H "Authorization: Bearer $tok" -d '{"approve":true,"by":"my-app"}' "$api/approvals/ap_k3x9q2mfvr"
```

Show the person `detail`: it is the exact payload. Follow `event: approvals` on `/events` to see
new ones as they come. The approvals page, host apps and `testlab approve` / `deny` share one
queue, and the first answer wins. Nobody answering within `mcp.approvalTimeoutSeconds` (2 minutes
by default) is a no.

## Rules

- The API is loopback only unless the server is shared (`listen` + `allowedHosts`,
  docs/SERVICE.md). It refuses any other `Host` and any other site's `Origin`, so web pages can't
  call it; Testlab's own pages use a session cookie from a one-time link (`testlab open`).
- It doesn't write to Jira and doesn't uninstall. Those come from Claude and wait for the person's
  approval of the exact payload, given on the approvals page, with `testlab approve`, or through
  `/approvals`.
- Results are scrubbed of stored secrets, like every MCP result.
