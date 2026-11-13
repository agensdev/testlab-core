package no.agens.testlab.core.dump

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Renders the daemon's `dump_active` / `dump` JSON exactly like the handsets CLI does
 * (`handsets-cli/src/ui_dump.rs`, v0.1.38): [renderTable] is `hs ui`, [renderTree] is
 * `hs ui --tree`. The autopilot engine was tuned on that text, so the output must stay
 * byte-identical; goldens captured from the real CLI guard it.
 *
 * Rust details reproduced on purpose:
 * - string fields are read only when the JSON value is a string, first match wins;
 * - lengths and truncation count Unicode code points (Rust `chars()`), not UTF-16 units;
 * - the table pads the verb and class columns to the widest row (byte length, as `str::len`);
 * - centres use integer division; a `null` child prints an empty, indented tree line.
 */
object UiDumpRender {

    /** `hs ui`: flat, verb-led table of interactive or labelled nodes. */
    fun renderTable(dump: JsonElement): String {
        val rows = mutableListOf<Row>()
        for (root in collectRoots(dump)) collectInteractive(root, rows)
        val verbW = rows.maxOfOrNull { utf8Length(it.verb) } ?: 0
        val classW = rows.maxOfOrNull { utf8Length(it.cls) } ?: 0
        val out = StringBuilder()
        for (r in rows) {
            out.append(padEnd(r.verb, verbW)).append("  ").append(padEnd(r.cls, classW)).append("  ").append(r.label)
            if (r.id.isNotEmpty()) out.append("  ").append(r.id)
            out.append("  ").append(r.coords)
            if (r.flags.isNotEmpty()) out.append("  [").append(r.flags).append(']')
            out.append('\n')
        }
        return out.toString()
    }

    /** `hs ui --tree`: every node on one indented line. */
    fun renderTree(dump: JsonElement): String {
        val out = StringBuilder()
        for (root in collectRoots(dump)) walk(root, 0, out)
        return out.toString()
    }

    /**
     * For accessibility testing: every node that has something for a screen reader or takes an
     * action, with text, content description and hint as separate fields (TalkBack reads the
     * description when there is one) and every state flag, `disabled` included.
     */
    fun renderA11y(dump: JsonElement): String {
        val out = StringBuilder()
        for (root in collectRoots(dump)) walkA11y(root, 0, out)
        return out.toString()
    }

    private fun walkA11y(node: JsonElement, depth: Int, out: StringBuilder) {
        val flags = getStr(node, "flags").orEmpty()
        val text = getStr(node, "text").orEmpty()
        val desc = getStr(node, "desc").orEmpty()
        val hint = getStr(node, "hint").orEmpty()
        val idShort = getStr(node, "rid").orEmpty().substringAfterLast('/')
        val actionable = 'c' in flags || 'L' in flags || 'f' in flags || 's' in flags || 'k' in flags
        if ((text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || actionable) && hasVisibleBounds(node)) {
            val line = StringBuilder()
            repeat(depth.coerceAtMost(12)) { line.append(' ') }
            line.append(getStr(node, "cls").orEmpty().substringAfterLast('.'))
            if (idShort.isNotEmpty()) line.append(" #").append(idShort)
            if (text.isNotEmpty()) line.append("  text=").append(truncateQuoted(text, 80))
            if (desc.isNotEmpty()) line.append("  desc=").append(truncateQuoted(desc, 80))
            if (hint.isNotEmpty()) line.append("  hint=").append(truncateQuoted(hint, 60))
            val tags = A11Y_TAGS.filter { (c, _) -> c in flags }.map { it.second } + (if ('e' !in flags) listOf("disabled") else emptyList())
            if (tags.isNotEmpty()) line.append("  [").append(tags.joinToString(",")).append(']')
            bounds(node)?.let { b -> line.append("  @(").append((b[0] + b[2]) / 2).append(',').append((b[1] + b[3]) / 2).append(')') }
            out.append(line).append('\n')
        }
        children(node)?.forEach { walkA11y(it, depth + 1, out) }
    }

    private val A11Y_TAGS = listOf(
        'c' to "clickable", 'L' to "long-clickable", 'f' to "focusable", 'F' to "input-focused", 's' to "scrollable",
        'k' to "checkable", 'K' to "checked", 'S' to "selected", 'p' to "password",
    )

