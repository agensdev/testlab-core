package no.agens.testlab.cli

import kotlinx.coroutines.runBlocking
import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.Spaces
import no.agens.testlab.services.service.BackgroundService
import no.agens.testlab.services.update.Upgrader
import java.io.File

/** A background service that was running before the upgrade: its space, and whether it is Linux's `--system` one. */
private data class RunningService(val space: String, val system: Boolean) {
    val name: String get() = (if (system) "system " else "") + "service" + if (space == Spaces.DEFAULT) "" else " ($space)"
}

/** [block] with the space set to [space]: the service's unit, plist and task names follow it. */
private fun <T> inSpace(space: String, block: () -> T): T {
    val before = Spaces.override
    Spaces.override = space
    try { return block() } finally { Spaces.override = before }
}

/**
 * `upgrade [--ci | --from PATH] [--rollback]`: the newest release (or CI build, or downloaded files)
 * over this install. Every background service that was running (each space's, and Linux's --system
 * one) is stopped around the swap and started again with the new version, which is then checked.
 */
internal fun upgrade(opts: Opts): Int = runBlocking {
    val up = Upgrader(runtime)
    val svc = BackgroundService()
    val linux = HostOs.current() == HostOs.LINUX
    val running = Spaces.list().flatMap { space ->
        listOfNotNull(false, if (linux) true else null).mapNotNull { system ->
            RunningService(space, system).takeIf { runCatching { inSpace(space) { svc.status(system).state == BackgroundService.State.RUNNING } }.getOrDefault(false) }
        }
    }
    val home = up.cliHome() ?: File(System.getProperty("user.home"), ".local/opt/${AppIdentity.SLUG}")
    val launcher = File(home, "bin/${AppIdentity.SLUG}")

    fun stopServices() = running.forEach { r ->
        val res = inSpace(r.space) { svc.stop(r.system) }
        println(if (res.code == 0) "${r.name}: stopped" else "${r.name}: could not stop it (${res.out.trim().take(80)}); it keeps the old version until restarted")
    }

    /** Started through the new launcher (this JVM's classes come from the folder just replaced), then its version checked. */
    fun restartServices(expected: String?) = running.forEach { r ->
        val cmd = listOf(launcher.path) + Spaces.args(r.space) + listOf("service", "start") + if (r.system) listOf("--system") else emptyList()
        val code = runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().let { p -> p.inputStream.readAllBytes(); p.waitFor() } }.getOrDefault(1)
        val again = "${AppIdentity.SLUG} ${(Spaces.args(r.space) + listOf("service", "start") + if (r.system) listOf("--system") else emptyList()).joinToString(" ")}"
        if (code != 0) { println("${r.name}: did not start again: run ${if (r.system) "sudo " else ""}$again"); return@forEach }
        val version = if (r.system) null else serverVersion(r.space)
        println("${r.name}: started again" + when {
            r.system -> ""
            version == null -> " (not answering yet: ${AppIdentity.SLUG} service status)"
            expected != null && version != expected -> ", but it reports $version, not $expected: ${AppIdentity.SLUG} service status"
            else -> ", running $version"
        })
    }

    if (opts.flag("rollback")) {
        stopServices()
        val done = try { up.rollback() } catch (e: Upgrader.Failed) { throw CliError(e.message ?: "rollback failed") }
        done.forEach { println("rolled back: $it") }
        restartServices(null)
        return@runBlocking 0
    }

    val progress = ConsoleProgress()
    val build = try {
        when {
            opts.value("from") != null -> up.fromPath(File(opts.value("from")!!).absoluteFile)
            opts.flag("ci") -> up.latestCi(progress::show)
            else -> up.latestRelease(progress = progress::show) ?: run {
                println("No release newer than ${AppIdentity.VERSION}. For the newest package run: ${AppIdentity.SLUG} upgrade --ci")
                return@runBlocking 0
            }
        }
    } catch (e: Upgrader.Failed) {
        throw CliError(e.message ?: "no build")
    } catch (e: no.agens.testlab.services.github.GitHubError) {
        val owner = AppIdentity.REPO.substringBefore('/')
        throw CliError(
            "GitHub: ${e.message}\nUpgrades read ${AppIdentity.REPO} with github.token@$owner, else github.token. If that token is another account's " +
                "(a work account), add one that can read the repo: ${AppIdentity.SLUG} secrets set github.token@$owner\nOr download the build yourself: ${AppIdentity.SLUG} upgrade --from ~/Downloads/dist-macos",
        )
    } finally {
        progress.done()
    }
    println("${AppIdentity.NAME} ${AppIdentity.VERSION} → ${build.version ?: "?"} from ${build.label}")

    stopServices()
    try {
        val v = up.installCli(build.cli, home)
        println("CLI: $v at ${home.path}")
        if (File(no.agens.testlab.services.AppDirs.forHost().data, "claude-plugin").isDirectory) println("Claude Code plugin: updated with it (restart Claude Code, or /mcp → testlab → Reconnect: /reload-plugins leaves the running server on the old version)")
        if (System.getProperty("testlab.home") == null) println("  link it into your PATH: ln -sf ${File(home, "bin/${AppIdentity.SLUG}").path} ~/.local/bin/${AppIdentity.SLUG}")
    } catch (e: Upgrader.Failed) {
        restartServices(null)
        throw CliError("upgrade stopped: ${e.message}")
    }
    restartServices(build.version)
    if (running.isEmpty()) println("No background service was running; ${AppIdentity.SLUG} service status shows whether one is installed.")
    println("Done. The old version is kept: ${AppIdentity.SLUG} upgrade --rollback puts it back. Start a new chat in Claude Desktop for the new tools.")
    0
}

/** The version the server of [space] reports in its control.json, waiting up to 30 s for it to come up. */
private fun serverVersion(space: String): String? {
    val root = AppDirs.root()
    val data = if (space == Spaces.DEFAULT) root.data else File(root.data, "spaces/$space")
    val f = File(data, "control.json")
    val since = System.currentTimeMillis()
    while (System.currentTimeMillis() - since < 30_000) {
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonObject }.getOrNull()
        val pid = (o?.get("pid") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
        val alive = pid?.let { ProcessHandle.of(it).map { h -> h.isAlive }.orElse(false) } ?: false
        if (alive && f.lastModified() >= since - 1_000) return (o!!["version"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        Thread.sleep(500)
    }
    return null
}

/** One updating line for download progress on a terminal. */
private class ConsoleProgress {
    private var last = ""
    fun show(s: String) {
        if (s == last) return
        last = s
        if (System.console() != null) print("\r$s   ") else if (s.endsWith("0 %")) println(s)
    }
    fun done() { if (last.isNotEmpty() && System.console() != null) println() }
}
