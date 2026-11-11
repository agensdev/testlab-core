package no.agens.testlab.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.agens.testlab.mcp.Param.Companion.SERIAL
import no.agens.testlab.mcp.Param.Companion.bool
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.proxyman.Proxyman
import no.agens.testlab.services.proxyman.ProxyState
import no.agens.testlab.services.runtime.LabRuntime

private fun stateJson(s: ProxyState) = buildJsonObject {
    put("serial", s.serial)
    put("proxy", s.proxy ?: "direct")
    put("routed_by_testlab", s.ours)
    put("adb_reverse", s.reverse)
    s.wanted?.let { put("set_by_testlab", it) }
    if (s.was != null) { put("was", s.was); s.wasBy?.let { put("was_set_by", it) } }
    s.note?.let { put("note", it) }
    if (s.wanted != null && !s.ours) put("mismatch", "the phone uses ${s.proxy ?: "no proxy"}, not ${s.wanted} that Testlab set: something else changed it (another tool, or the same phone routed under another serial); handsets_proxy(on) sets it again")
}

internal fun ToolBox.proxymanTools(rt: LabRuntime) {
    tool(
        "handsets_proxy",
        """
        Route the phone's traffic through the user's Proxyman (on), back to direct (off), or show where
        it goes (status). Needs Proxyman turned on in the settings (`testlab open settings`) and running. USB phones and emulators
        use adb reverse + 127.0.0.1; Wi-Fi/IP phones this computer's LAN address. An iOS Simulator
        has no proxy of its own: on sets this Mac's system proxy (asks first; the Mac's own settings
        come back with off) and puts Proxyman's CA in the simulator's keychain. While on, the proxy is
        kept pinned; disconnecting the device or quitting turns it off. Then proxyman_get_flows shows the
        traffic, and lab_report_write attaches it as flows.har. HTTPS needs Proxyman's certificate on the
        phone (the result says how) and a debuggable build.
        """,
        str("action", "on | off | status", "status", listOf("on", "off", "status")),
        SERIAL,
    ) { a ->
        val serial = rt.device(a.serial).serial
        val s = when (a.str("action")) {
            "on" -> rt.proxyman.route(serial)
            "off" -> rt.proxyman.unroute(serial)
            else -> rt.proxyman.status(serial)
        }
        text(JsonObject(stateJson(s) + if (a.str("action") == "on") mapOf("https" to JsonPrimitive(Proxyman.certHint(rt.profile))) else emptyMap()).encode())
    }

    tool(
        "lab_proxyman_health",
        """
        Is Proxyman forwarding? Checks that its port answers, a real request through it, its MCP server,
        where each routed phone points, and (phone_trusts_ca, an estimate from the latest flows to the
        app's domain) whether the phone trusts this Proxyman's certificate: healthy alone is measured
        from this computer, not from the phone. A hung Proxyman accepts connections but never answers: the
        phone then has no network and the app shows «Beklager, noe gikk galt». The fix is to restart
        Proxyman (or handsets_proxy off).
        """,
        readOnly = true,
    ) {
        val h = rt.proxyman.health()
        text(buildJsonObject {
            put("healthy", h.healthy)
            put("proxy_port_open", h.proxyReachable)
            put("via_proxy_http_code", h.viaProxyStatus ?: "")
            h.viaProxySeconds?.let { put("via_proxy_seconds", "%.2f".format(it).toDouble()) }
            put("mcp", h.mcp)
            rt.proxyman.sharedWith()?.let { put("shared", it) }
            if (h.mcp.startsWith("connected")) runCatching { rt.proxyman.caTrust() }.getOrNull()?.let { put("phone_trusts_ca", it) }
            if (h.mcp.startsWith("connected")) runCatching { rt.proxyman.proxymanOs() }.getOrNull()?.let { put("proxyman_os", it.name.lowercase()) }
            if (h.mcp.startsWith("connected")) put("tools", "proxyman_<name> in sessions started from now on; in an older session that lacks them, lab_proxyman_call(tool, arguments)")
            putJsonArray("routed_devices") { h.devices.forEach { add(stateJson(it)) } }
            if (!h.healthy) put("fix", if (!h.proxyReachable) "Start Proxyman, or check its port (`testlab open settings`, Proxyman)" else "Proxyman is hung: quit and restart it; handsets_proxy(off) gives the phone its network back meanwhile")
        }.encode())
    }

    tool(
        "lab_proxyman_call",
        """
        Call one of Proxyman's own tools by name (get_flows, get_flow_detail, filter_flows,
        create_map_local…) with its arguments. The same tools appear as proxyman_<name> when this
        session started after Proxyman's MCP bridge was connected; use this one when they are missing.
        tool="list" returns their names and descriptions.
        """,
        str("tool", "Proxyman tool name without the proxyman_ prefix, or list"),
        Param("arguments", "object", "the tool's arguments", JsonObject(emptyMap())),
    ) { a ->
        val name = a.str("tool").removePrefix("proxyman_")
        val listed = rt.proxyman.refreshTools()
        if (name == "list" || name.isBlank()) return@tool text(listed.joinToString("\n") { t ->
            "${(t["name"] as? JsonPrimitive)?.content}: ${(t["description"] as? JsonPrimitive)?.content.orEmpty().lineSequence().firstOrNull().orEmpty().take(160)}"
        }.ifEmpty { "(Proxyman's MCP bridge lists no tools: lab_proxyman_health says why)" })
        val names = listed.mapNotNull { (it["name"] as? JsonPrimitive)?.content }
        if (names.isNotEmpty() && name !in names) throw IllegalArgumentException("Proxyman has no tool $name; it has: ${names.joinToString(", ")}")
        val r = rt.proxyman.call(name, a.json("arguments") as? JsonObject ?: JsonObject(emptyMap()))
        val out = (r["content"] as? JsonArray).orEmpty().joinToString("\n") { c ->
            val o = c as? JsonObject
            if ((o?.get("type") as? JsonPrimitive)?.content == "text") (o["text"] as? JsonPrimitive)?.content.orEmpty() else c.toString()
        }
        if ((r["isError"] as? JsonPrimitive)?.content == "true") throw IllegalStateException(out.ifEmpty { "Proxyman's $name failed" })
        text(Proxyman.redactTokens(out).ifEmpty { "(no output)" })
    }

    tool(
        "lab_reset_proxyman",
        """
        Delete ALL Proxyman rules in one call (Map Local, Map Remote, breakpoints, scripting, block/allow
        lists, reverse proxies, network conditions, DNS spoofing) and by default clear the captured
        flows: the cleanup after mocking. Afterwards relaunch the app (handsets_close_app +
        handsets_open_app) so it fetches the real responses again. On a Proxyman on another machine
        (lab_proxyman_health says "shared") this also deletes rules other people made there: prefer
        proxyman_delete_rule for your own.
        """,
        bool("clear_flows", "also clear the captured flows", true),
        destructive = true,
    ) { a ->
        rt.confirmWrite("Reset Proxyman", "Delete every Proxyman rule" + (if (a.bool("clear_flows")) " and the captured flows?" else "?") +
            (rt.proxyman.sharedWith()?.let { " $it, so this deletes other people's rules too." } ?: ""))
        text(rt.proxyman.reset(a.bool("clear_flows")))
    }

    tool(
        "lab_proxy_force",
        """
        Force a response state through Proxyman, made the way that works on this Proxyman, as a rule of
        this session (named [tl <session>], removed by lab_proxy_cleanup and by lab_report_write):
        Status, delay and rewrite are scripting rules on every OS (macOS, Linux, Windows):
        - status: answer with a status code: the app gets the real status. An error status (4xx/5xx)
          gets an error body ({"errors":[…],"error":…}) instead of the real data, unless body is given
          or keep_body=true: an app that reads the real data from a 500 would look as if it ignored
          the error. If Proxyman refuses the scripting rule, the result says why and falls back to
          Map Local, which Linux/Windows serve as 200.
        - delay: hold the response delay_ms (the race and locked-sheet technique).
        - rewrite: patch each live response of that URL as it passes (patches: dotted key path → JSON
          value, e.g. {"features.0.enabled": false, "count": 3}; `*` = every list element). The key
          paths are checked against the latest captured response first. Prefer it to writing whole
          bodies: the app's parser gets its real shape. Fallback (or with body): Map Local serving a
          patched copy, which needs the exact URL.
        - block: the request fails (block list).
        WebSockets (url = the socket's wss://… URL, wildcards allowed; messages from the server):
        - ws_rewrite: change key paths in each JSON message (patches, e.g. {"payload.data.onScore.home": 3}).
        - ws_drop: the app never gets the message (a missed event, stale screen).
        - ws_delay: hold each message delay_ms.
        - ws_inject: send body to the app at the next matching server message ({{id}} = its subscription id).
        - ws_close: close the socket at the first matching message (reconnect handling).
        match (substring or /regex/) and graphql_operation (the subscription's name) narrow which
        messages. Proxyman applies a socket rule to sockets opened after it: make the app reconnect.
        lab_ws_messages reads the messages back. Sockets that need a subprotocol (AppSync, Apollo)
        fail whenever decrypted, because Proxyman doesn't pass it on: lab_ws_messages says so.
        Which phones: status, delay and rewrite apply only to this device (serial, else the active
        one) when Proxyman can tell it apart, by the address its traffic comes from (a phone using
        the proxy over the network, as on a shared rig). Through adb reverse or as a simulator every
        device arrives as 127.0.0.1: then the rule applies to all of them, and the result says so.
        Block and a Map Local fallback always apply to every device. graphql_operation (e.g.
        GetAddressQuery) keeps any kind to that one GraphQL operation on a shared endpoint, so the
        case covers only what it asks for. all_devices=true makes the rule
        for everyone; only_device (a User-Agent fragment) scopes by User-Agent instead of address.
        Then make the app fetch again (leave and re-enter, pull to refresh), and check the flow before
        judging the screen. A mocked POST is still sent to the server unless blocked: for writes that
        must not happen, block them.
        """,
        str("kind", "status | delay | rewrite | block | ws_rewrite | ws_drop | ws_delay | ws_inject | ws_close",
            enum = listOf("status", "delay", "rewrite", "block") + no.agens.testlab.services.proxyman.Sockets.KINDS),
        str("url", "the request URL (wildcards * allowed; a Map Local rewrite needs the exact URL)"),
        str("method", "GET, POST… (never ANY)", "GET"),
        Param.int("status", "status code for kind=status", 0),
        str("body", "response body for status; for rewrite the body to start from instead of the captured one", ""),
        Param.int("delay_ms", "for kind=delay", 0),
        Param("patches", "object", "for kind=rewrite: key path → new JSON value", JsonObject(emptyMap())),
        SERIAL,
        bool("all_devices", "the rule for every device through this Proxyman, not only this one", false),
        str("only_device", "scope by this User-Agent fragment instead of the device's address", ""),
        str("graphql_operation", "only this GraphQL operation (operationName; for ws_* the subscription's name); empty = all of them", ""),
        str("match", "ws_*: only server messages containing this text (or matching /regex/)", ""),
        bool("keep_body", "kind=status: keep the real response body instead of an error body", false),
    ) { a ->
        val kind = a.str("kind")
        val (scope, why) = when {
            kind !in setOf("status", "delay", "rewrite") + no.agens.testlab.services.proxyman.Sockets.KINDS || a.bool("all_devices") -> null to null
            a.strOrNull("only_device") != null -> no.agens.testlab.services.proxyman.ProxyRules.DeviceScope("User-Agent", userAgent = a.strOrNull("only_device")) to null
            else -> runCatching { rt.serialFor(a.serial) }.getOrNull()?.let { rt.proxyman.scopeFor(it) } ?: (null to null)
        }
        text(rt.proxyman.rules.force(kind, a.str("url"), a.str("method"), a.int("status").takeIf { it > 0 }, a.strOrNull("body"),
            a.int("delay_ms").takeIf { it > 0 }?.toLong(), a.json("patches") as? JsonObject, scope,
            operation = a.strOrNull("graphql_operation"), keepBody = a.bool("keep_body"), match = a.strOrNull("match")) + (why?.let { "\n\nNOTE: $it" } ?: ""))
    }

    tool(
        "lab_ws_messages",
        """
        The app's WebSocket traffic through Proxyman: the newest sessions (host filters, e.g. the
        realtime host) with their last messages, both directions, GraphQL over WebSocket decoded
        (type, subscription id and its operation name; keep-alives counted, not listed), and the
        messages a lab_proxy_force ws_* rule changed marked (script: edited/dropped/injected). A
        session the server refused for its subprotocol gets a `problem`: that socket can't work while
        decrypted (a Proxyman limitation), so the app's realtime feature fails because of the proxy.
        Messages can carry tokens and personal data: quote only what the report needs.
        """,
        str("host", "only sessions whose host or URL contains this; empty = all", ""),
        Param.int("sessions", "how many sessions, newest first", 3),
        Param.int("last", "messages per session (the last ones)", 30),
        str("flow_id", "one session by its flow id", ""),
        readOnly = true,
    ) { a ->
        text(no.agens.testlab.services.proxyman.Sockets.read(rt.proxyman, a.strOrNull("host"), a.int("sessions").coerceIn(1, 20), a.int("last").coerceIn(1, 500), a.strOrNull("flow_id")).encode())
    }

    tool(
        "lab_proxy_rules",
        """
        The Proxyman rules now (each marked made by Testlab, with its session, or not), and the proxy
        changes this space made in the last `since_minutes` (rules created, updated, deleted, SSL list
        changes) with their times: what a report needs to say about mocked evidence.
        """,
        Param.int("since_minutes", "list proxy changes from this many minutes back", 120),
        readOnly = true,
    ) { a ->
        val rules = rt.proxyman.rules.list()
        val since = System.currentTimeMillis() / 1000.0 - a.int("since_minutes") * 60
        text(buildJsonObject {
            put("session", rt.proxyman.rules.session)
            putJsonArray("rules") { for (r in rules) add(buildJsonObject { put("id", r.id); put("type", r.type); put("name", r.name); put("url", r.url); put("testlab", r.ours) }) }
            putJsonArray("changes") { for (c in rt.proxyman.rules.changes(since)) add(buildJsonObject {
                put("at", java.time.Instant.ofEpochMilli((c.epoch * 1000).toLong()).toString()); put("tool", c.tool); put("summary", c.summary); put("session", c.session)
            }) }
        }.encode())
    }

    tool(
        "lab_proxy_cleanup",
        """
        Delete Testlab's own Proxyman rules and prove they are gone: delete by the session's tag, list
        again (Proxyman reassigns ids, so a deleted rule can survive), then request each mocked URL
        through the proxy and check it gets a real answer. scope=session (default: this session's rules),
        or all (every rule Testlab made, any session). Rules other people made are left alone
        (lab_reset_proxyman deletes everything). lab_report_write runs this by itself.
        """,
        str("scope", "session | all", "session", listOf("session", "all")),
    ) { a ->
        val c = rt.proxyman.rules.cleanup(if (a.str("scope") == "all") null else rt.proxyman.rules.session)
        text(c.summary() + if (c.clean) "\nThe app may hold mocked data in its cache: leave the screen and come back, or restart it." else "")
    }

    tool(
        "lab_report_flows",
        "Attach (or refresh) the Proxyman traffic in a written report: flows.har plus a «Network log» request table. lab_report_write does this by itself when the Proxyman settings say so.",
        str("report_id", "a written report"),
    ) { a ->
        val f = rt.reports.attachFlows(a.str("report_id")) ?: return@tool text("No Proxyman flows to attach: is the phone routed through Proxyman (handsets_proxy on)?")
        text("Network log attached to ${a.str("report_id")}: ${f.count} request(s) to ${f.hosts.size} host(s) → flows.har (${f.file.length() / 1024} KB)")
    }
}

