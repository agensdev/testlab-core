package no.agens.testlab.mcp

import kotlinx.serialization.json.jsonArray
import kotlin.test.assertNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.device.adb.AdbDevice
import no.agens.testlab.device.hs.FakePhone
import no.agens.testlab.device.hs.HsEndpoint
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.runtime.Approver
import no.agens.testlab.services.runtime.DeviceBackend
import no.agens.testlab.services.runtime.LabEvent
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The MCP server end to end: a real MCP client over Streamable HTTP against the gateway, with a
 * scripted phone behind the runtime. Covers the tool set, auth, DNS-rebinding checks and that no
 * stored secret ever comes back in a result, an error, an event or a trace.
 */
class McpServerTest {
    private val tmp = Files.createTempDirectory("tl-mcp").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val secretValues = listOf(NNIN, PASSWORD, JEV_KEY, GH_TOKEN, TOKEN)
    private val secrets = MemorySecretStore(
        mapOf(
            "account.demo.nnin" to NNIN,
            "account.demo.login_password" to PASSWORD,
            "jev.key" to JEV_KEY,
            "github.token" to GH_TOKEN,
            "mcp.token" to TOKEN,
        ),
    )
    private val phone = FakePhone(SCREEN)
    private val settings = SettingsStore(File(dirs.data, "settings.json"))
    private val rt = LabRuntime(dirs, settings, secrets, backendOverride = object : DeviceBackend {
        override fun devices() = listOf(AdbDevice("fake-1", "device", model = "Fake_Phone"))
        override fun connect(serial: String, takeOver: Boolean) = HsEndpoint(serial, phone.port)
        override fun release(serial: String) {}
    }).also { no.agens.testlab.services.profile.ExampleProfile.install(it) }
    private val port = ServerSocket(0).use { it.localPort }
    private val mcp = TestlabMcp(rt, McpEndpoint(port))
    private val gateway = mcp.gateway().also { it.start(McpEndpoint(port)) }
    private val http = HttpClient(CIO) { install(SSE) }

    @AfterTest
    fun tearDown() {
        http.close()
        gateway.stop()
        rt.close()
        phone.close()
        tmp.deleteRecursively()
    }

    private suspend fun client(): Client = http.mcpStreamableHttp("http://127.0.0.1:$port/mcp") { header("Authorization", "Bearer $TOKEN") }

    private suspend fun Client.call(name: String, vararg args: Pair<String, Any?>): CallToolResult = callTool(name, args.toMap())

    private val CallToolResult.text: String get() = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    private fun assertNoSecrets(where: String, text: String) {
        for (v in secretValues) assertFalse(v in text, "$where leaks a stored secret: …${text.take(300)}")
    }

    @Test
    fun proxymansOwnToolsAreMirroredOnlyWhenTurnedOn() = runBlocking {
        assertTrue(client().listTools().tools.none { it.name.startsWith("proxyman_") }, "off by default")
        val fake = File(tmp, "mcp-server").apply { writeText(McpServerTest::class.java.getResource("/proxyman/fake-mcp-server.py")!!.readText()); setExecutable(true) }
        settings.update { it.copy(proxyman = it.proxyman.copy(enabled = true, mcpServer = fake.path)) }
        rt.proxyman.refreshTools()
        val c = client()
        val names = c.listTools().tools.map { it.name }
        assertTrue("proxyman_get_flows" in names && "proxyman_create_map_local" in names, names.toString())
        val r = c.call("proxyman_create_map_local", "url" to "https://api.example.com/x")
        assertTrue("\"method\": \"GET\"" in r.text && "method 'ANY' is broken" in r.text, r.text)
        assertTrue("api.example.com" in c.call("proxyman_get_flows", "limit" to 5).text)
        c.close()
    }

    @Test
    fun toolSetIsTheLabsWherePresent() = runBlocking {
        val c = client()
        val tools = c.listTools().tools.associateBy { it.name }
        assertEquals(EXPECTED_TOOLS.sorted(), tools.keys.sorted(), "tool set changed: update EXPECTED_TOOLS deliberately")
        for ((name, labParams) in LAB_SIGNATURES) {
            val ours = tools.getValue(name).inputSchema.properties!!.keys
            assertTrue(ours.containsAll(labParams), "$name lost lab parameters ${labParams - ours}")
        }
        // Required parameters are exactly those without a default.
        assertEquals(listOf("selector_or_text"), tools.getValue("handsets_tap").inputSchema.required)
        assertEquals(listOf("plan"), tools.getValue("handsets_jev_plan").inputSchema.required)
        c.close()
    }

