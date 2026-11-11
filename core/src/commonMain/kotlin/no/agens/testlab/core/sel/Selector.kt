package no.agens.testlab.core.sel

/**
 * The CSS-like selector language of upstream `hs` (selector.rs, v0.1.38), matched client-side
 * against the dump:
 *
 * ```
 * Tag  *  [attr=v] [attr~=v] [attr^=v] [attr$=v] [attr]  :clickable :enabled :visible …
 * :has-text("x") :text-is("x")  :in(SEL) :below(SEL) :right-of(SEL) :near(SEL, PX)
 * A, B   (OR)
 * ```
 */
class Selector private constructor(
    val cls: ClassMatch,
    val attrs: List<AttrPred>,
    val flags: List<Char>,
    val relations: List<Relation>,
) {
    sealed interface ClassMatch {
        data object Any : ClassMatch
        /** Fully qualified, e.g. `android.widget.EditText`. */
        data class Exact(val name: String) : ClassMatch
        /** Last segment only, e.g. `EditText`. */
        data class Simple(val name: String) : ClassMatch
    }

    enum class Op { EQ, HAS, SUBSTR, PREFIX, SUFFIX }
    data class AttrPred(val key: String, val op: Op, val value: String)

    sealed interface Relation {
        data class In(val sels: List<Selector>) : Relation
        data class Near(val sels: List<Selector>, val px: Long) : Relation
        data class Below(val sels: List<Selector>) : Relation
        data class RightOf(val sels: List<Selector>) : Relation
    }

    /** Class, attribute and flag predicates (relations need the whole tree, see [Matcher]). */
    fun matchesLocal(n: UiNode): Boolean {
        val cls = n.cls
        when (val c = this.cls) {
            ClassMatch.Any -> {}
            is ClassMatch.Exact -> if (cls != c.name) return false
            is ClassMatch.Simple -> if (cls.substringAfterLast('.') != c.name) return false
        }
        for (p in attrs) {
            val v = n.str(p.key)
            val ok = when (p.op) {
                Op.EQ -> v == p.value
                Op.SUBSTR -> v.contains(p.value)
                Op.PREFIX -> v.startsWith(p.value)
                Op.SUFFIX -> v.endsWith(p.value)
                Op.HAS -> v.isNotEmpty()
            }
            if (!ok) return false
        }
        if (flags.isNotEmpty()) {
            val f = n.flags
            if (flags.any { it !in f }) return false
        }
        return true
    }

    class ParseError(message: String) : IllegalArgumentException(message)

    companion object {
        fun parse(input: String): List<Selector> {
            val out = splitTopLevel(input, ',').map { it.trim() }.filter { it.isNotEmpty() }.map(::parseOne)
            if (out.isEmpty()) throw ParseError("empty selector")
            return out
        }

        fun parseOrNull(input: String): List<Selector>? = try { parse(input) } catch (e: ParseError) { null }

        private fun parseOne(s: String): Selector {
            var i = 0
            val (cls, adv) = parseClass(s)
            i += adv
            val attrs = mutableListOf<AttrPred>()
            val flags = mutableListOf<Char>()
            val rels = mutableListOf<Relation>()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '[' -> { val (p, a) = parseAttr(s.substring(i)); attrs += p; i += a }
                    c == ':' -> {
                        val (ps, a) = parsePseudo(s.substring(i))
                        when (ps) {
                            is Pseudo.Flag -> flags += ps.c
                            is Pseudo.Rel -> rels += ps.r
                            is Pseudo.Attr -> attrs += ps.p
                        }
                        i += a
                    }
                    c.isWhitespace() -> i++
                    else -> throw ParseError("unexpected '$c' at position $i in selector")
                }
            }
            return Selector(cls, attrs, flags, rels)
        }

        private fun isIdent(c: Char) = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '.' || c == '$'

        private fun parseClass(s: String): Pair<ClassMatch, Int> {
            if (s.isEmpty() || s[0] == '[' || s[0] == ':') return ClassMatch.Any to 0
            if (s[0] == '*') return ClassMatch.Any to 1
            var i = 0
            while (i < s.length && isIdent(s[i])) i++
            if (i == 0) throw ParseError("expected class name or '*' at position 0")
            val name = s.substring(0, i)
            return (if ('.' in name) ClassMatch.Exact(name) else ClassMatch.Simple(name)) to i
        }

        private fun parseAttr(s: String): Pair<AttrPred, Int> {
            val close = s.indexOf(']').takeIf { it >= 0 } ?: throw ParseError("unterminated [attribute]")
            val inner = s.substring(1, close)
            val (key, op, value) = when {
                "~=" in inner -> inner.indexOf("~=").let { Triple(inner.substring(0, it), Op.SUBSTR, unquote(inner.substring(it + 2))) }
                "^=" in inner -> inner.indexOf("^=").let { Triple(inner.substring(0, it), Op.PREFIX, unquote(inner.substring(it + 2))) }
                "$=" in inner -> inner.indexOf("$=").let { Triple(inner.substring(0, it), Op.SUFFIX, unquote(inner.substring(it + 2))) }
                '=' in inner -> inner.indexOf('=').let { Triple(inner.substring(0, it), Op.EQ, unquote(inner.substring(it + 1))) }
                else -> Triple(inner, Op.HAS, "")
            }
            return AttrPred(normaliseKey(key.trim()), op, value) to close + 1
        }

        /** CSS-like attribute names to the dump's JSON field names. */
        private fun normaliseKey(k: String): String = when (k) {
            "class" -> "cls"
            "package", "package-name" -> "pkg"
            "id", "resource-id" -> "rid"
            "content-desc", "desc" -> "desc"
            else -> k
        }

        private sealed interface Pseudo {
            data class Flag(val c: Char) : Pseudo
            data class Rel(val r: Relation) : Pseudo
            data class Attr(val p: AttrPred) : Pseudo
        }

        private val FLAGS = mapOf(
            "clickable" to 'c', "long-clickable" to 'L', "scrollable" to 's', "checkable" to 'k', "checked" to 'K',
            "focusable" to 'f', "focused" to 'F', "enabled" to 'e', "selected" to 'S', "password" to 'p', "visible" to 'v',
        )

        private fun parsePseudo(s: String): Pair<Pseudo, Int> {
            var i = 1
            while (i < s.length && ((s[i] in 'a'..'z') || (s[i] in 'A'..'Z') || s[i] == '-')) i++
            val name = s.substring(1, i)
            if (i < s.length && s[i] == '(') {
                val close = findMatchingParen(s, i) ?: throw ParseError("unterminated :$name( argument")
                val inner = s.substring(i + 1, close)
                when (name) {
                    "has-text", "text-is" -> {
                        val v = unquote(inner)
                        if (v.isEmpty()) throw ParseError(":$name needs (\"TEXT\")")
                        return Pseudo.Attr(AttrPred("text", if (name == "has-text") Op.SUBSTR else Op.EQ, v)) to close + 1
                    }
                }
                val rel = when (name) {
                    "in" -> Relation.In(parse(inner))
                    "below" -> Relation.Below(parse(inner))
                    "right-of" -> Relation.RightOf(parse(inner))
                    "near" -> {
                        // Split on the last top-level comma so SEL may itself contain commas.
                        val parts = splitTopLevel(inner, ',')
                        if (parts.size < 2) throw ParseError(":near needs (SELECTOR, PX)")
                        val pxSrc = parts.last().trim()
                        val px = pxSrc.toLongOrNull() ?: throw ParseError(":near distance must be an int, got '$pxSrc'")
                        Relation.Near(parse(parts.dropLast(1).joinToString(",")), px)
                    }
                    else -> throw ParseError("unknown relational pseudo-class :$name(…)")
                }
                return Pseudo.Rel(rel) to close + 1
            }
            val flag = FLAGS[name] ?: throw ParseError("unknown pseudo-class :$name")
            return Pseudo.Flag(flag) to i
        }

        /** Splits on [delim] outside parentheses, brackets and quotes. */
        internal fun splitTopLevel(input: String, delim: Char): List<String> {
            val out = mutableListOf<String>()
            val buf = StringBuilder()
            var paren = 0
            var bracket = 0
            var dq = false
            var sq = false
            for (c in input) {
                if (dq) { buf.append(c); if (c == '"') dq = false; continue }
                if (sq) { buf.append(c); if (c == '\'') sq = false; continue }
                when {
                    c == '"' -> { dq = true; buf.append(c) }
                    c == '\'' -> { sq = true; buf.append(c) }
                    c == '(' -> { paren++; buf.append(c) }
                    c == ')' -> { paren--; buf.append(c) }
                    c == '[' -> { bracket++; buf.append(c) }
                    c == ']' -> { bracket--; buf.append(c) }
                    c == delim && paren == 0 && bracket == 0 -> { out += buf.toString(); buf.clear() }
                    else -> buf.append(c)
                }
            }
            out += buf.toString()
            return out
        }

        private fun findMatchingParen(s: String, start: Int): Int? {
            var depth = 0
            for (i in start until s.length) {
                when (s[i]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) return i }
                }
            }
            return null
        }

        private fun unquote(raw: String): String {
            val s = raw.trim()
            return if (s.length >= 2 && (s[0] == '"' || s[0] == '\'') && s[s.length - 1] == s[0]) s.substring(1, s.length - 1) else s
        }
    }
}

