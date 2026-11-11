package no.agens.testlab.device.ios

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

class AxeException(message: String) : RuntimeException(message)

/**
 * AXe (github.com/cameroncooke/AXe, MIT): reads a simulator's accessibility tree and sends touches
 * and keys through the simulator itself, without XCUITest or WebDriverAgent. A screen is one call
 * of a few hundred milliseconds, as `uiautomator` is on Android, and nothing has to be built or
 * started first. Coordinates are points. [exec] replaces the process in tests; it gets the
 * arguments after `axe` and the stdin, if any.
 */
class AxeCli(
    val bin: String,
    private val exec: ((List<String>, ByteArray?) -> XcrunCli.Result)? = null,
) {
    fun run(args: List<String>, timeoutMs: Long = 30_000, stdin: ByteArray? = null): XcrunCli.Result {
        exec?.let { return it(args, stdin) }
        val out = File.createTempFile("axe", ".out"); val err = File.createTempFile("axe", ".err")
        try {
            val pb = ProcessBuilder(listOf(bin) + args).redirectOutput(out).redirectError(err)
            if (stdin == null) pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            val p = pb.start()
            stdin?.let { p.outputStream.use { o -> o.write(it) } }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                throw AxeException("axe ${args.firstOrNull().orEmpty()} did not finish in ${timeoutMs / 1000}s")
            }
            return XcrunCli.Result(p.exitValue(), out.readText(), err.readText())
        } finally { out.delete(); err.delete() }
    }

    private fun checked(udid: String, vararg args: String, stdin: ByteArray? = null): String {
        val r = run(args.toList() + listOf("--udid", udid), stdin = stdin)
        if (!r.ok) throw AxeException("axe ${args.first()} failed: " + r.stderr.ifBlank { r.stdout }.trim().lineSequence().firstOrNull().orEmpty().take(300))
        return r.stdout
    }

    /** The accessibility tree as JSON: an array with the app (or SpringBoard) at the top. */
    /**
     * The screen's accessibility tree. Right after a simulator boots, AXe can't read it yet («No
     * translation object returned»): that is waited out for up to ten seconds.
     */
    fun describe(udid: String): String {
        var tries = 0
        while (true) {
            try {
                return checked(udid, "describe-ui")
            } catch (e: AxeException) {
                if ("No translation object" !in (e.message ?: "") || ++tries >= 10) throw e
                Thread.sleep(1_000)
            }
        }
    }

    fun tap(udid: String, x: Int, y: Int) { checked(udid, "tap", "-x", x.toString(), "-y", y.toString()) }

    fun swipe(udid: String, x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int) {
        checked(udid, "swipe", "--start-x", "$x1", "--start-y", "$y1", "--end-x", "$x2", "--end-y", "$y2", "--duration", "%.2f".format(java.util.Locale.ROOT, (durMs.coerceIn(50, 10_000)) / 1000.0))
    }

    /** US-keyboard text only: other characters go through the pasteboard (the bridge does that). */
    fun type(udid: String, text: String) { checked(udid, "type", "--stdin", stdin = text.toByteArray()) }

    fun key(udid: String, code: Int) { checked(udid, "key", code.toString()) }

    fun keyCombo(udid: String, modifiers: List<Int>, key: Int) { checked(udid, "key-combo", "--modifiers", modifiers.joinToString(","), "--key", key.toString()) }

    fun button(udid: String, name: String) { checked(udid, "button", name) }

    companion object {
        /** HID usage codes AXe's `key` takes. */
        const val ENTER = 40; const val DELETE = 42; const val TAB = 43; const val SPACE = 44
        const val A = 4; const val V = 25; const val CMD = 227

        /** Whether AXe can type [text] itself (it knows the US keyboard only). */
        fun typeable(text: String): Boolean = text.all { it == '\n' || it == '\t' || it.code in 0x20..0x7E }

        /**
         * `axe` on PATH or in Homebrew's folders (a launchd service has a bare PATH); null when it is
         * not installed, or when TESTLAB_IOS_DRIVER=wda asks for WebDriverAgent alone.
         */
        fun locate(env: Map<String, String> = System.getenv()): String? {
            if (env["TESTLAB_IOS_DRIVER"].equals("wda", ignoreCase = true)) return null
            return (env["PATH"].orEmpty().split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin"))
                .map { File(it, "axe") }.firstOrNull { it.canExecute() }?.path
        }

        const val INSTALL = "brew install cameroncooke/axe/axe"
    }
}

/**
 * AXe's `describe-ui` JSON as the daemon's dump JSON, like [XcuiTree] does for WebDriverAgent's XML:
 * the same widget names, `rid` = the accessibility identifier, `text` the label (an input's value),
 * bounds in pixels ([scale] times points). The accessibility tree holds only what is on screen and
 * meant for the user, so there is no `visible` to read; elements wholly outside the screen go.
 */
