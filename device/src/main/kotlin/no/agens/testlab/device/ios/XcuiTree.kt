package no.agens.testlab.device.ios

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDriverAgent's `/source` XML as the daemon's dump JSON (`{root: {cls, pkg, rid, text, desc,
 * hint, flags, bounds, children}}`), so the dump table, selectors, tap/fill/wait_for and the autopilot read
 * an iOS screen as they read an Android one.
 *
 * - XCUI types become the Android widget names those are tuned on: Button, EditText (with `p` for
 *   a SecureTextField), TextView, Switch, ImageView, ScrollView…
 * - `rid` is the accessibility identifier (WDA's `name` when it differs from the label), `text`
 *   the label (an input's value), `desc` an input's label, `hint` its placeholder.
 * - Bounds are points times [scale]: pixels, as screenshots and taps use.
 * - Hidden nodes go (without WDA's `visible`, left out because it is slow to work out: nodes with no
 *   size or wholly off the screen); the keyboard stays as one `Keyboard` node without its keys; wrappers that
 *   only hold one child and say nothing are skipped, so the tree isn't twenty levels deep.
 * - [XCUI] and [XCUI_INDEX] keep the element's own type and its index among that type, which the
 *   bridge uses to find the element again through WebDriver.
 */
object XcuiTree {
    const val XCUI = "xcui"
    const val XCUI_INDEX = "xi"

    fun toDump(xml: String, scale: Double, pkg: String): JsonObject {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = f.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
        val top = doc.documentElement.let { if (it.tagName == "AppiumAUT") it.elements().firstOrNull() ?: it else it }
        val counters = HashMap<String, Int>()
        val screen = (top.a("width").toDoubleOrNull() ?: 0.0) to (top.a("height").toDoubleOrNull() ?: 0.0)
        val root = convert(top, scale, pkg, counters, inTabBar = false, screen) ?: buildJsonObject { put("cls", "View"); put("pkg", pkg); put("flags", "v") }
        return buildJsonObject { put("root", root) }
    }

    private fun Element.elements(): List<Element> {
        val out = mutableListOf<Element>()
        val n = childNodes
        for (i in 0 until n.length) (n.item(i) as? Element)?.let(out::add)
        return out
    }

    private fun Element.a(name: String): String = getAttribute(name).orEmpty()

    private fun convert(e: Element, scale: Double, pkg: String, counters: MutableMap<String, Int>, inTabBar: Boolean, screen: Pair<Double, Double>): JsonObject? {
        val type = e.a("type").ifEmpty { e.tagName }.removePrefix("XCUIElementType")
        // Counted before skipping, in document order, as `**/XCUIElementTypeX[n]` counts.
        val index = counters.merge(type, 1, Int::plus)!!
        if (hidden(e, screen)) return null
        val kids = if (type == "Keyboard") emptyList() else e.elements().mapNotNull { convert(it, scale, pkg, counters, inTabBar || type == "TabBar", screen) }

        val label = e.a("label"); val name = e.a("name"); val value = e.a("value")
        val cls = CLASSES[type] ?: "View"
        val input = cls == "EditText"
        val container = type in CONTAINERS
        val accessible = e.a("accessible") == "true"
        val text = when {
            input -> value
            // Its name is the screen's title: what `state top` and the autopilot's screen.title read.
            type == "NavigationBar" -> name.ifEmpty { label }
            container && !accessible -> ""
            label.isNotEmpty() -> label
            type == "StaticText" -> value
            else -> ""
        }
        val desc = if (input) label else ""
        val rid = name.takeIf { it.isNotEmpty() && it != label && it != value && !container }.orEmpty()

        // A wrapper that says nothing and holds one thing: the thing.
        if (container && text.isEmpty() && rid.isEmpty() && kids.size == 1 && type != "Application" && type != "Cell") return kids[0]

        val flags = buildString {
            if (type in CLICKABLE || (container && accessible && text.isNotEmpty())) append('c')
            if (type in SCROLLABLE) append('s')
            if (input) append('f')
            if (e.a("focused") == "true" || e.a("hasFocus") == "true") append('F')
            if (type in CHECKABLE) { append('k'); if (value == "1" || value.equals("true", true)) append('K') }
            if (type == "SecureTextField") append('p')
            if (e.a("selected") == "true" || (inTabBar && type == "Button" && value == "1")) append('S')
            append('v')
            if (e.a("enabled") != "false") append('e')
        }
        fun n(k: String) = e.a(k).toDoubleOrNull() ?: 0.0
        val x = n("x"); val y = n("y"); val w = n("width"); val h = n("height")
        return buildJsonObject {
            put("cls", cls)
            put("pkg", pkg)
            if (rid.isNotEmpty()) put("rid", rid)
            if (text.isNotEmpty()) put("text", text)
            if (desc.isNotEmpty()) put("desc", desc)
            e.a("placeholderValue").takeIf { it.isNotEmpty() }?.let { put("hint", it) }
            put("flags", flags)
            put("bounds", buildJsonArray {
                add(JsonPrimitive(Math.round(x * scale))); add(JsonPrimitive(Math.round(y * scale)))
                add(JsonPrimitive(Math.round((x + w) * scale))); add(JsonPrimitive(Math.round((y + h) * scale)))
            })
            put(XCUI, type)
            put(XCUI_INDEX, index)
            if (kids.isNotEmpty()) put("children", JsonArray(kids))
        }
    }

    /** WDA's `visible` when the source has it; else by the bounds: no size, or wholly off [screen] (points). */
    private fun hidden(e: Element, screen: Pair<Double, Double>): Boolean {
        if (e.hasAttribute("visible")) return e.a("visible") == "false"
        val x = e.a("x").toDoubleOrNull() ?: return false
        val y = e.a("y").toDoubleOrNull() ?: return false
        val w = e.a("width").toDoubleOrNull() ?: 0.0
        val h = e.a("height").toDoubleOrNull() ?: 0.0
        if (w <= 0.0 || h <= 0.0) return true
        val (sw, sh) = screen
        return sw > 0 && sh > 0 && (x >= sw || y >= sh || x + w <= 0 || y + h <= 0)
    }

    /** Whether the on-screen keyboard is up, from a dump [toDump] made. */
    fun keyboardShown(dump: JsonObject): Boolean {
        fun walk(o: JsonObject): Boolean = (o[XCUI] as? JsonPrimitive)?.content == "Keyboard" ||
            (o["children"] as? JsonArray).orEmpty().any { (it as? JsonObject)?.let(::walk) == true }
        return (dump["root"] as? JsonObject)?.let(::walk) == true
    }

    private val CLASSES = mapOf(
        "Button" to "Button", "Link" to "Button", "Icon" to "Button", "MenuItem" to "Button", "Key" to "Button",
        "StaticText" to "TextView", "TextField" to "EditText", "SecureTextField" to "EditText", "SearchField" to "EditText", "TextView" to "EditText",
        "Switch" to "Switch", "Toggle" to "Switch", "CheckBox" to "CheckBox",
        "Image" to "ImageView", "Slider" to "SeekBar", "Picker" to "NumberPicker", "PickerWheel" to "NumberPicker", "DatePicker" to "DatePicker",
        "ScrollView" to "ScrollView", "Table" to "RecyclerView", "CollectionView" to "RecyclerView",
        "WebView" to "WebView", "Keyboard" to "Keyboard", "Alert" to "AlertDialog", "Sheet" to "AlertDialog",
        "ProgressIndicator" to "ProgressBar", "ActivityIndicator" to "ProgressBar", "PageIndicator" to "View",
        "Cell" to "View", "Other" to "View", "Group" to "View", "Window" to "View", "Application" to "View",
        "NavigationBar" to "View", "TabBar" to "View", "Toolbar" to "View", "SegmentedControl" to "View",
    )
    private val CONTAINERS = setOf("Other", "Group", "Window", "Application", "Cell", "NavigationBar", "TabBar", "Toolbar", "ScrollView", "Table", "CollectionView", "WebView", "SegmentedControl", "Alert", "Sheet")
    private val CLICKABLE = setOf("Button", "Link", "Icon", "MenuItem", "Cell", "Switch", "Toggle", "CheckBox", "Key", "SegmentedControl")
    private val SCROLLABLE = setOf("ScrollView", "Table", "CollectionView", "WebView")
    private val CHECKABLE = setOf("Switch", "Toggle", "CheckBox")
}
