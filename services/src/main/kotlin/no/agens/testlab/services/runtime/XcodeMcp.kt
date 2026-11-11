package no.agens.testlab.services.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.AppIdentity
import no.agens.testlab.services.proxyman.McpClient
import no.agens.testlab.services.proxyman.StdioMcp
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Xcode's own MCP server (Xcode 27, `xcrun mcpbridge`): Testlab is its client, for what only Xcode
 * has, such as the App Store and TestFlight crash and performance reports. With headless mode on
 * (`sudo xcrun mcp-server enable`) it needs no Xcode window. Xcode asks once whether Testlab may use
 * it; [explain] turns a call held up by that into the command that answers it.
 */
class XcodeMcp(
    private val log: File,
    private val xcrun: String = "/usr/bin/xcrun",
    private val connect: () -> McpClient = { StdioMcp(listOf(xcrun, "mcpbridge"), log, 240_000) },
    private val exec: (List<String>, Long) -> Pair<Int, String> = ::run,
) {
    private val lock = Mutex()
    private var client: McpClient? = null

    data class Status(val xcode: String?, val headless: String?, val pending: List<String>)

    /** Calls [tool]; its structured result, else its text as JSON (or as `{text}`). */
    suspend fun call(tool: String, args: JsonObject): JsonObject {
        val c = try {
            lock.withLock { client?.takeIf { it.alive } ?: connect().also { it.start(); client = it } }
        } catch (e: Exception) {
            lock.withLock { runCatching { client?.close() }; client = null }
            throw IllegalStateException(explain(e.message ?: e::class.simpleName.orEmpty()))
        }
        val r = try { c.callTool(tool, args) } catch (e: Exception) {
            if (!c.alive) lock.withLock { client = null }
            throw IllegalStateException(explain(e.message ?: e::class.simpleName.orEmpty()))
        }
        val text = StdioMcp.text(r)
        if (StdioMcp.isError(r)) throw IllegalStateException("Xcode ($tool): " + text.take(800).ifBlank { "failed" })
        return (r["structuredContent"] as? JsonObject) ?: (StdioMcp.parseLoose(text) as? JsonObject) ?: JsonObject(mapOf("text" to JsonPrimitive(text)))
    }

    /** Xcode's version, headless mode's state, and requests waiting for an approval. */
    fun status(): Status {
        val version = exec(listOf(xcrun, "xcodebuild", "-version"), 30_000).takeIf { it.first == 0 }?.second?.lines()?.firstOrNull()?.trim()
        val st = exec(listOf(xcrun, "mcp-server", "status", "--format", "json"), 30_000)
        val headless = if (st.first == 0) st.second.trim() else null
        return Status(version, headless, headless?.let { pendingIds(it) }.orEmpty())
    }

    /** What to do when a call doesn't get through: Xcode too old, headless mode off, or an approval waiting. */
    fun explain(problem: String): String {
        val s = runCatching { status() }.getOrNull()
        val major = s?.xcode?.let { Regex("Xcode (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return when {
            no.agens.testlab.device.HostOs.current() != no.agens.testlab.device.HostOs.MAC -> "this needs a Mac with Xcode 27 (Xcode's MCP server): $problem"
            s?.xcode == null -> "Xcode isn't installed (or `xcode-select` points elsewhere): $problem"
            major != null && major < 27 -> "${s.xcode} has no MCP server for this: it needs Xcode 27 or newer"
            s.pending.isNotEmpty() -> "Xcode is waiting for your approval to let ${AppIdentity.NAME} use it: run `${AppIdentity.SLUG} xcode approve` in a terminal (it asks for your Mac password), then try again"
            s.headless == null -> "Xcode's MCP server didn't answer ($problem). Without an Xcode window it needs headless mode: `${AppIdentity.SLUG} xcode enable`"
            else -> "Xcode's MCP server: $problem (`${AppIdentity.SLUG} xcode status` shows its state; its log: ${log.path})"
        }
    }

    fun close() { runCatching { client?.close() }; client = null }

    companion object {
        /** The ids of requests waiting for an approval in `mcp-server status --format json` (pending/requests lists). */
        fun pendingIds(statusJson: String): List<String> {
            val root = runCatching { Json.parseToJsonElement(statusJson) }.getOrNull() ?: return emptyList()
            val out = mutableListOf<String>()
            fun walk(e: JsonElement, inPending: Boolean) {
                when (e) {
                    is JsonObject -> {
                        if (inPending) (e["id"] as? JsonPrimitive)?.content?.let { out += it }
                        e.forEach { (k, v) -> walk(v, inPending || Regex("(?i)pending|request").containsMatchIn(k)) }
                    }
                    is JsonArray -> e.forEach { walk(it, inPending) }
                    is JsonPrimitive -> Unit
                }
            }
            walk(root, false)
            return out.distinct()
        }

        private fun run(cmd: List<String>, timeoutMs: Long): Pair<Int, String> = runCatching {
            val out = File.createTempFile("tl-xcode", ".out")
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return@runCatching -1 to "timed out" }
                p.exitValue() to out.readText()
            } finally { out.delete() }
        }.getOrElse { -1 to (it.message ?: "failed") }
    }
}