    @Test
    fun liveScreenIsAnMcpAppsView() = runBlocking {
        val c = client()
        val tools = c.listTools().tools.associateBy { it.name }
        val ui = tools.getValue("handsets_show_screen").meta!!["ui"]!!.jsonObject
        assertEquals(Views.SCREEN, ui["resourceUri"]!!.jsonPrimitive.content)
        assertNull(tools.getValue(Views.FRAME).meta, "not app-only: hosts that honour that hide it from their plugins too")
        assertNull(tools.getValue("handsets_tap").meta, "ordinary tools carry no _meta")

        val page = c.readResource(ReadResourceRequest(ReadResourceRequestParams(Views.SCREEN))).contents.single() as TextResourceContents
        assertEquals(Views.MIME, page.mimeType)
        assertTrue("ui/initialize" in page.text && "handsets_view_frame" in page.text && "{{APP}}" !in page.text)

        val shown = Json.parseToJsonElement(c.call("handsets_show_screen").text).jsonObject
        assertEquals("fake-1", shown["serial"]!!.jsonPrimitive.content)
        val frame = c.call(Views.FRAME, "size" to 480)
        assertEquals("image/jpeg", assertIs<ImageContent>(frame.content.first()).mimeType)
        assertEquals(shown["width"], Json.parseToJsonElement((frame.content[1] as TextContent).text).jsonObject["width"])
        c.call("handsets_tap_xy", "x" to 7, "y" to 8, "outside_app" to true)
        val calls = Json.parseToJsonElement(c.call(Views.ACTIVITY).text).jsonObject["calls"]!!.jsonArray.map { it.jsonObject["tool"]!!.jsonPrimitive.content }
        assertEquals("handsets_tap_xy", calls.first(), "newest first")
        assertTrue(Views.FRAME !in calls, "polling stays out of the activity list")
        c.close()
    }

    @Test
    fun theActivityFeedSaysWhatWasDoneAndWhy() = runBlocking {
        val c = client()
        val tools = c.listTools().tools.associateBy { it.name }
        assertTrue("why" in tools.getValue("handsets_tap").inputSchema.properties!!.keys, "acting tools take why")
        assertTrue("why" !in tools.getValue("handsets_ui_dump").inputSchema.properties!!.keys, "reading tools don't")
        c.call("handsets_use_device")
        val tap = c.call("handsets_tap", "selector_or_text" to "Neste", "why" to "go on to the next step of the form")
        assertTrue(tap.isError != true && "tapped" in tap.text, "why never reaches the tool: ${tap.text}")
        val feed = Json.parseToJsonElement(c.call(Views.ACTIVITY).text).jsonObject["calls"]!!.jsonArray.first().jsonObject
        assertEquals("Tap «Neste»", feed["title"]!!.jsonPrimitive.content)
        assertEquals("go on to the next step of the form", feed["why"]!!.jsonPrimitive.content)
        c.call("handsets_tap_xy", "x" to 5, "y" to 6, "outside_app" to true)
        val bare = Json.parseToJsonElement(c.call(Views.ACTIVITY).text).jsonObject["calls"]!!.jsonArray.first().jsonObject
        assertEquals("Tap at 5,6", bare["title"]!!.jsonPrimitive.content)
        assertNull(bare["why"], "no why given, none shown")
        // A blind coordinate tap with another app in front is refused; with the app under test it goes.
        phone.top = "com.android.settings/.SubSettings"
        assertTrue("not tapped: com.android.settings is in front" in c.call("handsets_tap_xy", "x" to 540, "y" to 2070).text)
        phone.top = "com.example.shop.feature/.MainActivity"
        assertTrue("tapped \"Neste\"" in c.call("handsets_tap_xy", "x" to 540, "y" to 2070).text, "a coordinate tap says what it hit")
        phone.top = "com.example.app/.MainActivity"
        val named = Json.parseToJsonElement(c.call(Views.ACTIVITY).text).jsonObject["calls"]!!.jsonArray.first().jsonObject
        assertEquals("Tap «Neste»", named["title"]!!.jsonPrimitive.content)
        c.close()
    }

