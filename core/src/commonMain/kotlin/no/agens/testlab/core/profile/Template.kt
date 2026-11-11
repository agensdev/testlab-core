package no.agens.testlab.core.profile

/**
 * The small part of Mustache the devtest comment needs: `{{name}}` values, `{{#list}}…{{.}}…{{/list}}`
 * repeated per item (or shown once for a non-empty value), and `{{^list}}…{{/list}}` when empty.
 * Section tags alone on a line take the line with them, so templates read naturally. Values are
 * inserted as they are (the comment is Markdown, not HTML).
 */
object Template {
    class Error(message: String) : IllegalArgumentException(message)

    private val TAG = Regex("""\{\{\s*([#^/]?)\s*([A-Za-z0-9_.]+)\s*}}""")

    fun render(template: String, values: Map<String, String?>, lists: Map<String, List<String>>): String =
        renderBlock(standalone(template), values, lists, item = null).replace(Regex("\n{3,}"), "\n\n")

    /** `{{#x}}` / `{{/x}}` / `{{^x}}` alone on a line: drop the line break too. */
    private fun standalone(t: String): String = t.replace(Regex("""(?m)^[ \t]*(\{\{\s*[#^/]\s*[A-Za-z0-9_.]+\s*}})[ \t]*\n"""), "$1")

    private fun renderBlock(t: String, values: Map<String, String?>, lists: Map<String, List<String>>, item: String?): String {
        val out = StringBuilder()
        var i = 0
        while (true) {
            val m = TAG.find(t, i) ?: break
            out.append(t, i, m.range.first)
            val (kind, name) = m.destructured
            when (kind) {
                "" -> out.append(if (name == ".") item.orEmpty() else values[name] ?: lists[name]?.joinToString(", ") ?: "")
                "#", "^" -> {
                    val close = findClose(t, name, m.range.last + 1) ?: throw Error("{{$kind$name}} has no {{/$name}}")
                    val inner = t.substring(m.range.last + 1, close.first)
                    val list = lists[name]
                    val present = list?.isNotEmpty() ?: !values[name].isNullOrBlank()
                    if (kind == "#" && present) {
                        if (list != null) list.forEach { out.append(renderBlock(inner, values, lists, it)) }
                        else out.append(renderBlock(inner, values, lists, values[name]))
                    }
                    if (kind == "^" && !present) out.append(renderBlock(inner, values, lists, item))
                    i = close.last + 1
                    continue
                }
                "/" -> throw Error("{{/$name}} without an opening tag")
            }
            i = m.range.last + 1
        }
        out.append(t.substring(i))
        return out.toString()
    }

    /** The matching `{{/name}}`, skipping nested sections of the same name. */
    private fun findClose(t: String, name: String, from: Int): IntRange? {
        var depth = 0
        var i = from
        while (true) {
            val m = TAG.find(t, i) ?: return null
            val (kind, n) = m.destructured
            if (n == name) {
                if (kind == "#" || kind == "^") depth++
                if (kind == "/") { if (depth == 0) return m.range else depth-- }
            }
            i = m.range.last + 1
        }
    }
}
