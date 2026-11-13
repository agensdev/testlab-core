package no.agens.testlab.device.ios

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import no.agens.testlab.device.hs.HsEndpoint
import no.agens.testlab.device.hs.HsWire
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The on-device daemon's wire protocol (HsWire) for an iOS Simulator or a real iPhone, served on this
 * computer: `HsDevice` connects to it like to the Android daemon through its adb forward, so the tools,
 * The autopilot, the live view and plans drive an iOS device without knowing.
 *
 * With [axe] (AXe installed), the screen, taps, swipes, text and keys go through AXe: one quick call
 * each, as on Android, with nothing to build or start. WebDriverAgent (`wda`: Appium's XCUITest
 * driver) is then started only for what AXe can't do (hiding the keyboard, the volume keys) and its
 * MJPEG live view; without AXe (always so on a real iPhone) every verb is a WebDriver call. The
 * [target] does apps and screenshots either way: `simctl` on a simulator, `devicectl` and
 * WebDriverAgent on a phone.
 *
 * Coordinates on the wire are pixels (as in screenshots); AXe and WebDriver work in points, so they
 * are divided by [scale] on the way out.
 */
class IosBridge(
    private val target: IosTarget,
    private val axe: AxeCli?,
    /** Opens the WebDriver session and gives its MJPEG port (the live view); called once, when first needed. */
    wda: () -> Pair<WebDriver, Int?>,
    private val tmp: File,
) : Closeable {
    constructor(udid: String, xcrun: XcrunCli, axe: AxeCli?, wda: () -> Pair<WebDriver, Int?>, tmp: File) : this(SimTarget(xcrun, udid), axe, wda, tmp)
    constructor(udid: String, xcrun: XcrunCli, driver: WebDriver, mjpegPort: Int?, tmp: File) : this(SimTarget(xcrun, udid), null, { driver to mjpegPort }, tmp)

    private val udid = target.udid

    private val session = lazy(wda)
    private val driver: WebDriver get() = session.value.first
    private val mjpegPort: Int? get() = if (session.isInitialized()) session.value.second else null

    /** Opens the WebDriver session now (setup builds WebDriverAgent once, so a later hide-keyboard doesn't wait for it). */
    fun startWda(): WebDriver = driver

    /** The WebDriver session, when one was opened. */
    val driverIfStarted: WebDriver? get() = if (session.isInitialized()) session.value.first else null
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    val endpoint = HsEndpoint(udid, server.localPort)
    @Volatile private var closed = false
    @Volatile private var clip = ""
    private val lock = Any()

    private fun sizePt(): Pair<Int, Int> = if (axe != null) axeSize() else driver.windowRect()

    /**
     * Points to pixels (3 on most iPhones, 2 on iPads): a screenshot's long side over the window's,
     * so it doesn't matter which way up either is.
     */
    private val scaleLazy: Double by lazy {
        val (wPt, hPt) = sizePt()
        val img = shot("png").let { f -> try { ImageIO.read(f) } finally { f.delete() } }
        // Long side over long side: the same whichever way up (and with or without an EXIF turn).
        if (img != null && maxOf(wPt, hPt) > 0) maxOf(img.width, img.height).toDouble() / maxOf(wPt, hPt) else 3.0
    }
    private val scale get() = scaleLazy

    @Volatile private var geo: Pair<Long, Triple<Int, Int, Double>>? = null

    /** The screen in pixels as it is turned now (asked again after 2 s, and after a rotation), and the scale. */
    private val geometry: Triple<Int, Int, Double> get() {
        geo?.takeIf { System.currentTimeMillis() - it.first < 2_000 }?.let { return it.second }
        val (wPt, hPt) = sizePt()
        return Triple(Math.round(wPt * scale).toInt(), Math.round(hPt * scale).toInt(), scale).also { geo = System.currentTimeMillis() to it }
    }

    init {
        tmp.mkdirs()
        Thread({ acceptLoop() }, "ios-bridge-$udid").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        while (!closed) {
            val s = runCatching { server.accept() }.getOrNull() ?: continue
            Thread({ serve(s) }, "ios-bridge-conn-$udid").apply { isDaemon = true }.start()
        }
    }

    private fun serve(s: Socket) = s.use { sock ->
        sock.tcpNoDelay = true
        val input = sock.getInputStream().buffered()
        val out = sock.getOutputStream()
        while (!closed) {
            val cmd = try { HsWire.readFrame(input).decodeToString() } catch (e: Exception) { return }
            val verb = cmd.substringBefore(' ')
            try {
                when (verb) {
                    "stream" -> { mirror(out); return }
                    "stream_h264" -> { HsWire.writeFrame(out, "ERR:unsupported:iOS screens are not recorded through the bridge".toByteArray()); return }
                    "dumpsys" -> { HsWire.writeFrame(out, dumpsys(cmd.substringAfter(' ', "")).toByteArray()); HsWire.writeFrame(out, ByteArray(0)) }
                    else -> HsWire.writeFrame(out, synchronized(lock) { answer(verb, cmd.substringAfter(' ', "")) })
                }
            } catch (e: BridgeError) {
                HsWire.writeFrame(out, "ERR:${e.tag}:${e.message}".toByteArray())
            } catch (e: Exception) {
                HsWire.writeFrame(out, "ERR:ios:${(e.message ?: e.toString()).lineSequence().first().take(300)}".toByteArray())
            }
        }
    }

    private class BridgeError(val tag: String, message: String) : RuntimeException(message)

    private fun ok(s: String = "ok") = s.toByteArray()

    // --- the verbs ---

    private fun answer(verb: String, rest: String): ByteArray {
        val a = Args.parse(rest)
        return when (verb) {
            "info" -> ok("${geometry.first} ${geometry.second}")
            "dump_active" -> ok(dump().toString())
            "state" -> ok(if (rest.trim() == "top") top() else deviceState().toString())
            "pm_list" -> ok(target.bundleIds().joinToString("\n"))
            "screenshot" -> screenshot(a)
            "tap" -> { tapPx(a.int("x"), a.int("y")); ok() }
            "swipe" -> { swipePx(a.int("x1"), a.int("y1"), a.int("x2"), a.int("y2"), a.intOr("dur", 300)); ok() }
            "swipe_dir" -> { swipeDir(rest.trim()); ok() }
            "orientation" -> ok(orientationName(driver.orientation()))
            "home" -> {
                // The app in front and the profile's bundles terminated, then the home screen.
                val stopped = mutableListOf<String>()
                if (a["close"] == "1") {
                    val front = runCatching { driver.activeApp() }.getOrNull()
                    val targets = (listOfNotNull(front) + a["pkgs"].orEmpty().split(',')).map { it.trim() }.filter { it.isNotEmpty() && it != "com.apple.springboard" }.distinct()
                    for (b in targets) if (runCatching { driver.terminate(b) }.isSuccess) stopped += b
                }
                driver.homescreen()
                lastDump = null
                ok(stopped.joinToString(","))
            }
            "mjpeg_settings" -> {
                driver.appiumSettings(buildJsonObject {
                    a["fps"]?.toIntOrNull()?.let { put("mjpegServerFramerate", it) }
                    a["scale"]?.toIntOrNull()?.let { put("mjpegScalingFactor", it) }
                })
                ok()
            }
            "orientation_set" -> {
                driver.setOrientation(when (rest.trim()) {
                    "portrait" -> "PORTRAIT"; "landscape" -> "LANDSCAPE"; "landscape-right" -> "UIA_DEVICE_ORIENTATION_LANDSCAPERIGHT"
                    else -> throw BridgeError("BAD_ARG", "orientation: portrait, landscape or landscape-right")
                })
                geo = null
                ok(orientationName(driver.orientation()))
            }
            "drag" -> { dragPx(dragPoints(a["pts"].orEmpty()), a.intOr("dur", 800), a.intOr("hold", 150), a.intOr("repeat", 1)); ok() }
            "key" -> { key(rest.trim()); ok() }
            "text" -> { typeText(rest); ok() }
            "clip_set" -> { clip = rest; ok() }
            "paste" -> { typeText(clip); ok() }
            "submit" -> { if (axe != null) axe.key(udid, AxeCli.ENTER) else driver.keys("\n"); ok() }
            "am_start" -> {
                val head = rest.substringBefore(" -- ")
                val args = rest.substringAfter(" -- ", "").takeIf { " -- " in rest }?.split(no.agens.testlab.device.hs.HsDevice.ARG_SEP).orEmpty()
                target.launch((Args.parse(head)["n"] ?: head.trim()).substringBefore('/'), args)
                ok()
            }
            "am_force_stop" -> { target.terminate(rest.trim()); ok() }
            "node_click" -> { val n = node(rest); tapCenter(n); ok() }
            "node_focus" -> { tapCenter(node(rest)); ok() }
            "node_set_text" -> { setText(rest, a["value"].orEmpty()); ok() }
            "wait_for_idle" -> { waitIdle(a.intOr("idle_ms", 200).toLong(), a.intOr("timeout_ms", 10_000).toLong()); ok() }
            "wait_for_activity" -> ok(waitActivity(a["n"].orEmpty(), a.intOr("timeout_ms", 15_000).toLong()))
            "keyframe" -> ok()
            "open_prompt_accept" -> ok(acceptOpenPrompt())
            else -> throw BridgeError("BAD_ARG", "unknown verb $verb")
        }
    }

    @Volatile private var lastDump: JsonObject? = null
    @Volatile private var lastDumpAt = 0L

    private fun bundle(): String {
        if (axe != null) return ((dump()["root"] as? JsonObject)?.get("pkg") as? JsonPrimitive)?.content.orEmpty()
        return runCatching { ((driver.mobile("activeAppInfo") as? JsonObject)?.get("bundleId") as? JsonPrimitive)?.content }.getOrNull().orEmpty()
    }

    private fun dump(): JsonObject = (if (axe != null) AxTree.parse(axe.describe(udid), scale, ::bundleOf, focused).dump else XcuiTree.toDump(driver.source(), scale, bundle()))
        .also { lastDump = it; lastDumpAt = System.currentTimeMillis() }

    /** What [waitIdle] compares: the screen's tree, as cheaply as the driver gives it. */
    private fun source(): String = if (axe != null) axe.describe(udid) else driver.source()

    /** The size in points, from the tree's app element (or a usual iPhone's when it has none). */
    private fun axeSize(): Pair<Int, Int> {
        val size = AxTree.parse(axe!!.describe(udid), 1.0, { _, _ -> "" }).sizePt
        return size?.let { it.first.toInt() to it.second.toInt() } ?: (402 to 874)
    }

    /** The bounds of the input last tapped: the accessibility tree doesn't say which field has the focus. */
    @Volatile private var focused: IntArray? = null

    private val bundles = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** A process's bundle id, from the target's list of running apps. */
    private fun bundleOf(pid: Int?, name: String?): String {
        if (pid == null) return name.orEmpty()
        bundles[pid]?.let { return it }
        bundles.putAll(target.processBundles())
        // SpringBoard (the home screen) has no UIKitApplication entry.
        return bundles[pid] ?: if (name == "SpringBoard" || name.isNullOrEmpty()) "com.apple.springboard" else name
    }

    /** AXe types the US keyboard; anything else goes in through the pasteboard and Cmd+V. */
    private fun typeText(text: String) {
        if (axe == null) { driver.keys(text); return }
        if (text.isEmpty()) return
        if (AxeCli.typeable(text)) { axe.type(udid, text); return }
        try { target.pasteboard(text) } catch (e: XcrunException) { throw BridgeError("ios", e.message ?: "pasteboard failed") }
        axe.keyCombo(udid, listOf(AxeCli.CMD), AxeCli.V)
    }

    /**
     * The «activity» of an iOS screen: the app and the navigation bar's title, so a change of screen
     * shows. Asked before every ui_dump, so it is cheap: a dump of the last second, else WebDriver's
     * answer for the navigation bar alone rather than the whole tree.
     */
    private fun top(): String {
        val recent = lastDump?.takeIf { System.currentTimeMillis() - lastDumpAt < 1_000 }
        val pkg: String
        val title: String?
        if (axe != null) {
            val d = recent ?: dump()
            pkg = ((d["root"] as? JsonObject)?.get("pkg") as? JsonPrimitive)?.content.orEmpty()
            title = nodes(d).firstOrNull { (it.first["xcui"] as? JsonPrimitive)?.content == "NavigationBar" }?.first?.let { (it["text"] as? JsonPrimitive)?.content } ?: AxTree.title(d)
        } else if (recent != null) {
            pkg = ((recent["root"] as? JsonObject)?.get("pkg") as? JsonPrimitive)?.content.orEmpty()
            title = nodes(recent).firstOrNull { (it.first["xcui"] as? JsonPrimitive)?.content == "NavigationBar" }?.first?.let { (it["text"] as? JsonPrimitive)?.content }
        } else {
            pkg = bundle()
            title = driver.find("class name", "XCUIElementTypeNavigationBar")?.let { driver.attribute(it, "name") }
        }
        return if (title.isNullOrEmpty()) pkg else "$pkg/$title"
    }

    private fun deviceState(): JsonObject {
        val (name, os) = target.info()
        return buildJsonObject {
            put("manufacturer", "Apple")
            put("model", name)
            put("os_name", "iOS")
            os?.let { put("release", it) }
            put("width", geometry.first.toString()); put("height", geometry.second.toString())
            put("top_activity", runCatching { top() }.getOrDefault(""))
        }
    }

    private fun shot(type: String): File = File(tmp, "shot-${System.nanoTime()}.$type").also { target.screenshot(it, type) }

    /** The live view's frames, read once for every screenshot (see [Appium.fast]). */
    @Volatile private var frames: MjpegTap? = null

    /**
     * A screenshot from WebDriverAgent's MJPEG stream: the first frame after the call, so it shows
     * what the last tap did (or the last frame, when the screen stands still). Null (take a real one)
     * when no stream runs or has sent anything, and for PNG and full size.
     */
    private fun streamShot(a: Args): ByteArray? {
        if (!Appium.fast() || (a["fmt"] ?: "jpeg") != "jpeg" || a["max"] == "1") return null
        val port = mjpegPort ?: return null
        val tap = frames ?: synchronized(this) { frames ?: MjpegTap(port).also { frames = it } }
        val jpeg = tap.next(System.nanoTime(), 350) ?: return null
        val turn = Exif.orientation(jpeg)
        val img = ImageIO.read(jpeg.inputStream())?.let { Exif.upright(it, turn) } ?: return null
        val size = a["size"]?.toIntOrNull()
        if (turn == 1 && (size == null || maxOf(img.width, img.height) <= size)) return jpeg
        if (size == null) return encode(img, "jpeg", (a["q"]?.toIntOrNull() ?: 80) / 100f)
        return encode(fit(img, size), "jpeg", (a["q"]?.toIntOrNull() ?: 80) / 100f)
    }

    private fun screenshot(a: Args): ByteArray {
        streamShot(a)?.let { return it }
        val f = shot("png")
        try {
            // A real iPad's picture is portrait-native with an EXIF turn (8 = landscape): turned upright,
            // and written without the tag, so its positions are the screen's as taps see it.
            val bytes = f.readBytes()
            val img = ImageIO.read(bytes.inputStream())?.let { Exif.upright(it, Exif.orientation(bytes)) } ?: throw BridgeError("ios", "the device gave no image")
            val size = a["size"]?.toIntOrNull()
            val scaled = if (size != null && a["max"] != "1") fit(img, size) else img
            return encode(scaled, a["fmt"] ?: "jpeg", (a["q"]?.toIntOrNull() ?: 80) / 100f)
        } finally { f.delete() }
    }

    private fun tapPx(x: Int, y: Int) {
        if (axe != null) {
            // The field under the finger has the focus from now on (for `F` in the next dump).
            focused = lastDump?.let { d -> nodes(d).map { it.first }.lastOrNull { n -> n.isInput() && n.contains(x, y) } }?.bounds()
            axe.tap(udid, (x / scale).toInt(), (y / scale).toInt())
        } else driver.tap((x / scale).toInt(), (y / scale).toInt())
    }

    private fun swipePx(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int) {
        val p = listOf(x1, y1, x2, y2).map { (it / scale).toInt() }
        if (axe != null) axe.swipe(udid, p[0], p[1], p[2], p[3], dur) else driver.swipe(p[0], p[1], p[2], p[3], dur)
    }

    /**
     * A finger along [pts] (pixels) through WebDriverAgent's W3C actions (in points): one action
     * sequence for every pass, legs timed by length, even passes back along the path.
     */
    private fun dragPx(pts: List<Pair<Int, Int>>, durMs: Int, holdMs: Int, repeat: Int) {
        val path = dragPath(pts, repeat).map { (x, y) -> (x / scale).toInt() to (y / scale).toInt() }
        driver.drag(path, legTimes(pts, durMs, repeat), holdMs)
    }

    private fun JsonObject.isInput() = (this["cls"] as? JsonPrimitive)?.content == "EditText"
    private fun JsonObject.bounds(): IntArray? = (this["bounds"] as? JsonArray)?.map { (it as JsonPrimitive).content.toDouble().toInt() }?.toIntArray()?.takeIf { it.size == 4 }
    private fun JsonObject.contains(x: Int, y: Int) = bounds()?.let { b -> x in b[0]..b[2] && y in b[1]..b[3] } == true

    /** As on Android: the finger moves in [dir] (up scrolls the content further down). */
    private fun swipeDir(dir: String) {
        val (w, h) = geometry.first to geometry.second
        when (dir) {
            "up" -> swipePx(w / 2, h * 7 / 10, w / 2, h * 3 / 10, 300)
            "down" -> swipePx(w / 2, h * 3 / 10, w / 2, h * 7 / 10, 300)
            "left" -> swipePx(w * 8 / 10, h / 2, w * 2 / 10, h / 2, 300)
            "right" -> swipePx(w * 2 / 10, h / 2, w * 8 / 10, h / 2, 300)
            else -> throw BridgeError("BAD_ARG", "swipe_dir up|down|left|right")
        }
    }

    private fun key(spec: String) {
        val k = spec.removePrefix("KEYCODE_").uppercase()
        if (axe != null) {
            val code = when (k) { "ENTER" -> AxeCli.ENTER; "DEL" -> AxeCli.DELETE; "TAB" -> AxeCli.TAB; "SPACE", "CODE=62" -> AxeCli.SPACE; else -> null }
            if (code != null) { axe.key(udid, code); return }
            if (k == "HOME") { axe.button(udid, "home"); return }
        }
        when (k) {
            "BACK" -> back()
            "HOME" -> driver.mobile("pressButton", buildJsonObject { put("name", "home") })
            "ENTER" -> driver.keys("\n")
            "DEL" -> driver.keys("\b")
            "TAB" -> driver.keys("\t")
            "SPACE", "CODE=62" -> driver.keys(" ")
            "VOLUME_UP", "VOLUME_DOWN" -> driver.mobile("pressButton", buildJsonObject { put("name", if (k == "VOLUME_UP") "volumeUp" else "volumeDown") })
            else -> throw BridgeError("BAD_ARG", "the key $spec has no iOS counterpart")
        }
    }

    /**
     * iOS has no back key. Like Android's BACK: the keyboard goes first; then the navigation bar's
     * back button (its first button); else a swipe in from the left edge, the system's back gesture.
     */
    private fun back() {
        val d = dump()
        if (XcuiTree.keyboardShown(d)) { runCatching { driver.mobile("hideKeyboard") }; return }
        val navBack = nodes(d).firstOrNull { (n, ancestors) ->
            (n["xcui"] as? JsonPrimitive)?.content == "Button" && ancestors.any { (it["xcui"] as? JsonPrimitive)?.content == "NavigationBar" }
        }?.first ?: if (axe != null) axBackButton(d) else null
        if (navBack != null) { tapCenter(navBack); return }
        val (w, h) = geometry.first to geometry.second
        swipePx(2, h / 2, w * 6 / 10, h / 2, 250)
    }

    /**
     * The accessibility tree has no navigation bar around the back button: a button named BackButton
     * or labelled Back, in the top left of the screen.
     */
    private fun axBackButton(d: JsonObject): JsonObject? {
        val (w, h) = geometry.first to geometry.second
        return nodes(d).map { it.first }.firstOrNull { n ->
            val b = n.bounds() ?: return@firstOrNull false
            fun s(k: String) = (n[k] as? JsonPrimitive)?.content.orEmpty()
            s("cls") == "Button" && b[1] < h / 6 && b[0] < w / 3 && (s("rid") == "BackButton" || s("text").lowercase() in BACK_LABELS)
        }
    }

    private fun dumpsys(service: String): String = when (service.trim()) {
        "input_method" -> "mInputShown=" + XcuiTree.keyboardShown(dump())
        else -> ""
    }

    private fun nodes(d: JsonObject): List<Pair<JsonObject, List<JsonObject>>> {
        val out = mutableListOf<Pair<JsonObject, List<JsonObject>>>()
        fun walk(o: JsonObject, anc: List<JsonObject>) {
            out += o to anc
            (o["children"] as? JsonArray).orEmpty().forEach { c -> (c as? JsonObject)?.let { walk(it, anc + o) } }
        }
        (d["root"] as? JsonObject)?.let { walk(it, emptyList()) }
        return out
    }

    /** The node a daemon selector (`id=… text="…"`, `class=EditText text~=…`) names, on a fresh dump. */
    private fun node(selector: String): JsonObject {
        val sel = Args.parse(selector).filterKeys { it != "value" }
        val all = nodes(dump()).map { it.first }
        if (sel.isEmpty()) return all.firstOrNull { 'F' in ((it["flags"] as? JsonPrimitive)?.content ?: "") } ?: throw BridgeError("not-found", "no focused field")
        return all.firstOrNull { n -> Args.matches(n, sel) } ?: throw BridgeError("not-found", "no node matched $selector")
    }

    private fun tapCenter(n: JsonObject) {
        val b = n.bounds() ?: throw BridgeError("not-found", "the node has no bounds")
        tapPx((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
        if (axe != null && n.isInput()) focused = b
    }

    /** An input's text in one go: found through WebDriver by its identifier, else by its type and index. */
    private fun setText(selectorAndValue: String, value: String) {
        val n = node(selectorAndValue.substringBefore(" value=").let { if (it.startsWith("value=")) "" else it })
        if (axe != null) {
            // Focus it, select what is there and delete it, then type.
            tapCenter(n)
            Thread.sleep(150)
            axe.keyCombo(udid, listOf(AxeCli.CMD), AxeCli.A)
            axe.key(udid, AxeCli.DELETE)
            typeText(value)
            return
        }
        val rid = (n["rid"] as? JsonPrimitive)?.content
        val type = (n["xcui"] as? JsonPrimitive)?.content ?: "TextField"
        val index = (n["xi"] as? JsonPrimitive)?.content ?: "1"
        val el = rid?.let { driver.find("accessibility id", it) }
            ?: driver.find("-ios class chain", "**/XCUIElementType$type[$index]")
            ?: run { tapCenter(n); driver.activeElement() }
            ?: throw BridgeError("not-found", "could not reach the field through WebDriver")
        driver.clear(el)
        if (value.isNotEmpty()) driver.setValue(el, value)
    }

    /**
     * The system's «Open in “App”?» that a simulator shows when its own URL scheme is opened from
     * outside (simctl openurl): its Open button tapped, from the tree or else through WebDriverAgent's
     * alert. «accepted», or «none» when no such prompt is up.
     */
    private fun acceptOpenPrompt(): String {
        val all = nodes(dump()).map { it.first }
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
        if (all.none { OPEN_PROMPT.matches(it.s("text")) }) return "none"
        all.firstOrNull { it.s("cls") == "Button" && it.s("text") in OPEN_LABELS }?.let { tapCenter(it); return "accepted" }
        for (label in OPEN_LABELS) {
            val args = buildJsonObject { put("action", "accept"); put("buttonLabel", label) }
            if (runCatching { driver.mobile("alert", args) }.isSuccess) return "accepted"
        }
        return "none"
    }

    private fun waitIdle(idleMs: Long, timeoutMs: Long) {
        val t0 = System.currentTimeMillis()
        var last = source(); var since = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            Thread.sleep(idleMs.coerceIn(100, 1_000))
            val now = source()
            if (now == last) { if (System.currentTimeMillis() - since >= idleMs) return } else { last = now; since = System.currentTimeMillis() }
        }
        throw BridgeError("TIMEOUT", "the screen kept changing for ${timeoutMs / 1000.0}s")
    }

    private fun waitActivity(n: String, timeoutMs: Long): String {
        val want = n.substringBefore('/')
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (bundle() == want) return "ok $want after ${System.currentTimeMillis() - t0}ms"
            Thread.sleep(250)
        }
        throw BridgeError("TIMEOUT", "$want not in front after ${timeoutMs / 1000.0}s")
    }

    /** WebDriverAgent's MJPEG frames, each as one frame on the wire, until the client goes. */
    private fun mirror(out: OutputStream) {
        val port = mjpegPort ?: if (axe != null) return pollMirror(out) else throw BridgeError("unsupported", "no MJPEG stream for this device")
        val conn = URI("http://127.0.0.1:$port/").toURL().openConnection().apply { connectTimeout = 5_000; readTimeout = 30_000 }
        conn.getInputStream().buffered().use { input -> Mjpeg.frames(input) { jpeg -> runCatching { HsWire.writeFrame(out, jpeg) }.isSuccess && !closed } }
    }

    /** Without WebDriverAgent (AXe): simctl screenshots, about four a second. */
    private fun pollMirror(out: OutputStream) {
        while (!closed) {
            val t0 = System.currentTimeMillis()
            val f = runCatching { shot("jpeg") }.getOrNull()
            val bytes = try { f?.let { ImageIO.read(it) }?.let { encode(fit(it, 900), "jpeg", 0.6f) } } finally { f?.delete() }
            if (bytes != null && runCatching { HsWire.writeFrame(out, bytes) }.isFailure) return
            Thread.sleep((250 - (System.currentTimeMillis() - t0)).coerceAtLeast(20))
        }
    }

    override fun close() {
        closed = true
        frames?.close()
        runCatching { server.close() }
    }

    companion object {
        private val BACK_LABELS = setOf("back", "tilbake")
        private val OPEN_PROMPT = Regex("""^(Open in|Åpne i) [“«"].+[”»"]\?$""")
        private val OPEN_LABELS = listOf("Open", "Åpne")

        fun fit(img: BufferedImage, longEdge: Int): BufferedImage {
            val k = longEdge.toDouble() / maxOf(img.width, img.height)
            if (k >= 1.0) return img
            val w = (img.width * k).toInt().coerceAtLeast(1); val h = (img.height * k).toInt().coerceAtLeast(1)
            return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { o ->
                val g = o.createGraphics()
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(img, 0, 0, w, h, null); g.dispose()
            }
        }

        fun encode(img: BufferedImage, fmt: String, quality: Float): ByteArray {
            val bos = ByteArrayOutputStream()
            if (fmt == "png") { ImageIO.write(img, "png", bos); return bos.toByteArray() }
            // JPEG has no alpha: draw onto RGB first.
            val rgb = if (img.type == BufferedImage.TYPE_INT_RGB) img else BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().apply { drawImage(img, 0, 0, null); dispose() } }
            val w = ImageIO.getImageWritersByFormatName("jpeg").next()
            ImageIO.createImageOutputStream(bos).use { ios ->
                w.output = ios
                val p = w.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality.coerceIn(0.1f, 1f) }
                w.write(null, IIOImage(rgb, null, null), p)
            }
            w.dispose()
            return bos.toByteArray()
        }
    }
}

