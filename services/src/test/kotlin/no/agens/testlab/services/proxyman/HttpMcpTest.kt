package no.agens.testlab.services.proxyman

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A Streamable HTTP MCP server like Proxyman's bridge: a session id from initialize, required after; SSE answers. */
class HttpMcpTest {
    private val tmp = Files.createTempDirectory("httpmcp").toFile()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val seen = mutableListOf<String?>()

    init {
        server.createContext("/mcp") { ex ->
            val o = Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()) as JsonObject
            val method = o["method"]!!.jsonPrimitive.content
            val sid = ex.requestHeaders.getFirst("mcp-session-id")
            seen += sid
            fun reply(code: Int, body: String, sse: Boolean = false) {
                ex.responseHeaders.add("content-type", if (sse) "text/event-stream" else "application/json")
                if (method == "initialize") ex.responseHeaders.add("mcp-session-id", "s-1")
                val b = body.toByteArray(); ex.sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong()); if (b.isNotEmpty()) ex.responseBody.write(b); ex.close()
            }
            val id = (o["id"] as? JsonPrimitive)?.content
            when {
                method == "initialize" -> reply(200, """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}""")
                sid != "s-1" -> reply(400, """{"jsonrpc":"2.0","id":"server-error","error":{"code":-32600,"message":"Bad Request: Missing session ID"}}""")
                id == null -> reply(202, "")
                method == "tools/list" -> reply(200, "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"get_flows\"},{\"name\":\"get_version\"}]}}\n\n", sse = true)
                method == "tools/call" -> reply(200, """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"6.0"}]}}""")
                else -> reply(404, "")
            }
        }
        server.start()
    }

    @AfterTest fun tearDown() { server.stop(0); tmp.deleteRecursively() }

    private val url get() = "http://127.0.0.1:${server.address.port}/mcp"

    @Test
    fun talksToABridgeWithSessionsAndSse() = runBlocking {
        val c = HttpMcp(url, File(tmp, "log"))
        c.start()
        assertEquals(listOf("get_flows", "get_version"), c.listTools().map { it["name"]!!.jsonPrimitive.content })
        assertEquals("6.0", StdioMcp.text(c.callTool("get_version", JsonObject(emptyMap()))))
        assertEquals(listOf(null, "s-1", "s-1", "s-1"), seen, "the session id goes back on every call after initialize")
        c.close()
    }

    @Test
    fun anUnreachableBridgeSaysWhere() = runBlocking {
        val c = HttpMcp("http://127.0.0.1:1/mcp", File(tmp, "log"))
        val e = assertFailsWith<McpChildError> { c.start() }
        assertEquals(true, e.message!!.contains("127.0.0.1:1"))
    }
}
