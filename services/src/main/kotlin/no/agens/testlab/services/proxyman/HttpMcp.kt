package no.agens.testlab.services.proxyman

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * A remote MCP server over Streamable HTTP (a Proxyman on another machine, behind an MCP bridge):
 * POST JSON-RPC, the `Mcp-Session-Id` the server hands out on initialize sent back on every call,
 * answers as JSON or as an SSE stream. No Node adapter needed. Problems go to [log].
 */
class HttpMcp(private val url: String, private val log: File, private val timeoutMs: Long = 30_000) : McpClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicInteger(1)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    @Volatile private var session: String? = null
    @Volatile private var up = false

    override val alive: Boolean get() = up

    override suspend fun start() {
        request("initialize", buildJsonObject {
            put("protocolVersion", "2025-06-18")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", no.agens.testlab.AppIdentity.SLUG); put("version", no.agens.testlab.AppIdentity.VERSION) }
        })
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") })
        up = true
    }

    private suspend fun post(body: JsonObject): HttpResponse<String> = withContext(Dispatchers.IO) {
        val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMillis(timeoutMs))
            .header("content-type", "application/json").header("accept", "application/json, text/event-stream")
            .header("mcp-protocol-version", "2025-06-18")
        session?.let { b.header("mcp-session-id", it) }
        val r = try { http.send(b.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString()) }
        catch (e: Exception) { up = false; note("$url: ${e.message ?: e}"); throw McpChildError("$url did not answer (${e.message ?: e.javaClass.simpleName})") }
        r.headers().firstValue("mcp-session-id").ifPresent { session = it }
        r
    }

    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val id = ids.getAndIncrement()
        val r = post(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
        if (r.statusCode() == 404 && session != null) { up = false; session = null; throw McpChildError("$url forgot the session (restarted?)") }
        if (r.statusCode() !in 200..299) { note("$method: HTTP ${r.statusCode()} ${r.body().take(300)}"); throw McpChildError("$url: HTTP ${r.statusCode()} ${r.body().take(200)}") }
        val msg = message(r, id) ?: throw McpChildError("$url: no answer to $method")
        (msg["error"] as? JsonObject)?.let { e -> throw McpChildError((e["message"] as? JsonPrimitive)?.content ?: e.toString()) }
        return msg["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    /** The JSON-RPC answer with [id]: the body itself, or the matching `data:` event of an SSE stream. */
    private fun message(r: HttpResponse<String>, id: Int): JsonObject? {
        val body = r.body()
        val candidates = if (r.headers().firstValue("content-type").orElse("").startsWith("text/event-stream") || body.trimStart().startsWith("event:") || body.trimStart().startsWith("data:"))
            body.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
        else listOf(body)
        return candidates.mapNotNull { runCatching { json.parseToJsonElement(it) as JsonObject }.getOrNull() }
            .firstOrNull { (it["id"] as? JsonPrimitive)?.intOrNull == id }
    }

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

    override suspend fun callTool(name: String, args: JsonObject): JsonObject = request("tools/call", buildJsonObject { put("name", name); put("arguments", args) })

    override fun close() {
        // Ends the session on the server; a server that doesn't support DELETE just ignores it.
        session?.let { s -> runCatching { http.send(HttpRequest.newBuilder(URI(url)).header("mcp-session-id", s).timeout(Duration.ofSeconds(3)).DELETE().build(), HttpResponse.BodyHandlers.discarding()) } }
        session = null; up = false
    }

    private fun note(line: String) { runCatching { log.parentFile?.mkdirs(); log.appendText("${java.time.LocalDateTime.now().withNano(0)} $line\n") } }
}
