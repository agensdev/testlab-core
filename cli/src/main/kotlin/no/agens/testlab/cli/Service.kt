package no.agens.testlab.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.service.BackgroundService
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val CLI_MAIN = "no.agens.testlab.cli.MainKt"

/** `service install|uninstall|start|stop|status|logs`: `serve` run by the OS (docs/SERVICE.md). */
internal fun service(opts: Opts): Int {
    val svc = BackgroundService()
    val system = opts.flag("system")
    val dirs = AppDirs.forHost()
    val spec = BackgroundService.Spec(
        command = serviceCommand(opts) + serveArgs(opts),
        logFile = File(dirs.data, "logs/service-console.log"),
        workDir = dirs.data,
        user = opts.value("user")?.takeIf { system },
        passphraseFile = opts.value("passphrase-file")?.let { File(it).absoluteFile }?.takeIf { system },
        env = opts.value("passphrase-file")?.takeIf { !system }?.let { mapOf("TESTLAB_VAULT_PASSPHRASE_FILE" to File(it).absolutePath) }.orEmpty(),
    )
    when (opts.positional().getOrNull(0)) {
        "install" -> {
            spec.passphraseFile?.let { if (!it.isFile) throw CliError("no passphrase file at ${it.path}") }
            val where = try { svc.install(spec, system) } catch (e: IllegalStateException) { throw CliError("installing the service failed: ${e.message}") } catch (e: IllegalArgumentException) { throw CliError(e.message ?: "bad options") }
            println("installed: $where")
            println("runs: ${spec.command.joinToString(" ")}")
            println("logs: ${svc.logsCommand(spec, system)}")
            if (HostOs.current() == HostOs.LINUX && !system && !svc.lingering()) {
                if (opts.flag("boot")) {
                    val r = svc.enableLinger()
                    println(if (r.code == 0 && svc.lingering()) "starts at boot too (lingering on for ${System.getProperty("user.name")})"
                        else "couldn't turn lingering on (${r.out.trim().take(120)}): sudo loginctl enable-linger ${System.getProperty("user.name")}")
                } else println("starts at login. To start at boot, before anyone logs in: ${AppIdentity.SLUG} service install --boot (or sudo loginctl enable-linger ${System.getProperty("user.name")})")
            }
            if (HostOs.current() == HostOs.LINUX && spec.passphraseFile == null && System.getenv("TESTLAB_VAULT_PASSPHRASE_FILE") == null && System.getenv("DBUS_SESSION_BUS_ADDRESS") == null) {
                println("No desktop keyring here: give the vault passphrase with --passphrase-file FILE (mode 0600), or the service can't unlock the vault.")
            }
            println("status: ${AppIdentity.SLUG} service status")
        }
        "uninstall" -> println(if (svc.uninstall(system)) "removed ${svc.unitFile(system).path}" else "not installed")
        "start" -> {
            // Each space has its own service: one never installed in this space (e.g. right after
            // `space create` or `space migrate`) is installed now, which starts it too.
            if (svc.status(system).state == BackgroundService.State.NOT_INSTALLED) {
                val space = no.agens.testlab.services.Spaces.current()
                val where = try { svc.install(spec, system) } catch (e: IllegalStateException) { throw CliError("installing the service failed: ${e.message}") }
                println("the space $space had no service yet: installed and started ($where)")
                println("runs: ${spec.command.joinToString(" ")}")
            } else result(svc.start(system), "started")
        }
        "stop" -> result(svc.stop(system), "stopped")
        "status" -> {
            val st = svc.status(system)
            println("service: ${st.state.name.lowercase().replace('_', ' ')}  (${st.where}${if (st.detail.isNotBlank()) ", ${st.detail}" else ""})")
            if (st.state != BackgroundService.State.NOT_INSTALLED) println("starts by itself: ${svc.startsAt(system).words}" +
                when {
                    svc.startsAt(system) == BackgroundService.Start.LOGIN && HostOs.current() == HostOs.LINUX -> " (not after a reboot until you log in: ${AppIdentity.SLUG} service install --boot)"
                    svc.startsAt(system) == BackgroundService.Start.LOGIN -> " (after a restart, once you log in); after a crash it restarts, after `${AppIdentity.SLUG} service stop` it waits for the next login or start"
                    else -> ""
                })
            val c = ControlClient.find(opts)
            if (c == null) println("server: not running (no control.json in ${dirs.data})")
            else runCatching { c.get("/status") }.onSuccess { s ->
                val o = s.jsonObject
                println("server: ${c.api} — mode ${o.str("mode")}, profile ${o["profile"]?.jsonObject?.str("id")}, device ${o.str("active_device") ?: "none"}, ${o["pending_approvals"]?.jsonPrimitive?.intOrNull ?: 0} approval(s) waiting")
            }.onFailure { println("server: control.json says ${c.api}, but it does not answer (${it.message})") }
        }
        "logs" -> println(svc.logsCommand(spec, system))
        else -> throw CliError("usage: ${AppIdentity.SLUG} service install [--boot]|uninstall|start|stop|status|logs [--system]")
    }
    return 0
}

