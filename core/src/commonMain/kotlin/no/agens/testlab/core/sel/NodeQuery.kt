package no.agens.testlab.core.sel

import kotlinx.serialization.json.JsonElement

/** A tap target found in a dump: the matched label plus the (possibly promoted) node to tap. */
data class TapHit(
    val text: String,
    val desc: String,
    val cls: String,
    val rid: String,
    val flags: String,
    val bounds: Bounds,
    val priority: Int,
    val via: String,
) {
    val label: String get() = text.ifEmpty { desc }
}

/** Optional filters, as upstream's `--clickable/--enabled/--visible`. All off by default, as for `hs tap`. */
data class HitFilter(val clickable: Boolean = false, val enabled: Boolean = false, val visible: Boolean = false) {
    fun accepts(flags: String, b: Bounds): Boolean {
        if (clickable && 'c' !in flags) return false
        if (enabled && 'e' !in flags) return false
        if (visible && ('v' !in flags || !b.hasArea)) return false
        return true
    }
}

/**
 * How upstream `hs tap TEXT` / `hs fill SELECTOR VALUE` find their node (tap_text.rs and
 * main.rs of v0.1.38), plus one extension: when the text search finds nothing and the query is
 * a CSS-like selector, the selector engine is used (upstream's `hs tap` has no selector support).
 */
object NodeQuery {
    // Match priorities, lower is better (tap_text.rs `Priority`).
    const val EXACT_TEXT = 0
    const val EXACT_DESC = 1
    const val IEXACT_TEXT = 2
    const val IEXACT_DESC = 3
    const val SUB_TEXT = 4
    const val SUB_DESC = 5
    // Extension tiers: case-insensitive beyond ASCII (Ø/ø, Å/å), ranked after every upstream tier.
    private const val U_EXACT_TEXT = 6
    private const val U_EXACT_DESC = 7
    private const val U_SUB_TEXT = 8
    private const val U_SUB_DESC = 9
    private const val SELECTOR = 10

    /** Candidates for `tap QUERY`, best first: `#id`, else text/desc, else a CSS-like selector. */
    fun tapHits(dump: JsonElement, query: String, filter: HitFilter = HitFilter()): List<TapHit> {
        val root = UiNode.primaryRoot(dump) ?: return emptyList()
        if (query.startsWith("#")) return byId(root, query.substring(1), filter)
        val byText = byText(root, query, filter)
        if (byText.isNotEmpty()) return byText
        val sels = Selector.parseOrNull(query) ?: return emptyList()
        return bySelector(UiNode.roots(dump), root, sels, filter)
    }

    /**
     * What `wait_for` waits for: `#id`, text or content description containing [query]
     * (case-insensitive), or a CSS-like selector. Returns the matching label, or null.
     */
    fun present(dump: JsonElement, query: String): String? {
        val roots = UiNode.roots(dump).ifEmpty { listOfNotNull(UiNode.primaryRoot(dump)) }
        if (query.startsWith("#")) {
            val id = query.substring(1)
            if (id.isEmpty()) return null
            return roots.firstNotNullOfOrNull { r -> r.walk().firstOrNull { it.rid.isNotEmpty() && (it.rid == id || it.rid.substringAfterLast('/') == id) } }?.let { query }
        }
        val q = query.lowercase()
        if (q.isNotEmpty()) {
            roots.firstNotNullOfOrNull { r -> r.walk().firstOrNull { q in it.text.lowercase() || q in it.desc.lowercase() } }?.let { return it.label() }
        }
        val sels = Selector.parseOrNull(query) ?: return null
        return Matcher(roots).findAll(sels).firstOrNull()?.let { it.label().ifEmpty { query } }
    }

    private fun UiNode.label(): String = text.ifEmpty { desc }

    /** `#id`: the local part of the resource id (`back_btn` finds `com.foo:id/back_btn`) or the full id. */
    fun byId(root: UiNode, idQuery: String, filter: HitFilter = HitFilter()): List<TapHit> {
        if (idQuery.isEmpty()) return emptyList()
        return root.walk().mapNotNull { n ->
            val rid = n.rid
            if (rid.isEmpty() || (rid != idQuery && rid.substringAfterLast('/') != idQuery)) return@mapNotNull null
            val b = n.bounds ?: return@mapNotNull null
            if (!filter.accepts(n.flags, b)) return@mapNotNull null
            TapHit(n.text, n.desc, n.cls, rid, n.flags, b, EXACT_TEXT, "id")
        }.toList()
    }