    @Test
    fun deviceToolsDriveThePhone() = runBlocking {
        val c = client()
        assertTrue(c.call("handsets_use_device").text.startsWith("ready fake-1 (Fake Phone): 1080x2400"))
        val devices = Json.parseToJsonElement(c.call("handsets_list_adb_devices").text).jsonObject
        assertEquals("fake-1", devices["active"]!!.jsonPrimitive.content)

        val table = c.call("handsets_ui_dump").text
        assertTrue("\"Neste\"" in table && "#next" in table, table)
        assertTrue("‹redacted›" in table, "the national ID shown in the field is masked")
        assertNoSecrets("ui_dump", table)
        assertNoSecrets("ui_dump json", c.call("handsets_ui_dump", "form" to "json").text)

        val tap = c.call("handsets_tap", "selector_or_text" to "Neste")
        assertEquals("tapped \"Neste\" cls=android.widget.Button flags=cfev bounds=[100,2000,980,2140] at (540,2070) via tap → ok", tap.text)
        assertEquals(540 to 2070, phone.taps.last())
        assertTrue(phone.nodeCommands.any { it.startsWith("node_click id=com.example.app:id/next text=\"Neste\"") }, "click-by-id tried first")

        val off = c.call("handsets_tap", "selector_or_text" to "Storage")
        assertEquals(true, off.isError)
        assertTrue("off screen" in off.text, off.text)

        c.call("handsets_tap_xy", "x" to 5, "y" to 6, "outside_app" to true)
        assertEquals(5 to 6, phone.taps.last())

        assertEquals("ok typed demo.login_password", c.call("handsets_type_secret", "ref" to "demo.login_password").text)
        assertTrue(PASSWORD in phone.typed, "the value reached the phone")

        val shot = c.call("handsets_screenshot", "size" to 720)
        assertEquals("image/jpeg", assertIs<ImageContent>(shot.content.first()).mimeType)

        assertTrue("Fake Phone" in c.call("handsets_info").text)
        assertEquals("com.example.app/.MainActivity", c.call("handsets_current_focus").text)
        c.call("handsets_navigate", "target" to "back")
        assertEquals("BACK", phone.keys.last())
        assertTrue("com.example.shop.feature" in c.call("handsets_list_apps").text)
        assertTrue(c.call("handsets_wait_for", "selector_or_text" to "velkommen", "timeout_s" to 2).text.startsWith("ok \"Velkommen\""))
        assertEquals(true, c.call("handsets_wait_for", "selector_or_text" to "Kvittering", "timeout_s" to 1).isError)
        assertTrue("books" in c.call("handsets_go", "destination" to "list").text)
        c.close()
    }

    @Test
    fun noSecretInResultsErrorsEventsOrTraces() = runBlocking {
        val events = mutableListOf<LabEvent>()
        val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) { rt.events.collect { synchronized(events) { events += it } } }
        val c = client()
        c.call("handsets_use_device")
        val outputs = mutableListOf<String>()
        outputs += c.call("handsets_type_text", "text" to PASSWORD).text
        outputs += c.call("handsets_tap", "selector_or_text" to PASSWORD).text // error text echoes the query
        outputs += c.call("handsets_fill", "selector" to "id=com.example.app:id/nnin", "cred" to "demo.nnin").text
        outputs += c.call("lab_list_credentials").text
        outputs += c.call("lab_status").text
        val plan = """[{"tap":"Neste"},{"type":{"cred":"demo.nnin"}},{"type":"$PASSWORD"},{"wait_for":"Velkommen","timeout":2},{"screenshot":"after"}]"""
        val run = c.call("handsets_jev_plan", "plan" to plan, "backend" to "stub")
        outputs += run.text
        assertEquals("completed", Json.parseToJsonElement(run.text).jsonObject["status"]!!.jsonPrimitive.content, run.text)
        outputs.forEachIndexed { i, o -> assertNoSecrets("result $i", o) }
        assertTrue(phone.nodeCommands.any { it == "node_set_text id=com.example.app:id/nnin value=\"$NNIN\"" }, "the value reached the phone")

