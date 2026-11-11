package no.agens.testlab.cli

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import java.net.URLEncoder

/**
 * `open [PAGE] [ARG]`: a page in the browser, signed in: `devices` (the default: what adb reaches,
 * Wi-Fi pairing), `reports`, `screen [serial]` (the phone live),
 * `report [id]` (newest when none), `profiles` (make one from a repo, edit, import, export),
 * `handback` (a plan waiting for a person: tap what the autopilot couldn't pick), `setup` (keys,
 * Jira, test accounts, Apple team), `approvals` (writes waiting for a yes), `settings`, `guide`.
 * Uses the running server (the background service or `serve`); without one it serves here
 * until Ctrl-C. `--print` only prints the link (for another computer, through a tunnel). The link uses
 * the name testlab.localhost, which browsers send to this computer; `--ip` uses 127.0.0.1 instead.
 */
internal fun open(opts: Opts): Int {
    val pos = opts.positional()
    val page = pos.getOrNull(0) ?: "devices"
    if (page !in no.agens.testlab.mcp.UiPages.PAGES) throw CliError("no page '$page': ${no.agens.testlab.mcp.UiPages.PAGES.joinToString(" | ")}")
    val arg = pos.getOrNull(1)
    val query = when (page) {
        "screen" -> arg?.let { "serial" to it }
        "report" -> arg?.let { "id" to it }
        else -> arg?.let { "id" to it }
    }

    val (url, gateway) = signedIn(opts, page, query)
    println(url)
    if (!opts.flag("print")) browse(url)
    gateway?.let { g ->
        Runtime.getRuntime().addShutdownHook(Thread { g.stop(); runtime.close() })
        Thread.currentThread().join()
    }
    return 0
}

/**
 * A signed-in link to [page] (a one-time ticket) on the running server; without one, a server
 * started here, returned so the caller stops it when done.
 */
internal fun signedIn(opts: Opts, page: String, query: Pair<String, String>? = null): Pair<String, no.agens.testlab.mcp.McpGateway?> {
    var gateway: no.agens.testlab.mcp.McpGateway? = null
    val client = ControlClient.find(opts)?.takeIf { c -> runCatching { c.get("/status") }.isSuccess } ?: run {
        // Nothing running: serve here for as long as the page is open.
        val ep = endpoint(opts)
        gateway = no.agens.testlab.mcp.TestlabMcp(runtime, ep).gateway().also { it.start(ep) }
        System.err.println("${AppIdentity.NAME} is serving here (${ep.url}) while the page is open. `${AppIdentity.SLUG} service install` keeps one running instead.")
        ControlClient.find(opts) ?: throw CliError("the server started, but wrote no control.json (is the control API off? ${AppIdentity.SLUG} config set mcp.controlApi true)")
    }
    val ticket = client.post("/ui/ticket", "{}").jsonObject["ticket"]!!.jsonPrimitive.content
    // The pages go by name (testlab.localhost); --ip keeps 127.0.0.1 for a browser that doesn't resolve *.localhost.
    val base = client.api.removeSuffix("/api/v1").let { if (opts.flag("ip")) it else it.replace(Regex("//(127\\.0\\.0\\.1|localhost|\\[::1\\])"), "//${no.agens.testlab.mcp.McpGateway.UI_HOST}") }
    return "$base/ui/$page?t=$ticket" + (query?.let { (k, v) -> "&$k=" + URLEncoder.encode(v, Charsets.UTF_8) } ?: "") to gateway
}

/** Whether this session can show a page: a desktop (macOS, Windows, or Linux with a display). */
internal fun canBrowse(): Boolean = no.agens.testlab.services.runtime.Browser.available()

/** The default browser (the one registered for https, also for file pages), or the link printed when there is none to open. */
internal fun browse(url: String) {
    if (!no.agens.testlab.services.runtime.Browser.open(url)) System.err.println("Open that link in your browser (it works once, within a minute).")
}
