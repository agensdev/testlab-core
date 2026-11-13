package no.agens.testlab.mcp

import no.agens.testlab.AppIdentity
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import io.ktor.client.request.put
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.device.adb.AdbDevice
import no.agens.testlab.device.hs.FakePhone
import no.agens.testlab.device.hs.HsEndpoint
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.jira.MockJira
import no.agens.testlab.services.profile.ExampleProfile
import no.agens.testlab.services.runtime.DeviceBackend
import no.agens.testlab.services.runtime.LabEvent
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The control API for host apps, over real HTTP: auth, Host/Origin checks, discovery and the devtest hand-off. */
class ControlApiTest {
    private val tmp = Files.createTempDirectory("tl-api").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val token = "api-token-for-tests-0123456789"
    private val phone = FakePhone(McpServerTest.SCREEN)
    private val jira = MockJira()
    private val rt = LabRuntime(
        dirs, SettingsStore(File(dirs.data, "settings.json")),
        MemorySecretStore(mapOf("mcp.token" to token, "jira.email" to "test@example.com", "jira.token" to "jira-token-xyz-123")),
        backendOverride = object : DeviceBackend {
            override fun devices() = listOf(AdbDevice("fake-1", "device", model = "Fake_Phone"))
            override fun connect(serial: String, takeOver: Boolean) = HsEndpoint(serial, phone.port)
            override fun release(serial: String) {}
        },
    ).also { it.jiraHttp = jira.client; ExampleProfile.install(it) }
    private val port = ServerSocket(0).use { it.localPort }
    private val gateway = TestlabMcp(rt, McpEndpoint(port)).gateway().also { it.start(McpEndpoint(port)) }
    private val http = HttpClient(CIO) { expectSuccess = false }
    private val base = "http://127.0.0.1:$port/api/v1"

    @AfterTest
    fun tearDown() { http.close(); gateway.stop(); rt.close(); phone.close(); tmp.deleteRecursively() }

