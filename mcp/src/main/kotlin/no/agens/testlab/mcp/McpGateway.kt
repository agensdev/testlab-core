package no.agens.testlab.mcp

import io.ktor.server.plugins.origin
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import io.ktor.server.response.respondText
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.agens.testlab.AppIdentity
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The MCP endpoint (Streamable HTTP): bound to 127.0.0.1 unless the `mcp.listen` setting says
 * otherwise (a shared phone rig), the SDK's DNS-rebinding protection on with loopback plus the
 * configured `allowedHosts`, and a per-install bearer token on every request, because these
 * tools act on a phone. Each client session gets its own Server with the full tool set.
 */
class McpGateway(
    private val token: () -> String,
    private val instructions: () -> String,
    private val register: Server.() -> Unit,
    /** The control API for host apps on the same port (`/api/v1`), or null. */
    private val api: ControlApi? = null,
    /** Where the discovery file goes while the server runs (control.json), or null. */
    private val discovery: java.io.File? = null,
    /** The bind address (the `mcp.listen` setting). */
    private val listen: () -> String = { LOOPBACK_BIND },
    /** Host names allowed besides loopback (the `mcp.allowedHosts` setting). */
    private val allowedHosts: () -> List<String> = { emptyList() },
    /** The browser pages (`/ui/…`, `testlab open`), signed in by a cookie; null = none. */
    private val ui: UiPages? = null,
) {
    @Volatile private var server: EmbeddedServer<*, *>? = null
    @Volatile var port: Int? = null
        private set

    val running: Boolean get() = server != null

    private val clientCount = MutableStateFlow(0)
    /** MCP sessions currently connected, for the status bar. */
    val clients: StateFlow<Int> = clientCount.asStateFlow()

    /** The host names requests may carry: loopback, the configured ones, and a specific bind address. */
    @Volatile var hosts: Set<String> = LOOPBACK_HOSTS
        private set

    fun start(endpoint: McpEndpoint) {
        stop()
        val bind = listen().trim().ifEmpty { LOOPBACK_BIND }
        hosts = hostsFor(bind, allowedHosts())
        val s = embeddedServer(CIO, host = bind, port = endpoint.port) {
            install(createApplicationPlugin("NtlBearerAuth") {
                onCall { call ->
                    val path = call.request.path()
                    val isApi = path.startsWith("/api/")
                    val isUi = path.startsWith("/ui/")
                    if (!path.startsWith("/mcp") && !isApi && !isUi) return@onCall
                    if (isApi || isUi) {
                        // DNS rebinding: only a loopback (or configured) Host.
                        val hostHeader = call.request.header(HttpHeaders.Host).orEmpty()
                        val host = hostHeader.substringBeforeLast(':').trim('[', ']')
                        if (host.lowercase() !in hosts) {
                            call.respondText(apiError("Host '$host' is not allowed"), ContentType.Application.Json, HttpStatusCode.Forbidden); return@onCall
                        }
                        if (isUi) return@onCall // the page route checks its ticket or cookie
                        // A browser: only our own pages (same origin, on loopback), signed in by their cookie.
                        val origin = call.request.header(HttpHeaders.Origin)
                        // …and only from this computer, whatever Host a client claims.
                        val fromHere = isLoopback(call.request.origin.remoteHost) || call.request.origin.remoteHost.startsWith("127.") || call.request.origin.remoteHost == "0:0:0:0:0:0:0:1"
                        val sameOrigin = origin != null && origin == "http://$hostHeader" && isLocalName(host) && fromHere
                        if (origin != null && !sameOrigin) {
                            call.respondText(apiError("browser origins are not allowed"), ContentType.Application.Json, HttpStatusCode.Forbidden); return@onCall
                        }
                        if (ui != null && ui.session(call) && isLocalName(host) && fromHere) return@onCall
                        if (origin != null) {
                            call.respondText(apiError("this page isn't signed in: open it with `${AppIdentity.SLUG} open`"), ContentType.Application.Json, HttpStatusCode.Unauthorized); return@onCall
                        }
                    }
                    val expected = try { token() } catch (e: Exception) { null }
                    if (expected == null) {
                        call.respondText(if (isApi) apiError("the vault is locked: open ${AppIdentity.NAME} and unlock it") else LOCKED, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        return@onCall
                    }
                    val got = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim().orEmpty()
                    if (!constantTimeEquals(got, expected)) {
                        call.respondText(if (isApi) apiError("missing or wrong bearer token (see control.json in the data folder)") else UNAUTHORIZED, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    }
                }
            })
            mcpStreamableHttp(
                path = "/mcp",
                allowedHosts = hosts.map { if (':' in it) "[$it]" else it },
                // MCP clients send no Origin; a browser page only from this computer, as before sharing.
                allowedOrigins = LOOPBACK_HOSTS.map { "http://" + if (':' in it) "[$it]" else it },
            ) { newServer() }
            api?.let { a -> routing { with(a) { mount() } } }
            ui?.let { u -> routing { with(u) { mountUi() } } }
        }
        s.start(wait = false)
        server = s
        port = endpoint.port
        discovery?.let { f -> runCatching { ControlApi.writeDiscovery(f, endpoint.url, token(), api?.mode ?: "service") } }
    }

    fun newServer(): Server = Server(
        Implementation(name = AppIdentity.SLUG, version = AppIdentity.VERSION, title = AppIdentity.NAME),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                prompts = ServerCapabilities.Prompts(listChanged = false),
                resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
            ),
        ),
        instructionsProvider = instructions,
        block = register,
    ).also { srv ->
        srv.onConnect { clientCount.value++ }
        srv.onClose { clientCount.value = (clientCount.value - 1).coerceAtLeast(0) }
    }

    fun stop() {
        if (server != null) discovery?.delete()
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        server = null
        port = null
    }

    companion object {
        private const val LOCKED =
            """{"jsonrpc":"2.0","id":null,"error":{"code":-32002,"message":"${AppIdentity.NAME}'s vault is locked: open the app and unlock it."}}"""
        private const val UNAUTHORIZED =
            """{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Missing or wrong bearer token. Print the connect command with `${AppIdentity.SLUG} mcp-config`."}}"""

        private fun apiError(message: String) = """{"error":{"message":"${message.replace("\"", "'")}"}}"""

        const val LOOPBACK_BIND = "127.0.0.1"
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

        /** The name the browser pages use (`testlab.localhost`): browsers send *.localhost to this computer, never to DNS. */
        val UI_HOST = "${AppIdentity.SLUG}.localhost"

        /** A Host the browser pages may come in under: loopback, or [UI_HOST]. */
        fun isLocalName(host: String) = isLoopback(host) || host.equals(UI_HOST, ignoreCase = true)

        fun isLoopback(bind: String) = bind.trim().trim('[', ']') in setOf("127.0.0.1", "localhost", "::1")

        /**
         * Loopback plus [allowed]; a specific bind address (a LAN or tailnet IP) is allowed by itself.
         * A wildcard bind (0.0.0.0, ::) without any allowed host is refused: every name the rig is
         * reached by must be listed, or the DNS-rebinding check rejects real clients.
         */
        fun hostsFor(bind: String, allowed: List<String>): Set<String> {
            val b = bind.trim().trim('[', ']').lowercase()
            val extra = allowed.map { it.trim().lowercase().substringBefore('/').trim('[', ']') }.filter { it.isNotEmpty() }.toMutableSet()
            val wildcard = b == "0.0.0.0" || b == "::"
            if (!wildcard && !isLoopback(b)) extra += b
            require(!wildcard || extra.isNotEmpty()) {
                "listening on $bind needs the names clients use in mcp.allowedHosts (`config set`, or --allow-host), e.g. the rig's tailnet name and IP"
            }
            return LOOPBACK_HOSTS + UI_HOST + extra
        }

        fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

        fun constantTimeEquals(a: String, b: String): Boolean =
            b.isNotEmpty() && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