/** Evaluates selectors, relations included, over the dump's window roots. */
class Matcher(val roots: List<UiNode>) {
    fun matches(sel: Selector, n: UiNode): Boolean {
        if (!sel.matchesLocal(n)) return false
        return sel.relations.all { holds(it, n) }
    }

    private fun holds(rel: Selector.Relation, n: UiNode): Boolean = when (rel) {
        is Selector.Relation.In -> n.ancestors().any { p -> rel.sels.any { matches(it, p) } }
        is Selector.Relation.Near -> {
            val b = n.bounds
            b != null && findAll(rel.sels).any { a ->
                val ab = a.bounds ?: return@any false
                val dx = (b.left + b.right).toLong() / 2 - (ab.left + ab.right).toLong() / 2
                val dy = (b.top + b.bottom).toLong() / 2 - (ab.top + ab.bottom).toLong() / 2
                dx * dx + dy * dy <= rel.px * rel.px
            }
        }
        is Selector.Relation.Below -> n.bounds?.let { b -> findAll(rel.sels).any { a -> a.bounds?.let { b.top >= it.bottom } == true } } ?: false
        is Selector.Relation.RightOf -> n.bounds?.let { b -> findAll(rel.sels).any { a -> a.bounds?.let { b.left >= it.right } == true } } ?: false
    }

    /** Every node matching any of [sels], in document order. */
    fun findAll(sels: List<Selector>): List<UiNode> = roots.flatMap { r -> r.walk().filter { n -> sels.any { matches(it, n) } }.toList() }
}