private fun result(r: BackgroundService.Result, ok: String) {
    if (r.code != 0) throw CliError(r.out.trim().ifEmpty { "exit ${r.code}" })
    println(ok)
}

/** The `serve` flags the service is installed with. */
private fun serveArgs(opts: Opts): List<String> = buildList {
    add("serve")
    opts.value("port")?.let { add("--port"); add(it) }
    opts.value("listen")?.let { add("--listen"); add(it) }
    opts.values("allow-host").takeIf { it.isNotEmpty() }?.let { add("--allow-host"); add(it.joinToString(",")) }
}

internal fun serviceCommand(opts: Opts): List<String> {
    // The space goes with the command: the service serves this space, under this space's name.
    opts.value("launcher")?.let { return listOf(File(it).absolutePath) + no.agens.testlab.services.Spaces.args() }
    return try { BackgroundService.selfCommand(CLI_MAIN) + no.agens.testlab.services.Spaces.args() } catch (e: IllegalStateException) { throw CliError("${e.message}: pass --launcher PATH") }
}

/** `approvals [--watch]`. */
internal fun approvals(opts: Opts): Int {
    val c = ControlClient.find(opts) ?: throw CliError("${AppIdentity.NAME} is not running here (no control.json; start `${AppIdentity.SLUG} serve` or the app)")
    fun show() {
        val pending = c.get("/approvals").jsonObject["pending"] as? JsonArray ?: JsonArray(emptyList())
        if (pending.isEmpty()) println("no approvals waiting")
        pending.forEach { p ->
            val o = p.jsonObject
            println("${o.str("id")}  ${o.str("action")}  (until ${o.str("expires")})")
            o.str("detail")?.lineSequence()?.forEach { println("    $it") }
        }
    }
    show()
    if (opts.flag("watch")) {
        println("watching; Ctrl-C stops")
        c.events { type -> if (type == "approvals") { println("---"); show() } }
    }
    return 0
}

/** `approve ID|--all`, `deny ID|--all`. */
internal fun answer(opts: Opts, approve: Boolean): Int {
    val c = ControlClient.find(opts) ?: throw CliError("${AppIdentity.NAME} is not running here (no control.json)")
    val ids = if (opts.flag("all")) {
        (c.get("/approvals").jsonObject["pending"] as? JsonArray).orEmpty().map { it.jsonObject.str("id")!! }
    } else listOf(opts.positional().getOrNull(0) ?: throw CliError("usage: ${AppIdentity.SLUG} ${if (approve) "approve" else "deny"} ID|--all  (ids: ${AppIdentity.SLUG} approvals)"))
    if (ids.isEmpty()) { println("no approvals waiting"); return 0 }
    var failed = 0
    for (id in ids) {
        runCatching { c.post("/approvals/$id", """{"approve":$approve,"by":"cli"}""") }
            .onSuccess { println("${if (approve) "approved" else "declined"} $id") }
            .onFailure { failed++; System.err.println("$id: ${it.message}") }
    }
    return if (failed == 0) 0 else 1
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

/** The running server's control API, found through `<data>/control.json` (or `--control FILE`). */
internal class ControlClient(val api: String, private val token: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun get(path: String) = call(HttpRequest.newBuilder(URI(api + path)).GET())
    fun post(path: String, body: String) = call(HttpRequest.newBuilder(URI(api + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)))

    private fun call(b: HttpRequest.Builder): kotlinx.serialization.json.JsonElement {
        val r = http.send(b.header("Authorization", "Bearer $token").timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString())
        val body = runCatching { json.parseToJsonElement(r.body()) }.getOrNull()
        if (r.statusCode() !in 200..299) {
            val msg = (body as? JsonObject)?.get("error")?.jsonObject?.str("message") ?: r.body().take(200)
            throw RuntimeException("HTTP ${r.statusCode()}: $msg")
        }
        return body ?: throw RuntimeException("not JSON: ${r.body().take(200)}")
    }

    /** Follows the event stream, calling [on] with each event's type. */
    fun events(on: (String) -> Unit) {
        val req = HttpRequest.newBuilder(URI("$api/events")).header("Authorization", "Bearer $token").GET().build()
        http.send(req, HttpResponse.BodyHandlers.ofLines()).body().forEach { line -> if (line.startsWith("event: ")) on(line.removePrefix("event: ").trim()) }
    }

    companion object {
        fun find(opts: Opts): ControlClient? {
            val f = opts.value("control")?.let(::File) ?: System.getenv("TESTLAB_CONTROL")?.let(::File) ?: File(AppDirs.forHost().data, "control.json")
            if (!f.isFile) return null
            val o = runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: return null
            return ControlClient(o.str("api") ?: return null, o.str("token") ?: return null)
        }
    }
}