/** The daemon's `k=v` arguments, values optionally in double quotes; `k~=v` means "contains". */
/** WebDriverAgent's orientation in Testlab's words (as handsets_orientation and Android use them). */
internal fun orientationName(wda: String): String = when (wda.uppercase().removePrefix("UIA_DEVICE_ORIENTATION_")) {
    "PORTRAIT" -> "portrait"
    "LANDSCAPE", "LANDSCAPELEFT" -> "landscape"
    "LANDSCAPERIGHT" -> "landscape-right"
    "PORTRAIT_UPSIDEDOWN", "PORTRAITUPSIDEDOWN" -> "portrait-upside-down"
    else -> wda.lowercase().ifEmpty { "unknown" }
}

/** "x,y;x,y;…" from the drag verb. */
internal fun dragPoints(s: String): List<Pair<Int, Int>> = s.split(';').filter { it.isNotBlank() }.map { p ->
    val (x, y) = p.split(',').map { it.trim().toDouble().toInt() }
    x to y
}.also { require(it.size >= 2) { "a drag needs at least two points" } }

/** The points of [repeat] passes along [pts], every other one backwards (scrubbing). */
internal fun dragPath(pts: List<Pair<Int, Int>>, repeat: Int): List<Pair<Int, Int>> =
    (0 until maxOf(1, repeat)).fold(listOf(pts.first())) { acc, pass -> acc + (if (pass % 2 == 1) pts.reversed() else pts).drop(1) }

