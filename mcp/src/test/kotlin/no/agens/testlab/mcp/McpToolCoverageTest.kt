package no.agens.testlab.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import no.agens.testlab.services.runtime.TalkBack
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.device.adb.AdbDevice
import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.device.hs.FakePhone
import no.agens.testlab.device.hs.HsEndpoint
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.builds.MockGitHub
import no.agens.testlab.services.runtime.Approver
import no.agens.testlab.services.runtime.DeviceBackend
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every tool once, through the same call path as MCP, against a scripted phone, an in-process
 * adb and a mock GitHub. The secret-leak, auth and protocol checks are in McpServerTest; this one
 * proves each tool does its job and fails with a clear sentence.
 */
class McpToolCoverageTest {
    private val tmp = Files.createTempDirectory("tl-tools").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    @Volatile private var locked = false
    private var installOutput = "Performing Streamed Install\nSuccess"
    /** The phone's enabled accessibility services; TalkBack is on at the start. */
    @Volatile private var a11y = "com.example.other/.Svc:${TalkBack.GOOGLE_COMPONENT}"
    @Volatile private var simState = "READY"
    @Volatile private var fontScale = "1.0"
    private val adb = FakeAdb { args ->
        val cmd = args.drop(3).joinToString(" ")
        when {
            args.getOrNull(0) == "mdns" -> "List of discovered mdns services\nadb-R5CT\t_adb-tls-connect._tcp\t10.0.0.50:37123\nadb-R5CT\t_adb-tls-pairing._tcp\t10.0.0.50:40001"
            args.getOrNull(0) == "pair" -> "Successfully paired to 10.0.0.50:40001 [guid=adb-R5CT]"
            args.getOrNull(0) == "connect" && args[1] == "10.0.0.9:5555" -> "failed to connect to '10.0.0.9:5555': Connection refused"
            args.getOrNull(0) == "connect" -> "connected to ${args[1]}"
            args.getOrNull(0) == "disconnect" -> "disconnected ${args[1]}"
            args.getOrNull(2) == "uninstall" -> "Success"
            args.getOrNull(2) == "install" -> installOutput
            "grep -i talkback" in cmd -> "package:${TalkBack.GOOGLE_PACKAGE}"
            cmd.startsWith("settings get secure enabled_accessibility_services") -> a11y.ifEmpty { "null" }
            cmd.startsWith("dumpsys accessibility") -> "touchExplorationEnabled=${"talkback" in a11y}"
            cmd.startsWith("settings put secure enabled_accessibility_services") -> {
                a11y = cmd.removePrefix("settings put secure enabled_accessibility_services ").substringBefore(";").trim().trim('\'', '"'); ""
            }
            cmd.startsWith("am start") -> "Status: ok\nActivity: com.example.shop.feature/.MainActivity"
            cmd.startsWith("dumpsys window") -> "mDreamingLockscreen=$locked"
            cmd.startsWith("getprop gsm.sim.state") -> simState
            "echo \"system.font_scale=" in cmd -> "system.font_scale=$fontScale\nglobal.http_proxy=null"
            cmd.startsWith("settings get system font_scale") -> fontScale
            cmd.startsWith("settings put system font_scale") -> { fontScale = cmd.removePrefix("settings put system font_scale ").trim(); "" }
            cmd.startsWith("input keyevent 61") || cmd.startsWith("input keycombination") -> "mInputShown=false"
            cmd.startsWith("input keyevent KEYCODE_ENTER") -> { locked = false; "" }
            cmd.startsWith("pm path") -> "package:/data/app/x/base.apk"
            cmd.startsWith("pm list packages") -> "package:com.example.shop.feature\npackage:com.example.shop.main"
            cmd.startsWith("pm disable-user") -> "Package x new state: disabled-user"
            cmd.startsWith("pm enable") -> "Package x new state: enabled"
            else -> ""
        }
    }
    private val gh = MockGitHub()
    private val jira = no.agens.testlab.services.jira.MockJira()

    private val phone = FakePhone(McpServerTest.SCREEN)
    private val rt = LabRuntime(
        dirs, SettingsStore(File(dirs.data, "settings.json")),
        MemorySecretStore(mapOf("account.demo.nnin" to McpServerTest.NNIN, "device_pin" to "1234", "mcp.token" to "t0ken-for-tests", "github.token" to "ghp_coverage_0123456789", "jira.email" to "test@example.com", "jira.token" to "jira-coverage-token-0123")),
        adbOverride = adb.cli,
        backendOverride = object : DeviceBackend {
            override fun devices() = listOf(AdbDevice("fake-1", "device", model = "Fake_Phone"))
            override fun connect(serial: String, takeOver: Boolean) = HsEndpoint(serial, phone.port)
            override fun release(serial: String) {}
        },
    ).also { no.agens.testlab.services.profile.ExampleProfile.install(it) }.also { it.githubHttp = gh.client; it.jiraHttp = jira.client }
    private val tools = TestlabMcp(rt).tools
    private val called = mutableSetOf<String>()
    private val calls get() = adb.calls.toList()

    @AfterTest
    fun tearDown() { rt.close(); phone.close(); tmp.deleteRecursively() }

