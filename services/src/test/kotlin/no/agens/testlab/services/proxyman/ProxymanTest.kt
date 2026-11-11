package no.agens.testlab.services.proxyman

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxymanTest {
    private val tmp = Files.createTempDirectory("tl-px").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val log = File(tmp, "px-calls.jsonl")
    private val fake = File(tmp, "mcp-server").apply {
        writeText(ProxymanTest::class.java.getResource("/proxyman/fake-mcp-server.py")!!.readText()); setExecutable(true)
    }
    private var phoneProxy = ""
    private var routeSrc = "198.51.100.21"
    private val reverses = mutableSetOf<String>()
    private val adb = FakeAdb { a ->
        val c = a.dropWhile { it == "-s" || it == "emulator-5554" }
        when {
            c.firstOrNull() == "devices" -> "List of devices attached\nemulator-5554          device product:sdk model:Pixel transport_id:1"
            c.take(4) == listOf("shell", "settings", "get", "global") -> phoneProxy
            c.take(4) == listOf("shell", "ip", "route", "get") -> "${c[4]} dev wlan0 src $routeSrc uid 0"
            c.take(4) == listOf("shell", "settings", "put", "global") -> { phoneProxy = c[5]; "" }
            c == listOf("reverse", "--list") -> reverses.joinToString("\n") { "emulator-5554 $it $it" }
            c.firstOrNull() == "reverse" && c.getOrNull(1) == "--remove" -> { reverses.remove(c[2]); "" }
            c.firstOrNull() == "reverse" -> { reverses += c[1]; "" }
            else -> ""
        }
    }
    // A stand-in for Proxyman's proxy port: answers every request with 200.
    private val proxySocket = ServerSocket(0).also { ss ->
        thread(isDaemon = true) {
            while (!ss.isClosed) runCatching { ss.accept().use { s -> s.getInputStream().bufferedReader().readLine(); s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()) } }
        }
    }
    private val settings = SettingsStore(dirs.settings)
    private fun runtime() = LabRuntime(dirs, settings, MemorySecretStore(emptyMap()), adbOverride = adb.cli).also { no.agens.testlab.services.profile.ExampleProfile.install(it) }
    private val rt = runtime()

    init {
        settings.update { it.copy(proxyman = it.proxyman.copy(enabled = true, port = proxySocket.localPort, mcpServer = fake.path)) }
    }

    @AfterTest
    fun tearDown() { rt.close(); proxySocket.close(); tmp.deleteRecursively() }

    private fun logged() = log.takeIf { it.isFile }?.readLines().orEmpty()

    @Test
    fun anEmulatorIsRoutedThroughAdbReverseAndBackAgain() = runBlocking {
        val on = rt.proxyman.route("emulator-5554")
        val p = proxySocket.localPort
        assertEquals("127.0.0.1:$p", on.proxy)
        assertTrue(on.ours && on.reverse, on.toString())
        assertTrue("tcp:$p" in reverses)
        assertEquals(setOf("emulator-5554"), rt.proxyman.routed.value)
        val off = rt.proxyman.unroute("emulator-5554")
        assertEquals(null, off.proxy)
        assertEquals(":0", phoneProxy, "cleared the way Android expects")
        assertTrue(reverses.isEmpty())
        assertFalse(File(dirs.data, "proxied.json").exists())
    }

    @Test
    fun offGoesDirectWhoeverSetTheProxyAndSaysWho() = runBlocking {
        rt.proxyman.route("emulator-5554")
        phoneProxy = "10.0.0.5:8888"
        val off = rt.proxyman.unroute("emulator-5554")
        assertEquals(":0", phoneProxy)
        assertEquals("10.0.0.5:8888", off.was)
        assertTrue("not" in off.wasBy!!, off.wasBy)
        val again = rt.proxyman.unroute("emulator-5554")
        assertEquals(null, again.proxy)
        assertEquals(null, again.was, "off twice: direct both times, nothing to undo the second time")
    }

    @Test
    fun offDropsAnotherSpacesRoutingOfThePhoneSoItCantPinItBack() = runBlocking {
        val other = File(dirs.data, "spaces/beta/proxied.json").apply { parentFile.mkdirs(); writeText("""[{"serial":"emulator-5554","proxy":"192.0.2.9:9090"}]""") }
        phoneProxy = "192.0.2.9:9090"
        val off = rt.proxyman.unroute("emulator-5554")
        assertEquals(":0", phoneProxy)
        assertEquals("space beta", off.wasBy)
        assertFalse(other.exists())
    }

    private fun answering(body: String) = ServerSocket(0).also { ss ->
        thread(isDaemon = true) {
            while (!ss.isClosed) runCatching {
                ss.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    while (r.readLine()?.isNotEmpty() == true) Unit
                    val b = body.toByteArray()
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\n\r\n".toByteArray() + b)
                }
            }
        }
    }

    @Test
    fun aMapLocalRuleIsCheckedThroughTheProxy() = runBlocking {
        File(tmp, "wrap.sh").apply { writeText("#!/bin/sh\nFAKE_PX_LOG='${log.path}' exec python3 '${fake.path}'\n"); setExecutable(true) }.also { w ->
            settings.update { it.copy(proxyman = it.proxyman.copy(mcpServer = w.path, host = "127.0.0.1")) }
        }
        rt.proxyman.refreshTools()
        fun mapLocal(body: kotlinx.serialization.json.JsonElement) = runBlocking {
            StdioMcp.text(rt.proxyman.call("create_map_local", JsonObject(mapOf("url" to JsonPrimitive("http://api.example.com/x"), "method" to JsonPrimitive("GET"), "response_body" to body, "why" to JsonPrimitive("test")))))
        }
        // The stand-in proxy answers every request with an empty body: the rule is not served.
        val empty = mapLocal(JsonPrimitive("""{"a":1}"""))
        assertTrue("MAP LOCAL NOT SERVED" in empty && "an empty body" in empty, empty)
        val sent = logged().last()
        assertTrue("\"why\"" !in sent, "arguments Proxyman's schema doesn't have are left out: $sent")
        // A proxy that serves the body: verified. A body given as a JSON object goes as its text.
        answering("""{"a":1}""").use { ok ->
            settings.update { it.copy(proxyman = it.proxyman.copy(port = ok.localPort)) }
            val served = mapLocal(kotlinx.serialization.json.buildJsonObject { put("a", 1) })
            assertTrue("verified" in served, served)
            assertTrue("\"response_body\": \"{\\\"a\\\":1}\"" in logged().last(), logged().last())
        }
        val line = File(dirs.data, "logs/proxyman-mcp.log").readLines().last { "create_map_local" in it }
        assertTrue("7 bytes, sha256" in line && "\"a\":1" !in line, "the log has the body's size and hash, not the body: $line")
    }



    @Test
    fun routingRefusesWhenProxymanIsNotListening() = runBlocking {
        settings.update { it.copy(proxyman = it.proxyman.copy(port = ServerSocket(0).use { s -> s.localPort })) }
        assertTrue("start Proxyman" in assertFailsWith<ProxymanUnavailable> { rt.proxyman.route("emulator-5554") }.message!!)
        assertEquals("", phoneProxy, "the phone was not touched")
        settings.update { it.copy(proxyman = it.proxyman.copy(enabled = false)) }
        assertTrue("open settings" in assertFailsWith<ProxymanUnavailable> { rt.proxyman.route("emulator-5554") }.message!!)
    }

    @Test
    fun aCrashedSessionsRoutingIsUndoneAtTheNextStart() = runBlocking {
        rt.proxyman.route("emulator-5554")
        // No unroute: the app "crashed". A new runtime restores the phone.
        val next = runtime()
        assertEquals(listOf("emulator-5554"), next.proxyman.restoreAll())
        assertEquals(":0", phoneProxy)
        next.close()
    }

    @Test
    fun theMirroredToolsFixMethodAnyAndExportAndReset() = runBlocking {
        // FAKE_PX_LOG reaches the child through a wrapper script.
        File(tmp, "wrap.sh").apply { writeText("#!/bin/sh\nFAKE_PX_LOG='${log.path}' exec python3 '${fake.path}'\n"); setExecutable(true) }.also { w ->
            settings.update { it.copy(proxyman = it.proxyman.copy(mcpServer = w.path)) }
        }
        assertEquals(12, rt.proxyman.refreshTools().size)
        val r = rt.proxyman.call("create_map_local", JsonObject(mapOf("url" to JsonPrimitive("https://api.example.com/api/v1/config"))))
        assertTrue("\"method\": \"GET\"" in logged().last(), logged().last())
        assertTrue("method 'ANY' is broken" in StdioMcp.text(r))
        rt.proxyman.call("create_map_local", JsonObject(mapOf("url" to JsonPrimitive("x"), "method" to JsonPrimitive("POST"))))
        assertTrue("\"method\": \"POST\"" in logged().last(), "an explicit method is kept")

        val f = rt.proxyman.exportFlows(tmp)!!
        assertEquals(2, f.count)
        assertEquals(listOf("api.example.com"), f.hosts)
        assertTrue("api.example.com/api/v1/config" in File(tmp, "flows.har").readText())

        val reset = rt.proxyman.reset()
        assertTrue("deleted 5 rule(s), cleared captured flows" in reset, "the two rules made above, two others and a reverse proxy: $reset")
        assertTrue(logged().any { "\"rule_type\": \"reverse_proxy\"" in it })
    }

    @Test
    fun theSamePhoneRoutedAgainUnderAnotherSerialReplacesTheOldRouting() = runBlocking {
        // One phone over USB and over Wi-Fi adb: two serials, one ro.serialno, one proxy setting.
        val usb = "ABC123"
        val wifi = "192.0.2.10:5555"
        val removed = mutableListOf<String>()
        val two = FakeAdb { a ->
            val c = if (a.firstOrNull() == "-s") a.drop(2) else a
            when {
                c.firstOrNull() == "devices" -> "List of devices attached\n$usb device usb:1-1 product:p model:Nord transport_id:1\n$wifi device product:p model:Nord transport_id:2"
                c == listOf("shell", "getprop", "ro.serialno") -> usb
                c.take(4) == listOf("shell", "settings", "get", "global") -> phoneProxy
                c.take(4) == listOf("shell", "settings", "put", "global") -> { phoneProxy = c[5]; "" }
                c.firstOrNull() == "reverse" && c.getOrNull(1) == "--remove" -> { removed += a.getOrNull(1).orEmpty(); "" }
                else -> ""
            }
        }
        val r2 = LabRuntime(dirs, settings, MemorySecretStore(emptyMap()), adbOverride = two.cli).also { no.agens.testlab.services.profile.ExampleProfile.install(it) }
        try {
            r2.proxyman.route(usb)
            settings.update { it.copy(proxyman = it.proxyman.copy(host = "127.0.0.1")) }
            val on = r2.proxyman.route(wifi)
            assertTrue(on.ours, on.toString())
            assertEquals(setOf(wifi), r2.proxyman.routed.value, "the USB routing is gone, so it no longer pins its own address")
            assertEquals(listOf(usb), removed, "and its adb reverse is removed")
        } finally { r2.close() }
    }

    @Test
    fun theOsIsToldFromProxymansPaths() {
        assertEquals(no.agens.testlab.device.HostOs.LINUX, Proxyman.osFromText("""{"certificate_name": "/root/.config/Proxyman/certificate/certs/ca.pem"}"""))
        assertEquals(no.agens.testlab.device.HostOs.MAC, Proxyman.osFromText("/Users/me/Library/Application Support/com.proxyman.NSProxy/app-data/proxyman-ca.pem"))
        assertEquals(no.agens.testlab.device.HostOs.WINDOWS, Proxyman.osFromText("""C:\Users\me\AppData\Roaming\Proxyman\ca.pem"""))
        assertEquals(null, Proxyman.osFromText("""{"status": "installed"}"""))
        assertTrue(Proxyman.isRuleChange("create_map_local") && Proxyman.isRuleChange("toggle_rule"))
        assertFalse(Proxyman.isRuleChange("create_compose_http") || Proxyman.isRuleChange("get_flows"))
    }

    @Test
    fun aRemoteProxymanIsSaidToBeShared() {
        assertEquals(null, rt.proxyman.sharedWith(), "a local mcp-server is this computer's own")
        settings.update { it.copy(proxyman = it.proxyman.copy(mcpServer = "http://192.0.2.7:7900/mcp")) }
        assertTrue("another machine (192.0.2.7)" in rt.proxyman.sharedWith()!!)
    }

    @Test
    fun rulesAndFlowsAreKeptToOnePhoneWhenProxymanCanTellItApart() = runBlocking {
        val serial = "emulator-5554"
        wrap()
        // Through adb reverse (or no proxy) every device arrives as 127.0.0.1: no address of its own.
        phoneProxy = "127.0.0.1:${proxySocket.localPort}"
        assertEquals(null, rt.proxyman.deviceAddress(serial))
        assertTrue("adb reverse" in rt.proxyman.scopeFor(serial).second!!)
        // Over the network (a shared rig): the phone's own address on the way to the proxy.
        phoneProxy = "192.0.2.10:9090"
        assertEquals("198.51.100.21", rt.proxyman.deviceAddress(serial))
        val scope = rt.proxyman.scopeFor(serial).first!!

        // A scripting rule goes through on every OS now, and lets other devices' traffic pass.
        val r = rt.proxyman.rules.force("status", "https://api.example.com/x", "GET", 503, null, null, null, scope)
        assertTrue("Applies to only 198.51.100.21 ($serial)" in r, r)
        val sent = logged().last { "create_scripting_rule" in it }
        assertTrue("clientIpAddress" in sent && "198.51.100.21" in sent && "statusCode = 503" in sent, sent)
        assertTrue("only 198.51.100.21" in sent, "the rule's name says whose it is: $sent")
        val d = rt.proxyman.rules.force("delay", "https://api.example.com/x", "GET", null, null, 2000, null, scope)
        assertTrue("Applies to only" in d && "sleep(2000)" in logged().last(), d)
        val everyone = rt.proxyman.rules.force("status", "https://api.example.com/y", "GET", 500, null, null, null, null)
        assertTrue("every device" in everyone && "clientIpAddress" !in logged().last(), everyone)
        // Block lists can't be scoped: the result says so.
        runCatching { rt.proxyman.rules.force("block", "https://api.example.com/z", "GET", null, null, null, null, scope) }
            .onSuccess { assertTrue("can't be kept to one phone" in it, it) }
        // A rewrite patches each live response with a script, kept to this phone as well.
        val rw = rt.proxyman.rules.force("rewrite", "https://api.example.com/*", "GET", null, null, null, JsonObject(mapOf("items.0.enabled" to JsonPrimitive(false))), scope)
        assertTrue("Applies to only 198.51.100.21" in rw && "patches each live response" in rw, rw)
        val js = logged().last()
        assertTrue("create_scripting_rule" in js && "clientIpAddress" in js && "items.0.enabled" in js && "Map Local" !in rw, js)
        // An error status replaces the real data with an error body, and one GraphQL operation is picked by Proxyman's filter.
        val gql = rt.proxyman.rules.force("status", "https://api.example.com/graphql", "POST", 500, null, null, null, scope, operation = "GetAddressQuery")
        val g = logged().last()
        assertTrue("Testlab forced HTTP 500" in g && "\"graphql_query_name\": \"GetAddressQuery\"" in g && "operationName" !in g, g)
        assertTrue("Only the GraphQL operation GetAddressQuery" in gql && "keep_body=true" in gql, gql)
        rt.proxyman.rules.force("status", "https://api.example.com/graphql", "POST", 500, null, null, null, scope, keepBody = true)
        assertTrue("forced HTTP" !in logged().last() && "graphql_query_name" !in logged().last(), logged().last())

        // The report's network log keeps to its own phone's requests.
        val dir = File(tmp, "rep").apply { mkdirs() }
        val mine = rt.proxyman.exportFlows(dir, from = setOf("198.51.100.21"))!!
        assertEquals(listOf("f1"), mine.rows.map { it.id })
        assertEquals(setOf("198.51.100.21"), mine.from)
        assertEquals(2, rt.proxyman.exportFlows(dir)!!.rows.size, "without addresses: everything")
    }

    @Test
    fun whetherThePhoneTrustsTheCaIsEstimatedFromItsFlows() = runBlocking {
        // One Proxyman process per runtime: a fresh runtime per case, its fake answering get_flows as $FAKE_PX_TLS says.
        for ((tls, want) in listOf("fail" to "probably NOT trusted", "ok" to "trusted: 1 decrypted", "" to "unknown")) {
            val w = File(tmp, "wrap-$tls.sh").apply { writeText("#!/bin/sh\nFAKE_PX_TLS='$tls' exec python3 '${fake.path}'\n"); setExecutable(true) }
            settings.update { it.copy(proxyman = it.proxyman.copy(mcpServer = w.path)) }
            val r = runtime()
            try {
                r.proxyman.route("emulator-5554")
                val got = r.proxyman.caTrust()!!
                assertTrue(want in got && "example.com" in got, "$tls: $got")
            } finally { r.close() }
        }
    }

    @Test
    fun healthChecksARealRequestThroughTheProxy() = runBlocking {
        val h = rt.proxyman.health()
        assertTrue(h.proxyReachable)
        assertEquals("200", h.viaProxyStatus)
        assertTrue(h.healthy)
        assertTrue(h.mcp.startsWith("connected (12 tools)"), h.mcp)
    }

    private fun wrap(env: String = "") = File(tmp, "wrap.sh").apply { writeText("#!/bin/sh\n${env}FAKE_PX_LOG='${log.path}' exec python3 '${fake.path}'\n"); setExecutable(true) }.also { w ->
        settings.update { it.copy(proxyman = it.proxyman.copy(mcpServer = w.path)) }
    }

    @Test
    fun webSocketRulesAndMessages() = runBlocking {
        wrap()
        val ua = ProxyRules.DeviceScope("User-Agent", userAgent = "tl-probe")
        val r = rt.proxyman.rules.force("ws_rewrite", "wss://rt.example.com/graphql/realtime*", "GET", null, null, null,
            JsonObject(mapOf("payload.data.onScore.home" to JsonPrimitive(9))), ua, operation = "OnScore", match = "onScore")
        assertTrue("Only the GraphQL subscription OnScore" in r && "Applies to only User-Agent 'tl-probe'" in r && "subprotocol" in r, r)
        val sent = logged().last()
        assertTrue("\"modify_websocket_messages\": true" in sent && "onWebsocket" in sent && "return message;" in sent && "payload.data.onScore.home" in sent && "graphql_query_name" !in sent, sent)
        rt.proxyman.rules.force("ws_close", "wss://rt.example.com/*", "GET", null, null, null, null, null, match = "/\"type\":\"data\"/")
        assertTrue("abort()" in logged().last() && "const MATCH = /" in logged().last(), logged().last())
        assertFailsWith<IllegalArgumentException> { rt.proxyman.rules.force("ws_inject", "wss://rt.example.com/*", "GET", null, null, null, null, null) }

        // Read back: GraphQL over WebSocket decoded, the script's changes marked, the subprotocol refusal explained.
        val read = Sockets.read(rt.proxyman, "rt.example", 3, 30, null).toString()
        assertTrue("OnScore (id s-1)" in read && "\"operation\":\"OnScore\"" in read && "\"script\":\"edited\"" in read, read)
        assertTrue("keep_alives_left_out\":1" in read && "header=" !in read, "keep-alives counted, the URL's token left out: $read")
        assertTrue("NoProtocolError" in read && "Sec-WebSocket-Protocol" in read && "exclude the host" in read, read)
        assertEquals(Triple("subscribe", "a", "Live"), Sockets.decode("""{"id":"a","type":"subscribe","payload":{"operationName":"Live","query":"subscription Live { x }"}}"""))
    }

    @Test
    fun withoutProxymansOperationFilterTheScriptPicksTheOperation() = runBlocking {
        wrap("FAKE_PX_NOGQL=1 ")
        rt.proxyman.rules.force("delay", "https://api.example.com/graphql", "POST", null, null, 1500, null, null, operation = "GetAddressQuery")
        val s = logged().last()
        assertTrue("operationName" in s && "GetAddressQuery" in s && "graphql_query_name" !in s && "sleep(1500)" in s, s)
        // A block list can't pick an operation here: refused rather than blocking every operation.
        assertFailsWith<IllegalArgumentException> { rt.proxyman.rules.force("block", "https://api.example.com/graphql", "POST", null, null, null, null, null, operation = "GetAddressQuery") }
    }

    @Test
    fun aRefusedScriptSaysWhyAndRuleNamesAreStorable() = runBlocking {
        assertEquals("[tl ab12] status 500 api.example.com v1 config (only User-Agent Pixel 8)",
            ProxyRules.safeName("[tl ab12] status 500 https://api.example.com/v1/config (only User-Agent «Pixel 8»)"))
        assertEquals("api.example.com/v1/config", ProxyRules.shortUrl("https://api.example.com/v1/config/?a=1"))
        wrap("FAKE_PX_NOSCRIPT=1 ")
        val ua = ProxyRules.DeviceScope("User-Agent", userAgent = "tl-probe")
        // The status falls back to Map Local, named so Proxyman on Linux can store it, and says why.
        val r = rt.proxyman.rules.force("status", "https://api.example.com/v1/config", "GET", 500, null, null, null, ua)
        assertTrue("Proxyman refused the scripting rule (Scripting is not available in this build)" in r && "status 200" in r && "can't be kept to one phone" in r, r)
        val ml = logged().last()
        assertTrue("create_map_local" in ml && "api.example.com v1 config" in ml, ml)
        // A direct Map Local call with a URL for a name is stored too.
        val direct = rt.proxyman.call("create_map_local", JsonObject(mapOf("url" to JsonPrimitive("https://api.example.com/a"), "method" to JsonPrimitive("GET"), "response_body" to JsonPrimitive("{}"))))
        assertFalse(StdioMcp.isError(direct), StdioMcp.text(direct))
        // A delay has no fallback: the error says why the script was refused.
        val e = assertFailsWith<ProxymanUnavailable> { rt.proxyman.rules.force("delay", "https://api.example.com/x", "GET", null, null, 1000, null, ua) }
        assertTrue("Scripting is not available" in e.message!!, e.message)
        // A rewrite without a captured response or body has nothing to fall back on.
        val w = assertFailsWith<IllegalStateException> { rt.proxyman.rules.force("rewrite", "https://api.example.com/x", "GET", null, null, null, JsonObject(mapOf("a" to JsonPrimitive(1))), null) }
        assertTrue("Proxyman refused the scripting rule" in w.message!! && "no captured response" in w.message!!, w.message)
    }

    @Test
    fun rulesCarryTheSessionTagAndCleanupDeletesOnlyThoseAndChecks() = runBlocking {
        wrap()
        val session = rt.proxyman.rules.newSession()
        rt.proxyman.call("create_map_local", JsonObject(mapOf("url" to JsonPrimitive("http://api.example.com/a"), "method" to JsonPrimitive("GET"), "name" to JsonPrimitive("mine"))))
        assertTrue("\"name\": \"[tl $session] mine\"" in logged().last(), "the name carries the session's tag: ${logged().last()}")
        val listed = rt.proxyman.rules.list()
        assertEquals(1, listed.count { it.ours }, listed.toString())
        val changes = rt.proxyman.rules.changes()
        assertEquals("create_map_local", changes.single().tool)
        assertTrue("url=http://api.example.com/a" in changes.single().summary, changes.single().summary)

        val c = rt.proxyman.rules.cleanup()
        assertEquals(1, c.deleted.size)
        assertEquals(3, c.others, "the rules other people made stay (two rules and a reverse proxy)")
        assertTrue(c.survivors.isEmpty())
        assertTrue(c.checks.single().startsWith("real answer: http://api.example.com/a"), c.checks.toString())
        assertTrue(c.clean, c.summary())
        assertEquals(3, rt.proxyman.rules.list().size)
    }

    @Test
    fun aRuleThatSurvivesItsDeleteIsReported() = runBlocking {
        wrap("FAKE_PX_ZOMBIE=1 ")
        rt.proxyman.rules.newSession()
        rt.proxyman.call("create_map_local", JsonObject(mapOf("url" to JsonPrimitive("https://api.example.com/b"), "method" to JsonPrimitive("GET"))))
        val c = rt.proxyman.rules.cleanup()
        assertEquals(1, c.survivors.size)
        assertTrue(!c.clean && "SURVIVED the delete" in c.summary(), c.summary())
    }

    @Test
    fun ampersandPatternsAreFlagged() {
        val warn = ProxyRules.ampersandWarning("create_map_local", JsonObject(mapOf("url" to JsonPrimitive("https://x/*?a=1&b=2"))))
        assertTrue(warn != null && "regex without '&'" in warn)
        assertEquals(null, ProxyRules.ampersandWarning("create_map_local", JsonObject(mapOf("url" to JsonPrimitive("https://x/*"), "use_regex" to JsonPrimitive(true)))))
        assertEquals(null, ProxyRules.ampersandWarning("get_flows", JsonObject(mapOf("url" to JsonPrimitive("a&b")))))
    }

    @Test
    fun tokensAreMaskedInCapturedTraffic() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val har = "{\"url\":\"wss://chat.example/graphql?header=$jwt\",\"headers\":[{\"name\":\"Authorization\",\"value\":\"Bearer abc123\"},{\"name\":\"Accept\",\"value\":\"*/*\"}]}"
        val out = Proxyman.redactTokens(har)
        assertTrue(jwt !in out && "<jwt redacted>" in out, out)
        assertTrue("Bearer abc123" !in out && "\"value\":\"<redacted>\"" in out && "*/*" in out, out)
    }

    @Test
    fun jsonPatchSetsKeyPathsAndRefusesTypos() {
        val doc = kotlinx.serialization.json.Json.parseToJsonElement("""{"features":[{"name":"a","enabled":true},{"name":"b","enabled":true}],"count":1}""")
        val out = JsonPatch.apply(doc, kotlinx.serialization.json.Json.parseToJsonElement("""{"features.1.enabled":false,"count":3}""") as JsonObject)
        assertEquals("""{"features":[{"name":"a","enabled":true},{"name":"b","enabled":false}],"count":3}""", out.toString())
        val all = JsonPatch.apply(doc, kotlinx.serialization.json.Json.parseToJsonElement("""{"features.*.enabled":false}""") as JsonObject)
        assertTrue("true" !in all.toString())
        assertFailsWith<IllegalArgumentException> { JsonPatch.apply(doc, kotlinx.serialization.json.Json.parseToJsonElement("""{"featurs.0.enabled":false}""") as JsonObject) }
        assertFailsWith<IllegalArgumentException> { JsonPatch.apply(doc, kotlinx.serialization.json.Json.parseToJsonElement("""{"features.5.enabled":false}""") as JsonObject) }
    }
}