/** Each leg's time: [durMs] per pass, split over the legs by their length. */
internal fun legTimes(pts: List<Pair<Int, Int>>, durMs: Int, repeat: Int): List<Int> {
    val lens = pts.zipWithNext { a, b -> Math.hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) }
    val total = lens.sum()
    val one = lens.map { l -> maxOf(16, if (total > 0) Math.round(durMs * l / total).toInt() else durMs / lens.size) }
    return (0 until maxOf(1, repeat)).flatMap { pass -> if (pass % 2 == 1) one.reversed() else one }
}

internal class Args private constructor(private val m: Map<String, String>) : Map<String, String> by m {
    fun int(k: String): Int = m[k]?.toIntOrNull() ?: throw IllegalArgumentException("missing $k")
    fun intOr(k: String, d: Int): Int = m[k]?.toIntOrNull() ?: d

    companion object {
        private val TOKEN = Regex("""([A-Za-z_]+)(~?=)("([^"]*)"|\S*)""")

        fun parse(s: String): Args = Args(TOKEN.findAll(s).associate { m ->
            val key = if (m.groupValues[2] == "~=") m.groupValues[1] + "~" else m.groupValues[1]
            key to (m.groups[4]?.value ?: m.groupValues[3])
        })

        /** Whether node [n] fits every part of [sel] (id, text, desc, class; `~` = contains, any case). */
        fun matches(n: JsonObject, sel: Map<String, String>): Boolean = sel.all { (k, v) ->
            fun s(f: String) = (n[f] as? JsonPrimitive)?.content.orEmpty()
            when (k) {
                "id" -> s("rid") == v || s("rid").substringAfterLast('/') == v
                "text" -> s("text") == v
                "text~" -> v.lowercase() in s("text").lowercase()
                "desc" -> s("desc") == v
                "desc~" -> v.lowercase() in s("desc").lowercase()
                "class" -> s("cls").substringAfterLast('.') == v.substringAfterLast('.')
                "n", "nth" -> true
                else -> true
            }
        }
    }
}