    private suspend fun get(path: String, auth: Boolean = true): HttpResponse = http.get(base + path) { if (auth) header("Authorization", "Bearer $token") }
    private suspend fun post(path: String, body: String = "{}"): HttpResponse = http.post(base + path) {
        header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody(body)
    }
    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun discoveryAuthAndOriginChecks() = runBlocking {
        val d = Json.parseToJsonElement(File(dirs.data, "control.json").readText()).jsonObject
        assertEquals("http://127.0.0.1:$port/api/v1", d["api"]!!.jsonPrimitive.content)
        assertEquals(token, d["token"]!!.jsonPrimitive.content)
        if (no.agens.testlab.device.HostOs.current() != no.agens.testlab.device.HostOs.WINDOWS) {
            assertEquals(setOf("OWNER_READ", "OWNER_WRITE"), Files.getPosixFilePermissions(File(dirs.data, "control.json").toPath()).map { it.name }.toSet())
        }
        assertEquals(401, get("/status", auth = false).status.value)
        assertEquals(403, http.get("$base/status") { header("Authorization", "Bearer $token"); header("Origin", "https://evil.example") }.status.value)
        assertEquals(403, http.get("$base/status") { header("Authorization", "Bearer $token"); header("Host", "evil.example:$port") }.status.value)
        val s = get("/status").json()
        assertEquals("example-shop", s["profile"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("service", s["mode"]!!.jsonPrimitive.content)
        assertTrue("fake-1" in get("/devices").bodyAsText())
        gateway.stop()
        assertTrue(!File(dirs.data, "control.json").exists(), "the discovery file goes with the server")
    }

    @Test
    fun theViewsCallTheirToolsHereAndNothingElse() = runBlocking {
        val r = post("/view/call", """{"tool":"handsets_view_frame","args":{"format":"rgb","w":10,"h":20}}""")
        assertEquals(200, r.status.value)
        val body = r.json()
        assertEquals("false", body["isError"]!!.jsonPrimitive.content)
        val frame = Json.parseToJsonElement(body["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals("fake-1", frame["serial"]!!.jsonPrimitive.content)
        assertEquals(403, post("/view/call", """{"tool":"handsets_type_secret","args":{"ref":"demo.password"}}""").status.value, "only the views' tools")
        assertEquals(403, post("/view/call", """{"tool":"jira_add_comment","args":{}}""").status.value)
        assertEquals(401, http.post("$base/view/call") { contentType(ContentType.Application.Json); setBody("""{"tool":"handsets_view_frame"}""") }.status.value)
    }

    @Test
    fun browserPagesSignInWithAOneTimeTicketAndOnlyFromTheirOwnOrigin() = runBlocking {
        val noFollow = HttpClient(CIO) { expectSuccess = false; followRedirects = false }
        val origin = "http://127.0.0.1:$port"
        try {
            assertEquals(401, noFollow.get("$origin/ui/profiles").status.value, "no cookie, no page")
            val ticket = post("/ui/ticket").json()["ticket"]!!.jsonPrimitive.content
            val first = noFollow.get("$origin/ui/profiles?t=$ticket")
            assertEquals(302, first.status.value)
            assertEquals("/ui/profiles", first.headers["Location"], "the ticket leaves the address bar")
            val setCookie = first.headers["Set-Cookie"]!!
            assertTrue("HttpOnly" in setCookie && "SameSite=Strict" in setCookie, setCookie)
            val cookie = setCookie.substringBefore(';')
            assertEquals(401, noFollow.get("$origin/ui/profiles?t=$ticket").status.value, "a ticket works once")
            val page = noFollow.get("$origin/ui/profiles") { header("Cookie", cookie) }
            assertEquals(200, page.status.value)
            assertTrue("New profile from a repo" in page.bodyAsText())
            assertTrue("frame-ancestors 'none'" in page.headers["Content-Security-Policy"].orEmpty())
            // The page's own calls: the cookie from its own origin (or no Origin, a same-origin GET).
            assertEquals(200, http.get("$base/profiles") { header("Cookie", cookie) }.status.value)
            assertEquals(200, http.post("$base/view/call") { header("Cookie", cookie); header("Origin", origin); contentType(ContentType.Application.Json); setBody("""{"tool":"handsets_view_activity"}""") }.status.value)
            // Another site's page never gets in, cookie or not.
            assertEquals(403, http.get("$base/profiles") { header("Cookie", cookie); header("Origin", "https://evil.example") }.status.value)
            assertEquals(401, http.get("$base/profiles") { header("Cookie", "testlab_ui=forged") }.status.value)
            // The screen view in a tab: the MCP Apps view with the browser host in it.
            val screen = noFollow.get("$origin/ui/screen?t=" + post("/ui/ticket").json()["ticket"]!!.jsonPrimitive.content)
            val c2 = screen.headers["Set-Cookie"]!!.substringBefore(';')
            val html = noFollow.get("$origin/ui/screen") { header("Cookie", c2) }.bodyAsText()
            assertTrue("window.__testlabHost" in html && "\"handsets_show_screen\"" in html, "the browser host is in the page")
            // Profiles through the page's API: JSON out, a broken edit refused with its problems.
            val pj = http.get("$base/profiles/example-shop/json") { header("Cookie", cookie) }.json()["json"]!!.jsonPrimitive.content
            val bad = pj.replace("\"site\": \"example.atlassian.net\"", "\"site\": \"https://example.atlassian.net\"")
            val r = http.put("$base/profiles/example-shop/json") { header("Cookie", cookie); contentType(ContentType.Application.Json); setBody(buildJsonObject { put("json", bad) }.toString()) }.json()
            assertEquals("false", r["saved"]!!.jsonPrimitive.content)
            assertTrue("not a URL" in r["problems"].toString(), r.toString())
            assertEquals(400, http.post("$base/secrets/mcp.token") { header("Cookie", cookie); contentType(ContentType.Application.Json); setBody("""{"value":"x"}""") }.status.value, "only the tokens a profile needs")
        } finally { noFollow.close() }
    }

    @Test
    fun aPausedPlanWaitsForAPersonAndGoesOnFromTheBrowser() = runBlocking {
        val tools = TestlabMcp(rt).tools
        val r = tools.call("handsets_autopilot_plan", buildJsonObject { put("plan", """[{"tap":"No such button"},{"note":"after"}]"""); put("backend", "stub") })
        val text = (r.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue("\"paused_at\":0" in text.replace(" ", "") && "open handback" in text, text.take(400))
        val list = get("/handbacks").json()["handbacks"]!!.jsonArray
        val h = list.single().jsonObject
        assertEquals("claude-fake-1", h["id"]!!.jsonPrimitive.content)
        assertTrue("No such button" in h["step"]!!.jsonPrimitive.content)
        assertEquals(400, post("/handbacks/claude-fake-1", """{"action":"jump"}""").status.value)
        val done = post("/handbacks/claude-fake-1", """{"action":"tap","x":"540","y":"2070"}""").json()
        assertTrue("from step 1" in done["done"]!!.jsonPrimitive.content, done.toString())
        assertEquals(540 to 2070, phone.taps.last(), "the tap was made on the phone")
        assertEquals(404, post("/handbacks/claude-fake-1", """{"action":"skip"}""").status.value, "resolved once")
    }

    @Test
    fun theViewsGetRawDataWholeEvenWhenASecretTurnsUpInIt() = runBlocking {
        // The frame's base64 from the tool itself, then a "PIN" that occurs in it as a standalone run.
        val raw = Json.parseToJsonElement(post("/view/call", """{"tool":"handsets_view_frame","args":{"format":"rgb","w":10,"h":20}}""").json()["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content).jsonObject["data"]!!.jsonPrimitive.content
        val pin = Regex("(?<![0-9])[0-9]{4}(?![0-9])").find(raw)?.value ?: Regex("[A-Za-z]{6}").find(raw)!!.value
        rt.secrets.put("device_pin", pin)
        val again = Json.parseToJsonElement(post("/view/call", """{"tool":"handsets_view_frame","args":{"format":"rgb","w":10,"h":20}}""").json()["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content).jsonObject["data"]!!.jsonPrimitive.content
        assertTrue("‹redacted›" !in again, "base64 reaches the page whole")
        java.util.Base64.getDecoder().decode(again)
        assertTrue("‹redacted›" in get("/status").bodyAsText() || pin !in get("/status").bodyAsText(), "the other answers stay redacted")
    }

    @Test
    fun thePagesWorkUnderTheirLocalhostNameAndChangeSettings() = runBlocking {
        val noFollow = HttpClient(CIO) { expectSuccess = false; followRedirects = false }
        val name = "testlab.localhost:$port"
        try {
            val ticket = post("/ui/ticket").json()["ticket"]!!.jsonPrimitive.content
            val first = noFollow.get("http://127.0.0.1:$port/ui/settings?t=$ticket") { header("Host", name) }
            assertEquals(302, first.status.value, "the name is a Host the pages accept")
            val cookie = first.headers["Set-Cookie"]!!.substringBefore(';')
            val page = noFollow.get("http://127.0.0.1:$port/ui/settings") { header("Host", name); header("Cookie", cookie) }
            assertTrue("Proxyman" in page.bodyAsText() && "/ui/settings" in page.bodyAsText(), "the settings page, with the menu")
            suspend fun patch(body: String) = http.post("$base/settings") { header("Host", name); header("Cookie", cookie); header("Origin", "http://$name"); contentType(ContentType.Application.Json); setBody(body) }
            val ok = patch("""{"proxyman":{"enabled":true,"host":"10.0.0.5","port":9091}}""")
            assertEquals(200, ok.status.value)
            assertEquals(true, rt.settings.current.proxyman.enabled)
            assertEquals("10.0.0.5", rt.settings.current.proxyman.host)
            assertEquals(9091, rt.settings.current.proxyman.port)
            assertEquals(true, rt.settings.current.proxyman.mirrorTools, "a partial change keeps the rest")
            assertEquals(400, patch("""{"mcp":{"port":"not a number"}}""").status.value)
            assertEquals(AppIdentity.MCP_DEFAULT_PORT, rt.settings.current.mcp.port, "nothing saved from a refused change")
            assertEquals(200, http.post("$base/secrets/typesafe.key") { header("Cookie", cookie); contentType(ContentType.Application.Json); setBody("""{"value":"ts-key-for-tests"}""") }.status.value)
            assertEquals("ts-key-for-tests", rt.secrets.get("typesafe.key"))
            val items = http.get("$base/setup") { header("Cookie", cookie) }.json()["items"]!!.jsonArray
            assertTrue(items.any { it.jsonObject["group"]!!.jsonPrimitive.content == "Proxyman" }, "Proxyman is checked once it is on")
            // The name doesn't let another site in.
            assertEquals(403, http.get("$base/settings") { header("Host", name); header("Cookie", cookie); header("Origin", "http://evil.localhost:$port") }.status.value)
        } finally { noFollow.close() }
    }

    @Test
    fun aHostAppAnswersAnApproval() = runBlocking {
        rt.approver = rt.approvals.approver()
        val write = async { rt.approver.approve("Comment on SHOP-1", "the exact comment") }
        var id: String? = null
        withTimeout(5_000) { while (id == null) { id = rt.approvals.pending.value.firstOrNull()?.id; delay(10) } }
        val list = get("/approvals").json()["pending"]!!.toString()
        assertTrue("the exact comment" in list && id!! in list, list)
        assertEquals(1, get("/status").json()["pending_approvals"]!!.jsonPrimitive.content.toInt())
        assertEquals(400, post("/approvals/$id", """{"yes":true}""").status.value)
        assertEquals(200, post("/approvals/$id", """{"approve":true,"by":"my-app"}""").status.value)
        assertTrue(withTimeout(5_000) { write.await() })
        assertEquals("api:my-app", rt.approvals.resolved.value.first().by)
        assertEquals(404, post("/approvals/$id", """{"approve":false}""").status.value, "answered already")
    }

    @Test
    fun aSharedRigAcceptsItsAllowedHostsOnly() = runBlocking {
        val p2 = ServerSocket(0).use { it.localPort }
        val g = TestlabMcp(rt, McpEndpoint(p2)).gateway(listen = "127.0.0.1", allowedHosts = listOf("rig.example.ts.net", "203.0.113.7"))
        g.start(McpEndpoint(p2))
        try {
            suspend fun status(host: String) = http.get("http://127.0.0.1:$p2/api/v1/status") { header("Authorization", "Bearer $token"); header("Host", "$host:$p2") }.status.value
            assertEquals(200, status("rig.example.ts.net"))
            assertEquals(200, status("RIG.example.ts.net"))
            assertEquals(200, status("203.0.113.7"))
            assertEquals(403, status("evil.example"))
            val mcp = http.post("http://127.0.0.1:$p2/mcp") {
                header("Authorization", "Bearer $token"); header("Host", "evil.example:$p2"); header("Accept", "application/json, text/event-stream")
                contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
            }
            assertEquals(403, mcp.status.value, "the SDK's DNS-rebinding check uses the same host list")
            val ok = http.post("http://127.0.0.1:$p2/mcp") {
                header("Authorization", "Bearer $token"); header("Host", "rig.example.ts.net:$p2"); header("Accept", "application/json, text/event-stream")
                contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
            }
            assertEquals(200, ok.status.value, ok.bodyAsText())
        } finally { g.stop() }
        assertEquals(setOf("127.0.0.1", "localhost", "::1", "testlab.localhost", "203.0.113.7"), McpGateway.hostsFor("203.0.113.7", emptyList()))
        kotlin.test.assertFailsWith<IllegalArgumentException> { McpGateway.hostsFor("0.0.0.0", emptyList()) }
        assertEquals("http://[fd7a::1]:8378/mcp", McpEndpoint.forBind(8378, "fd7a::1").url)
        assertEquals("http://127.0.0.1:8378/mcp", McpEndpoint.forBind(8378, "0.0.0.0").url)
    }

    @Test
    fun aHostAppStartsADevtestAndGetsTheClaudePrompt() = runBlocking {
        val r = post("/devtest", """{"key":"shop-1234","pr":"#4321","branch":"feature/SHOP-1234-cart","start":"claude"}""")
        assertEquals(200, r.status.value, r.bodyAsText())
        val j = r.json()
        assertEquals("SHOP-1234", j["key"]!!.jsonPrimitive.content)
        assertEquals("4321", j["pr"]!!.jsonPrimitive.content)
        assertTrue(j["report_id"]!!.jsonPrimitive.content.contains("devtest-shop-1234"), j.toString())
        assertEquals("/mcp__testlab__devtest SHOP-1234", j["claude_prompt"]!!.jsonPrimitive.content, "the host starts its own Claude with it")
        assertEquals("feature/SHOP-1234-cart", get("/devtest/SHOP-1234").json()["branch"]!!.jsonPrimitive.content)
        assertEquals(400, post("/devtest", """{"key":"nope"}""").status.value)
        assertEquals(400, post("/devtest", "not json").status.value)
        assertEquals("null", post("/devtest", """{"key":"SHOP-1234"}""").json()["claude_prompt"].toString(), "no prompt unless asked for")
    }

    @Test
    fun eventsStreamToolCalls() = runBlocking {
        val first = async {
            http.prepareGet("$base/events") { header("Authorization", "Bearer $token") }.execute { r ->
                val ch = r.bodyAsChannel()
                withTimeout(10_000) {
                    while (true) { val l = ch.readUTF8Line() ?: break; if (l.startsWith("data:")) return@withTimeout l }
                    ""
                }
            }
        }
        // The stream has no replay: keep emitting until the subscriber has seen one.
        while (!first.isCompleted) { rt.emit(LabEvent.ToolCall("handsets_tap", "Neste", 12, true)); delay(200) }
        assertTrue("handsets_tap" in first.await())
    }

    @Test
    fun profilesAndErrorsAreJson() = runBlocking {
        assertTrue("example-shop" in get("/profiles").bodyAsText())
        assertEquals(400, get("/builds/branch").status.value)
        val e = post("/profiles/nope/activate").json()
        assertTrue("nope" in e["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertEquals(404, get("/jobs/zzz").status.value)
    }
}
