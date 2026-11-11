package no.agens.testlab.services.proxyman

import no.agens.testlab.AppIdentity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import no.agens.testlab.device.HostOs
import no.agens.testlab.services.i18n.tr
import no.agens.testlab.services.runtime.LabRuntime
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Proxyman is off in Settings, not installed, or not running. */
class ProxymanUnavailable(message: String) : RuntimeException(message)

/** One captured request, as Proxyman's get_flows lists it. */
data class Flow(val id: String, val method: String, val status: String, val host: String, val path: String)

/** [from]: the device addresses the export was kept to (null: every device's traffic). */
data class FlowExport(val file: File, val count: Int, val hosts: List<String>, val rows: List<Flow>, val from: Set<String>? = null)

/** Where a phone's traffic goes now. */
/** [proxy] is what the phone uses now; [wanted] what Testlab set (null: Testlab didn't route it). */
data class ProxyState(
    val serial: String, val proxy: String?, val ours: Boolean, val reverse: Boolean, val wanted: String? = null,
    /** After `off`: what the phone used before, and who had set it. */
    val was: String? = null, val wasBy: String? = null,
    /** What else the caller should know (an iOS Simulator: the whole Mac is routed; the CA). */
    val note: String? = null,
)

data class Health(val proxyReachable: Boolean, val viaProxyStatus: String?, val viaProxySeconds: Double?, val mcp: String, val devices: List<ProxyState>) {
    val healthy: Boolean get() = proxyReachable && viaProxyStatus != null && viaProxyStatus != "000"
}

/**
 * The user's own Proxyman (macOS, Windows or Linux) as the phones' HTTP proxy, plus its bundled
 * MCP server mirrored as `proxyman_*` tools, like the lab does. Everything is opt-in
 * (the Proxyman settings). A phone is routed with `adb reverse` + 127.0.0.1 over USB and on
 * emulators, or with this computer's LAN address over Wi-Fi/IP. Routing is recorded, kept pinned
 * while it is on, and undone on disconnect, on exit, and at the next start after a crash: a
 * phone left pointing at a proxy that is gone has no network.
 */
