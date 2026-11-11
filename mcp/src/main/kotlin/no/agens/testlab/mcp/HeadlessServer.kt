package no.agens.testlab.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.agens.testlab.AppIdentity
import no.agens.testlab.services.runtime.LabRuntime
import java.io.File
import java.io.PrintStream

/**
 * `serve`: the MCP server and the control API without a window, for a terminal, a background
 * service (docs/SERVICE.md) or a shared phone rig. Writes wait in [LabRuntime.approvals] for a
 * host app (`/api/v1/approvals`) or `testlab approve`; nobody answering is a no.
 */
object HeadlessServer {
    data class Options(
        val port: Int? = null,
        /** Overrides the `mcp.listen` setting for this run. */
        val listen: String? = null,
        /** Added to the `mcp.allowedHosts` setting for this run. */
        val allowHosts: List<String> = emptyList(),
    )

    class Failed(message: String) : RuntimeException(message)

    /** Starts the server and blocks until the process is stopped. */
    fun run(rt: LabRuntime, opts: Options, out: PrintStream = System.out, log: PrintStream = System.err): Nothing {
        val h = start(rt, opts, out, log)
        Runtime.getRuntime().addShutdownHook(Thread { h.stop() })
        // A service manager's stop (SIGTERM) is a clean exit, not a crash: exit 0, so systemd doesn't
        // record a failure and launchd's KeepAlive (restart unless it exited cleanly) leaves it stopped.
        runCatching { sun.misc.Signal.handle(sun.misc.Signal("TERM")) { log.println("stopping (SIGTERM)"); kotlin.system.exitProcess(0) } }
        Thread.currentThread().join()
        throw IllegalStateException("unreachable")
    }

    class Handle internal constructor(val gateway: McpGateway, val endpoint: McpEndpoint, private val rt: LabRuntime, private val scope: CoroutineScope) {
        fun stop() {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            gateway.stop()
            rt.close()
        }
    }

    fun start(rt: LabRuntime, opts: Options, out: PrintStream = System.out, log: PrintStream = System.err): Handle {
        val mcpCfg = rt.settings.current.mcp
        val bind = (opts.listen ?: mcpCfg.listen).trim().ifEmpty { McpGateway.LOOPBACK_BIND }
        val hosts = (mcpCfg.allowedHosts + opts.allowHosts).distinct()
        val ep = McpEndpoint.forBind(opts.port ?: mcpCfg.port, bind)
        rt.approver = rt.approvals.approver()
        val mcp = TestlabMcp(rt, ep)
        val gateway = mcp.gateway(listen = bind, allowedHosts = hosts)
        try {
            gateway.start(ep)
        } catch (e: IllegalArgumentException) {
            throw Failed(e.message ?: "bad listen address")
        } catch (e: Exception) {
            throw Failed("could not listen on $bind:${ep.port}: ${e.message} (is ${AppIdentity.NAME} or its service already running? `${AppIdentity.SLUG} service status`)")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        recover(rt, log)
        // Say what waits, so whoever reads the log (or the terminal) knows how to answer.
        scope.launch {
            var seen = emptySet<String>()
            rt.approvals.pending.collect { list ->
                list.filter { it.id !in seen }.forEach { p ->
                    log.println("approval ${p.id} waiting: ${p.action} — ${p.detail.lineSequence().first().take(160)}")
                    log.println("  answer: ${AppIdentity.SLUG} open approvals, or ${AppIdentity.SLUG} approve ${p.id} (or deny); no answer by ${p.expires} = no")
                    no.agens.testlab.services.service.DesktopNotice.show("${AppIdentity.NAME}: ${p.action}", "Waiting for your yes: ${AppIdentity.SLUG} open approvals")
                }
                seen = list.map { it.id }.toSet()
            }
        }
        scope.launch { rt.approvals.resolved.collect { r -> r.firstOrNull()?.let { log.println("approval ${it.id} ${if (it.approved) "approved" else "declined"} (${it.by}): ${it.action}") } } }

        log.println("${AppIdentity.NAME} MCP server on $bind:${ep.port} — ${mcp.tools.tools.size} tools, control API ${if (rt.settings.current.mcp.controlApi) "on" else "off"}. Ctrl-C stops it.")
        if (!McpGateway.isLoopback(bind)) {
            log.println("Reachable from other machines as: ${(gateway.hosts - McpGateway.LOOPBACK_HOSTS).joinToString { ep.urlFor(it) }}")
            log.println("Plain HTTP with a bearer token: use it on a tailnet or a trusted LAN, or put TLS in front (docs/SERVICE.md).")
        }
        if (!rt.settings.current.mcp.confirmWrites) log.println("mcp.confirmWrites is off: writes go through without asking.")
        // Its menu opens the pages, which the control API serves.
        if (rt.settings.current.mcp.controlApi) MenuBar.start(rt, { mcp.pageUrl(it) }, log)
        // Under a service manager stdout is a log file or the journal: the token stays out of it.
        if (interactive()) {
            log.println("Connect Claude Code (the token is in the command; `${AppIdentity.SLUG} mcp-config` prints it again):")
            out.println(ep.claudeCodeCommand(rt.mcpToken()))
        } else {
            log.println("Connect Claude Code: `${AppIdentity.SLUG} mcp-config` (as this account) prints the command with the token; host apps read control.json.")
        }
        return Handle(gateway, ep, rt, scope)
    }

    /** A person at a terminal, not a service manager (which sets TESTLAB_SERVICE=1 in the unit). */
    private fun interactive(): Boolean = System.getenv("TESTLAB_SERVICE") != "1" && System.console() != null

    /** What a crash left behind: Proxyman routing, interrupted recordings, disabled sibling builds. */
    private fun recover(rt: LabRuntime, log: PrintStream) {
        if (File(rt.dirs.data, "proxied.json").isFile) Thread { runCatching { rt.proxyman.restoreAll() }.onSuccess { r -> if (r.isNotEmpty()) log.println("Proxyman routing undone on ${r.joinToString()}") } }.start()
        Thread { runCatching { rt.recordings.recoverAll() }.onSuccess { r -> if (r.isNotEmpty()) log.println("recovered ${r.size} interrupted recording(s)") } }.start()
        Thread { runCatching { runBlocking { rt.isolation.restoreAll() } }.onSuccess { r -> r.filterValues { it.isNotEmpty() }.forEach { (s, p) -> log.println("re-enabled on $s: ${p.joinToString()}") } } }.start()
    }
}