    /** Text/desc matches ranked by [classify], container-sized matches rejected, labels promoted to their clickable row. */
    fun byText(root: UiNode, query: String, filter: HitFilter = HitFilter()): List<TapHit> {
        val qLow = asciiLower(query)
        val screenArea = root.bounds?.area ?: 0L
        val hits = mutableListOf<TapHit>()
        for (n in root.walk()) {
            val raw = n.bounds ?: continue
            val pri = classify(n.text, n.desc, query, qLow) ?: continue
            if (isAggregator(raw, screenArea)) continue
            val target = promoteToClickable(n, raw, screenArea)
            if (!filter.accepts(target.flags, target.bounds)) continue
            hits += TapHit(n.text, n.desc, target.cls, target.rid, target.flags, target.bounds, pri, "text")
        }
        return hits.sortedBy { it.priority } // stable: document order within a priority
    }

    private fun bySelector(roots: List<UiNode>, primary: UiNode, sels: List<Selector>, filter: HitFilter): List<TapHit> {
        val screenArea = primary.bounds?.area ?: 0L
        return Matcher(roots).findAll(sels).mapNotNull { n ->
            val raw = n.bounds?.takeIf { it.hasArea } ?: return@mapNotNull null
            val target = promoteToClickable(n, raw, screenArea)
            if (!filter.accepts(target.flags, target.bounds)) return@mapNotNull null
            TapHit(n.text, n.desc, target.cls, target.rid, target.flags, target.bounds, SELECTOR, "selector")
        }
    }

    internal fun classify(text: String, desc: String, q: String, qLow: String): Int? {
        if (text == q) return EXACT_TEXT
        if (desc == q) return EXACT_DESC
        val tl = asciiLower(text)
        val dl = asciiLower(desc)
        if (tl == qLow) return IEXACT_TEXT
        if (dl == qLow) return IEXACT_DESC
        if (qLow.isNotEmpty() && qLow in tl) return SUB_TEXT
        if (qLow.isNotEmpty() && qLow in dl) return SUB_DESC
        val qu = q.lowercase()
        if (qu.isEmpty()) return null
        val tu = text.lowercase()
        val du = desc.lowercase()
        return when {
            tu == qu -> U_EXACT_TEXT
            du == qu -> U_EXACT_DESC
            qu in tu -> U_SUB_TEXT
            qu in du -> U_SUB_DESC
            else -> null
        }
    }

