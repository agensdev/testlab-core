package no.agens.testlab.services.jira

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Atlassian Document Format ⇄ Markdown, enough for tickets and test reports: headings,
 * paragraphs, lists, code, quotes, rules, tables, links and the inline marks. Jira-only nodes
 * (mentions, panels, media, status lozenges…) become readable text on the way to Markdown;
 * Markdown images become links, since ADF media needs an uploaded attachment id.
 */
object Adf {
    // --- ADF → Markdown ---

    fun toMarkdown(doc: JsonElement?): String {
        if (doc == null || doc is JsonNull) return ""
        if (doc is JsonPrimitive) return doc.content // Jira API v2 answers plain text
        val sb = StringBuilder()
        blocks(content(doc), sb, "")
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun content(e: JsonElement?): List<JsonObject> = ((e as? JsonObject)?.get("content") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject.type() = (this["type"] as? JsonPrimitive)?.content.orEmpty()
    private fun JsonObject.attr(k: String): String? = ((this["attrs"] as? JsonObject)?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun blocks(nodes: List<JsonObject>, sb: StringBuilder, indent: String) {
        for (n in nodes) block(n, sb, indent)
    }

    private fun block(n: JsonObject, sb: StringBuilder, indent: String) {
        when (n.type()) {
            "paragraph" -> { sb.append(indent).append(inline(content(n)).replace("\n", "\n$indent")).append("\n\n") }
            "heading" -> { sb.append(indent).append("#".repeat((n.attr("level")?.toIntOrNull() ?: 1).coerceIn(1, 6))).append(' ').append(inline(content(n))).append("\n\n") }
            "bulletList" -> { list(n, sb, indent, ordered = false); sb.append('\n') }
            "orderedList" -> { list(n, sb, indent, ordered = true); sb.append('\n') }
            "codeBlock" -> {
                val text = content(n).joinToString("") { (it["text"] as? JsonPrimitive)?.content.orEmpty() }
                sb.append(indent).append("```").append(n.attr("language").orEmpty()).append('\n')
                text.lines().forEach { sb.append(indent).append(it).append('\n') }
                sb.append(indent).append("```\n\n")
            }
            "blockquote" -> quoted(content(n), sb, indent, null)
            "panel" -> quoted(content(n), sb, indent, n.attr("panelType")?.uppercase())
            "rule" -> sb.append(indent).append("---\n\n")
            "table" -> table(n, sb, indent)
            "mediaSingle", "mediaGroup" -> {
                for (m in content(n)) sb.append(indent).append("[attachment: ").append(m.attr("alt") ?: m.attr("id") ?: "media").append("]\n")
                sb.append('\n')
            }
            "expand", "nestedExpand" -> {
                n.attr("title")?.let { sb.append(indent).append("**").append(it).append("**\n\n") }
                blocks(content(n), sb, indent)
            }
            "taskList", "decisionList" -> {
                for (item in content(n)) {
                    val done = item.attr("state") == "DONE" || item.attr("state") == "DECIDED"
                    sb.append(indent).append(if (done) "- [x] " else "- [ ] ").append(inline(content(item))).append('\n')
                }
                sb.append('\n')
            }
            else -> {
                // Unknown block: keep its text rather than lose it.
                val inner = content(n)
                if (inner.any { it.type() in INLINE }) sb.append(indent).append(inline(inner)).append("\n\n") else blocks(inner, sb, indent)
            }
        }
    }

    private fun quoted(nodes: List<JsonObject>, sb: StringBuilder, indent: String, label: String?) {
        val inner = StringBuilder()
        label?.let { inner.append("**").append(it).append("**\n\n") }
        blocks(nodes, inner, "")
        inner.toString().trim().lines().forEach { sb.append(indent).append("> ").append(it).append('\n') }
        sb.append('\n')
    }

    private fun list(n: JsonObject, sb: StringBuilder, indent: String, ordered: Boolean) {
        var i = n.attr("order")?.toIntOrNull() ?: 1
        for (item in content(n)) {
            val marker = if (ordered) "${i++}. " else "- "
            val inner = StringBuilder()
            blocks(content(item), inner, "")
            // Tight lists: a nested list follows its item's text without a blank line.
            val lines = inner.toString().trim().lines().filter { it.isNotBlank() }
            lines.forEachIndexed { k, line ->
                sb.append(indent)
                if (k == 0) sb.append(marker) else if (line.isNotEmpty()) sb.append(" ".repeat(marker.length))
                sb.append(line).append('\n')
            }
        }
    }

    private fun table(n: JsonObject, sb: StringBuilder, indent: String) {
        val rows = content(n).map { row -> content(row).map { cell -> cellText(cell) } }
        if (rows.isEmpty()) return
        val width = rows.maxOf { it.size }
        fun line(cells: List<String>) = indent + "| " + (cells + List(width - cells.size) { "" }).joinToString(" | ") + " |\n"
        sb.append(line(rows.first()))
        sb.append(indent).append("|").append(" --- |".repeat(width)).append('\n')
        rows.drop(1).forEach { sb.append(line(it)) }
        sb.append('\n')
    }

    private fun cellText(cell: JsonObject): String {
        val inner = StringBuilder()
        blocks(content(cell), inner, "")
        return inner.toString().trim().replace("\n\n", "<br>").replace("\n", " ").replace("|", "\\|")
    }

    private val INLINE = setOf("text", "hardBreak", "mention", "emoji", "inlineCard", "status", "date", "placeholder")

    private fun inline(nodes: List<JsonObject>): String = buildString {
        for (n in nodes) when (n.type()) {
            "text" -> append(marked((n["text"] as? JsonPrimitive)?.content.orEmpty(), (n["marks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }))
            "hardBreak" -> append("  \n")
            "mention" -> append((n.attr("text") ?: "@someone").let { if (it.startsWith("@")) it else "@$it" })
            "emoji" -> append(n.attr("text") ?: n.attr("shortName").orEmpty())
            "inlineCard", "blockCard", "embedCard" -> n.attr("url")?.let { append('<').append(it).append('>') }
            "status" -> append('[').append(n.attr("text").orEmpty()).append(']')
            "date" -> n.attr("timestamp")?.toLongOrNull()?.let { append(java.time.Instant.ofEpochMilli(it).toString().take(10)) }
            "placeholder" -> {}
            else -> append(inline(content(n)))
        }
    }

    private fun marked(text: String, marks: List<JsonObject>): String {
        if (text.isEmpty()) return ""
        var t = text
        val types = marks.map { it.type() }
        if ("code" in types) t = "`$t`"
        else {
            if ("strong" in types) t = "**$t**"
            if ("em" in types) t = "*$t*"
            if ("strike" in types) t = "~~$t~~"
        }
        marks.firstOrNull { it.type() == "link" }?.attr("href")?.let { href -> t = if (href == text) "<$href>" else "[$t]($href)" }
        return t
    }

    // --- Markdown → ADF ---

    private val parser: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()

    fun fromMarkdown(markdown: String): JsonObject = buildJsonObject {
        put("type", "doc")
        put("version", 1)
        put("content", JsonArray(children(parser.parse(markdown)).flatMap { mdBlock(it) }.ifEmpty { listOf(paragraph(emptyList())) }))
    }

    private fun children(n: Node): List<Node> = generateSequence(n.firstChild) { it.next }.toList()

    private fun node(type: String, content: List<JsonElement>? = null, attrs: JsonObject? = null) = buildJsonObject {
        put("type", type)
        attrs?.let { put("attrs", it) }
        content?.let { put("content", JsonArray(it)) }
    }

    private fun paragraph(inl: List<JsonElement>) = node("paragraph", inl)

    private fun mdBlock(n: Node): List<JsonObject> = when (n) {
        is Paragraph -> listOf(paragraph(mdInline(n, emptyList())))
        is Heading -> listOf(node("heading", mdInline(n, emptyList()), buildJsonObject { put("level", n.level) }))
        is BulletList -> listOf(node("bulletList", children(n).map { listItem(it) }))
        is OrderedList -> listOf(node("orderedList", children(n).map { listItem(it) }, buildJsonObject { put("order", n.markerStartNumber ?: 1) }))
        is FencedCodeBlock -> listOf(codeBlock(n.literal, n.info?.takeIf { it.isNotBlank() }?.substringBefore(' ')))
        is IndentedCodeBlock -> listOf(codeBlock(n.literal, null))
        is BlockQuote -> listOf(node("blockquote", children(n).flatMap { mdBlock(it) }.filter { it.type() in setOf("paragraph", "bulletList", "orderedList", "codeBlock") }.ifEmpty { listOf(paragraph(emptyList())) }))
        is ThematicBreak -> listOf(node("rule"))
        is HtmlBlock -> listOf(codeBlock(n.literal, null))
        is TableBlock -> listOf(mdTable(n))
        else -> children(n).flatMap { mdBlock(it) }
    }

    private fun codeBlock(text: String, lang: String?) = node(
        "codeBlock",
        text.trimEnd('\n').takeIf { it.isNotEmpty() }?.let { listOf(textNode(it, emptyList())) } ?: emptyList(),
        lang?.let { buildJsonObject { put("language", it) } },
    )

    private fun listItem(item: Node): JsonObject {
        val blocks = children(item).flatMap { mdBlock(it) }
        // A list item must start with a paragraph (or a nested list).
        return node("listItem", if (blocks.isEmpty() || blocks.first().type() !in setOf("paragraph", "bulletList", "orderedList")) listOf(paragraph(emptyList())) + blocks else blocks)
    }

    private fun mdTable(t: TableBlock): JsonObject {
        val rows = children(t).flatMap { section ->
            when (section) {
                is TableHead, is TableBody -> children(section).filterIsInstance<TableRow>().map { row -> (section is TableHead) to row }
                else -> emptyList()
            }
        }
        return node(
            "table",
            rows.map { (head, row) ->
                node("tableRow", children(row).filterIsInstance<TableCell>().map { cell -> node(if (head) "tableHeader" else "tableCell", listOf(paragraph(mdInline(cell, emptyList())))) })
            },
            buildJsonObject { put("isNumberColumnEnabled", false); put("layout", "default") },
        )
    }

    private fun textNode(text: String, marks: List<JsonObject>) = buildJsonObject {
        put("type", "text")
        put("text", text)
        if (marks.isNotEmpty()) put("marks", JsonArray(marks))
    }

    private fun mark(type: String, attrs: JsonObject? = null) = buildJsonObject { put("type", type); attrs?.let { put("attrs", it) } }

    private fun mdInline(parent: Node, marks: List<JsonObject>): List<JsonElement> = children(parent).flatMap { n ->
        when (n) {
            is Text -> if (n.literal.isEmpty()) emptyList() else listOf(textNode(n.literal, marks))
            is SoftLineBreak -> listOf(textNode(" ", marks))
            is HardLineBreak -> listOf(node("hardBreak"))
            is Code -> listOf(textNode(n.literal, marks.filter { it.type() == "link" } + mark("code")))
            is Emphasis -> mdInline(n, marks + mark("em"))
            is StrongEmphasis -> mdInline(n, marks + mark("strong"))
            is Link -> mdInline(n, marks + mark("link", buildJsonObject { put("href", n.destination) })).ifEmpty { listOf(textNode(n.destination, marks + mark("link", buildJsonObject { put("href", n.destination) }))) }
            is Image -> {
                val alt = children(n).filterIsInstance<Text>().joinToString("") { it.literal }.ifEmpty { n.destination }
                listOf(textNode(alt, marks + mark("link", buildJsonObject { put("href", n.destination) })))
            }
            is HtmlInline -> listOf(textNode(n.literal, marks))
            else -> mdInline(n, marks)
        }
    }.let(::mergeText)

    /** Adjacent text with the same marks becomes one node (soft breaks would otherwise fragment every line). */
    private fun mergeText(nodes: List<JsonElement>): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        for (n in nodes) {
            val prev = out.lastOrNull() as? JsonObject
            val cur = n as JsonObject
            if (prev != null && prev.type() == "text" && cur.type() == "text" && prev["marks"] == cur["marks"]) {
                out[out.size - 1] = JsonObject(prev + ("text" to JsonPrimitive((prev["text"] as JsonPrimitive).content + (cur["text"] as JsonPrimitive).content)))
            } else out += n
        }
        return out
    }
}
