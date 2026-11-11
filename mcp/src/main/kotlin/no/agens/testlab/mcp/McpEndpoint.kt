package no.agens.testlab.mcp

import no.agens.testlab.AppIdentity

/**
 * The MCP endpoint and the snippets Settings offers for copying. [host] is where clients on this
 * computer reach it: 127.0.0.1, unless the server is bound to one other address only. Every
 * request needs the per-install bearer token, because the tools act on a phone (a read-only
 * server could do without a token; this one can not).
 */
data class McpEndpoint(val port: Int = AppIdentity.MCP_DEFAULT_PORT, val host: String = "127.0.0.1") {
    init {
        require(port in 1024..65535) { "MCP port must be 1024–65535, was $port" }
    }

    val url: String get() = urlFor(host)

    /** The URL as a client elsewhere sees it (`host` = the rig's LAN or tailnet name). */
    fun urlFor(host: String): String = "http://${if (':' in host) "[$host]" else host}:$port/mcp"

    companion object {
        /** For a bind address: a wildcard or loopback bind is reached on 127.0.0.1, a specific one on itself. */
        fun forBind(port: Int, bind: String): McpEndpoint {
            val b = bind.trim().trim('[', ']')
            return McpEndpoint(port, if (b.isEmpty() || b == "0.0.0.0" || b == "::" || McpGateway.isLoopback(b)) "127.0.0.1" else b)
        }
    }

    fun claudeCodeCommand(token: String): String =
        "claude mcp add --scope user --transport http ${no.agens.testlab.services.Spaces.mcpName()} $url --header \"Authorization: Bearer $token\""

    /** Claude Desktop only accepts stdio servers, so it starts the app's stdio bridge. */
    fun claudeDesktopJson(launcher: String): String = claudeDesktopJson(listOf(launcher))

    /** [command] is the program and the arguments before `--mcp-stdio` (a JVM with its classpath, for the CLI download). */
    fun claudeDesktopJson(command: List<String>): String {
        val args = (command.drop(1) + "--mcp-stdio").joinToString(",") { jsonString(it) }
        return """{"mcpServers":{"${no.agens.testlab.services.Spaces.mcpName()}":{"command":${jsonString(command.first())},"args":[$args]}}}"""
    }

    fun codexToml(token: String): String =
        "[mcp_servers.${no.agens.testlab.services.Spaces.mcpName().replace('-', '_')}]\nurl = \"$url\"\nhttp_headers = { Authorization = \"Bearer $token\" }"

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