/**
 * Proxyman's own tools as proxyman_* (the lab's names), passed through with redaction, the
 * activity feed and the method=ANY fix. Registered per MCP session from the cached tool list,
 * so a Proxyman started later shows up in the next session.
 */
internal fun ToolBox.mirrorProxyman(server: Server, rt: LabRuntime) {
    val px = rt.settings.current.proxyman
    if (!px.enabled || !px.mirrorTools) return
    for (t in rt.proxyman.tools) {
        val name = (t["name"] as? JsonPrimitive)?.content ?: continue
        val schema = t["inputSchema"] as? JsonObject ?: JsonObject(emptyMap())
        val desc = (t["description"] as? JsonPrimitive)?.content.orEmpty() +
            if (name in Proxyman.METHOD_TOOLS) "\n\nNOTE: give an explicit method (GET, POST…); 'ANY' never matches in Proxyman's MCP server, so it is replaced with GET." else ""
        val toolSchema = ToolSchema(
            properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap()),
            required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content },
        )
        registerRaw(server, "proxyman_$name", "[Proxyman] $desc", toolSchema) { args ->
            val r = rt.proxyman.call(name, args)
            val content = (r["content"] as? JsonArray).orEmpty().mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                when ((o["type"] as? JsonPrimitive)?.content) {
                    "text" -> TextContent(Proxyman.redactTokens((o["text"] as? JsonPrimitive)?.content.orEmpty()))
                    "image" -> ImageContent((o["data"] as? JsonPrimitive)?.content.orEmpty(), (o["mimeType"] as? JsonPrimitive)?.content ?: "image/png")
                    else -> TextContent(o.toString())
                }
            }
            CallToolResult(content.ifEmpty { listOf(TextContent("(no output)")) }, isError = (r["isError"] as? JsonPrimitive)?.content == "true")
        }
    }
}

/** For lab_status: the Proxyman part. */
internal fun proxymanStatus(rt: LabRuntime) = buildJsonObject {
    val px = rt.settings.current.proxyman
    put("enabled", px.enabled)
    if (px.enabled) {
        put("port", px.port)
        put("mcp_server", rt.proxyman.remoteUrl() ?: rt.proxyman.mcpServer()?.path ?: "not found")
        put("mirrored_tools", rt.proxyman.tools.size)
        putJsonArray("routed") { rt.proxyman.routed.value.forEach { add(JsonPrimitive(it)) } }
    }
}
