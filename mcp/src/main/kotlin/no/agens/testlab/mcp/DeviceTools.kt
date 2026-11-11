package no.agens.testlab.mcp

import no.agens.testlab.core.Platform

import no.agens.testlab.AppIdentity

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.agens.testlab.core.nav.Destinations
import no.agens.testlab.core.sel.NodeQuery
import no.agens.testlab.core.sel.UiNode
import no.agens.testlab.device.hs.HsDevice
import no.agens.testlab.mcp.Param.Companion.SERIAL
import no.agens.testlab.mcp.Param.Companion.bool
import no.agens.testlab.mcp.Param.Companion.int
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.runtime.NotApproved
import no.agens.testlab.services.secrets.SecretStore

internal val compactJson = Json { encodeDefaults = true }
internal fun JsonElement.encode(): String = compactJson.encodeToString(JsonElement.serializer(), this)

/** A credential by reference for [serial]: `acct.field`, or `device_pin` (per device first). */
internal fun LabRuntime.credential(ref: String, serial: String): String {
    if (ref == "device_pin") {
        secrets.get(SecretStore.devicePin(serial))?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return credentials.resolve(ref, serial)
}

/** handsets_key's names: one keycode, or a combination (pressed together). */
private val KEYS: Map<String, List<Int>> = linkedMapOf(
    "tab" to listOf(61), "shift_tab" to listOf(59, 61), "enter" to listOf(66), "space" to listOf(62),
    "escape" to listOf(111), "back" to listOf(4), "del" to listOf(67), "forward_del" to listOf(112),
    "dpad_up" to listOf(19), "dpad_down" to listOf(20), "dpad_left" to listOf(21), "dpad_right" to listOf(22),
    "dpad_center" to listOf(23), "page_up" to listOf(92), "page_down" to listOf(93),
    "move_home" to listOf(122), "move_end" to listOf(123), "search" to listOf(84),
)

internal fun ToolBox.deviceTools(rt: LabRuntime) {
    tool(
        "handsets_list_adb_devices",
        """
        List all devices and their state (online first): Android phones and emulators, and on a Mac
        the booted iOS Simulators.

        Returns JSON {"active": "<serial tools default to>", "devices": [{"serial", "platform", "os",
        "state", "kind", "transport", "model", "hint"}], "nearby": [...]}: every device, however it
        is connected: transport usb, wifi (Android 11+ wireless debugging), ip (adb connect
        host:port), emulator, or simulator (iOS; serial = its UDID). hint says what to do with one that is not ready (unauthorized, offline).
        `nearby` lists phones advertising wireless debugging on this network that are not connected
        yet: handsets_connect_device(address) connects one. Every handsets_* tool takes an optional
        `serial`; omitted = the active device (or the only ready one). To drive two devices, call
        handsets_use_device(serial=...) on each, then pass serial on every call.
        """,
        readOnly = true,
    ) {
        val devs = rt.listDevices().sortedBy { !it.ready }
        val nearby = runCatching { if (rt.locateAdb() == null) emptyList() else rt.adb().mdnsServices() }.getOrDefault(emptyList())
            .filter { (name, kind, _) -> kind == "connect" && devs.none { name in it.serial } }
        // One phone can be listed twice (USB serial and a Wi-Fi/TCP address): the same ro.serialno says so.
        val phoneIds = devs.filter { it.ready && it.platform == no.agens.testlab.core.Platform.ANDROID }.associate { d ->
            d.serial to runCatching { rt.adb().shell(d.serial, "getprop ro.serialno", 5_000).stdout.trim() }.getOrDefault("")
        }.filterValues { it.isNotEmpty() }
        text(buildJsonObject {
            put("active", rt.active.value ?: "")
            putJsonArray("devices") {
                for (d in devs) addJsonObject {
                    put("serial", d.serial)
                    put("platform", d.platform.id)
                    d.os?.let { put("os", it) }
                    put("state", d.state)
                    put("kind", if (d.isEmulator) "emulator" else "physical")
                    put("transport", d.transport)
                    d.model?.let { put("model", it.replace('_', ' ')) }
                    d.hint?.let { put("hint", it) }
                    // Shared phones: one client's space at a time; another's can't be driven from here.
                    when (rt.leases.state(d.serial)) { "other" -> put("lease", "in use by another client"); "yours" -> put("lease", "this space"); else -> {} }
                    phoneIds[d.serial]?.let { id -> phoneIds.filter { it.value == id && it.key != d.serial }.keys.firstOrNull() }?.let {
                        put("same_phone_as", "$it: the same phone under another connection; use one of them and pass that serial on every call")
                    }
                }
            }
            putJsonArray("nearby") { for ((name, _, addr) in nearby) addJsonObject { put("name", name); put("address", addr) } }
        }.encode())
    }

    tool(
        "handsets_connect_device",
        """
        Connect a phone over the network: `adb connect host:port`. For Android 11+ wireless
        debugging (Developer options → Wireless debugging), the phone shows the address to connect
        to; the first time it must be paired: pass pairing_code (the 6 digits from «Pair device with
        pairing code») and pairing_address (the address on that same dialog, a different port).
        For an older phone, `adb tcpip 5555` over USB once, then connect to its-ip:5555. Afterwards
        the phone is in handsets_list_adb_devices like a USB one; handsets_use_device(serial) as usual.
        """,
        str("address", "host:port to connect to (the Wireless debugging screen's IP address & port)"),
        str("pairing_code", "6-digit code, only for the first pairing", ""),
        str("pairing_address", "host:port from the pairing dialog (only with pairing_code)", ""),
    ) { a ->
        val adb = rt.adb()
        val out = mutableListOf<String>()
        a.strOrNull("pairing_code")?.let { code ->
            val pa = a.strOrNull("pairing_address") ?: throw BadArgument("pairing needs pairing_address too (the host:port in the pairing dialog)")
            out += kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { adb.pair(pa, code) }
        }
        out += kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { adb.connect(a.str("address")) }
        text(out.joinToString("\n") + "\nNow handsets_use_device(serial=\"${a.str("address")}\").")
    }

    tool("handsets_disconnect_device", "Disconnect a phone connected over the network (`adb disconnect host:port`); releases the daemon first.", str("address", "host:port")) { a ->
        runCatching { rt.drop(a.str("address")) }
        text(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { rt.adb().disconnect(a.str("address")) }.ifEmpty { "disconnected" })
    }

    tool(
        "handsets_use_device",
        """
        Connect a device and start ${AppIdentity.NAME}'s on-device daemon. Multi-device safe and idempotent.

        Pass the adb serial to prepare a specific device (see handsets_list_adb_devices); defaults to
        the active device, or the only one attached. If another automation tool (Maestro, Appium,
        uiautomator, upstream hs) holds the phone's accessibility connection, this fails and names
        it: close it, or pass take_over=true to stop it.
        """,
        SERIAL,
        bool("take_over", "stop other automation tools that hold the accessibility connection", false),
    ) { a ->
        val dev = rt.use(a.serial, a.bool("take_over") || rt.settings.current.device.takeOver)
        val (w, h) = dev.screenSize()
        val model = runCatching { rt.listDevices().firstOrNull { it.serial == dev.serial }?.model }.getOrNull()
        text("ready ${dev.serial}${model?.let { " (${it.replace('_', ' ')})" } ?: ""}: ${w}x$h, daemon running; this is now the active device")
    }

    tool("handsets_drop_device", "Stop the on-device daemon and release the device.", SERIAL) { a ->
        val s = a.serial ?: rt.active.value ?: throw IllegalStateException("no device in use")
        rt.drop(s)
        text("ok")
    }

    tool(
        "handsets_screenshot",
        """
        Capture the current screen. Returns a JPEG.

        `size` is the long edge in pixels: smaller is faster and fine for most reasoning, larger
        (up to native) is better for dense UIs. FLAG_SECURE screens (PIN, some login pages) cannot be captured.
        """,
        int("size", "long edge in pixels", 1080),
        SERIAL,
        readOnly = true,
    ) { a ->
        val dev = rt.device(a.serial)
        val shot = dev.screenshot(a.int("size").coerceIn(160, 4096), quality = 80)
        Out.Image(shot, caption = screenshotCaption(rt, dev, shot))
    }

    tool(
        "handsets_ui_dump",
        """
        Dump the accessibility tree.

        - "table" (default): verb-led flat list, the canonical input for picking a target: `tap` rows
          are actionable, `-` rows are labels; each row has widget, "label", #resource-id and x,y centre
        - "tree": indented tree
        - "a11y": for accessibility testing: text, content description (what TalkBack reads when
          set) and hint as separate fields, with every state flag (clickable, focusable, checked,
          disabled…). The daemon doesn't report TalkBack's own focus or live regions: a screenshot
          shows the focus frame
        - "json": structured JSON dump
        """,
        str("form", "table | tree | a11y | json", "table", listOf("table", "tree", "a11y", "json")),
        SERIAL,
        readOnly = true,
    ) { a ->
        val dev = rt.device(a.serial)
        // Right after an input the tree may still be the previous screen: wait until it settles, and
        // say which screen it is, so a stale dump shows.
        val screen = runCatching { dev.topActivity() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "screen: $it\n" }.orEmpty()
        text(
            when (a.str("form")) {
                "tree" -> screen + dev.tree(settle = true).trimEnd()
                "a11y" -> screen + no.agens.testlab.core.dump.UiDumpRender.renderA11y(dev.settledDump()).trimEnd()
                "json" -> dev.settledDump().encode()
                else -> screen + dev.table(settle = true).trimEnd()
            },
        )
    }

    tool(
        "handsets_tap",
        """
        Tap a node by text label, #resource-id or CSS-like selector.

        Text matches exact first, then case-insensitive, then substring (text before
        content-description); a label inside a clickable row taps the row. Examples: "Continue",
        "#fragment_games", 'Button:has-text("Sign in")', 'EditText:below(TextView[text=Email])'.
        Nodes that are off screen are refused: swipe first.
        """,
        str("selector_or_text", "label, #id or CSS-like selector"),
        SERIAL,
    ) { a ->
        text(rt.device(a.serial).tapText(a.str("selector_or_text")))
    }

    tool(
        "handsets_tap_xy",
        """
        Tap raw screen coordinates (use the x,y from handsets_ui_dump). Refused when another app is in
        front than the one under test (after a back left the app, coordinates meant for the app land
        in Settings or the launcher): re-read the screen first. `outside_app=true` taps anyway, for a
        system dialog or another app on purpose.
        """,
        int("x", "x in pixels"), int("y", "y in pixels"),
        bool("outside_app", "tap even when the app under test is not in front", false),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        if (!a.bool("outside_app")) ForegroundGuard.check(rt, dev)
        // What is there, so the activity feed (and the caller) can say «Tap "Settings"» rather than a coordinate.
        val label = runCatching { no.agens.testlab.core.dump.UiDumpRender.labelAt(dev.dumpJson(), a.int("x"), a.int("y")) }.getOrNull()
        dev.tap(a.int("x"), a.int("y"))
        text(if (label != null) "ok: tapped ${NodeQuery.debugQuote(label.take(60))}" else "ok")
    }

    tool("handsets_swipe", "Swipe in a cardinal direction (scroll). Re-read the screen afterwards: off-screen nodes are not in the dump.",
        str("direction", "up | down | left | right", enum = listOf("up", "down", "left", "right")), SERIAL) { a ->
        rt.device(a.serial).swipeDir(a.str("direction"))
        text("ok")
    }

    tool(
        "handsets_drag",
        """
        Press, move along a path, release: a finger dragged through `points` ([[x,y],…] in
        handsets_tap_xy pixels), for games and canvases (draw, brush, steer, drag-and-drop, sliders)
        where handsets_swipe (a scroll in one direction) isn't enough. hold_ms = still after the
        press (long-press-then-drag needs ~500); duration_ms = one pass along the whole path, split
        over its legs by length; repeat = passes, every other one back along the path (scrubbing).
        Android: a smooth finger (a MOVE every ~16 ms, timed on the phone). iOS: WebDriverAgent touch
        actions. Find the points with handsets_ui_dump, or handsets_jev_find on screens without a tree.
        """,
        Param("points", "plan", "[[x,y],[x,y],…] in handsets_tap_xy pixels, at least two"),
        int("duration_ms", "one pass along the path", 800),
        int("hold_ms", "still after the press, before moving", 150),
        int("repeat", "passes; even ones go back along the path", 1),
        bool("outside_app", "drag even when the app under test is not in front", false),
        SERIAL,
    ) { a ->
        val pts = parseDragPoints(a.json("points"))
        val dur = a.int("duration_ms").coerceIn(16, 60_000)
        val hold = a.int("hold_ms").coerceIn(0, 10_000)
        val repeat = a.int("repeat").coerceIn(1, 50)
        val dev = rt.device(a.serial)
        if (!a.bool("outside_app")) ForegroundGuard.check(rt, dev)
        if (rt.platformOf(dev.serial) == no.agens.testlab.core.Platform.ANDROID) {
            val d = rt.gestures.drag(dev.serial, pts, dur.toLong(), hold.toLong(), repeat)
            dev.markInput()
            text(if (d.via == "injector") "ok: dragged through ${pts.size} points, $repeat pass(es), ${d.ms} ms (${d.moves} moves)" else "ok, via ${d.via}")
        } else {
            dev.drag(pts, dur, hold, repeat)
            text("ok: dragged through ${pts.size} points, $repeat pass(es)")
        }
    }

    tool(
        "handsets_home",
        """
        Go to the home screen, by default with a clean state: close_apps=true stops the app in front
        (not the launcher) and the profile's apps (its default and production package, and on
        Android the installed builds of its family) first, so the next handsets_open_app starts
        fresh. close_apps=false only presses home. Android: force-stop + HOME. iOS:
        WebDriverAgent terminates the active app and the profile's bundles, then the home screen.
        """,
        bool("close_apps", "stop the app in front and the profile's apps first", true),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        val close = a.bool("close_apps")
        val app = rt.onPlatform(rt.platformOf(dev.serial)) { rt.profile.app }
        val own = listOfNotNull(app.defaultPackage.takeIf { it.isNotBlank() }, app.productionPackage?.takeIf { it.isNotBlank() })
        val stopped = if (rt.platformOf(dev.serial) == Platform.ANDROID) {
            val family = app.family?.takeIf { close && it.isNotBlank() }?.let { f -> runCatching { rt.ops(dev.serial).packages(dev.serial, f) }.getOrDefault(emptyList()).filter { it == f || it.startsWith("$f.") } }.orEmpty()
            rt.gestures.home(dev.serial, (own + family).distinct(), close)
        } else dev.home(close, own)
        dev.markInput()
        text(if (!close) "ok: home" else if (stopped.isEmpty()) "ok: home (no app to close)" else "ok: home; closed ${stopped.joinToString()}")
    }

    tool(
        "handsets_orientation",
        """
        The screen's orientation: get (default), or turn it: portrait (auto-rotate back on),
        landscape, landscape-right. Android: the rotation settings (landscape = auto-rotate off +
        user rotation 1, landscape-right = 3), read back from the phone's own display (not the
        virtual ones screen mirroring adds). iOS: WebDriverAgent. An app that fixes its own
        orientation (many games) stays as it is: the result says what the screen is now.
        Coordinates (handsets_tap_xy, ui_dump, screenshots) follow the screen as it is turned.
        """,
        str("mode", "get | portrait | landscape | landscape-right", "get", listOf("get", "portrait", "landscape", "landscape-right")),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        val mode = a.str("mode")
        if (rt.platformOf(dev.serial) == no.agens.testlab.core.Platform.ANDROID) {
            val o = if (mode == "get") rt.gestures.orientation(dev.serial) else rt.gestures.setOrientation(dev.serial, mode)
            val (w, h) = o.screen ?: dev.screenSize()
            text(buildJsonObject {
                put("orientation", o.name); put("rotation", o.rotation * 90)
                o.autoRotate?.let { put("auto_rotate", it) }; put("screen", "${w}x$h")
                if (mode != "get" && o.name != mode) put("note", "asked for $mode, the screen is ${o.name}: the app in front fixes its orientation")
            }.encode())
        } else {
            val o = if (mode == "get") dev.orientation() else dev.setOrientation(mode)
            val (w, h) = dev.screenSize()
            text(buildJsonObject {
                put("orientation", o); put("screen", "${w}x$h")
                if (mode != "get" && o != mode) put("note", "asked for $mode, the screen is $o: the app in front fixes its orientation")
            }.encode())
        }
    }

    tool(
        "handsets_type_text",
        "Type text into the currently focused field (any characters, including æøå and emoji). It types after what the field already holds; replace=true clears it first (or handsets_fill(selector, value)). For a stored secret use handsets_type_secret. On iOS replace=true can find no focused field: use handsets_fill(class=EditText, value), and \"\\n\" for Return.",
        str("text", "the text to type"),
        bool("replace", "clear the field's text first instead of typing after it", false),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        if (a.bool("replace")) text(dev.replaceFocused(a.str("text")))
        else dev.typeText(a.str("text")).let { via -> text(if (via == "typed") "ok" else "ok, $via") }
    }

    tool(
        "handsets_type_secret",
        """
        Type a stored credential into the focused field by reference; the value never appears in a
        result. `ref` is "account.field" (e.g. "demo.password") or "device_pin"; see
        lab_list_credentials for what is set.
        """,
        str("ref", "credential reference: account.field or device_pin"),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        dev.typeText(rt.credential(a.str("ref"), dev.serial))
        text("ok typed ${a.str("ref")}")
    }

    tool(
        "handsets_fill",
        """
        Set an input field's text in one accessibility action (no keyboard; replaces the content).
        `selector` is the field's label/hint text, or daemon grammar (id=…, text~=…, class=EditText).
        Pass `cred` instead of `value` to fill a stored credential by reference.
        """,
        str("selector", "field label/hint text, or id=… / text~=… / class=…"),
        str("value", "the text to set", ""),
        str("cred", "credential reference (account.field or device_pin) to fill instead of value", ""),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        val value = a.strOrNull("cred")?.let { rt.credential(it, dev.serial) } ?: a.str("value")
        text(dev.fill(a.str("selector"), value))
    }

    tool("handsets_submit", "Press the IME submit/done/search action on the focused field.", SERIAL) { a ->
        text(rt.device(a.serial).submit())
    }

    tool("handsets_hide_keyboard", "Hide the soft keyboard if it is showing (BACK only when the keyboard is up, so it never navigates away).", SERIAL) { a ->
        text(if (rt.device(a.serial).hideKeyboard()) "keyboard hidden" else "no keyboard showing")
    }

    tool("handsets_navigate", "Hardware-key navigation.",
        str("target", "back | home | recents | menu | power", enum = listOf("back", "home", "recents", "menu", "power")), SERIAL) { a ->
        rt.device(a.serial).key(a.str("target"))
        text("ok")
    }

    tool(
        "handsets_wait_for",
        """
        Block until something is on screen: text or content-description (case-insensitive substring),
        #resource-id or a CSS-like selector. Also: a package or pkg/.Activity in front, "idle", or a
        duration such as "500ms" / "2s".
        """,
        str("selector_or_text", "what to wait for"),
        int("timeout_s", "give up after this many seconds", 15),
        SERIAL,
        readOnly = true,
    ) { a -> text(rt.device(a.serial).waitFor(a.str("selector_or_text"), a.int("timeout_s").coerceIn(1, 600) * 1000L)) }

    tool(
        "handsets_open_app",
        "Launch an app by package name (or pkg/.Activity, or its visible name). Optional launch values: on Android `am start` extras ([\"--es\", \"key\", \"value\"], --ez for true/false, --ei for numbers); on iOS launch arguments. A dialog the profile lists as noise for the app (e.g. a test-build service asking to sign in) is dismissed with its named button, never accepted; the result says so.",
        str("package", "package, pkg/.Activity or app name"),
        Param.strings("args", "launch values: Android extras ([\"--es\", \"environment\", \"int\"]), iOS launch arguments ([\"-environment\", \"int\"]); plans pass credentials by reference"),
        SERIAL,
    ) { a ->
        val serial = a.strOrNull("serial")
        if (serial != null && rt.installOnly(serial)) return@tool text(rt.ops(serial).launch(serial, a.str("package").substringBefore('/'), a.strings("args")))
        val dev = rt.device(a.serial)
        dev.amStart(a.str("package"), a.strings("args"))
        // A noise dialog the profile lists for this app (a test-build service asking to sign in): dismissed, never accepted.
        val dismissed = dev.dismissNoiseAfterLaunch(a.str("package").substringBefore('/'))
        text(if (dismissed != null) "ok; dismissed $dismissed" else "ok")
    }

    tool("handsets_close_app", "Force-stop an app by package name.", str("package", "package name"), SERIAL) { a ->
        val serial = a.strOrNull("serial")
        if (serial != null && rt.installOnly(serial)) return@tool text(rt.ops(serial).stop(serial, a.str("package")))
        rt.device(a.serial).forceStop(a.str("package"))
        text("ok")
    }

    tool("handsets_list_apps", "List installed packages.", bool("third_party_only", "only apps the user installed", false), SERIAL, readOnly = true) { a ->
        val serial = a.strOrNull("serial")
        if (serial != null && rt.installOnly(serial)) return@tool text(rt.ops(serial).packages(serial).joinToString("\n"))
        text(rt.device(a.serial).listApps(a.bool("third_party_only")))
    }

    tool(
        "handsets_simulator_start",
        """
        Boot an iOS Simulator (macOS with Xcode): by name ("iPhone 17") or UDID; empty = list the
        available ones (none yet: handsets_simulator_create makes one). The booted simulator then shows in handsets_list_adb_devices; drive it like a
        phone with handsets_use_device(serial=<UDID>). With AXe installed (`testlab setup ios`) the
        screen is read and tapped through it, a fraction of a second per call; without it, the first
        session on a computer builds WebDriverAgent, which takes a few minutes.
        """,
        str("name", "simulator name or UDID; empty = list them", ""),
    ) { a ->
        val x = rt.xcrun ?: throw no.agens.testlab.services.runtime.NotOnPlatform("iOS Simulators need macOS with Xcode")
        val sims = x.simulators()
        val name = a.str("name").trim()
        if (name.isEmpty()) return@tool text(sims.filter { it.isPhone || it.booted }.joinToString("\n") { "${it.name}  ${it.runtime}  ${it.state}  ${it.udid}" }.ifEmpty { "(no simulators: add one in Xcode → Window → Devices and Simulators)" })
        val sim = sims.firstOrNull { it.udid == name } ?: sims.filter { it.name.equals(name, ignoreCase = true) }.maxByOrNull { it.runtime }
            ?: throw BadArgument("no simulator '$name': handsets_simulator_start() lists them")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            x.boot(sim.udid)
            runCatching { ProcessBuilder("open", "-a", "Simulator", "--args", "-CurrentDeviceUDID", sim.udid).start() }
        }
        text("booted ${sim.name} (${sim.runtime}): serial ${sim.udid}")
    }

    tool(
        "handsets_simulator_stop",
        "Shut an iOS Simulator down (its apps and data stay). Releases the session first.",
        str("serial", "the simulator's UDID"),
    ) { a ->
        val x = rt.xcrun ?: throw no.agens.testlab.services.runtime.NotOnPlatform("iOS Simulators need macOS with Xcode")
        val udid = a.str("serial")
        rt.drop(udid)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { x.shutdown(udid) }
        text("shut down $udid")
    }

    tool("handsets_current_focus", "Return the activity in front (package/activity).", SERIAL, readOnly = true) { a ->
        text(rt.device(a.serial).topActivity())
    }

    tool("handsets_info", "Device info: model, OS version, display, battery, network, the app in front, and whether TalkBack runs.", SERIAL, readOnly = true) { a ->
        val dev = rt.device(a.serial)
        val tb = if (rt.platformOf(dev.serial) != Platform.ANDROID) null else runCatching { rt.talkback.status(dev.serial) }.getOrNull()
        text(formatInfo(dev.serial, dev.deviceState()) + (tb?.let { "\ntalkback: " + (if (it.touchExploration) "on (running)" else if (it.enabled) "enabled, not running" else "off") + (if (it.turnedOffByUs.isNotEmpty()) ", turned off by ${AppIdentity.NAME}" else "") } ?: ""))
    }

    tool(
        "handsets_talkback",
        """
        TalkBack on the phone: status, on, off. The handsets_* taps, swipes and typing keep working while
        TalkBack runs (they are injected past its touch handling), so it need not be turned off to drive
        the app. Turn it on to test the app with TalkBack (handsets_ui_dump shows the labels it reads),
        and off again afterwards; off remembers what it removed and on restores it exactly.
        """,
        str("action", "status | off | on", "status", enum = listOf("status", "off", "on")),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        rt.ops(dev.serial).requireAndroid("TalkBack")
        var dismissed = emptyList<String>()
        val st = when (a.str("action")) {
            "off" -> rt.talkback.off(dev.serial)
            "on" -> rt.talkback.on(dev.serial).also { if (it.touchExploration) dismissed = rt.talkbackGestures.dismissSetup(dev.serial) }
            else -> rt.talkback.status(dev.serial)
        }
        text(buildString {
            append("TalkBack on ${dev.serial}: ")
            append(if (st.touchExploration) "running (touch exploration on)" else if (st.enabled) "enabled but not running" else "off")
            append(st.component?.let { "; installed: $it" } ?: "; not installed")
            if (st.turnedOffByUs.isNotEmpty()) append("; turned off by ${AppIdentity.NAME}: handsets_talkback(action=\"on\") restores it")
            if (dismissed.isNotEmpty()) append("; dismissed: ${dismissed.joinToString()}")
        })
    }

    tool(
        "handsets_talkback_touch",
        """
        TalkBack explore-by-touch: a real finger (a virtual touchscreen, not injected input) held on a
        node, so TalkBack moves its focus there and speaks it. Name the node like handsets_tap
        (text, #id or selector; resolved on a fresh dump right before the touch, since layouts move)
        or pass x,y. Then handsets_talkback_activate double-taps it. screenshot=true returns the screen
        ~1 s later: with TalkBack's "Display speech output" (TalkBack settings → Advanced → Developer
        settings) it shows what TalkBack said. Needs TalkBack on.
        """,
        str("selector_or_text", "the node: text, #id or selector; empty = use x,y", ""),
        int("x", "x in pixels, when no selector", -1),
        int("y", "y in pixels, when no selector", -1),
        int("nth", "which match, when several are on screen (0 = first)", 0),
        bool("screenshot", "return the screen ~1 s after the touch", false),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        val (x, y, what) = a.strOrNull("selector_or_text")?.let { q ->
            val hit = dev.locate(q, a.int("nth"))
            Triple(hit.bounds.centerX, hit.bounds.centerY, NodeQuery.debugQuote(hit.label))
        } ?: run {
            if (a.int("x") < 0 || a.int("y") < 0) throw BadArgument("pass selector_or_text, or x and y")
            Triple(a.int("x"), a.int("y"), "(${a.int("x")},${a.int("y")})")
        }
        rt.talkbackGestures.touch(dev.serial, x, y)
        val msg = "TalkBack touched $what at ($x,$y) on ${dev.serial}: its focus is there now; handsets_talkback_activate double-taps it"
        if (a.bool("screenshot")) {
            kotlinx.coroutines.delay(900)
            Out.Image(dev.screenshot(1080, quality = 80), caption = msg)
        } else text(msg)
    }

    tool(
        "handsets_talkback_activate",
        """
        TalkBack double-tap: activates the node TalkBack has in focus (after handsets_talkback_touch
        or _swipe), as a TalkBack user would. A real gesture through a virtual touchscreen; refused
        while TalkBack is off, where it would click whatever is under it.
        """,
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        rt.talkbackGestures.activate(dev.serial)
        text("TalkBack double-tap on ${dev.serial}: the focused node was activated (handsets_ui_dump or a screenshot shows the result)")
    }

    tool(
        "handsets_talkback_swipe",
        """
        TalkBack swipe right (next) or left (previous): moves TalkBack's focus to the next or
        previous element in its reading order, and it reads it. A real one-finger gesture; needs
        TalkBack on. A screenshot shows the focus frame.
        """,
        str("direction", "next | previous", "next", listOf("next", "previous")),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        rt.talkbackGestures.swipe(dev.serial, if (a.str("direction") == "previous") no.agens.testlab.services.runtime.TalkBackGestures.Direction.PREVIOUS else no.agens.testlab.services.runtime.TalkBackGestures.Direction.NEXT)
        text("TalkBack moved to the ${a.str("direction")} element on ${dev.serial}")
    }

    tool(
        "handsets_key",
        """
        Press a keyboard key, for keyboard and switch-access testing: tab, shift_tab, enter, space,
        escape, back, del, forward_del, dpad_up/down/left/right, page_up/down, move_home/end, search,
        or `code` for any Android keycode. `count` repeats it. Keep a keyboard flow free of taps
        (a tap puts the phone back into touch mode and the focus ring goes), and sync on what the
        screen shows rather than counting Tabs: one can get lost. The result says whether the
        on-screen keyboard is up.
        """,
        str("key", "the key name (empty with code)", ""),
        int("code", "an Android keycode instead of a name (e.g. 61 = TAB)", 0),
        int("count", "how many times", 1),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        rt.ops(dev.serial).requireAndroid("handsets_key (Android keycodes)")
        val name = a.str("key")
        val codes = when {
            name.isNotEmpty() -> KEYS[name] ?: throw BadArgument("unknown key '$name' (${KEYS.keys.joinToString()})")
            a.int("code") > 0 -> listOf(a.int("code"))
            else -> throw BadArgument("pass key or code")
        }
        val n = a.int("count").coerceIn(1, 50)
        dev.markInput()
        val one = if (codes.size == 1) "input keyevent ${codes[0]}" else "input keycombination ${codes.joinToString(" ")}"
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            rt.adb().shell(dev.serial, List(n) { one }.joinToString("; ") + "; dumpsys input_method | grep -m1 -o 'mInputShown=[a-z]*'")
        }
        if (!r.ok && r.stdout.isBlank()) throw IllegalStateException("adb input failed: ${r.stderr.trim().take(200)}")
        text("pressed ${name.ifEmpty { "keycode ${codes[0]}" }}${if (n > 1) " ×$n" else ""} on ${dev.serial}; keyboard " + if ("mInputShown=true" in r.stdout) "shown" else "hidden")
    }

    tool(
        "handsets_font_scale",
        """
        The phone's font size for large-text testing: status, set (scale, e.g. 1.3 or 2.0), or reset.
        set remembers the size it replaced and reset puts exactly that back: always reset when done.
        Apps may need a restart to pick it up.
        """,
        str("action", "status | set | reset", "status", listOf("status", "set", "reset")),
        Param("scale", "number", "the scale for set (0.5–3.0)", JsonPrimitive(1.3)),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        val now = when (a.str("action")) {
            "set" -> rt.ops(dev.serial).setFontScale(dev.serial, a.double("scale"))
            "reset" -> rt.ops(dev.serial).resetFontScale(dev.serial)
            else -> rt.ops(dev.serial).fontScale(dev.serial)
        }
        text("font scale on ${dev.serial}: $now" + (rt.ops(dev.serial).originalFontScale(dev.serial)?.let { "; was $it before ${AppIdentity.NAME} changed it: handsets_font_scale(action=\"reset\") puts it back" } ?: ""))
    }

    tool(
        "handsets_uninstall_app",
        """
        Uninstall an app by package name. Use before installing a build whose signature clashes
        with the installed copy. Asks for a yes first (the approvals page; `mcp.confirmWrites` off skips it).
        """,
        str("package", "package name"),
        SERIAL,
        destructive = true,
    ) { a ->
        val dev = rt.device(a.serial)
        val pkg = a.str("package")
        if (rt.settings.current.mcp.confirmWrites && !rt.approver.approve("Uninstall app", "Uninstall $pkg from ${dev.serial}? Its data (login, settings) is deleted.")) {
            throw NotApproved("not approved: uninstalling $pkg needs a yes (`${AppIdentity.SLUG} open approvals`, or `${AppIdentity.SLUG} approve`)")
        }
        text(rt.ops(dev.serial).uninstall(dev.serial, pkg))
    }

    tool(
        "handsets_unlock_device",
        """
        Wake the screen and unlock the device with its PIN. Does nothing when it is not locked.
        If `pin` is omitted, uses the stored device PIN (`testlab secrets set device_pin`).
        """,
        str("pin", "the PIN; empty = the stored device_pin", ""),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        val pin = a.strOrNull("pin") ?: runCatching { rt.credential("device_pin", dev.serial) }.getOrNull()
        text(rt.ops(dev.serial).unlock(dev.serial, pin))
    }

    tool(
        "handsets_go",
        """
        Jump the app straight to a named destination: a deeplink, or a tab tapped by its id. The
        active profile names the destinations; destination="list" lists them (the server
        instructions do too). Far more reliable than tapping through menus. `package` defaults to
        the build Testlab installed on the device for this round (when it is the profile's app), else the
        profile's app. On debug builds a deeplink mid-session can start a re-authentication that logs
        the user out: when the login matters, navigate by taps instead.
        """,
        str("destination", "a destination name, or list"),
        str("subpage", "optional subpage for destinations that take one (see list)", ""),
        str("package", "app package to target; empty = the build installed for this round, else the profile's default package", ""),
        SERIAL,
    ) { a ->
        val dest = a.str("destination")
        val destinations = rt.destinations()
        if (dest.trim().lowercase() == "list") return@tool text(destinations.listing())
        val target = destinations.resolve(dest, a.strOrNull("subpage")) ?: throw BadArgument("unknown destination '$dest': try handsets_go(\"list\")")
        val dev = rt.device(a.serial)
        val pkg = a.strOrNull("package") ?: roundPackage(rt, dev.serial)
        when (target) {
            is Destinations.Target.Deeplink -> { dev.markInput(); text(rt.ops(dev.serial).openUrl(dev.serial, target.url, pkg) + browserFirstRun(dev)) }
            is Destinations.Target.Tab -> text(navTap(dev, pkg, target.rid))
        }
    }

    tool(
        "handsets_open_url",
        """
        Open a page or deeplink in the app: a full URL, or a path under the profile's
        navigation.host (query strings survive, e.g. "/search?q=shoes").
        Routed through the app's deeplink handler. Use handsets_go for the named destinations.
        """,
        str("url", "a URL, or a path under the profile's navigation.host"),
        str("package", "app package to target; empty = the build installed for this round, else the profile's default package (or device.packageOverride)", ""),
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        dev.markInput()
        text(rt.ops(dev.serial).openUrl(dev.serial, a.str("url"), a.strOrNull("package") ?: roundPackage(rt, dev.serial)) + browserFirstRun(dev))
    }
}