object AxTree {
    data class Parsed(val dump: JsonObject, val pid: Int?, val appName: String?, val sizePt: Pair<Double, Double>?)

    fun parse(json: String, scale: Double, pkg: (Int?, String?) -> String, focused: IntArray? = null): Parsed {
        val el = Json.parseToJsonElement(json)
        val tops = when (el) { is JsonArray -> el.mapNotNull { it as? JsonObject }; is JsonObject -> listOf(el); else -> emptyList() }
        val app = tops.firstOrNull { s(it, "type") == "Application" } ?: tops.firstOrNull()
        val pid = app?.let { (it["pid"] as? JsonPrimitive)?.content?.toIntOrNull() }
        val name = app?.let { s(it, "AXLabel") }
        val size = app?.let { frame(it) }?.let { (_, _, w, h) -> w to h }?.takeIf { it.first > 0 && it.second > 0 }
        val p = pkg(pid, name)
        val screen = size ?: (10_000.0 to 10_000.0)
        val counters = HashMap<String, Int>()
        val kids = tops.mapNotNull { convert(it, scale, p, screen, counters, focused, inTabBar = false) }
        val root = when {
            kids.size == 1 -> kids[0]
            else -> buildJsonObject { put("cls", "View"); put("pkg", p); put("flags", "ve"); put(XcuiTree.XCUI, "Application"); if (kids.isNotEmpty()) put("children", JsonArray(kids)) }
        }
        return Parsed(buildJsonObject { put("root", root) }, pid, name, size)
    }