    /** Rust `to_ascii_lowercase`: only A–Z change. */
    internal fun asciiLower(s: String): String {
        if (s.none { it in 'A'..'Z' }) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in 'A'..'Z') c + 32 else c)
        return sb.toString()
    }

    /** A node covering more than half the screen is a container (a launcher's "Apps: …" list), not a target. */
    internal fun isAggregator(b: Bounds, screenArea: Long): Boolean = screenArea > 0 && b.area * 2 > screenArea

    private class Target(val bounds: Bounds, val rid: String, val cls: String, val flags: String)

    /** A non-clickable label inside a clickable row is tapped through the row (its click-by-id works too). */
    private fun promoteToClickable(n: UiNode, b: Bounds, screenArea: Long): Target {
        if ('c' in n.flags) return Target(b, n.rid, n.cls, n.flags)
        for (anc in n.ancestors()) {
            val af = anc.flags
            if ('c' !in af) continue
            val ab = anc.bounds ?: continue
            if (!ab.hasArea) continue
            if (isAggregator(ab, screenArea)) break
            return Target(ab, anc.rid, anc.cls, af)
        }
        return Target(b, n.rid, n.cls, n.flags)
    }

    /**
     * The daemon selector for the faster click-by-id path: the id plus the matched label, so a
     * sibling sharing the id is not clicked instead. Null when the label can't be quoted for the
     * daemon (it has no escapes) or the command would pass the 256-byte cap: then the caller taps.
     */
    fun clickSelector(hit: TapHit): String? {
        if (hit.rid.isEmpty() || 'c' !in hit.flags) return null
        val sel = when {
            hit.text.isNotEmpty() -> quoted(hit.text)?.let { "id=${hit.rid} text=$it" }
            hit.desc.isNotEmpty() -> quoted(hit.desc)?.let { "id=${hit.rid} desc=$it" }
            else -> "id=${hit.rid}"
        } ?: return null
        return sel.takeIf { "node_click $it".encodeToByteArray().size <= 256 }
    }

    /** `"value"` for the daemon's tokenizer, or null when the value has a quote, backslash or line break. */
    fun quoted(v: String): String? = if (v.any { it == '"' || it == '\\' || it == '\n' || it == '\r' }) null else "\"$v\""

    /** The human line `hs tap` prints, e.g. `tapped "Neste" cls=… flags=cev bounds=[…] at (540,2200) via click → ok`. */
    fun tapReport(hit: TapHit, method: String): String {
        val b = hit.bounds
        return "tapped ${debugQuote(hit.label)} cls=${hit.cls} flags=${hit.flags} bounds=[${b.left},${b.top},${b.right},${b.bottom}] at (${b.centerX},${b.centerY}) via $method → ok"
    }

    /** Rust's `{:?}` for a string, as far as labels need it. */
    fun debugQuote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    // --- fill ---

    private val INPUT_CLASSES = setOf("EditText", "AutoCompleteTextView", "MultiAutoCompleteTextView")

    fun isInputWidget(n: UiNode): Boolean = n.simpleClass in INPUT_CLASSES

    /**
     * Resolves `fill`'s free-text query to a daemon selector (main.rs `resolve_fill_selector`): a
     * query with `=` already is one; otherwise the input whose text/desc/hint matches, else the
     * input nearest below or right of a matching label. Null when nothing matches.
     */
    fun fillSelector(dump: JsonElement, query: String): String? {
        if ('=' in query) return query
        val root = UiNode.primaryRoot(dump) ?: return null
        return fillTarget(root, query)?.let(::daemonSelector)
    }

    fun fillTarget(root: UiNode, query: String): UiNode? {
        val qLow = query.lowercase()
        var best: Pair<Int, UiNode>? = null
        for (n in root.walk()) {
            if (!isInputWidget(n)) continue
            val p = fillPriority(query, qLow, n.text, n.desc, n.str("hint")) ?: continue
            if (best == null || p < best.first) best = p to n
        }
        best?.let { return it.second }
        // Label-anchored: the nearest input below or right of a matching label, like a person reads a form.
        val labels = root.walk().filter { n ->
            qLow.isNotEmpty() && ((n.text.isNotEmpty() && qLow in n.text.lowercase()) || (n.desc.isNotEmpty() && qLow in n.desc.lowercase()))
        }
        for (label in labels) {
            val lb = label.bounds ?: continue
            var nearest: Pair<Long, UiNode>? = null
            for (n in root.walk()) {
                if (!isInputWidget(n)) continue
                val eb = n.bounds ?: continue
                // 8 px slack: a label whose bottom touches the field's top still counts as above it.
                if (eb.top >= lb.bottom - 8 || eb.left >= lb.right - 8) {
                    val dx = (eb.left + eb.right).toLong() / 2 - (lb.left + lb.right).toLong() / 2
                    val dy = (eb.top + eb.bottom).toLong() / 2 - (lb.top + lb.bottom).toLong() / 2
                    val dist = dx * dx + dy * dy
                    if (nearest == null || dist < nearest.first) nearest = dist to n
                }
            }
            nearest?.let { return it.second }
        }
        return null
    }

    private fun fillPriority(q: String, qLow: String, text: String, desc: String, hint: String): Int? {
        if (text == q || desc == q || hint == q) return 0
        val t = text.lowercase()
        val d = desc.lowercase()
        val h = hint.lowercase()
        if (t == qLow || d == qLow || h == qLow) return 1
        if (qLow.isNotEmpty() && (qLow in t || qLow in d || qLow in h)) return 2
        return null
    }

    /** A daemon-grammar selector naming [n]: the resource id, else class plus its own text. */
    fun daemonSelector(n: UiNode): String {
        if (n.rid.isNotEmpty()) return "id=${n.rid}"
        val simple = n.cls.ifEmpty { "EditText" }.substringAfterLast('.')
        val q = n.text.takeIf { it.isNotEmpty() }?.let(::quoted)
        return if (q != null) "class=$simple text=$q" else "class=$simple"
    }
}