/**
 * Chrome's first-run screens (welcome, the EU search-engine choice) take over a link opened in the
 * browser on a phone where Chrome was never started: say so instead of leaving it to a stale dump.
 */
private suspend fun browserFirstRun(dev: no.agens.testlab.device.hs.HsDevice): String {
    kotlinx.coroutines.delay(800)
    val top = runCatching { dev.topActivity() }.getOrNull().orEmpty()
    if (!top.startsWith("com.android.chrome") || !Regex("FirstRun|SearchEngineChoice|Fre|Signin", RegexOption.IGNORE_CASE).containsMatchIn(top)) return ""
    return "\nNOTE: Chrome shows its first-run screens ($top), not the page: go through them once (accept, pick a search engine, " +
        "skip sign-in), then open the link again."
}

/** A bottom-bar tab has no deeplink: bring the app forward, back out of games until the bar shows, tap the tab by id. */
private suspend fun navTap(dev: HsDevice, pkg: String, rid: String): String {
    dev.amStart(pkg)
    delay(1_000)
    repeat(5) {
        val root = UiNode.primaryRoot(dev.dumpJson())
        if (root != null && NodeQuery.byId(root, "home_screen").isNotEmpty()) return dev.tapText("#$rid")
        dev.key("BACK")
        delay(800)
    }
    return dev.tapText("#$rid")
}