class Proxyman(private val rt: LabRuntime) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val recordFile get() = File(rt.dirs.data, "proxied.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var child: McpClient? = null
    private var pinJob: Job? = null

    private val cfg get() = rt.settings.current.proxyman
    val enabled: Boolean get() = cfg.enabled

    private val routedFlow = MutableStateFlow(records().keys)
    val routed: StateFlow<Set<String>> = routedFlow.asStateFlow()

    @Volatile private var toolCache: List<JsonObject> = emptyList()
    /** Proxyman's tools as last listed (empty until connected). */
    val tools: List<JsonObject> get() = toolCache

    /** Whether this Proxyman's [tool] takes [param] (its input schema), e.g. graphql_query_name on newer versions. */
    suspend fun takes(tool: String, param: String): Boolean {
        if (toolCache.isEmpty()) refreshTools()
        val props = (toolCache.firstOrNull { (it["name"] as? JsonPrimitive)?.content == tool }?.get("inputSchema") as? JsonObject)?.get("properties") as? JsonObject
        return props?.containsKey(param) == true
    }

    // --- the MCP server ---

    fun mcpServerCandidates(os: HostOs = HostOs.current(), home: File = File(System.getProperty("user.home")), env: Map<String, String> = System.getenv()): List<File> = buildList {
        cfg.mcpServer?.takeIf { it.isNotBlank() && remoteUrl() == null }?.let { add(File(it)) }
        when (os) {
            HostOs.MAC -> {
                add(File("/Applications/Proxyman.app/Contents/MacOS/mcp-server"))
                add(File(home, "Applications/Proxyman.app/Contents/MacOS/mcp-server"))
            }
            HostOs.WINDOWS -> {
                env["APPDATA"]?.let { add(File(it, "Proxyman/bin/mcp-server.exe")) }
                env["LOCALAPPDATA"]?.let { add(File(it, "Programs/Proxyman/resources/bin/mcp-server.exe")) }
            }
            HostOs.LINUX -> {
                add(File(home, ".config/Proxyman/bin/mcp-server"))
                add(File("/opt/proxyman/resources/bin/mcp-server"))
            }
        }
    }

    fun mcpServer(): File? = mcpServerCandidates().firstOrNull { it.isFile }

    /** `mcpServer` as an http(s) URL: a Proxyman's MCP bridge on another machine, spoken to directly. */
    fun remoteUrl(): String? = cfg.mcpServer?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private suspend fun client(): McpClient = mutex.withLock {
        if (!enabled) throw ProxymanUnavailable("Proxyman is off: turn it on in the settings (`${no.agens.testlab.AppIdentity.SLUG} open settings`, Proxyman)")
        child?.takeIf { it.alive }?.let { c ->
            // A slow first start (an adapter fetching itself with npx) can miss the first listing: ask again.
            if (toolCache.isEmpty()) toolCache = runCatching { c.listTools() }.getOrDefault(emptyList())
            return c
        }
        val log = File(rt.dirs.data, "logs/proxyman-mcp.log")
        val c: McpClient = remoteUrl()?.let { HttpMcp(it, log) } ?: run {
            val exe = mcpServer() ?: throw ProxymanUnavailable("Proxyman's mcp-server was not found (looked in ${mcpServerCandidates().joinToString { it.path }}): install Proxyman, or set its path (or an http:// URL of a remote one's MCP bridge) in the Proxyman settings (`testlab open settings`)")
            StdioMcp(listOf(exe.path), log)
        }
        try { c.start() } catch (e: Exception) { c.close(); throw ProxymanUnavailable("Proxyman's MCP server did not start: ${e.message}. Is Proxyman running${remoteUrl()?.let { " and its bridge reachable at $it" } ?: ""}?") }
        child = c
        toolCache = runCatching { c.listTools() }
            .onFailure { e -> runCatching { File(rt.dirs.data, "logs/proxyman-mcp.log").appendText("tools/list failed: ${e.message}\n") } }
            .getOrDefault(emptyList())
        c
    }

    /** Lists Proxyman's tools again (for new MCP sessions); empty when Proxyman is off or absent. */
    suspend fun refreshTools(): List<JsonObject> = if (!enabled) emptyList<JsonObject>().also { toolCache = it } else runCatching { client(); toolCache }.getOrDefault(emptyList())

    @Volatile private var osProbed: String? = null
    @Volatile private var probedOs: HostOs? = null

    /**
     * The OS Proxyman runs on: this computer's for a local mcp-server; for a remote bridge, read from
     * the CA path in get_certificate_status (once per URL). Null when it can't be told.
     */
    suspend fun proxymanOs(): HostOs? {
        val url = remoteUrl() ?: return HostOs.current()
        if (osProbed != url) {
            probedOs = runCatching { osFromText(StdioMcp.text(client().callTool("get_certificate_status", JsonObject(emptyMap())))) }.getOrNull()
            osProbed = url
        }
        return probedOs
    }

    /** The rules Testlab makes: session tags, the change log, verified cleanup. */
    val rules = ProxyRules(rt, this)

    /** A Map Local rule that matched but didn't serve its body this session (the check after creating one). */
    @Volatile var mapLocalFailed: String? = null
        private set

    /** Calls one of Proxyman's own tools (unprefixed name), with the lab's method=ANY fix and its warnings. */
    suspend fun call(name: String, args: JsonObject): JsonObject {
        // Scripting rules work on every Proxyman (Linux 3.16.1 and 26.x checked) once the method isn't ANY,
        // which fixAnyMethod sees to: a rule with method ANY was what "matched but changed nothing".
        val (fixedArgs, fixed) = fixAnyMethod(name, rules.tagged(name, tidyArgs(name, args)))
        if (name in MAP_LOCAL_TOOLS) logSent(name, fixedArgs)
        val r = try { client().callTool(name, fixedArgs) } catch (e: McpChildError) {
            mutex.withLock { child?.close(); child = null }
            throw ProxymanUnavailable("Proxyman's MCP server stopped answering (${e.message}). Is Proxyman still running?")
        }
        val failed = (r["isError"] as? JsonPrimitive)?.content == "true"
        if (!failed) runCatching { rules.log(name, fixedArgs) }
        val notes = buildList {
            if (fixed) add(ANY_NOTE)
            ProxyRules.ampersandWarning(name, fixedArgs)?.let(::add)
            if (name in MAP_LOCAL_TOOLS && args.containsKey("response_body") && (args["response_body"] as? JsonPrimitive)?.content.isNullOrBlank()) {
                add("\n\nWARNING (${no.agens.testlab.AppIdentity.NAME}): response_body is empty, so every matching request gets an empty (0-byte) response; apps usually show an error page for that.")
            }
            if (isRuleChange(name) && (r["isError"] as? JsonPrimitive)?.content != "true") sharedNote()?.let(::add)
            if (name in MAP_LOCAL_TOOLS && (r["isError"] as? JsonPrimitive)?.content != "true") checkServed(fixedArgs)?.let(::add)
        }
        if (notes.isEmpty()) return r
        val content = (r["content"] as? JsonArray).orEmpty().toMutableList()
        val i = content.indexOfFirst { (it as? JsonObject)?.get("type")?.let { t -> (t as JsonPrimitive).content } == "text" }
        val text = notes.joinToString("")
        if (i >= 0) {
            val b = content[i] as JsonObject
            content[i] = JsonObject(b + ("text" to JsonPrimitive(((b["text"] as? JsonPrimitive)?.content ?: "") + text)))
        } else content += JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text.trimStart())))
        return JsonObject(r + ("content" to JsonArray(content)))
    }

    /**
     * The arguments as Proxyman's schema takes them: a response_body given as JSON (object or array) is
     * sent as its text, and arguments its schema doesn't have (it allows no others) are left out.
     */
    private fun tidyArgs(name: String, args: JsonObject): JsonObject {
        var a = args
        (a["response_body"] as? JsonElement)?.takeIf { it !is JsonPrimitive && it !is kotlinx.serialization.json.JsonNull }?.let { a = JsonObject(a + ("response_body" to JsonPrimitive(it.toString()))) }
        val props = (toolCache.firstOrNull { (it["name"] as? JsonPrimitive)?.content == name }?.get("inputSchema") as? JsonObject)?.get("properties") as? JsonObject
        if (props != null) a = JsonObject(a.filterKeys { it in props })
        return a
    }

    /** What went to Proxyman for a Map Local rule, without the body itself: its size and hash say whether it arrived. */
    private fun logSent(name: String, args: JsonObject) = runCatching {
        val body = (args["response_body"] as? JsonPrimitive)?.content
        val sha = body?.let { b -> java.security.MessageDigest.getInstance("SHA-256").digest(b.toByteArray()).joinToString("") { "%02x".format(it) }.take(16) }
        val shown = JsonObject(args.mapValues { (k, v) -> if (k == "response_body") JsonPrimitive("<${body?.toByteArray()?.size ?: 0} bytes, sha256 $sha>") else v })
        File(rt.dirs.data, "logs/proxyman-mcp.log").apply { parentFile?.mkdirs() }.appendText("${java.time.Instant.now()} $name ${rt.redactor.apply(shown.toString())}\n")
    }

    /**
     * Asks for a concrete Map Local URL through the proxy and compares the answer with response_body:
     * Proxyman can list a rule, and even report it as matched, while it serves the real response or
     * nothing. Only GET/HEAD rules on a URL without wildcards are checked; the request goes through
     * this computer's own connection to the proxy, trusting any certificate for this check only.
     */
    private suspend fun checkServed(args: JsonObject): String? = withContext(Dispatchers.IO) {
        fun str(k: String) = (args[k] as? JsonPrimitive)?.content
        val url = str("url")?.trim() ?: return@withContext null
        val method = str("method")?.uppercase() ?: "GET"
        if (method !in setOf("GET", "HEAD") || "*" in url || str("use_regex") == "true" || !url.startsWith("http")) return@withContext null
        val want = str("response_body") ?: if (args.containsKey("url") && str("id") == null) "{}" else return@withContext null
        val host = cfg.host?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
        delay(300) // the rule takes effect a moment after the call returns
        val got = runCatching { fetchVia(host, cfg.port, url, method) }.getOrElse { e -> return@withContext "\n\nNOTE (${no.agens.testlab.AppIdentity.NAME}): couldn't check that the rule is served (${e.message?.take(120)})." }
        val (status, body) = got
        if (method == "HEAD" || body.trim() == want.trim()) {
            mapLocalFailed = null
            "\n\nverified (${no.agens.testlab.AppIdentity.NAME}): a request through the proxy got the rule's response ($status, ${body.toByteArray().size} bytes)."
        } else {
            val what = if (body.isEmpty()) "an empty body" else "${body.toByteArray().size} bytes that are not response_body (the real server's answer?)"
            mapLocalFailed = "got $what"
            "\n\nMAP LOCAL NOT SERVED (${no.agens.testlab.AppIdentity.NAME}): a request through the proxy to this URL got $status with $what, " +
                "though response_body is ${want.toByteArray().size} bytes. The rule exists but doesn't work: delete it (proxyman_delete_rule), " +
                "and try once more with a short name without spaces. If that fails too, this Proxyman can't mock this request."
        }
    }

    /** [url] fetched through this Proxyman (any certificate trusted, for checks only): status and body. */
    fun fetchThrough(url: String, method: String = "GET"): Pair<Int, String> =
        fetchVia(cfg.host?.takeIf { it.isNotBlank() } ?: "127.0.0.1", cfg.port, url, method)

    private fun fetchVia(host: String, port: Int, url: String, method: String): Pair<Int, String> {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
        val client = java.net.http.HttpClient.newBuilder().proxy(java.net.ProxySelector.of(InetSocketAddress(host, port)))
            .sslContext(ssl).version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(5)).build()
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofSeconds(10))
            .method(method, java.net.http.HttpRequest.BodyPublishers.noBody()).header("Cache-Control", "no-cache").build()
        val r = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
        return r.statusCode() to r.body()
    }

    /** Where Proxyman is and who else it serves, when it isn't this computer's own; null for a local one. */
    fun sharedWith(): String? {
        val url = remoteUrl() ?: return null
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        val ours = records().values.map { it.serial }
        return "Proxyman runs on another machine ($host) and may be shared: its rules apply to every phone whose traffic goes through it, " +
            "not only the ${if (ours.isEmpty()) "phones" else "phone(s) ${ours.joinToString()}"} this ${no.agens.testlab.AppIdentity.NAME} routes there"
    }

    private fun sharedNote(): String? = sharedWith()?.let { "\n\nNOTE (${no.agens.testlab.AppIdentity.NAME}): $it. Keep the URL pattern narrow, and delete the rule (proxyman_delete_rule) as soon as the test is done." }

    /**
     * The captured flows as flows.har in [dir]. With [from] (device addresses, see [deviceAddress]) only
     * those devices' requests: on a shared Proxyman the other phones' traffic stays out of the report.
     */
    suspend fun exportFlows(dir: File, limit: Int = 500, from: Set<String>? = null): FlowExport? {
        val listed = StdioMcp.parseLoose(StdioMcp.text(call("get_flows", buildJsonObject { put("limit", limit) })))
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
        // A flow without an address (an older Proxyman) can't be told apart: it stays.
        fun JsonObject.mine() = from.isNullOrEmpty() || (s("remoteDeviceIP").ifEmpty { s("clientIpAddress") }).let { it.isEmpty() || it in from }
        val flows = ((listed as? JsonObject)?.get("flows") as? JsonArray ?: listed as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.mine() }
        val rows = flows.mapNotNull { f -> f.s("id").ifEmpty { null }?.let { Flow(it, f.s("method"), f.s("statusCode"), f.s("host"), f.s("path").take(120)) } }
        if (rows.isEmpty()) return null
        val har = File(dir, "flows.har")
        call("export_flows", buildJsonObject { put("format", "har"); put("save_path", har.absolutePath); put("flow_ids", buildJsonArray { rows.forEach { add(JsonPrimitive(it.id)) } }) })
        if (!har.isFile || har.length() == 0L) return null
        // Tokens travel in captured traffic (Authorization headers, a JWT in a websocket URL's query): never in a report.
        runCatching { har.writeText(redactTokens(har.readText())) }
        return FlowExport(har, rows.size, rows.map { it.host }.filter { it.isNotEmpty() }.distinct().sorted(), rows, from?.takeIf { it.isNotEmpty() })
    }

    /** Deletes every Proxyman rule (and by default the captured flows): the cleanup after mocking. */
    suspend fun reset(clearFlows: Boolean = true): String {
        client() // off or absent: say so, rather than a list of per-rule warnings
        var deleted = 0
        val warnings = mutableListOf<String>()
        fun idOf(o: JsonObject) = listOf("id", "ruleId", "rule_id").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.content }
        runCatching {
            val d = StdioMcp.parseLoose(StdioMcp.text(call("list_rules", JsonObject(emptyMap())))) as? JsonObject
            for (r in (d?.get("rules") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }) {
                val id = idOf(r) ?: continue
                val type = (r["type"] as? JsonPrimitive)?.content ?: continue
                runCatching { call("delete_rule", buildJsonObject { put("id", id); put("rule_type", type) }); deleted++ }.onFailure { warnings += "$type/$id: ${it.message?.take(50)}" }
            }
        }.onFailure { warnings += "list_rules: ${it.message?.take(60)}" }
        for ((tool, type) in listOf("list_reverse_proxies" to "reverse_proxy", "list_network_conditions" to "network_condition", "list_dns_spoofing" to "dns_spoofing")) {
            runCatching {
                val d = StdioMcp.parseLoose(StdioMcp.text(call(tool, JsonObject(emptyMap()))))
                val items = (d as? JsonObject)?.values?.firstOrNull { it is JsonArray } as? JsonArray ?: d as? JsonArray
                for (it in items.orEmpty().mapNotNull { it as? JsonObject }) {
                    val id = idOf(it) ?: continue
                    call("delete_rule", buildJsonObject { put("id", id); put("rule_type", type) }); deleted++
                }
            }.onFailure { warnings += "$tool: ${it.message?.take(50)}" }
        }
        if (clearFlows) runCatching { call("clear_session", JsonObject(emptyMap())) }.onFailure { warnings += "clear_session: ${it.message?.take(50)}" }
        val shown = warnings.filter { "not available" !in it }
        return "Proxyman reset: deleted $deleted rule(s)" + (if (clearFlows) ", cleared captured flows" else "") +
            ". Relaunch the app so it fetches the real responses again." + (if (shown.isNotEmpty()) "\nwarnings: " + shown.take(6).joinToString("; ") else "")
    }

    // --- routing a phone ---

    @Serializable
    private data class Routed(val serial: String, val proxy: String, val reversePort: Int? = null, val phone: String? = null)

    /** The phone's own serial (ro.serialno): one phone shows up under its USB serial and its Wi-Fi address. */
    private fun phoneId(serial: String): String? =
        runCatching { rt.adb().run(listOf("shell", "getprop", "ro.serialno"), serial, timeoutMs = 10_000).stdout.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun records(): Map<String, Routed> = runCatching { json.decodeFromString<List<Routed>>(recordFile.readText()).associateBy { it.serial } }.getOrDefault(emptyMap())

    @Synchronized private fun saveRecords(m: Map<String, Routed>) {
        recordFile.parentFile?.mkdirs()
        if (m.isEmpty()) recordFile.delete() else recordFile.writeText(json.encodeToString(m.values.toList()))
        routedFlow.value = m.keys
    }

    /**
     * The address Proxyman sees [serial]'s traffic come from: the phone's own IP when its proxy is
     * another machine's address (a shared rig, or a phone routed over Wi-Fi). Null when Proxyman
     * can't tell it from other devices: `adb reverse` and simulators arrive as 127.0.0.1, like
     * every device routed that way, and so does a phone without a proxy.
     */
    fun deviceAddress(serial: String): String? = runCatching {
        if (rt.platformOf(serial) != no.agens.testlab.core.Platform.ANDROID) return null
        val adb = rt.adb()
        val proxy = adb.run(listOf("shell", "settings", "get", "global", "http_proxy"), serial, 10_000).stdout.trim()
        val host = proxy.substringBeforeLast(':').trim().trim('[', ']').takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val ip = runCatching { java.net.InetAddress.getByName(host) }.getOrNull()?.takeIf { !it.isLoopbackAddress }?.hostAddress ?: return null
        val route = adb.run(listOf("shell", "ip", "route", "get", ip), serial, 10_000).stdout
        Regex("\\bsrc\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(route)?.groupValues?.get(1)
    }.getOrNull()

    /** A rule for [serial] only, as Proxyman can tell it ([deviceAddress]); null with why when it can't. */
    fun scopeFor(serial: String): Pair<ProxyRules.DeviceScope?, String?> {
        val ip = deviceAddress(serial)
            ?: return null to "Proxyman can't tell $serial from other devices (it arrives through adb reverse or as a simulator, as 127.0.0.1): the rule applies to every device that reaches this Proxyman that way. Route the phone over Wi-Fi for a rule of its own, or pass only_device (a User-Agent fragment)."
        return ProxyRules.DeviceScope(serial, ip = ip) to null
    }

    /** The proxy address the phone should use, and whether it needs `adb reverse`. */
    fun addressFor(serial: String): Pair<String, Boolean> {
        val port = cfg.port
        cfg.host?.takeIf { it.isNotBlank() }?.let { return "$it:$port" to false }
        val dev = runCatching { rt.listDevices().firstOrNull { it.serial == serial } }.getOrNull()
        return when (dev?.transport) {
            "wifi", "ip" -> {
                val phone = serial.substringBeforeLast(':').trim('[', ']').takeIf { it.isNotEmpty() && dev.transport == "ip" }
                (lanAddress(phone) ?: throw ProxymanUnavailable("No network address of this computer found for the phone to reach Proxyman: set one in the Proxyman settings (`testlab open settings`)")) + ":$port" to false
            }
            else -> "127.0.0.1:$port" to true // USB and emulators: adb reverse to this computer
        }
    }

    // --- iOS Simulators: the Mac's system proxy ---

    /** The Mac's proxy for the simulators (this space's record of what it replaced). */
    val mac by lazy { MacProxy(File(rt.dirs.data, "mac-proxy.json")) }

    private fun isSim(serial: String) = rt.platformOf(serial) == no.agens.testlab.core.Platform.IOS

    private suspend fun routeSim(serial: String): ProxyState {
        val addr = "${cfg.host?.takeIf { it.isNotBlank() } ?: "127.0.0.1"}:${cfg.port}"
        if (!reachable(addr.substringBeforeLast(':'), cfg.port)) throw ProxymanUnavailable("Nothing listens on $addr: start Proxyman (and check its port in Proxyman → Settings) before routing the simulator through it")
        if (!mac.routed(serial)) rt.confirmWrite(
            "Route this Mac through Proxyman",
            "An iOS Simulator uses the Mac's network, so its proxy is the Mac's: all of this Mac's web traffic goes through $addr until handsets_proxy(off). The settings it replaces are saved and put back.",
        )
        mac.route(serial, addr)
        val ca = runCatching { installCa(serial, addr) }.getOrElse { "the CA was not installed (${it.message}): open http://proxy.man/ssl in the simulator's Safari, install it, then Settings → General → About → Certificate Trust Settings" }
        return statusSim(serial).copy(note = "the whole Mac goes through $addr while a simulator is routed; $ca")
    }

    /**
     * Proxyman's CA in the simulator's keychain: its own file when Proxyman runs on this Mac, else
     * what proxy.man/ssl hands out through the proxy.
     */
    private fun installCa(udid: String, addr: String): String {
        val x = rt.xcrun ?: return "no simctl"
        val local = File(System.getProperty("user.home"), "Library/Application Support/com.proxyman.NSProxy/app-data/proxyman-ca.pem")
        val pem = if (addr.startsWith("127.0.0.1") && local.isFile) local else File(rt.dirs.cache, "proxyman-ca-${addr.replace(':', '_')}.pem").also { f ->
            val body = fetchViaProxy(addr, "http://proxy.man/ssl")
            val text = body.decodeToString()
            when {
                "-----BEGIN CERTIFICATE-----" in text -> f.writeText(text.substring(text.indexOf("-----BEGIN CERTIFICATE-----")))
                body.size > 100 && body[0] == 0x30.toByte() -> f.writeText("-----BEGIN CERTIFICATE-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(body) + "\n-----END CERTIFICATE-----\n")
                else -> throw IllegalStateException("proxy.man/ssl gave no certificate")
            }
        }
        x.addRootCert(udid, pem)
        return "Proxyman's CA is trusted on the simulator"
    }

    private fun fetchViaProxy(addr: String, url: String): ByteArray {
        val c = java.net.URI(url).toURL().openConnection(java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress(addr.substringBeforeLast(':'), addr.substringAfterLast(':').toInt())))
        c.connectTimeout = 5_000; c.readTimeout = 10_000
        return c.getInputStream().use { it.readBytes() }
    }

    private fun statusSim(serial: String): ProxyState {
        val cur = mac.current()
        val rec = mac.record()?.takeIf { serial in it.sims }
        return ProxyState(serial, cur, rec != null && cur == rec.proxy, false, rec?.proxy)
    }

    private fun unrouteSim(serial: String): ProxyState {
        val was = mac.current()
        val ours = mac.record()?.proxy
        val restored = mac.unroute(serial)
        return statusSim(serial).copy(was = was, wasBy = if (was != null && was == ours) "this ${no.agens.testlab.AppIdentity.NAME}" else null,
            note = if (restored) "the Mac's own proxy settings are back" else "other simulators are still routed: the Mac stays on Proxyman until they go direct too")
    }

    suspend fun route(serial: String): ProxyState = withContext(Dispatchers.IO) {
        if (!enabled) throw ProxymanUnavailable("Proxyman is off: turn it on in the settings (`testlab open settings`, Proxyman)")
        if (isSim(serial)) return@withContext routeSim(serial)
        val (addr, reverse) = addressFor(serial)
        val port = cfg.port
        if (!reachable(if (reverse) "127.0.0.1" else addr.substringBeforeLast(':'), port)) {
            throw ProxymanUnavailable("Nothing listens on ${if (reverse) "127.0.0.1" else addr.substringBeforeLast(':')}:$port: start Proxyman (and check its port in Proxyman → Settings) before routing the phone through it")
        }
        val adb = rt.adb()
        if (reverse) adb.run(listOf("reverse", "tcp:$port", "tcp:$port"), serial).also { if (!it.ok) throw ProxymanUnavailable("adb reverse failed: ${(it.stderr + it.stdout).trim().take(200)}") }
        // The same phone routed earlier under another serial (USB vs Wi-Fi) would keep pinning its old
        // address over this one every 15 s: this routing replaces it.
        val phone = phoneId(serial)
        val ready = runCatching { rt.listDevices().filter { it.ready }.map { it.serial }.toSet() }.getOrDefault(emptySet())
        val same = records().values.filter { r -> r.serial != serial && phone != null && (r.phone ?: r.serial.takeIf { it in ready }?.let(::phoneId)) == phone }
        for (r in same) r.reversePort?.let { p -> runCatching { adb.run(listOf("reverse", "--remove", "tcp:$p"), r.serial) } }
        // Record before changing the phone: a crash right after still gets undone.
        saveRecords(records() - same.map { it.serial }.toSet() + (serial to Routed(serial, addr, if (reverse) port else null, phone)))
        adb.run(listOf("shell", "settings", "put", "global", "http_proxy", addr), serial)
        startPinning()
        status(serial)
    }

    /**
     * The phone goes direct: whoever set its proxy. Another space's record of the phone is dropped too,
     * or that space's pinning would put its proxy back within 15 s. Safe to call when it was never routed.
     */
    suspend fun unroute(serial: String): ProxyState = withContext(Dispatchers.IO) {
        if (isSim(serial)) return@withContext unrouteSim(serial)
        val rec = records()[serial]
        val others = dropOtherSpaces(serial)
        var cur: String? = null
        runCatching {
            val adb = rt.adb()
            cur = adb.run(listOf("shell", "settings", "get", "global", "http_proxy"), serial).stdout.trim().takeUnless { it.isEmpty() || it == "null" || it == ":0" }
            if (cur != null) adb.run(listOf("shell", "settings", "put", "global", "http_proxy", ":0"), serial)
            rec?.reversePort?.let { adb.run(listOf("reverse", "--remove", "tcp:$it"), serial) }
        }
        saveRecords(records() - serial)
        val by = when {
            cur == null -> null
            rec != null && cur == rec.proxy -> "this ${no.agens.testlab.AppIdentity.NAME}"
            others.any { it.second == cur } -> "space ${others.first { it.second == cur }.first}"
            else -> "not ${no.agens.testlab.AppIdentity.NAME} (set on the phone or by another tool)"
        }
        status(serial).copy(was = cur, wasBy = by)
    }

    /** Removes [serial] from every other space's proxied.json; returns (space, proxy) for each record dropped. */
    private fun dropOtherSpaces(serial: String): List<Pair<String, String>> = rt.dirs.otherSpacesData().mapNotNull { d ->
        val f = File(d, "proxied.json")
        if (!f.isFile || f.canonicalPath == recordFile.canonicalPath) return@mapNotNull null
        runCatching {
            val all = json.decodeFromString<List<Routed>>(f.readText())
            val hit = all.firstOrNull { it.serial == serial } ?: return@mapNotNull null
            val rest = all - hit
            if (rest.isEmpty()) f.delete() else f.writeText(json.encodeToString(rest))
            (if (d.canonicalPath == rt.dirs.rootData.canonicalPath) no.agens.testlab.services.Spaces.DEFAULT else d.name) to hit.proxy
        }.getOrNull()
    }

    fun status(serial: String): ProxyState {
        if (isSim(serial)) return statusSim(serial)
        val cur = runCatching { rt.adb().run(listOf("shell", "settings", "get", "global", "http_proxy"), serial, timeoutMs = 10_000).stdout.trim() }.getOrDefault("")
            .takeUnless { it.isEmpty() || it == "null" || it == ":0" }
        val rec = records()[serial]
        val rev = rec?.reversePort?.let { p -> runCatching { "tcp:$p" in rt.adb().run(listOf("reverse", "--list"), serial).stdout }.getOrDefault(false) } ?: false
        return ProxyState(serial, cur, rec != null && cur == rec.proxy, rev, rec?.proxy)
    }

    /** Undoes every recorded routing (app start after a crash, app exit). */
    fun restoreAll(): List<String> = records().keys.mapNotNull { s -> runCatching { kotlinx.coroutines.runBlocking { unroute(s) }; s }.getOrNull() } +
        (mac.record()?.sims.orEmpty().takeIf { runCatching { mac.restoreAll() }.getOrDefault(false) }.orEmpty())

    /** Re-applies the proxy and the reverse port while routing is on: a replug drops `adb reverse`, other tools change the proxy. */
    private fun startPinning() {
        if (pinJob?.isActive == true) return
        pinJob = scope.launch {
            while (isActive) {
                delay(15_000)
                val recs = records()
                if (recs.isEmpty()) break
                val ready = runCatching { rt.listDevices().filter { it.ready }.map { it.serial }.toSet() }.getOrDefault(emptySet())
                for (rec in recs.values) {
                    if (rec.serial !in ready) continue
                    // Only the space holding the phone keeps its proxy; a process that lost it (another space
                    // took the phone, or `off` from another space) forgets the routing instead of fighting.
                    when (runCatching { rt.leases.state(rec.serial) }.getOrDefault("yours")) {
                        "other" -> { saveRecords(records() - rec.serial); continue }
                        "free" -> continue
                    }
                    // The Proxyman host setting changed since the phone was routed: pin the new address.
                    val r = if (rec.reversePort == null && !cfg.host.isNullOrBlank()) {
                        val want = runCatching { addressFor(rec.serial).first }.getOrNull()
                        if (want != null && want != rec.proxy) rec.copy(proxy = want).also { saveRecords(records() + (it.serial to it)) } else rec
                    } else rec
                    runCatching {
                        val adb = rt.adb()
                        r.reversePort?.let { p -> if ("tcp:$p" !in adb.run(listOf("reverse", "--list"), r.serial).stdout) adb.run(listOf("reverse", "tcp:$p", "tcp:$p"), r.serial) }
                        if (adb.run(listOf("shell", "settings", "get", "global", "http_proxy"), r.serial).stdout.trim() != r.proxy) adb.run(listOf("shell", "settings", "put", "global", "http_proxy", r.proxy), r.serial)
                    }
                }
            }
        }
    }

    // --- health ---

    suspend fun health(): Health = withContext(Dispatchers.IO) {
        val host = cfg.host?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
        val port = cfg.port
        val up = reachable(host, port)
        val (status, secs) = if (up) viaProxy(host, port) else (null to null)
        val mcp = when {
            !enabled -> "off"
            remoteUrl() == null && mcpServer() == null -> "mcp-server not found"
            else -> runCatching { client(); "connected (${toolCache.size} tools)" }.getOrElse { it.message ?: "error" }
        }
        Health(up, status, secs, mcp, records().keys.map { status(it) } + mac.record()?.sims.orEmpty().map { statusSim(it) })
    }

    /**
     * Whether a routed phone trusts this Proxyman's CA, judged from its latest flows to the app's
     * domain (the profile's navigation host, cut to its last two labels): TLS connections Proxyman
     * could not open (CONNECT, status 999, not intercepted) and none decrypted mean the phone rejects
     * the certificate; decrypted ones mean it trusts it. An estimate: a shared Proxyman also holds
     * other phones' flows, and a domain outside SSL Proxying looks the same as a rejected certificate.
     */
    suspend fun caTrust(): String? {
        if (!enabled || (records().isEmpty() && mac.record() == null)) return null
        val host = rt.profile.navigation.host.takeIf { it.isNotBlank() } ?: return null
        val domain = host.substringAfter("://").substringBefore('/').split('.').takeLast(2).joinToString(".")
        val listed = runCatching { StdioMcp.parseLoose(StdioMcp.text(call("get_flows", buildJsonObject { put("limit", 200); put("host_filter", domain) }))) }.getOrNull() ?: return null
        val flows = ((listed as? JsonObject)?.get("flows") as? JsonArray ?: listed as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
        val decrypted = flows.count { it.s("isIntercepted") == "true" && it.s("method") != "CONNECT" }
        val failed = flows.count { it.s("method") == "CONNECT" && it.s("statusCode") == "999" && it.s("isIntercepted") != "true" }
        return when {
            decrypted > 0 -> "trusted: $decrypted decrypted request(s) to $domain"
            failed > 0 -> "probably NOT trusted: $failed TLS connection(s) to $domain failed through this Proxyman and none were decrypted. The app then shows spinners or errors. ${certHint(rt.profile)}"
            else -> "unknown: no traffic to $domain yet (open the app, then ask again)"
        } + " (estimate from Proxyman's latest flows${if (sharedWith() != null) ", which include other phones' on a shared Proxyman" else ""})"
    }

    private fun reachable(host: String, port: Int): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 2_000) }; true }.getOrDefault(false)

    /** A real request through the proxy: a hung Proxyman accepts connections but never answers. */
    private fun viaProxy(host: String, port: Int): Pair<String?, Double?> = runCatching {
        val t0 = System.nanoTime()
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 3_000)
            s.soTimeout = 8_000
            s.getOutputStream().write("GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray())
            val line = s.getInputStream().bufferedReader().readLine().orEmpty()
            line.split(' ').getOrNull(1) to (System.nanoTime() - t0) / 1e9
        }
    }.getOrDefault("000" to null)

    fun close() {
        runCatching { restoreAll() }
        scope.cancel()
        child?.close()
        child = null
    }

    companion object {
        /** Proxyman's rule tools whose method="ANY" default never matches (see the lab's docs/proxyman-map-local.md). */
        val METHOD_TOOLS = setOf(
            "create_map_local", "update_map_local", "create_map_remote", "update_map_remote", "create_blacklist", "update_blacklist",
            "create_allow_list", "update_allow_list", "create_breakpoint", "update_breakpoint",
        )
        /** Proxyman's MCP server writes no scripting rules on Linux or Windows (the rule appears, the script does nothing). */
        val SCRIPT_TOOLS = setOf("create_scripting_rule", "update_scripting_rule")
        val MAP_LOCAL_TOOLS = setOf("create_map_local", "update_map_local")

        /** Tools that add or change rules every phone through this Proxyman is subject to (composing a request is not one). */
        fun isRuleChange(name: String): Boolean =
            (name.startsWith("create_") || name.startsWith("update_") || name == "toggle_rule") && "compose_http" !in name

        /** The OS from a path Proxyman reports (its CA certificate): /Users/… macOS, C:\… Windows, ~/.config Linux. */
        fun osFromText(text: String): HostOs? = when {
            "/Users/" in text || "com.proxyman.NSProxy" in text -> HostOs.MAC
            "AppData" in text || Regex("""[A-Za-z]:\\""").containsMatchIn(text) -> HostOs.WINDOWS
            ".config/Proxyman" in text -> HostOs.LINUX
            else -> null
        }

        private val JWT = Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")
        private val AUTH_HEADER = Regex("""(?i)("(?:name|key)"\s*:\s*"(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key)"\s*,\s*"value"\s*:\s*")[^"]*(")""")

        /** Bearer tokens (any JWT) and Authorization/Cookie header values in captured traffic, masked. */
        fun redactTokens(text: String): String = AUTH_HEADER.replace(JWT.replace(text, "<jwt redacted>")) { "${it.groupValues[1]}<redacted>${it.groupValues[2]}" }

        const val ANY_NOTE = "\n\nNOTE (${no.agens.testlab.AppIdentity.NAME}): method 'ANY' is broken in Proxyman's MCP server (the rule is created but never matches any request), " +
            "so this rule was created with method=GET. To intercept another verb, create one rule per method (method='POST', etc.); do not use 'ANY'."

        fun fixAnyMethod(name: String, args: JsonObject): Pair<JsonObject, Boolean> {
            if (name !in METHOD_TOOLS) return args to false
            val m = (args["method"] as? JsonPrimitive)?.content ?: "ANY"
            return if (m.uppercase() == "ANY") JsonObject(args + ("method" to JsonPrimitive("GET"))) to true else args to false
        }

        /** This computer's IPv4 address on the phone's network (same /24), else the first private one. */
        fun lanAddress(phoneHost: String?): String? {
            val mine = runCatching {
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
            }.getOrDefault(emptyList())
            val phone = phoneHost?.let { h -> runCatching { InetAddress.getByName(h) as? Inet4Address }.getOrNull() }
            if (phone != null) mine.firstOrNull { it.address.take(3) == phone.address.take(3) }?.let { return it.hostAddress }
            return mine.firstOrNull()?.hostAddress
        }

        /** What the phone needs for HTTPS decryption, shown in Settings and by the tools. */
        /** Installing Proxyman's certificate; a profile can say which of its builds trust it (`texts["proxyman.cert"]`). */
        fun certHint(profile: no.agens.testlab.core.profile.Profile? = null): String =
            profile?.texts?.get("proxyman.cert") ?: tr("To see HTTPS: with the phone routed, open http://proxy.man/ssl in the phone's browser, download Proxyman's certificate and install it (Settings → Security → Encryption & credentials → Install a certificate → CA certificate). Builds that trust user-installed CAs (debug-overrides in network_security_config.xml, usual for debug builds) show their HTTPS; release builds usually don't.")
    }
}
