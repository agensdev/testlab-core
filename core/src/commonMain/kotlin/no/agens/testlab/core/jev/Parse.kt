package no.agens.testlab.core.jev

import no.agens.testlab.core.jev.PyText.NS
import no.agens.testlab.core.jev.PyText.S

/**
 * Parsers for `hs ui` text, ported from jevpick.py. The device side renders dump_active JSON
 * to the same text (see UiDumpRender), so lab dumps, saved fixtures and live screens all go
 * through one path.
 */
object DumpParser {
    // Anchored on the trailing "x,y" so unknown middle columns can't break it.
    // Real rows may end with flags after the coordinates: `720,346  [long scroll]`.
    private val LINE_RE = Regex(
        "(?dU)^$S*($NS+)$S+($NS+)$S+(.*?)$S*(\\d+),(\\d+)((?:$S+\\[[^\\]]*\\])*)$S*$",
    )
    private val TABLE_RID = Regex("(?dU)(?:^|$S)#($NS+)")
    private val BRACKETS = Regex("\\[([^\\]]*)\\]")

    // `hs ui --tree` lines: <indent>Widget [#id] [desc="…"] ["text"] [[flags]] @(x,y)
    private val TREE_RE = Regex("(?dU)^($S*)($NS+)(.*?)$S*@\\((-?\\d+),(-?\\d+)\\)$S*$")
    private val TREE_FLAGS = Regex("(?dU)$S\\[([\\w,]+)\\]$")
    private val TREE_RID = Regex("(?dU)^$S+#($NS+)")
    private val TREE_DESC_TEXT = Regex("(?dU)^$S+desc=\"(.*?)\"(?:$S+\"(.*)\")?$")
    private val TREE_TEXT = Regex("(?dU)^$S+\"(.*)\"$")
    private val TREE_TAIL = Regex("(?dU)@\\(-?\\d+,-?\\d+\\)$S*$")

    data class Parsed(val nodes: List<Node>, val unparsed: List<String>)

    enum class Form { AUTO, TREE, TABLE }

    fun stripPkg(rid: String): String {
        val r = rid.trimStart('#')
        return if (":id/" in r) r.substringAfter(":id/") else r
    }

    /** The `hs ui` verb-led table. */
    fun parseTable(dump: String): Parsed {
        val nodes = mutableListOf<Node>()
        val unparsed = mutableListOf<String>()
        for (line in PyText.splitLines(dump)) {
            if (PyText.strip(line).isEmpty()) continue
            val m = LINE_RE.find(line)
            if (m == null) { unparsed += line; continue }
            val g = m.groupValues
            val mid = g[3]
            var text = ""
            var rest = mid
            if ('"' in mid) {
                val a = mid.indexOf('"')
                val b = mid.lastIndexOf('"')
                if (b > a) {
                    text = mid.substring(a + 1, b)
                    rest = mid.substring(0, a) + " " + mid.substring(b + 1)
                }
            }
            val rid = TABLE_RID.find(rest)?.let { stripPkg(it.groupValues[1]) }.orEmpty()
            val flags = BRACKETS.findAll(g[6]).joinToString(" ") { it.groupValues[1] }
            nodes += Node(g[1], g[2], PyText.norm(text), rid, g[4].toInt(), g[5].toInt(), PyText.rstrip(line), flags)
        }
        return Parsed(nodes, unparsed)
    }

    /**
     * `hs ui --tree`; verb=tap for nodes flagged click. Label-less clickable wrappers borrow the
     * texts of their descendants (Compose buttons, tabs, a dialog's Ok): the flat table hides them.
     */
    fun parseTree(dump: String): Parsed {
        val nodes = mutableListOf<Node>()
        val unparsed = mutableListOf<String>()
        for (line in PyText.splitLines(dump)) {
            if (PyText.strip(line).isEmpty()) continue
            val m = TREE_RE.find(line)
            if (m == null) { unparsed += line; continue }
            val g = m.groupValues
            var mid = g[3]
            var flags = ""
            TREE_FLAGS.find(mid)?.let { fm ->
                flags = fm.groupValues[1]
                mid = mid.substring(0, fm.range.first)
            }
            mid = PyText.rstrip(mid)
            var rid = ""
            TREE_RID.find(mid)?.let { rm ->
                rid = stripPkg(rm.groupValues[1])
                mid = mid.substring(rm.range.last + 1)
            }
            var text = ""
            var desc = ""
            val dm = TREE_DESC_TEXT.find(mid)
            if (dm != null) {
                desc = dm.groupValues[1]
                text = dm.groups[2]?.value.orEmpty()
            } else {
                TREE_TEXT.find(mid)?.let { text = it.groupValues[1] }
            }
            val verb = if ("click" in flags.split(',')) "tap" else "-"
            nodes += Node(
                verb, g[2], PyText.norm(text), rid, g[4].toInt(), g[5].toInt(), PyText.rstrip(line),
                flags.replace(',', ' '), PyText.norm(desc), g[1].length,
            )
        }
        // Borrow labels for label-less clickable wrappers from their descendants.
        for ((i, n) in nodes.withIndex()) {
            if (!n.actionable || n.text.isNotEmpty()) continue
            val parts = mutableListOf<String>()
            if (n.desc.isNotEmpty()) parts += n.desc
            for (d in nodes.subList(i + 1, nodes.size)) {
                if (d.depth <= n.depth) break
                for (t in listOf(d.text, d.desc)) if (t.isNotEmpty() && t !in parts) parts += t
                if (parts.size >= LABEL_PARTS) break
            }
            n.text = parts.take(LABEL_PARTS).joinToString(" / ")
        }
        return Parsed(nodes, unparsed)
    }

    fun looksLikeTree(dump: String): Boolean {
        val head = PyText.splitLines(dump).take(5).filter { PyText.strip(it).isNotEmpty() }
        return head.isNotEmpty() && head.all { TREE_TAIL.containsMatchIn(it) }
    }

    /** Auto-detects tree vs table so saved dumps of either form work. */
    fun parse(dump: String, form: Form = Form.AUTO): Parsed {
        val f = if (form == Form.AUTO) (if (looksLikeTree(dump)) Form.TREE else Form.TABLE) else form
        val p = if (f == Form.TREE) parseTree(dump) else parseTable(dump)
        val total = p.nodes.size + p.unparsed.size
        if (total > 0 && p.unparsed.size > total / 2.0) {
            throw JevError(
                "ui table format not recognised (>50% unparsed lines) - check hs version; " +
                    "first unparsed: '${PyText.take(p.unparsed[0], 120)}'",
            )
        }
        return p
    }
}