/** The `hs info` summary rows from the daemon's `state device` snapshot. */
internal fun formatInfo(serial: String, st: JsonObject): String {
    fun s(k: String) = (st[k] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" } ?: ""
    val title = listOf(s("manufacturer"), s("model")).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { s("device") }
    val rows = buildList {
        add("Serial" to serial)
        if (s("release").isNotEmpty()) add("OS" to "${s("os_name").ifEmpty { "Android" }} ${s("release")}" + (if (s("sdk").isNotEmpty()) " (SDK ${s("sdk")})" else ""))
        if (s("width").isNotEmpty()) add("Display" to "${s("width")} × ${s("height")} (rotation ${s("rotation").ifEmpty { "0" }})")
        if (s("battery_level").isNotEmpty()) add("Battery" to "${s("battery_level")}% (" + (if (s("interactive") == "true") "screen on" else "screen off") + (if (s("battery_charging") == "true") ", charging" else "") + ")")
        val net = listOf(s("network"), s("wifi_ssid")).filter { it.isNotEmpty() }.joinToString(" · ")
        if (net.isNotEmpty()) add("Network" to net)
        if (s("locale").isNotEmpty()) add("Locale" to s("locale"))
        if (s("top_activity").isNotEmpty()) add("Front" to s("top_activity"))
    }
    val w = rows.maxOf { it.first.length }
    return (listOf(title.ifEmpty { serial }) + rows.map { (k, v) -> k.padEnd(w) + "  " + v }).joinToString("\n")
}

/** The profile's (or this computer's) default package, or a sentence saying how to set one. */
/**
 * The app a deeplink should open on [serial]: this computer's override when set, else the build
 * Testlab installed there last if it is one of the profile's app family (the build under test this
 * round, even when it is not the profile's default package), else the profile's default.
 */
internal suspend fun roundPackage(rt: LabRuntime, serial: String): String {
    if (!rt.settings.current.device.packageOverride.isNullOrBlank()) return requirePackage(rt)
    val app = rt.profileFor(serial).app
    val family = listOfNotNull(app.family, app.defaultPackage.ifBlank { null }, app.productionPackage)
    val installed = runCatching { rt.builds.installedOn(serial) }.getOrDefault(emptyList())
        .filter { b -> family.any { f -> b.packageName == f || b.packageName.startsWith("$f.") } }
        .maxByOrNull { it.installedAt }
    return installed?.packageName ?: requirePackage(rt)
}

internal fun requirePackage(rt: LabRuntime): String = rt.defaultPackage().ifBlank {
    throw BadArgument("no app package: pass package, or set app.defaultPackage in the profile «${rt.profile.name}»")
}

/** handsets_drag's points: [[x,y],…] as JSON (or its text), at least two. */
internal fun parseDragPoints(v: kotlinx.serialization.json.JsonElement?): List<Pair<Int, Int>> {
    val el = (v as? kotlinx.serialization.json.JsonPrimitive)?.let { p -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(p.content) }.getOrNull() } ?: v
    val arr = el as? kotlinx.serialization.json.JsonArray ?: throw BadArgument("points must be a list of [x, y] pairs, e.g. [[300,900],[700,900]]")
    val pts = arr.map { p ->
        val xy = (p as? kotlinx.serialization.json.JsonArray)?.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }
            ?: (p as? kotlinx.serialization.json.JsonObject)?.let { o -> listOf("x", "y").map { (o[it] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() } }
        if (xy == null || xy.size != 2 || xy.any { it == null || it < 0 }) throw BadArgument("each point is [x, y] in pixels (not $p)")
        xy[0]!!.toInt() to xy[1]!!.toInt()
    }
    if (pts.size < 2) throw BadArgument("a drag needs at least two points")
    return pts
}