    private fun call(name: String, vararg args: Pair<String, Any>): CallToolResult = runBlocking {
        called += name
        tools.call(name, JsonObject(args.associate { (k, v) -> k to (if (v is kotlinx.serialization.json.JsonElement) v else if (v is Number) JsonPrimitive(v) else if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString())) }))
    }

    private val CallToolResult.text get() = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    private fun ok(name: String, vararg args: Pair<String, Any>): String {
        val r = call(name, *args)
        assertTrue(r.isError != true, "$name failed: ${r.text}")
        return r.text
    }

    private fun fails(name: String, vararg args: Pair<String, Any>): String {
        val r = call(name, *args)
        assertEquals(true, r.isError, "$name should fail: ${r.text}")
        assertTrue(r.text.isNotBlank() && "Exception" !in r.text, "a plain sentence, not a stack trace: ${r.text}")
        return r.text
    }

    private fun github() {
        val repo = "/repos/example-org/shop-android"
        val now = Instant.now()
        gh.json("GET $repo/pulls/42", """{"number":42,"title":"SHOP-1","draft":false,"state":"open","html_url":"u","head":{"ref":"feature/SHOP-1-x","sha":"abc1234def"}}""")
        gh.json("GET $repo/actions/workflows/build_feature.yml/runs?branch=feature%2FSHOP-1-x&status=success",
            """{"workflow_runs":[{"id":900,"name":"Build Feature","path":".github/workflows/build_feature.yml","head_branch":"feature/SHOP-1-x","head_sha":"abc1234def","status":"completed","conclusion":"success","event":"pull_request","run_number":77,"created_at":"${now.minusSeconds(600)}","html_url":"https://github.com/example-org/shop-android/actions/runs/900"}]}""")
        gh.json("GET $repo/actions/workflows/build_feature.yml/runs?branch=feature%2FSHOP-1-x&status=", """{"workflow_runs":[]}""")
        // The run a dispatch creates: GitHub lists it a moment after the dispatch, so its time is "now" when asked.
        gh.on("GET $repo/actions/workflows/build_feature.yml/runs?branch=feature%2FSHOP-1-x&per_page") {
            respond(
                """{"workflow_runs":[{"id":901,"name":"Build Feature","path":"p","head_branch":"feature/SHOP-1-x","head_sha":"abc1234def","status":"queued","conclusion":null,"event":"workflow_dispatch","run_number":78,"created_at":"${Instant.now().toString().take(19)}Z","html_url":"https://github.com/example-org/shop-android/actions/runs/901"}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        gh.json("GET $repo/actions/runs/900/artifacts",
            """{"artifacts":[{"id":502,"name":"app-feature-firebase.apk","size_in_bytes":9000,"expired":false,"created_at":"${now.minusSeconds(600)}","expires_at":"${now.plusSeconds(86400)}","workflow_run":{"id":900,"head_branch":"feature/SHOP-1-x","head_sha":"abc1234def"}}]}""")
        gh.on("GET $repo/actions/artifacts/502/zip") { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://storage.example/502.zip?sig=x")) }
        gh.on("GET https://storage.example/502.zip") { respond(MockGitHub.artifactZip(MockGitHub.FIXTURE, "app-feature-firebase.apk"), HttpStatusCode.OK) }
        gh.json("GET $repo/releases?per_page=",
            """[{"tag_name":"8.7.0-rc2","name":"8.7.0","published_at":"2026-09-30T10:00:00Z","prerelease":false,"assets":[{"id":71,"name":"app-releaseDebug-minifiedDebug.apk","size":9000,"url":"https://api.github.com$repo/releases/assets/71"}]}]""")
        gh.on("GET $repo/releases/assets/71") { respond(MockGitHub.FIXTURE.readBytes(), HttpStatusCode.OK) }
        gh.on("POST $repo/actions/workflows/build_feature.yml/dispatches") { respond("", HttpStatusCode.NoContent) }
    }

    @Test
    fun everyToolOnce() {
        rt.approver = Approver { _, _ -> true }
        github()

        val devices = ok("handsets_list_adb_devices")
        assertTrue("fake-1" in devices && "\"transport\":\"usb\"" in devices && "\"nearby\":[{\"name\":\"adb-R5CT\",\"address\":\"10.0.0.50:37123\"}]" in devices, devices)
        assertTrue("Now handsets_use_device(serial=\"10.0.0.50:37123\")" in ok("handsets_connect_device", "address" to "10.0.0.50:37123", "pairing_code" to "123456", "pairing_address" to "10.0.0.50:40001"))
        assertTrue(calls.containsAll(listOf("pair 10.0.0.50:40001 123456", "connect 10.0.0.50:37123")), calls.toString())
        assertTrue("pairing_address" in fails("handsets_connect_device", "address" to "10.0.0.2:5555", "pairing_code" to "123456"))
        assertTrue("refused" in fails("handsets_connect_device", "address" to "10.0.0.9:5555"))
        ok("handsets_disconnect_device", "address" to "10.0.0.50:37123")
        assertTrue(ok("handsets_use_device").startsWith("ready fake-1"))
        assertTrue("Android 16" in ok("handsets_info"))
        val shot = call("handsets_screenshot", "size" to 480)
        assertEquals("image/jpeg", (shot.content.first { it is ImageContent } as ImageContent).mimeType)
        assertTrue("tap pixels" in shot.text, "the factor from the picture to tap pixels: ${shot.text}")
        assertTrue("\"width\":" in ok("handsets_show_screen"))
        assertEquals("image/jpeg", (call("handsets_view_frame", "size" to 480).content.first() as ImageContent).mimeType)
        assertTrue("handsets_show_screen" in ok("handsets_view_activity"))
        // Terminal panes: raw RGB fitted into the box (aspect kept), or PNG.
        val rgb = Json.parseToJsonElement(ok("handsets_view_frame", "format" to "rgb", "w" to 20, "h" to 40)).jsonObject
        val (rw, rh) = rgb["w"]!!.jsonPrimitive.content.toInt() to rgb["h"]!!.jsonPrimitive.content.toInt()
        assertTrue(rw <= 20 && rh <= 40 && (rw == 20 || rh == 40), "fitted: ${rw}x$rh")
        assertEquals(rw * rh * 3, java.util.Base64.getDecoder().decode(rgb["data"]!!.jsonPrimitive.content).size)
        val png = java.util.Base64.getDecoder().decode(Json.parseToJsonElement(ok("handsets_view_frame", "format" to "png", "w" to 60)).jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals(listOf(0x89, 'P'.code, 'N'.code, 'G'.code), png.take(4).map { it.toInt() and 0xff })
        val file = java.io.File(Json.parseToJsonElement(ok("handsets_view_frame", "format" to "file", "w" to 60)).jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals(0x89, file.readBytes()[0].toInt() and 0xff, "a PNG file the terminal reads: ${file.path}")
        assertTrue("\"Neste\"" in ok("handsets_ui_dump", "form" to "tree"))
        val a11yDump = ok("handsets_ui_dump", "form" to "a11y")
        assertTrue("text=\"Neste\"" in a11yDump && "clickable" in a11yDump, a11yDump)
        assertTrue("via tap" in ok("handsets_tap", "selector_or_text" to "Neste"))
        // TalkBack: taps and swipes are injected past it; the tool turns it off and back on exactly.
        assertTrue("talkback: on (running)" in ok("handsets_info"))
        assertEquals("ok", ok("handsets_tap_xy", "x" to 1, "y" to 2, "outside_app" to true))
        assertEquals("ok", ok("handsets_swipe", "direction" to "up"))
        val off = ok("handsets_talkback", "action" to "off")
        assertTrue("TalkBack on fake-1: off" in off && "turned off by" in off, off)
        assertEquals("com.example.other/.Svc", a11y, "only TalkBack was removed")
        assertTrue("running" in ok("handsets_talkback", "action" to "on"))
        assertEquals("com.example.other/.Svc:${TalkBack.GOOGLE_COMPONENT}", a11y, "restored exactly")
        assertTrue("running" in ok("handsets_talkback"))
        // Real TalkBack gestures: a virtual touchscreen fed to `adb shell hid -`.
        val touched = ok("handsets_talkback_touch", "selector_or_text" to "Neste")
        assertTrue("(540,2070)" in touched, touched)
        val hid = calls.last { "shell hid -" in it }
        assertTrue("\"command\":\"register\"" in hid && "\"name\":\"testlab-touch\"" in hid, hid.take(300))
        assertTrue("\"duration\":600" in hid, "explore = one touch held 600 ms")
        ok("handsets_talkback_touch", "x" to 10, "y" to 20)
        ok("handsets_talkback_activate")
        assertEquals(2, Regex("\"report\":\\[1,1,").findAll(calls.last { "shell hid -" in it }).count(), "double-tap = two touch-downs")
        assertTrue("next" in ok("handsets_talkback_swipe", "direction" to "next"))
        ok("handsets_talkback_swipe", "direction" to "previous")
        assertTrue("x and y" in fails("handsets_talkback_touch"))
        ok("handsets_talkback", "action" to "off")
        assertTrue("isn't on" in fails("handsets_talkback_activate"), "no double-tap without TalkBack: it would click what is under it")
        ok("handsets_talkback", "action" to "on")
        // Keyboard and large text.
        assertTrue("keyboard hidden" in ok("handsets_key", "key" to "tab", "count" to 2))
        assertTrue(calls.any { "input keyevent 61; input keyevent 61" in it }, calls.takeLast(3).toString())
        ok("handsets_key", "key" to "shift_tab")
        assertTrue(calls.any { "input keycombination 59 61" in it })
        assertTrue("unknown key" in fails("handsets_key", "key" to "hyperdrive"))
        assertTrue("1.5" in ok("handsets_font_scale", "action" to "set", "scale" to 1.5) && fontScale == "1.5")
        assertTrue("was 1.0" in ok("handsets_font_scale"))
        ok("handsets_font_scale", "action" to "reset")
        assertEquals("1.0", fontScale, "reset puts back exactly what was there")
        ok("handsets_type_text", "text" to "abc")
        ok("handsets_type_secret", "ref" to "demo.nnin")
        assertTrue("other.thing" in fails("handsets_type_secret", "ref" to "other.thing"), "a missing credential is named")
        assertTrue(ok("handsets_fill", "selector" to "id=com.example.app:id/nnin", "value" to "x").startsWith("ok set_text"))
        ok("handsets_submit")
        assertEquals("no keyboard showing", ok("handsets_hide_keyboard"))
        ok("handsets_navigate", "target" to "home")
        assertTrue(ok("handsets_wait_for", "selector_or_text" to "Velkommen", "timeout_s" to 1).startsWith("ok"))
        ok("handsets_open_app", "package" to "com.example.app")
        ok("handsets_close_app", "package" to "com.example.app")
        assertTrue("com.example.app" in ok("handsets_list_apps", "third_party_only" to true))
        assertTrue("/" in ok("handsets_current_focus"))

        assertEquals("Success", ok("handsets_uninstall_app", "package" to "com.example.app"))
        assertTrue(calls.any { it == "-s fake-1 uninstall com.example.app" }, calls.toString())

        assertEquals("not locked", ok("handsets_unlock_device"))
        locked = true
        assertEquals("unlocked", ok("handsets_unlock_device"))
        assertTrue(calls.any { it == "-s fake-1 shell input text '1234'" }, "the stored PIN is typed: $calls")
        // After a reboot the SIM wants its PIN first: unlock refuses rather than type the device PIN there.
        locked = true; simState = "PIN_REQUIRED,ABSENT"
        val typedBefore = calls.count { "input text" in it }
        assertTrue("SIM" in fails("handsets_unlock_device"))
        assertEquals(typedBefore, calls.count { "input text" in it }, "nothing typed into the SIM PIN field")
        simState = "READY"; ok("handsets_unlock_device")

        val go = ok("handsets_go", "destination" to "books", "subpage" to "results")
        assertTrue("https://www.example.com/shop/books/results" in go, go)
        assertTrue(calls.any { "android.intent.action.VIEW -d 'https://www.example.com/shop/books/results' 'com.example.shop.feature'" in it }, calls.toString())
        assertTrue("shop" in ok("handsets_go", "destination" to "list") && "books music" in ok("handsets_go", "destination" to "list"))
        assertTrue("unknown destination" in fails("handsets_go", "destination" to "casino"))
        ok("handsets_open_url", "url" to "/search?q=books&rows=[1,2,3]")
        assertTrue(calls.any { "-d 'https://www.example.com/search?q=books&rows=[1,2,3]'" in it }, "the & survives the device shell: $calls")
        phone.screen = TAB_SCREEN
        assertTrue("via" in ok("handsets_go", "destination" to "profil"))

        phone.screen = McpServerTest.SCREEN
        assertTrue("\"choice\"" in ok("handsets_autopilot_pick", "goal" to "Press Neste", "backend" to "stub"))
        ok("handsets_autopilot_step", "goal" to "Press Neste", "backend" to "stub")
        fails("handsets_autopilot_find", "goal" to "the back arrow").let { assertTrue("TypeSafe" in it || "image" in it, it) }
        // The fake phone can't run the touch injector: the drag falls back to input motionevent, and says so.
        assertTrue("input motionevent" in ok("handsets_drag", "points" to "[[100,200],[300,400],[500,400]]", "duration_ms" to 300, "outside_app" to true))
        assertTrue(calls.any { "app_process" in it && "'100,200;300,400;500,400' 300 150 1" in it }, calls.takeLast(5).toString())
        assertTrue(calls.any { "input motionevent DOWN 100 200" in it && "input motionevent UP 500 400" in it }, calls.takeLast(5).toString())
        assertTrue("at least two" in fails("handsets_drag", "points" to "[[1,2]]"))
        assertTrue("display 0" in fails("handsets_orientation"), "the fake phone has no window dump")
        assertTrue(ok("handsets_home", "close_apps" to false) == "ok: home" && calls.any { "input keyevent KEYCODE_HOME" in it })
        assertTrue("\"status\"" in ok("handsets_autopilot_do", "goal" to "Open Neste", "backend" to "stub", "max_steps" to 2))
        assertTrue("\"completed\"" in ok("handsets_autopilot_plan", "plan" to """[{"tap":"Neste"},{"sleep":0}]""", "backend" to "stub"))
        assertTrue(ok("lab_save_plan", "name" to "cover", "plan" to """[{"note":"coverage"},{"tap":"Neste"}]""").startsWith("saved cover"))
        assertTrue("cover" in ok("lab_list_plans"))
        assertTrue("coverage" in ok("lab_read_plan", "name" to "cover"))
        assertTrue("\"completed\"" in ok("handsets_autopilot_plan_file", "name" to "cover", "backend" to "stub"))
        val login = fails("handsets_login", "backend" to "stub")
        assertTrue("demo.login_password" in login && "not set" in login, "missing Login fields are refused before the run: $login")
        assertTrue(calls.none { "pm disable-user" in it }, "nothing was disabled for a login that never started")

        // Builds.
        val list = ok("handsets_list_branch_builds", "branch_or_pr" to "42")
        assertTrue("\"branch\":\"feature/SHOP-1-x\"" in list && "\"latest_commit\":true" in list && "app-feature-firebase.apk" in list, list)
        assertTrue("\"building\":null" in list, list)
        gh.json("GET /repos/example-org/shop-android/actions/workflows/build_feature.yml/runs?branch=feature%2FSHOP-1-x&status=in_progress",
            """{"workflow_runs":[{"id":902,"name":"Build Feature","path":"p","head_branch":"feature/SHOP-1-x","head_sha":"fff0000","status":"in_progress","conclusion":null,"event":"pull_request","run_number":79,"created_at":"${Instant.now()}","html_url":"https://github.com/example-org/shop-android/actions/runs/902"}]}""")
        val busy = ok("handsets_list_branch_builds", "branch_or_pr" to "42")
        assertTrue("\"building\":{\"run_id\":902,\"status\":\"in_progress\"" in busy, "a newer run still going is reported: $busy")
        gh.json("GET /repos/example-org/shop-android/actions/workflows/build_feature.yml/runs?branch=feature%2FSHOP-1-x&status=in_progress", """{"workflow_runs":[]}""")
        val inst = ok("handsets_install_branch_build", "branch_or_pr" to "42")
        assertTrue("\"package\":\"no.agens.testlab.fixture\"" in inst && "\"install\":\"Success\"" in inst, inst)
        installOutput = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package no.agens.testlab.fixture signatures do not match newer version; ignoring!]"
        val clash = ok("handsets_install_branch_build", "branch_or_pr" to "42")
        assertTrue("\"problem\":\"INSTALL_FAILED_UPDATE_INCOMPATIBLE\"" in clash && "handsets_uninstall_app(\\\"no.agens.testlab.fixture\\\")" in clash, clash)
        installOutput = "Success"
        assertTrue("8.7.0-rc2" in ok("handsets_list_builds"))
        assertTrue("\"tag\":\"8.7.0-rc2\"" in ok("handsets_install_build"))
        val installed = ok("handsets_installed_builds")
        assertTrue("\"tag\":\"8.7.0-rc2\"" in installed, "the release replaced the branch build of the same package: $installed")
        assertTrue("runs/901" in ok("handsets_start_branch_build", "branch_or_pr" to "42"))
        rt.approver = Approver { _, _ -> false }
        assertTrue("not approved" in fails("handsets_start_branch_build", "branch_or_pr" to "42"))

        // Recording and reports (the fake phone streams a 3-second H.264 clip).
        assertTrue(ok("lab_session_start").startsWith("Recording fake-1"))
        assertEquals("Already recording fake-1.", ok("lab_session_start"))
        assertTrue("recording" in ok("lab_session_status"))
        val rid = ok("lab_report_start", "title" to "Coverage").lines().first().removePrefix("report_id: ")
        assertTrue("![Start](01-start.jpg)" in ok("lab_report_screenshot", "report_id" to rid, "caption" to "Start"))
        fun item(id: String, text: String) = JsonObject(mapOf("id" to JsonPrimitive(id), "text" to JsonPrimitive(text)))
        assertTrue("2 item(s)" in ok("lab_report_items", "report_id" to rid, "items" to kotlinx.serialization.json.JsonArray(listOf(item("1", "The cart opens"), item("2", "Push opens the cart")))))
        assertTrue("1 item(s) left" in ok("lab_report_item", "report_id" to rid, "item" to "1", "status" to "tested", "result" to "pass", "evidence" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("01-start.jpg")))))
        assertTrue("needs the reason" in fails("lab_report_item", "report_id" to rid, "item" to "2", "status" to "not_tested"))
        assertTrue("neither tested nor marked" in fails("lab_report_write", "report_id" to rid, "markdown" to "x"), "an unaccounted item stops the report")
        assertTrue("0 item(s) left" in ok("lab_report_item", "report_id" to rid, "item" to "2", "status" to "not_tested", "note" to "needs a second device"))
        assertTrue("noted" in ok("lab_session_note", "report_id" to rid, "text" to "Why", "kind" to "chapter"))
        assertTrue("\"completed\"" in ok("handsets_autopilot_plan", "plan" to """[{"screenshot":"From the plan"}]""", "report_id" to rid, "backend" to "stub"), "plan screenshots land in the report")
        Thread.sleep(300)
        val written = ok("lab_report_write", "report_id" to rid, "markdown" to "Alt ok.\n\n![Start](01-start.jpg)")
        assertTrue("Report written: file:" in written && "recording: session.mp4" in written, written)
        assertTrue("Result (from the plan items): No failures in what was tested. 1 item(s) not tested." in written, written)
        val md = File(rt.reports.folder(rid), "report.md").readText()
        assertTrue("### Not tested\n- **2** Push opens the cart: needs a second device" in md && "[01-start.jpg](01-start.jpg)" in md, md)
        assertTrue("event(s)" in ok("lab_report_annotate", "report_id" to rid))
        // No HTTP pages in this process (and no browser on a CI runner): it says how to open the report instead.
        assertTrue("open report $rid" in ok("lab_open_page", "page" to "report", "id" to rid) || "Opened the report" in ok("lab_open_page", "page" to "report", "id" to rid))
        assertTrue("unknown report_id" in fails("lab_open_page", "page" to "report", "id" to "nope"))
        assertTrue(rid in ok("lab_list_reports"))
        // The inline report view: the page, then the recording in pieces.
        assertTrue("\"id\":\"$rid\"" in ok("lab_show_report"), "empty = the newest written report")
        val page = Json.parseToJsonElement(ok("lab_view_report", "report_id" to rid)).jsonObject
        assertTrue("data:image/jpeg;base64," in page["html"]!!.jsonPrimitive.content, "screenshots inlined")
        val video = page["videos"]!!.jsonArray.first().jsonObject
        assertEquals("session.mp4", video["file"]!!.jsonPrimitive.content)
        val piece = Json.parseToJsonElement(ok("lab_view_report_file", "report_id" to rid, "file" to "session.mp4")).jsonObject
        val bytes = java.util.Base64.getDecoder().decode(piece["data"]!!.jsonPrimitive.content)
        assertEquals(minOf(video["size"]!!.jsonPrimitive.long, 1L shl 20), bytes.size.toLong())
        assertTrue("only a report's .mp4" in fails("lab_view_report_file", "report_id" to rid, "file" to "../settings.json"))
        val chFrame = Json.parseToJsonElement(ok("lab_view_report_frame", "report_id" to rid, "t" to 0, "caption" to "Start", "format" to "rgb", "w" to 10)).jsonObject
        assertTrue(chFrame["source"]!!.jsonPrimitive.content in setOf("video", "screenshot"), chFrame.toString().take(200))
        assertEquals(10, chFrame["w"]!!.jsonPrimitive.content.toInt())
        assertTrue("unknown report_id" in fails("lab_report_screenshot", "report_id" to "nope", "caption" to "x"))
        val cmp = ok("lab_compare_backends", "plan" to """[{"tap":"Neste","verify":false},{"wait_for":"Never","timeout":0.2}]""", "name" to "cmp", "backends" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("stub"), JsonPrimitive("stub"))))
        assertTrue("Report: file:" in cmp && cmp.lines().count { it.startsWith("Stub (first option): error") && "1 hand-back" in it } == 2, cmp)
        // The side-by-side video needs ffmpeg, which CI runners (and many laptops) don't have; the report works without it.
        val ffmpeg = no.agens.testlab.device.ToolLocator().findOptional("ffmpeg") != null
        assertEquals(ffmpeg, "Side by side:" in cmp, "side by side exactly when ffmpeg is installed: $cmp")
        ok("lab_session_start")
        assertTrue("discarded: fake-1" in ok("lab_session_discard"))

        jiraAndGitHub(rid)

        // Proxyman is off by default: status works, the rest says how to turn it on.
        assertTrue("\"proxy\":\"direct\"" in ok("handsets_proxy"))
        assertTrue("open settings" in fails("handsets_proxy", "action" to "on"))
        assertTrue("\"mcp\":\"off\"" in ok("lab_proxyman_health"))
        assertTrue("Proxyman is off" in fails("lab_proxyman_call", "tool" to "get_flows"))

        // iOS Simulators: off macOS they say so; on a Mac runner they list (or find no such simulator).
        val sims = call("handsets_simulator_start")
        assertTrue(if (sims.isError == true) "macOS" in sims.text else sims.text.isNotBlank(), sims.text)
        val stop = call("handsets_simulator_stop", "serial" to "NO-SUCH-UDID")
        assertTrue(stop.text.isNotBlank(), "a plain answer either way")
        rt.approver = Approver { _, _ -> true }
        assertTrue("Proxyman is off" in fails("lab_reset_proxyman"))
        assertTrue("Proxyman is off" in fails("lab_proxy_force", "kind" to "status", "url" to "https://api.example.com/x", "status" to 503))
        assertTrue("Proxyman is off" in fails("lab_proxy_rules"))
        assertTrue("Proxyman is off" in fails("lab_ws_messages"))
        assertTrue("Proxyman is off" in fails("lab_proxy_cleanup"))

        // The phone's settings across a session, its health, its logs.
        assertTrue("snapshot of fake-1" in ok("lab_device_state", "action" to "snapshot"))
        fontScale = "1.3"
        assertTrue("font_scale: 1.0 → 1.3" in ok("lab_device_state"), "a changed setting shows")
        assertTrue("font_scale: 1.3 → 1.0" in ok("lab_device_state", "action" to "restore"))
        val health = ok("lab_health", "quick" to true)
        assertTrue("\"check\":\"adb\",\"result\":\"pass\"" in health && "\"check\":\"sim\"" in health, health)
        assertTrue("no lines" in ok("lab_logs_read"))
        assertTrue("no log capture running" in ok("lab_logs_stop"))
        assertTrue("no log capture" in ok("lab_analytics_events"))
        assertTrue(call("lab_logs_start", "preset" to "crash").text.isNotBlank())
        rt.logs.stopAll()
        assertTrue("not an .apk" in fails("handsets_install_apk", "path" to "/etc/passwd"))
        // The example profile has no test repos: the tools say so (TestReposTest covers fetching them).
        assertTrue("has no test repos" in ok("lab_test_repos"))
        assertTrue("no test repo 'x'" in fails("lab_test_repo_files", "repo" to "x"))
        assertTrue("no test repo 'x'" in fails("lab_test_repo_read", "repo" to "x", "path" to "a"))
        assertTrue("has no test repos" in ok("lab_test_repo_search", "query" to "a"))
        assertTrue("no test repo of kind maestro" in ok("lab_maestro_flows"))
        // The example profile names no Firebase apps; emulators answer plainly with or without the Android CLI (CrashlyticsTest covers both through a fake CLI).
        assertTrue("which Firebase apps" in fails("lab_crashlytics_issues"))
        assertTrue("which Firebase apps" in fails("lab_crashlytics_issue", "issue_id" to "firebase/x"))
        assertTrue(call("lab_crashlytics_apps").text.isNotBlank())
        assertTrue(call("handsets_emulator_start").text.isNotBlank())
        assertTrue(call("handsets_emulator_create", "profile" to "").text.isNotBlank())
        assertTrue(call("handsets_emulator_stop", "name" to "no-such-avd").text.isNotBlank())
        assertTrue(fails("lab_pr_screens", "pr" to 999999).isNotBlank(), "an unknown PR says so (PrScreensTest covers a real one)")
        // Xcode's MCP server and simulators need a Mac: off it, the tools say so (XcodeMcpTest covers the client).
        if (no.agens.testlab.device.HostOs.current() != no.agens.testlab.device.HostOs.MAC) {
            assertTrue("Mac" in fails("lab_ios_crashes", "bundle_id" to "com.example.shop"))
            assertTrue("Mac" in fails("lab_ios_crash", "signature" to "x", "bundle_id" to "com.example.shop"))
            assertTrue("Mac" in fails("lab_ios_performance", "bundle_id" to "com.example.shop"))
            assertTrue("macOS" in fails("handsets_simulator_create"))
        }
        assertTrue(fails("handsets_control_build", "pr" to "42").isNotBlank())
        assertTrue("Proxyman is off" in fails("lab_report_flows", "report_id" to rid))

        assertTrue("\"demo.nnin\":\"set\"" in ok("lab_list_credentials"))
        // Several test accounts: notes, which can log in, and a login without an account takes a free, complete one.
        val ta = rt.testAccounts
        for (n in listOf("rich", "poor")) for (f in listOf("nnin", "login_one_time_code", "login_password", "app_personal_code")) rt.secrets.put("account.$n.$f", "v-$n-$f")
        ta.add("rich", "500 kr on the account")
        val creds = ok("lab_list_credentials")
        assertTrue("\"rich\":{\"note\":\"500 kr on the account\",\"login_on\":[\"android\"]" in creds, creds)
        assertTrue("\"demo\":{\"login_on\":[]" in creds && "\"poor\":{" in creds, "vault-only accounts are listed, incomplete ones log in nowhere: $creds")
        assertEquals("rich", ta.pick("fake-1"), "the first complete account")
        ta.loggedIn("other-phone", "rich")
        assertEquals("poor", ta.pick("fake-1"), "one no other device is logged in with")
        assertTrue("\"in_use_on\":\"other-phone\"" in ok("lab_list_credentials"))
        ta.loggedIn("fake-1", "poor")
        assertEquals("poor", ta.pick("fake-1"), "the one this device used last")
        ta.remove("poor")
        assertTrue(rt.secrets.keys().none { it.startsWith("account.poor.") } && "poor" !in ta.names())
        val status = ok("lab_status")
        assertTrue("\"active\":\"fake-1\"" in status && "\"jira_login\":" in status, status)
        val profiles = ok("lab_list_profiles")
        assertTrue("* example-shop: Example Shop" in profiles && "  generic:" in profiles, profiles)
        assertTrue("Active profile: generic" in ok("lab_use_profile", "id" to "generic"))
        assertTrue("no profile 'nope'" in fails("lab_use_profile", "id" to "nope"))
        assertTrue("default)" in ok("lab_use_profile", "id" to "example-shop") && rt.profile.id == "example-shop")
        assertTrue("drive-device" in ok("lab_list_skills"))
        assertTrue(ok("lab_read_skill", "name" to "write-plan").contains("# Write a plan"))
        assertTrue("\"decisions\"" in ok("lab_autopilot_stats", "since_minutes" to 5))
        assertEquals("ok", ok("handsets_drop_device"))

        assertEquals(McpServerTest.EXPECTED_TOOLS.toSet(), called, "tools not covered: ${McpServerTest.EXPECTED_TOOLS - called}")
    }

    private fun jiraAndGitHub(rid: String) {
        val repo = "/repos/example-org/shop-android"
        val pr = """{"number":4321,"title":"SHOP-1234: New cart view","body":"Fikser carten","user":{"login":"dev"},"state":"open","draft":false,"merged":false,"head":{"ref":"feature/SHOP-1234-cart","sha":"abc1234def"},"base":{"ref":"main"},"created_at":"2026-09-30T10:00:00Z","updated_at":"2026-10-01T10:00:00Z","additions":10,"deletions":2,"changed_files":2,"labels":[{"name":"android"}],"requested_reviewers":[],"html_url":"https://github.com/example-org/shop-android/pull/4321"}"""
        val diff = "diff --git a/app/Cart.kt b/app/Cart.kt\n+val rows = 10\ndiff --git a/app/Other.kt b/app/Other.kt\n+val other = 1\n"
        gh.on("GET $repo/pulls/4321") { req ->
            if (req.headers[HttpHeaders.Accept]?.contains("diff") == true) respond(diff, HttpStatusCode.OK)
            else respond(pr, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        gh.json("GET $repo/pulls/4321/files", """[{"filename":"app/Cart.kt","status":"modified","additions":10,"deletions":2}]""")
        gh.json("GET /search/issues", """{"items":[{"number":4321,"title":"SHOP-1234: New cart view","state":"open","draft":false,"user":{"login":"dev"},"updated_at":"2026-10-01T10:00:00Z","html_url":"u","pull_request":{}}]}""")
        gh.json("GET $repo/git/matching-refs/heads/", "[]")
        gh.on("GET $repo/contents/app/Cart.kt") { respond("class Cart", HttpStatusCode.OK) }
        gh.json("GET $repo/actions/runs?", """{"workflow_runs":[{"id":900,"name":"Build Feature","path":"p","head_branch":"feature/SHOP-1234-cart","head_sha":"abc1234def","status":"completed","conclusion":"success","event":"pull_request","run_number":77,"created_at":"2026-10-01T10:00:00Z","html_url":"u"}]}""")
        rt.approver = Approver { _, _ -> true }

        val issue = ok("jira_get_issue", "key" to "SHOP-1234")
        assertTrue(issue.startsWith("# SHOP-1234 · New cart view") && "- The cart viser **items**" in issue && "Klar for test på INT" in issue, issue)
        assertTrue("\"key\":\"SHOP-1234\"" in ok("jira_search", "jql" to "project = SHOP"))
        assertTrue("\"warnings\":[]" in ok("jira_devtest_queue"))
        // One ticket per device: the first takes it, a second device beside it finds nothing left.
        assertTrue("\"key\":\"SHOP-1234\"" in ok("jira_devtest_next", "serial" to "fake-1"))
        assertTrue("\"key\":\"SHOP-1234\"" in ok("jira_devtest_next", "serial" to "fake-1"), "the same device gets its own ticket again")
        assertTrue("\"none\":" in ok("jira_devtest_next", "serial" to "fake-2"))
        assertTrue("\"testing_on\":\"fake-1\"" in ok("jira_devtest_queue"))
        val linked = ok("jira_linked_prs", "key" to "SHOP-1234")
        assertTrue("\"single_open_pr\":4321" in linked && "Jira development panel" in linked, linked)
        assertTrue("\"candidates\":[{\"pr\":4321," in linked, "the candidate carries its number: $linked")
        assertTrue("4321" in ok("github_find_prs", "query" to "SHOP-1234"))
        assertTrue("\"pr\":4321" in ok("github_find_prs", "query" to "cart is:open"))
        assertTrue("feature/SHOP-1234-cart → main" in ok("github_get_pr", "number" to 4321))
        assertTrue("feature/SHOP-1234-cart → main" in ok("github_get_pr", "number" to 4321, "platform" to "android"))
        assertTrue("android or ios" in fails("github_get_pr", "number" to 4321, "platform" to "windows"))
        assertTrue("app/Cart.kt" in ok("github_pr_files", "number" to 4321))
        val d = ok("github_pr_diff", "number" to 4321, "path" to "Cart")
        assertTrue("rows" in d && "other" !in d, d)
        assertEquals("class Cart", ok("github_read_file", "path" to "app/Cart.kt", "ref" to "feature/SHOP-1234-cart"))
        assertTrue("\"conclusion\":\"success\"" in ok("github_list_runs"))
        assertTrue("assigned to Utvikler Utviklersen: not changed" in ok("jira_assign", "key" to "SHOP-1234"))
        assertTrue("now assigned to you" in ok("jira_assign", "key" to "SHOP-1234", "force" to true))
        assertTrue("Comment posted" in ok("jira_add_comment", "key" to "SHOP-1234", "markdown" to "Hei **der**"))
        assertTrue("Labels of SHOP-1234: +devTest" in ok("jira_update_labels", "key" to "SHOP-1234", "add" to "devTest"))
        assertTrue("only files inside the reports folder" in fails("jira_attach", "key" to "SHOP-1234", "files" to "/etc/passwd"))
        assertTrue("Attached to SHOP-1234" in ok("jira_attach", "key" to "SHOP-1234", "report_id" to rid))
        val preview = ok("jira_post_devtest_report", "key" to "SHOP-1234", "tested" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("✅ The cart shows the items"))), "result" to "Godkjent.", "outcome" to "approved", "report_id" to rid, "preview" to true)
        assertTrue(preview.startsWith("PREVIEW") && "**Build:** " in preview && "8.7.0-rc2" in preview && "add label devtest-approved" in preview, preview)
        val before = jira.seen.size
        assertTrue("Posted on SHOP-1234" in ok("jira_post_devtest_report", "key" to "SHOP-1234", "tested" to "✅ The cart shows the items", "result" to "Godkjent.", "outcome" to "approved", "report_id" to rid))
        assertTrue(jira.seen.drop(before).any { it.method == "PUT" && "devtest-approved" in it.body.orEmpty() })
        rt.approver = Approver { _, _ -> false }
        assertTrue("Not approved" in fails("jira_add_comment", "key" to "SHOP-1234", "markdown" to "x"))
        assertTrue("is not a Jira issue key" in fails("jira_get_issue", "key" to "hello"))
    }

    companion object {
        val TAB_SCREEN = """{"ts":1,"root":{"cls":"android.widget.FrameLayout","pkg":"com.example.shop.feature","bounds":[0,0,1080,2400],"flags":"ev","children":[
            {"cls":"android.widget.FrameLayout","rid":"com.example.shop.feature:id/home_screen","desc":"Hjem","bounds":[0,2250,270,2400],"flags":"cev"},
            {"cls":"android.widget.FrameLayout","rid":"com.example.shop.feature:id/fragment_profile","desc":"Profil","bounds":[810,2250,1080,2400],"flags":"cev"}]}}"""
    }
}
