package no.agens.testlab.mcp

import no.agens.testlab.AppIdentity

import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * `--mcp-stdio`, for Claude Desktop, which only starts stdio servers. When the app is running,
 * newline-delimited JSON-RPC from stdin is forwarded to its HTTP endpoint (one process owns the
 * phone); otherwise the tools are served in this process. Nothing but protocol goes to stdout.
 */
object StdioBridge {
    fun appIsListening(endpoint: McpEndpoint): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", endpoint.port), 500); true }
    } catch (e: Exception) {
        false
    }

    /** Forwards stdin to the running app until stdin closes. */
    fun forward(endpoint: McpEndpoint, token: String, input: InputStream = System.`in`, output: OutputStream = System.out, log: PrintStream = System.err) {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val out = PrintStream(output, true, Charsets.UTF_8)
        var session: String? = null
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val req = HttpRequest.newBuilder(URI.create(endpoint.url))
                .timeout(Duration.ofMinutes(30)) // a plan can run for minutes
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer $token")
                .apply { session?.let { header("Mcp-Session-Id", it) } }
                .POST(HttpRequest.BodyPublishers.ofString(line))
                .build()
            val resp = try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                log.println("[${endpoint.url}] ${e.message}")
                errorFor(line, "${AppIdentity.NAME} is not reachable at ${endpoint.url}: ${e.message}")?.let(out::println)
                continue
            }
            resp.headers().firstValue("Mcp-Session-Id").ifPresent { session = it }
            val body = resp.body().orEmpty()
            when {
                resp.statusCode() == 202 || body.isBlank() -> {}
                resp.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream") ->
                    body.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.filter { it.isNotEmpty() }.forEach(out::println)
                else -> out.println(body.replace("\n", ""))
            }
        }
    }

    /** Serves the tools in this process over stdin/stdout until the client disconnects. */
    fun serveHere(mcp: TestlabMcp, input: InputStream = System.`in`, output: OutputStream = System.out) = runBlocking {
        val server = mcp.gateway().newServer()
        // The session ends when stdin reaches EOF (the client quit), and its transport is then null.
        // Polling that cannot miss an EOF that arrives while createSession is still running (the
        // SDK registers the session after connecting, so its session list can keep a dead one).
        val session = server.createSession(StdioServerTransport(input.asSource().buffered(), output.asSink().buffered()) {})
        while (session.transport != null) kotlinx.coroutines.delay(100)
    }

    /**
     * kotlin-logging (inside the MCP SDK) prints a startup line to stdout, which would corrupt a
     * stdio session. Call before anything touches the SDK.
     */
    fun quietStdout() {
        System.setProperty("kotlin-logging.logStartupMessage", "false")
    }

    /** A JSON-RPC error for a request line (null for notifications, which get no answer). */
    private fun errorFor(line: String, message: String): String? {
        val id = Regex("\"id\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+)").find(line)?.groupValues?.get(1) ?: return null
        val msg = message.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"$msg"}}"""
    }
}