    /**
     * What a tap at x,y lands on, for saying so in words: the smallest node there with text or a
     * description (a row's title rather than the whole list). Null where there is none.
     */
    fun labelAt(dump: JsonElement, x: Int, y: Int): String? {
        var best: Pair<Long, String>? = null
        fun visit(n: JsonElement) {
            val b = bounds(n)
            if (b != null && x >= b[0] && x < b[2] && y >= b[1] && y < b[3]) {
                val label = getStr(n, "text").orEmpty().ifEmpty { getStr(n, "desc").orEmpty() }.trim()
                val area = (b[2] - b[0]) * (b[3] - b[1])
                if (label.isNotEmpty() && (best == null || area < best!!.first)) best = area to label
            }
            children(n)?.forEach(::visit)
        }
        collectRoots(dump).forEach(::visit)
        return best?.second
    }

    private class Row(val verb: String, val cls: String, val label: String, val id: String, val coords: String, val flags: String)

    private fun collectInteractive(node: JsonElement, rows: MutableList<Row>) {
        val clsShort = getStr(node, "cls").orEmpty().substringAfterLast('.')
        val idShort = getStr(node, "rid").orEmpty().substringAfterLast('/')
        if (isInteractive(node) && hasVisibleBounds(node)) {
            val b = bounds(node)
            val coords = if (b != null) "${(b[0] + b[2]) / 2},${(b[1] + b[3]) / 2}" else "?,?"
            val flags = getStr(node, "flags").orEmpty()
            val text = getStr(node, "text").orEmpty()
            val desc = getStr(node, "desc").orEmpty()
            val labelSrc = text.ifEmpty { desc }
            val label = when {
                labelSrc.isNotEmpty() -> truncateQuoted(labelSrc, 60)
                idShort.isNotEmpty() -> "#$idShort"
                else -> ""
            }
            val verb = when {
                isInputWidget(clsShort) -> "fill"
                'c' in flags -> "tap"
                else -> "-"
            }
            val tags = TABLE_TAGS.filter { (c, _) -> c in flags }.joinToString(" ") { it.second }
            val idField = if (idShort.isEmpty() || label.startsWith('#')) "" else "#$idShort"
            rows += Row(verb, clsShort, label, idField, coords, tags)
        }
        children(node)?.forEach { collectInteractive(it, rows) }
    }

    private fun walk(node: JsonElement, depth: Int, out: StringBuilder) {
        val clsShort = getStr(node, "cls").orEmpty().substringAfterLast('.')
        val idShort = getStr(node, "rid").orEmpty().substringAfterLast('/')
        val text = getStr(node, "text").orEmpty()
        val desc = getStr(node, "desc").orEmpty()
        val flags = getStr(node, "flags").orEmpty()
        val line = StringBuilder()
        repeat(depth) { line.append("  ") }
        line.append(clsShort)
        if (idShort.isNotEmpty()) line.append(" #").append(idShort)
        if (text.isNotEmpty()) line.append("  ").append(truncateQuoted(text, 80))
        else if (desc.isNotEmpty()) line.append("  desc=").append(truncateQuoted(desc, 80))
        val tags = TREE_TAGS.filter { (c, _) -> c in flags }.joinToString(",") { it.second }
        if (tags.isNotEmpty()) line.append("  [").append(tags).append(']')
        bounds(node)?.let { b -> line.append("  @(").append((b[0] + b[2]) / 2).append(',').append((b[1] + b[3]) / 2).append(')') }
        out.append(line).append('\n')
        children(node)?.forEach { walk(it, depth + 1, out) }
    }

    private val TABLE_TAGS = listOf('L' to "long", 's' to "scroll", 'k' to "check", 'K' to "checked", 'p' to "password")
    private val TREE_TAGS = listOf(
        'c' to "click", 'L' to "long", 's' to "scroll", 'f' to "focusable", 'F' to "focused",
        'k' to "check", 'K' to "checked", 'p' to "password", 'S' to "selected",
    )
    private val INHERENT_WIDGETS = setOf(
        "EditText", "Button", "ImageButton", "Switch", "CheckBox", "RadioButton", "ToggleButton", "Spinner",
        "SeekBar", "RatingBar", "WebView", "AutoCompleteTextView", "MultiAutoCompleteTextView",
        "DatePicker", "TimePicker", "NumberPicker",
    )