/**
 * Keeps reading WebDriverAgent's MJPEG stream on [port] and hands out its frames: [next] waits for
 * the first one after a moment, so a screenshot never shows the screen from before the call. A
 * dropped stream is opened again on the next ask.
 */
class MjpegTap(private val port: Int) : Closeable {
    private val lock = Object()
    @Volatile private var latest: ByteArray? = null
    @Volatile private var latestAt = 0L
    @Volatile private var reader: Thread? = null
    @Volatile private var closed = false

    private fun ensureReading() {
        if (reader?.isAlive == true || closed) return
        reader = Thread({
            runCatching {
                val conn = URI("http://127.0.0.1:$port/").toURL().openConnection().apply { connectTimeout = 3_000; readTimeout = 10_000 }
                conn.getInputStream().buffered().use { input ->
                    Mjpeg.frames(input) { jpeg ->
                        synchronized(lock) { latest = jpeg; latestAt = System.nanoTime(); lock.notifyAll() }
                        !closed
                    }
                }
            }
        }, "mjpeg-tap-$port").apply { isDaemon = true; start() }
    }

    /**
     * The first frame that arrived after [since] (`System.nanoTime()`). WebDriverAgent may send no
     * frame while the screen stands still, so after [waitMs] the last frame counts as the screen;
     * null when none came at all (no stream: take a real screenshot).
     */
    fun next(since: Long, waitMs: Long): ByteArray? {
        ensureReading()
        val until = System.currentTimeMillis() + waitMs
        synchronized(lock) {
            while (latestAt <= since) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) return if (reader?.isAlive == true) latest else null
                lock.wait(left)
            }
            return latest
        }
    }

    override fun close() { closed = true; reader?.interrupt() }
}

/** Splits a multipart MJPEG stream into JPEG images (by their start and end markers, so any boundary works). */
object Mjpeg {
    fun frames(input: InputStream, onFrame: (ByteArray) -> Boolean) {
        val buf = ByteArrayOutputStream()
        var inImage = false
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return
            if (!inImage) {
                if (prev == 0xFF && b == 0xD8) { inImage = true; buf.reset(); buf.write(0xFF); buf.write(0xD8) }
            } else {
                buf.write(b)
                if (prev == 0xFF && b == 0xD9) {
                    inImage = false
                    if (!onFrame(buf.toByteArray())) return
                }
            }
            prev = b
        }
    }
}
