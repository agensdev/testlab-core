package no.agens.testlab.services.proxyman

import no.agens.testlab.AppIdentity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class McpChildError(message: String) : RuntimeException(message)

/** An MCP server Testlab talks to: a stdio child ([StdioMcp]) or a remote one over HTTP ([HttpMcp]). */
interface McpClient : AutoCloseable {
    val alive: Boolean
    suspend fun start()
    suspend fun listTools(): List<JsonObject>
    suspend fun callTool(name: String, args: JsonObject): JsonObject
}

/**
 * A minimal MCP client for a stdio server we start ourselves (Proxyman's bundled `mcp-server`):
 * newline-delimited JSON-RPC, initialize, tools/list and tools/call. Its stderr goes to a file:
 * an unread pipe would stall the child.
 */
class StdioMcp(private val command: List<String>, private val stderrLog: File, private val timeoutMs: Long = 30_000) : McpClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private var process: Process? = null
    private val writeLock = Any()

    override val alive: Boolean get() = process?.isAlive == true

    override suspend fun start(): Unit = withContext(Dispatchers.IO) {
        stderrLog.parentFile?.mkdirs()
        val p = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(stderrLog)).start()
        process = p
        Thread({
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val o = runCatching { json.parseToJsonElement(line) as JsonObject }.getOrNull() ?: continue
                    val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: continue
                    pending.remove(id)?.complete(o)
                }
            }
            val gone = McpChildError("${command.first()} exited (${runCatching { p.exitValue() }.getOrNull() ?: "?"}); see ${stderrLog.path}")
            pending.values.forEach { it.completeExceptionally(gone) }
            pending.clear()
        }, "mcp-child-reader").apply { isDaemon = true }.start()
        request("initialize", buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", no.agens.testlab.AppIdentity.SLUG); put("version", no.agens.testlab.AppIdentity.VERSION) }
        })
        notify("notifications/initialized")
        Unit
    }

    private fun send(o: JsonObject) {
        val p = process ?: throw McpChildError("not started")
        synchronized(writeLock) {
            p.outputStream.write((o.toString() + "\n").toByteArray())
            p.outputStream.flush()
        }
    }

    private fun notify(method: String) = send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) })

    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        withContext(Dispatchers.IO) { send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }) }
        val r = try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(id) }
        (r["error"] as? JsonObject)?.let { e -> throw McpChildError((e["message"] as? JsonPrimitive)?.content ?: e.toString()) }
        return r["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    /** The server's tools as raw JSON (name, description, inputSchema). */
    override suspend fun listTools(): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val r = request("tools/list", cursor?.let { c -> buildJsonObject { put("cursor", c) } } ?: JsonObject(emptyMap()))
            out += (r["tools"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            cursor = (r["nextCursor"] as? JsonPrimitive)?.content
        } while (cursor != null)
        return out
    }

    /** tools/call; returns the raw result ({content, isError}). */
    override suspend fun callTool(name: String, args: JsonObject): JsonObject = request("tools/call", buildJsonObject { put("name", name); put("arguments", args) })

    override fun close() {
        process?.let { p -> runCatching { p.outputStream.close() }; p.destroy(); if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
        process = null
    }

    companion object {
        /** The text blocks of a tool result, joined. */
        fun text(result: JsonObject): String = (result["content"] as? JsonArray).orEmpty().mapNotNull { b ->
            (b as? JsonObject)?.takeIf { (it["type"] as? JsonPrimitive)?.content == "text" }?.let { (it["text"] as? JsonPrimitive)?.content }
        }.joinToString("\n")

        fun isError(result: JsonObject): Boolean = (result["isError"] as? JsonPrimitive)?.content == "true"

        /** Proxyman prefixes results with "xxx result:"; the JSON starts at the first brace or bracket. */
        fun parseLoose(text: String): JsonElement? {
            for (ch in listOf('{', '[')) {
                val i = text.indexOf(ch)
                if (i >= 0) runCatching { return Json.parseToJsonElement(text.substring(i)) }
            }
            return null
        }
    }
}