    private fun s(o: JsonObject, k: String): String = (o[k] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content?.trim().orEmpty()

    /** x, y, width, height in points: `frame`, else the `AXFrame` string «{{x, y}, {w, h}}». */
    private fun frame(o: JsonObject): List<Double>? {
        val f = o["frame"] as? JsonObject
        val v = f?.let { listOf("x", "y", "width", "height").map { k -> (it[k] as? JsonPrimitive)?.content?.toDoubleOrNull() } }
        if (v != null && v.all { it != null }) return v.map { it!! }
        val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(s(o, "AXFrame")).map { it.value.toDouble() }.toList()
        return nums.takeIf { it.size == 4 }
    }

    private fun convert(o: JsonObject, scale: Double, pkg: String, screen: Pair<Double, Double>, counters: MutableMap<String, Int>, focused: IntArray?, inTabBar: Boolean): JsonObject? {
        val type = normalType(o)
        val index = counters.merge(type, 1, Int::plus)!!
        val f = frame(o)
        // Wholly off screen (scrolled away): not on the screen, as WebDriverAgent's visible=false.
        if (f != null && type != "Application" && (f[0] + f[2] <= 0 || f[1] + f[3] <= 0 || f[0] >= screen.first || f[1] >= screen.second || (f[2] <= 0 && f[3] <= 0))) return null
        val tabBar = inTabBar || type == "TabBar"
        val kids = if (type == "Keyboard") emptyList() else (o["children"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { c -> convert(c, scale, pkg, screen, counters, focused, tabBar) } }

        val label = s(o, "AXLabel"); val value = s(o, "AXValue")
        val id = s(o, "AXUniqueId").ifEmpty { s(o, "AXIdentifier") }
        val cls = CLASSES[type] ?: "View"
        val input = cls == "EditText"
        val container = type in CONTAINERS
        val text = when {
            input -> value
            type == "Application" -> ""
            label.isNotEmpty() -> label
            type == "StaticText" || type == "Heading" -> value
            else -> ""
        }
        val desc = if (input) label else ""
        val rid = id.takeIf { it != label && it != value }.orEmpty()

        if (container && text.isEmpty() && rid.isEmpty() && kids.size == 1 && type != "Application" && type != "Cell") return kids[0]
        // An empty group with nothing in it says nothing.
        if (container && text.isEmpty() && rid.isEmpty() && kids.isEmpty() && type != "Application") return null

        val bounds = f?.let { (x, y, w, h) -> listOf(x * scale, y * scale, (x + w) * scale, (y + h) * scale).map { Math.round(it).toInt() } } ?: listOf(0, 0, 0, 0)
        val enabled = (o["enabled"] as? JsonPrimitive)?.content != "false"
        val flags = buildString {
            if (type in CLICKABLE || (o["custom_actions"] as? JsonArray)?.isNotEmpty() == true || (type == "Cell")) append('c')
            if (type in SCROLLABLE) append('s')
            if (input) append('f')
            if (input && focused != null && bounds == focused.toList()) append('F')
            if (type in CHECKABLE) { append('k'); if (value == "1" || value.equals("true", true) || value.equals("on", true)) append('K') }
            if (type == "SecureTextField") append('p')
            if ((tabBar || type == "Tab" || type == "TabBarButton" || type == "RadioButton") && (value == "1" || value.equals("selected", true))) append('S')
            append('v')
            if (enabled) append('e')
        }
        return buildJsonObject {
            put("cls", cls)
            put("pkg", pkg)
            if (rid.isNotEmpty()) put("rid", rid)
            if (text.isNotEmpty()) put("text", text)
            if (desc.isNotEmpty()) put("desc", desc)
            put("flags", flags)
            put("bounds", buildJsonArray { bounds.forEach { add(JsonPrimitive(it)) } })
            put(XcuiTree.XCUI, type)
            put(XcuiTree.XCUI_INDEX, index)
            if (kids.isNotEmpty()) put("children", JsonArray(kids))
        }
    }

    /** The element's type; a keyboard key or the keyboard itself (by role or description) is «Keyboard». */
    private fun normalType(o: JsonObject): String {
        val type = s(o, "type").removePrefix("AX").ifEmpty { s(o, "role").removePrefix("AX") }.ifEmpty { "Other" }
        val rd = s(o, "role_description").lowercase()
        return when {
            type == "Keyboard" || type == "Key" || rd == "keyboard" || rd == "keyboard key" -> "Keyboard"
            type == "TextArea" -> "TextView"
            type == "PopUpButton" -> "Button"
            else -> type
        }
    }

    /** Whether the keyboard is up, from a dump [parse] made. */
    fun keyboardShown(dump: JsonObject): Boolean = XcuiTree.keyboardShown(dump)

    /** The screen's title: the first heading, as the navigation bar's title reads. */
    fun title(dump: JsonObject): String? {
        fun walk(o: JsonObject): String? {
            if ((o[XcuiTree.XCUI] as? JsonPrimitive)?.content == "Heading") (o["text"] as? JsonPrimitive)?.content?.let { return it }
            return (o["children"] as? JsonArray).orEmpty().firstNotNullOfOrNull { (it as? JsonObject)?.let(::walk) }
        }
        return (dump["root"] as? JsonObject)?.let(::walk)
    }

    private val CLASSES = mapOf(
        "Button" to "Button", "Link" to "Button", "MenuItem" to "Button", "Tab" to "Button", "TabBarButton" to "Button", "RadioButton" to "Button",
        "StaticText" to "TextView", "Heading" to "TextView",
        "TextField" to "EditText", "SecureTextField" to "EditText", "SearchField" to "EditText", "TextView" to "EditText", "ComboBox" to "EditText",
        "Switch" to "Switch", "Toggle" to "Switch", "CheckBox" to "CheckBox",
        "Image" to "ImageView", "Slider" to "SeekBar", "Picker" to "NumberPicker", "PickerWheel" to "NumberPicker", "DatePicker" to "DatePicker",
        "ScrollView" to "ScrollView", "ScrollArea" to "ScrollView", "Table" to "RecyclerView", "CollectionView" to "RecyclerView", "List" to "RecyclerView",
        "WebView" to "WebView", "WebArea" to "WebView", "Keyboard" to "Keyboard", "Alert" to "AlertDialog", "Sheet" to "AlertDialog", "Dialog" to "AlertDialog",
        "ProgressIndicator" to "ProgressBar", "ActivityIndicator" to "ProgressBar", "BusyIndicator" to "ProgressBar",
        "Cell" to "View", "Other" to "View", "Group" to "View", "Window" to "View", "Application" to "View",
        "NavigationBar" to "View", "TabBar" to "View", "TabGroup" to "View", "Toolbar" to "View", "SegmentedControl" to "View",
    )
    private val CONTAINERS = setOf("Other", "Group", "Window", "Application", "Cell", "NavigationBar", "TabBar", "TabGroup", "Toolbar", "ScrollView", "ScrollArea", "Table", "CollectionView", "List", "WebView", "WebArea", "SegmentedControl", "Alert", "Sheet", "Dialog")
    private val CLICKABLE = setOf("Button", "Link", "MenuItem", "Cell", "Switch", "Toggle", "CheckBox", "SegmentedControl", "Tab", "TabBarButton", "RadioButton", "Slider", "TextField", "SecureTextField", "SearchField")
    private val SCROLLABLE = setOf("ScrollView", "ScrollArea", "Table", "CollectionView", "List", "WebView", "WebArea")
    private val CHECKABLE = setOf("Switch", "Toggle", "CheckBox")
}