/**
 * What a screenshot is in tap pixels: the picture's size and the factor to handsets_tap_xy (positions
 * in the picture × factor = tap pixels), and a warning when a real iPhone/iPad's screen is black
 * (asleep or locked: it can't be unlocked from here).
 */
internal suspend fun screenshotCaption(rt: LabRuntime, dev: HsDevice, shot: ByteArray): String? = runCatching {
    val img = javax.imageio.ImageIO.read(shot.inputStream()) ?: return null
    val (sw0, sh0) = dev.screenSize()
    val (sw, sh) = if ((img.width > img.height) != (sw0 > sh0)) sh0 to sw0 else sw0 to sh0
    val f = sw.toDouble() / img.width
    buildString {
        append("picture ${img.width}x${img.height}; tap pixels (${sw}x$sh) = picture × ${"%.3f".format(java.util.Locale.ROOT, f)}")
        if (rt.platformOf(dev.serial) == Platform.IOS && rt.realIos(dev.serial) && blackScreen(img)) {
            append(". The screen is black: the device is asleep or locked. Unlock it by hand, and set Settings → Display & Brightness → Auto-Lock to Never")
        }
    }
}.getOrNull()

/** Nearly every sampled pixel black. */
internal fun blackScreen(img: java.awt.image.BufferedImage): Boolean {
    var dark = 0; var n = 0
    for (y in 0 until img.height step maxOf(1, img.height / 40)) for (x in 0 until img.width step maxOf(1, img.width / 20)) {
        val p = img.getRGB(x, y); n++
        if (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF) < 30) dark++
    }
    return n > 0 && dark >= n * 0.98
}