        Thread.sleep(200)
        job.cancel()
        assertTrue(events.isNotEmpty())
        events.forEach { assertNoSecrets("event $it", it.toString()) }
        val traces = dirs.traces.listFiles()!!.filter { it.name.endsWith(".jsonl") }
        assertTrue(traces.isNotEmpty(), "the plan wrote a trace")
        traces.forEach { assertNoSecrets("trace ${it.name}", it.readText()) }
        c.close()
    }

    @Test
    fun jevToolsWithTheStub() = runBlocking {
        val c = client()
        val pick = Json.parseToJsonElement(c.call("handsets_jev_pick", "goal" to "Press Neste", "backend" to "stub").text).jsonObject
        assertEquals(0.8, pick["confidence"]!!.jsonPrimitive.content.toDouble())
        assertNotNull(pick["action"])
        val step = c.call("handsets_jev_step", "goal" to "Press Neste", "backend" to "stub")
        assertFalse(step.isError == true, step.text)
        // Without a key the hosted backend refuses with a clear message instead of guessing.
        rt.secrets.delete("jev.key")
        val noKey = c.call("handsets_jev_pick", "goal" to "Press Neste")
        assertEquals(true, noKey.isError)
        assertTrue("TypeSafe API key not set" in noKey.text, noKey.text)
        c.close()
    }

    @Test
    fun riskyIsRefusedOnTheProductionApp() = runBlocking {
        val c = client()
        phone.top = "com.example.shop/com.example.shop.MainActivity"
        val r = c.call("handsets_jev_step", "goal" to "Press Betal", "allow_risky" to true, "backend" to "stub")
        assertEquals(true, r.isError)
        assertTrue("production app" in r.text, r.text)
        val plan = """[{"open":"com.example.shop"},{"step":"Betal","allow_risky":true}]"""
        phone.top = "com.example.app/.MainActivity"
        assertTrue("production app" in c.call("handsets_jev_plan", "plan" to plan, "backend" to "stub").text)
        settings.update { it.copy(device = it.device.copy(allowRiskyOnProd = true)) }
        phone.top = "com.example.shop/com.example.shop.MainActivity"
        assertFalse(c.call("handsets_jev_pick", "goal" to "Press Neste", "allow_risky" to true, "backend" to "stub").isError == true)
        c.close()
    }

    @Test
    fun writesNeedApproval() = runBlocking {
        val c = client()
        val refused = c.call("handsets_uninstall_app", "package" to "com.example.app")
        assertEquals(true, refused.isError)
        assertTrue("not approved" in refused.text)
        var asked: String? = null
        rt.approver = Approver { _, detail -> asked = detail; false }
        c.call("handsets_uninstall_app", "package" to "com.example.app")
        assertEquals("Uninstall com.example.app from fake-1? Its data (login, settings) is deleted.", asked)
        c.close()
    }

    @Test
    fun plansSkillsPromptsAndResources() = runBlocking {
        val c = client()
        val bad = c.call("lab_save_plan", "name" to "demo", "plan" to """[{"wait_for":""},{"type":{}}]""")
        assertEquals(true, bad.isError)
        assertTrue("step 0" in bad.text && "step 1" in bad.text, bad.text)
        val saved = c.call("lab_save_plan", "name" to "demo", "plan" to """[{"note":"tap next"},{"tap":"Neste"},{"type":{"cred":"other.pin"}}]""")
        assertTrue(saved.text.startsWith("saved demo") && "other.pin" in saved.text, saved.text)
        assertTrue("\"name\":\"demo\"" in c.call("lab_list_plans").text)
        assertTrue("Neste" in c.call("lab_read_plan", "name" to "demo").text)
        assertTrue("other.pin" in c.call("handsets_jev_plan_file", "name" to "demo", "backend" to "stub").text, "a missing credential is refused before the run")
        assertEquals(true, c.call("lab_read_plan", "name" to "../secrets").isError)

        val skills = c.call("lab_list_skills").text
        assertTrue(Skills.BUNDLED.all { "- $it:" in skills }, skills)
        assertTrue(c.call("lab_read_skill", "name" to "drive-device").text.startsWith("---\nname: drive-device"))

        assertEquals(listOf("device-login", "devtest", "drive-device", "write-plan"), c.listPrompts().prompts.map { it.name }.sorted())
        val prompt = c.getPrompt(GetPromptRequest(GetPromptRequestParams("write-plan", mapOf("flow" to "buy a Books coupon", "name" to "books"))))
        assertTrue("lab_save_plan(name=\"books\")" in (prompt.messages.single().content as TextContent).text)

        val resources = c.listResources().resources.map { it.uri }
        assertTrue(listOf("testlab://skills/drive-device", "testlab://schema/plan").all { it in resources }, resources.toString())
        val schema = c.readResource(ReadResourceRequest(ReadResourceRequestParams("testlab://schema/plan"))).contents.single() as TextResourceContents
        assertEquals("array", Json.parseToJsonElement(schema.text).jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue("devtest" in c.serverInstructions.orEmpty())
        c.close()
    }

    @Test
    fun unknownArgumentsAreErrors() = runBlocking {
        val c = client()
        val r = c.call("handsets_tap", "selector" to "Neste")
        assertEquals(true, r.isError)
        assertTrue("has no argument 'selector'" in r.text, r.text)
        c.close()
    }

    @Test
    fun bearerTokenAndHostChecks() {
        val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
        assertEquals(401, rawPost(init, mapOf()))
        assertEquals(401, rawPost(init, mapOf("Authorization" to "Bearer wrong")))
        assertEquals(200, rawPost(init, mapOf("Authorization" to "Bearer $TOKEN")))
        assertEquals(403, rawPost(init, mapOf("Authorization" to "Bearer $TOKEN"), host = "evil.example:$port"))
        assertEquals(403, rawPost(init, mapOf("Authorization" to "Bearer $TOKEN", "Origin" to "http://evil.example")))
    }

    /** A hand-written HTTP/1.1 request, so Host and Origin can be anything; returns the status code. */
    private fun rawPost(body: String, headers: Map<String, String>, host: String = "127.0.0.1:$port"): Int = Socket("127.0.0.1", port).use { s ->
        val bytes = body.encodeToByteArray()
        val req = buildString {
            append("POST /mcp HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\n")
            append("Content-Length: ${bytes.size}\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        s.getOutputStream().apply { write(req.encodeToByteArray()); write(bytes); flush() }
        s.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
    }

    companion object {
        const val NNIN = "01019912345"
        const val PASSWORD = "hunter2-secret"
        const val JEV_KEY = "apikey_test_123456"
        const val GH_TOKEN = "ghp_testtoken123456"
        const val TOKEN = "test-mcp-token-0123456789"

        val SCREEN = """{"ts":1,"root":{"cls":"android.widget.FrameLayout","pkg":"com.example.app","bounds":[0,0,1080,2400],"flags":"ev","children":[
            {"cls":"android.widget.TextView","text":"Velkommen","bounds":[100,200,980,300],"flags":"ev"},
            {"cls":"android.widget.EditText","rid":"com.example.app:id/nnin","text":"$NNIN","bounds":[100,400,980,520],"flags":"cfev"},
            {"cls":"android.widget.Button","rid":"com.example.app:id/next","text":"Neste","bounds":[100,2000,980,2140],"flags":"cfev"},
            {"cls":"android.widget.TextView","text":"Storage","bounds":[210,2402,392,2337],"flags":"e"}]}}"""

        val EXPECTED_TOOLS = listOf(
            "handsets_list_adb_devices", "handsets_connect_device", "handsets_disconnect_device", "handsets_use_device", "handsets_drop_device", "handsets_screenshot", "handsets_ui_dump",
            "handsets_tap", "handsets_tap_xy", "handsets_swipe", "handsets_type_text", "handsets_type_secret", "handsets_fill",
            "handsets_submit", "handsets_hide_keyboard", "handsets_navigate", "handsets_wait_for", "handsets_open_app",
            "handsets_close_app", "handsets_list_apps", "handsets_current_focus", "handsets_info", "handsets_uninstall_app",
            "handsets_unlock_device", "handsets_go", "handsets_open_url", "handsets_talkback",
            "handsets_talkback_touch", "handsets_talkback_activate", "handsets_talkback_swipe", "handsets_key", "handsets_font_scale",
            "handsets_show_screen", "handsets_view_frame", "handsets_view_activity", "lab_show_report", "lab_view_report", "lab_view_report_file", "lab_view_report_frame",
            "handsets_jev_pick", "handsets_jev_find", "handsets_drag", "handsets_orientation", "handsets_home", "handsets_jev_step", "handsets_jev_do", "handsets_jev_plan", "handsets_jev_plan_file", "handsets_login",
            "handsets_list_branch_builds", "handsets_install_branch_build", "handsets_list_builds", "handsets_install_build",
            "handsets_installed_builds", "handsets_start_branch_build",
            "lab_session_start", "lab_session_status", "lab_session_discard", "lab_session_note",
            "lab_report_start", "lab_report_screenshot", "lab_report_write", "lab_report_annotate", "lab_list_reports", "lab_compare_backends",
            "lab_list_plans", "lab_read_plan", "lab_save_plan", "lab_list_credentials", "lab_status", "lab_list_skills",
            "lab_read_skill", "lab_jev_stats", "lab_list_profiles", "lab_use_profile",
            "jira_get_issue", "jira_search", "jira_devtest_queue", "jira_devtest_next", "jira_linked_prs", "jira_add_comment", "jira_attach",
            "jira_update_labels", "jira_assign", "jira_post_devtest_report",
            "github_find_prs", "github_get_pr", "github_pr_files", "github_pr_diff", "github_read_file", "github_list_runs",
            "handsets_simulator_start", "handsets_simulator_stop", "handsets_proxy", "lab_proxyman_health", "lab_reset_proxyman", "lab_proxyman_call", "lab_report_flows",
            "lab_health", "lab_device_state", "lab_logs_start", "lab_logs_stop", "lab_logs_read", "lab_analytics_events",
            "lab_proxy_force", "lab_ws_messages", "lab_proxy_rules", "lab_proxy_cleanup", "lab_report_items", "lab_report_item",
            "handsets_control_build", "handsets_install_apk", "lab_open_page",
            "lab_test_repos", "lab_test_repo_files", "lab_test_repo_read", "lab_test_repo_search", "lab_maestro_flows",
            "handsets_emulator_start", "handsets_emulator_create", "handsets_emulator_stop", "lab_crashlytics_apps", "lab_crashlytics_issues", "lab_crashlytics_issue", "lab_pr_screens", "handsets_simulator_create", "lab_ios_crashes", "lab_ios_crash", "lab_ios_performance",
        )

        /** Parameter names of the lab's tools (opt/hs-mcp/server.py, opt/lab-mcp/server.py) that Testlab also has. */
        val LAB_SIGNATURES = mapOf(
            "handsets_use_device" to setOf("serial"),
            "handsets_drop_device" to setOf("serial"),
            "handsets_screenshot" to setOf("size", "serial"),
            "handsets_ui_dump" to setOf("form", "serial"),
            "handsets_tap" to setOf("selector_or_text", "serial"),
            "handsets_tap_xy" to setOf("x", "y", "outside_app", "serial"),
            "handsets_swipe" to setOf("direction", "serial"),
            "handsets_type_text" to setOf("text", "serial"),
            "handsets_fill" to setOf("selector", "value", "serial"),
            "handsets_navigate" to setOf("target", "serial"),
            "handsets_wait_for" to setOf("selector_or_text", "timeout_s", "serial"),
            "handsets_open_app" to setOf("package", "serial"),
            "handsets_close_app" to setOf("package", "serial"),
            "handsets_list_apps" to setOf("third_party_only", "serial"),
            "handsets_current_focus" to setOf("serial"),
            "handsets_info" to setOf("serial"),
            "handsets_submit" to setOf("serial"),
            "handsets_uninstall_app" to setOf("package", "serial"),
            "handsets_unlock_device" to setOf("pin", "serial"),
            "handsets_go" to setOf("destination", "serial"),
            "handsets_open_url" to setOf("url", "serial"),
            "handsets_jev_pick" to setOf("goal", "serial", "max_candidates", "allow_risky", "backend"),
            "handsets_jev_step" to setOf("goal", "serial", "min_confidence", "allow_risky", "backend"),
            "handsets_jev_do" to setOf("goal", "serial", "max_steps", "min_confidence", "allow_risky", "backend"),
            "handsets_jev_plan" to setOf("plan", "serial", "start", "min_confidence", "report_id", "backend"),
            "handsets_login" to setOf("env", "account", "serial"),
            "handsets_list_branch_builds" to setOf("branch_or_pr"),
            "handsets_install_branch_build" to setOf("branch_or_pr", "serial"),
            "handsets_list_builds" to emptySet(),
            "handsets_install_build" to setOf("tag", "serial"),
            "lab_list_skills" to emptySet(),
            "lab_session_start" to setOf("serial"),
            "lab_session_status" to setOf("serial"),
            "lab_session_discard" to setOf("serial"),
            "lab_session_note" to setOf("report_id", "text", "kind"),
            "lab_report_start" to setOf("title"),
            "lab_report_screenshot" to setOf("report_id", "caption"),
            "lab_report_write" to setOf("report_id", "markdown"),
            "lab_report_annotate" to setOf("report_id", "timeline"),
            "lab_read_skill" to setOf("name"),
            "lab_jev_stats" to setOf("since_minutes"),
        )
    }
}