    private fun isInteractive(node: JsonElement): Boolean {
        if (getStr(node, "text").orEmpty().isNotEmpty() || getStr(node, "desc").orEmpty().isNotEmpty()) return true
        if (getStr(node, "cls").orEmpty().substringAfterLast('.') in INHERENT_WIDGETS) return true
        return getStr(node, "rid").orEmpty().isNotEmpty() && 'c' in getStr(node, "flags").orEmpty()
    }

    private fun hasVisibleBounds(node: JsonElement): Boolean {
        val b = bounds(node) ?: return false
        return b[0] >= 0 && b[1] >= 0 && b[2] > b[0] && b[3] > b[1]
    }

    private fun isInputWidget(simple: String) =
        simple == "EditText" || simple == "AutoCompleteTextView" || simple == "MultiAutoCompleteTextView"

    /** Quotes and escapes a label, cutting it to [max] code points with a trailing ellipsis. */
    internal fun truncateQuoted(s: String, max: Int): String {
        val escaped = StringBuilder()
        forEachCodePoint(s) { cp ->
            if (!isInvisible(cp)) when (cp) {
                '"'.code -> escaped.append("\\\"")
                '\n'.code -> escaped.append('↵')
                '\t'.code -> escaped.append(' ')
                else -> appendCodePoint(escaped, cp)
            }
        }
        val e = escaped.toString()
        return if (codePointCount(e) > max) "\"${takeCodePoints(e, max)}…\"" else "\"$e\""
    }

    private fun isInvisible(cp: Int): Boolean =
        cp == 0x00AD || cp in 0x200B..0x200F || cp in 0x2028..0x202E || cp in 0x2060..0x206F || cp == 0xFEFF

    /** `dump_active` is `{"root": …}`; `dump` is `{"windows": [{"root": …}, …]}`. */
    internal fun collectRoots(v: JsonElement): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        if (v is JsonObject) for ((k, value) in v) {
            if (k == "root") out += value
            else if (k == "windows" && value is JsonArray) for (w in value) {
                if (w is JsonObject) for ((k2, v2) in w) if (k2 == "root") out += v2
            }
        }
        return out
    }

    internal fun getStr(node: JsonElement, key: String): String? {
        val v = (node as? JsonObject)?.get(key) as? JsonPrimitive ?: return null
        return if (v.isString) v.content else null
    }

    internal fun children(node: JsonElement): JsonArray? = (node as? JsonObject)?.get("children") as? JsonArray

    internal fun bounds(node: JsonElement): LongArray? {
        val a = (node as? JsonObject)?.get("bounds") as? JsonArray ?: return null
        if (a.size != 4) return null
        val n = LongArray(4)
        for (i in 0 until 4) {
            val p = a[i] as? JsonPrimitive ?: return null
            if (p.isString) return null
            n[i] = p.longOrNull ?: return null
        }
        return n
    }

    // --- code point helpers (commonMain has no String.codePoints()) ---

    private inline fun forEachCodePoint(s: String, f: (Int) -> Unit) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                f(((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000)
                i += 2
            } else {
                f(c.code)
                i += 1
            }
        }
    }

    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            sb.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
        } else sb.append(cp.toChar())
    }

    internal fun codePointCount(s: String): Int {
        var n = 0
        forEachCodePoint(s) { n++ }
        return n
    }

    private fun takeCodePoints(s: String, max: Int): String {
        val sb = StringBuilder()
        var n = 0
        forEachCodePoint(s) { cp -> if (n < max) { appendCodePoint(sb, cp); n++ } }
        return sb.toString()
    }

    private fun padEnd(s: String, width: Int): String {
        val n = codePointCount(s)
        return if (n >= width) s else s + " ".repeat(width - n)
    }

    private fun utf8Length(s: String): Int {
        var n = 0
        forEachCodePoint(s) { cp -> n += when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 } }
        return n
    }
}
