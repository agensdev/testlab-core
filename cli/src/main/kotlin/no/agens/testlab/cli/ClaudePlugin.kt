package no.agens.testlab.cli

import no.agens.testlab.AppIdentity
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.claude.ClaudeCli
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `claude-plugin`: installs the Claude Code plugin (the phone and reports as terminal panes) from
 * the copy inside this CLI, as a local marketplace, so it needs no access to the repository.
 * `--path` only writes it and prints where. `--if-installed` (what `upgrade` runs) only updates one
 * that is installed already.
 */
internal fun claudePlugin(opts: Opts, config: File? = null): Int {
    val space = no.agens.testlab.services.Spaces.current()
    val data = AppDirs.forHost().data
    val dir = File(data, "claude-plugin")
    // The space's Claude profile (`space claude` remembers it), so an upgrade updates the plugin there.
    val configDir = config ?: opts.value("config-dir")?.let(::File) ?: File(data, "claude-config-dir").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
    // A space's own marketplace name, so two spaces' plugins never update each other.
    val market = AppIdentity.SLUG + no.agens.testlab.services.Spaces.suffix(space)
    // After an upgrade: update it where it was installed, leave everyone else alone.
    val onlyIfInstalled = opts.flag("if-installed")
    if (onlyIfInstalled && !dir.isDirectory) return 0
    val index = resource("index.txt") ?: throw CliError("this build has no Claude Code plugin inside")
    dir.deleteRecursively()
    for (path in index.decodeToString().lines().filter { it.isNotBlank() }) {
        val bytes = resource(path) ?: throw CliError("the plugin's $path is missing from this build")
        File(dir, path).apply { parentFile.mkdirs() }.writeBytes(forSpace(path, bytes.decodeToString(), space)?.toByteArray() ?: bytes)
    }
    if (opts.flag("path")) { println(dir.path); return 0 }
    val claude = ClaudeCli.locate(opts.value("claude"))
        ?: if (onlyIfInstalled) { println("Claude Code plugin: written to ${dir.path} (claude not found to register it)"); return 0 } else throw CliError("Claude Code (claude) not found. Install it, or pass --claude PATH. The plugin is written to ${dir.path}:\n  claude plugin marketplace add ${dir.path}\n  claude plugin install testlab@$market")
    fun run(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(claude.path, *args).redirectErrorStream(true)
            .apply { configDir?.let { environment()["CLAUDE_CONFIG_DIR"] = it.path } }.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "timed out" }
        return p.exitValue() to out.trim()
    }
    // Testlab marketplaces whose folder is gone (moved by `space migrate`, or a deleted space) make
    // Claude Code warn on every start: they go, with their plugin. Ours, registered at another path, is re-added.
    val known = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(run("plugin", "marketplace", "list", "--json").second) as kotlinx.serialization.json.JsonArray }.getOrNull().orEmpty()
        .mapNotNull { e -> (e as? kotlinx.serialization.json.JsonObject)?.let { o -> o.str("name") to o.str("path") } }
        .filter { (n, _) -> n == AppIdentity.SLUG || n.startsWith(AppIdentity.SLUG + "-") }
    var registered = false
    for ((n, path) in known) {
        val stale = path.isNotEmpty() && path.endsWith("claude-plugin") && !File(path, ".claude-plugin/marketplace.json").isFile
        val moved = n == market && path.isNotEmpty() && File(path).absoluteFile.normalize() != dir.absoluteFile.normalize()
        if (stale || moved) {
            run("plugin", "uninstall", "testlab@$n")
            run("plugin", "marketplace", "remove", n)
            println("Claude Code plugin: removed the marketplace $n (${if (stale) "its folder $path is gone" else "it pointed at $path"})")
        } else if (n == market) registered = true
    }
    val (mc, mo) = if (registered) run("plugin", "marketplace", "update", market) else run("plugin", "marketplace", "add", dir.path)
    if (mc != 0) throw CliError("claude plugin marketplace failed:\n$mo")
    val (ic, io) = run("plugin", "install", "testlab@$market")
    val out = if (ic == 0 && !io.contains("already", ignoreCase = true)) io else run("plugin", "update", "testlab@$market").let { (uc, uo) -> if (uc != 0) throw CliError("claude plugin update failed:\n$uo"); uo }
    if (onlyIfInstalled) { println("Claude Code plugin: updated to ${AppIdentity.VERSION} (restart Claude Code, or /mcp → testlab → Reconnect: /reload-plugins leaves the running server on the old version)"); return 0 }
    println(out)
    println(
        """
        |
        |Installed the Testlab plugin for Claude Code (${AppIdentity.VERSION}), from ${dir.path}.
        |Restart Claude Code, then: /testlab-screen (the phone) and /testlab-report [id] (a report).
        |If you added ${no.agens.testlab.services.Spaces.mcpName(space)} with `claude mcp add` before, `claude mcp remove ${no.agens.testlab.services.Spaces.mcpName(space)}`, so the tools aren't there twice.
        |Using CLAUDE_CONFIG_DIR profiles? Run this with the same one set as the Claude Code you use.
        """.trimMargin(),
    )
    return 0
}

/**
 * The plugin's files for [space]: the version is the CLI's (so Claude Code sees an upgrade as an
 * update); outside the default space the MCP server starts in the space, the panes read its
 * control.json and the marketplace has the space's name. Null: the file as it is.
 */
internal fun forSpace(path: String, text: String, space: String): String? {
    val other = space != no.agens.testlab.services.Spaces.DEFAULT
    return when (path) {
        "claude-plugin/.claude-plugin/plugin.json" -> text.replace(Regex("\"version\":\\s*\"[^\"]*\""), "\"version\": \"${AppIdentity.VERSION}\"")
        "claude-plugin/.mcp.json" -> if (other) text.replace("\"args\": [\"--mcp-stdio\"]", "\"args\": [\"--space\", \"$space\", \"--mcp-stdio\"]").replace("\"env\": {}", "\"env\": { \"TESTLAB_SPACE\": \"$space\" }") else null
        "claude-plugin/hooks/register.js" -> if (other) text.replace("const SPACE = ''", "const SPACE = '$space'") else null
        ".claude-plugin/marketplace.json" -> if (other) text.replaceFirst("\"name\": \"testlab\"", "\"name\": \"${AppIdentity.SLUG}-$space\"") else null
        else -> null
    }
}

private fun kotlinx.serialization.json.JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

private fun resource(path: String): ByteArray? = Opts::class.java.getResourceAsStream("/claude-plugin/$path")?.use { it.readBytes() }
