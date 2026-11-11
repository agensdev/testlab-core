package no.agens.testlab.core.jev

/**
 * Python string semantics that jevpick.py relies on, reproduced so the port behaves the same
 * on every label: Unicode whitespace as `str.isspace()`, `str.splitlines()` boundaries, slicing
 * and `len()` by code point, and `[^\W_]` ("contains a letter or digit").
 *
 * The regexes in this package only run on the JVM target, so Java's inline flags are used:
 * `(?d)` makes `.` and `$` behave like Python's (only `\n` is a line terminator).
 */
internal object PyText {
    /** Characters for which Python's `str.isspace()` is true (and `\s` matches). */
    const val WS = "\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"
    const val S = "[$WS]"
    const val NS = "[^$WS]"

    fun isSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F', ' ',
        '\u0085', ' ', ' ', ' ', ' ', ' ', ' ', '　' -> true
        else -> c in ' '..' '
    }

    /** `str.strip()` with no arguments. */
    fun strip(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isSpace(s[a])) a++
        while (b > a && isSpace(s[b - 1])) b--
        return s.substring(a, b)
    }

    fun rstrip(s: String): String {
        var b = s.length
        while (b > 0 && isSpace(s[b - 1])) b--
        return s.substring(0, b)
    }

    /** `str.splitlines()`: all of Python's line boundaries, no trailing empty element. */
    fun splitLines(s: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val boundary = c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c == '\u001C' ||
                c == '\u001D' || c == '\u001E' || c == '\u0085' || c == ' ' || c == ' '
            if (boundary) {
                out += s.substring(start, i)
                i += if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') 2 else 1
                start = i
            } else i++
        }
        if (start < s.length) out += s.substring(start)
        return out
    }

    /** `len(s)`: code points, not UTF-16 units. */
    fun len(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }

    /** `s[:n]` by code point. */
    fun take(s: String, n: Int): String {
        var i = 0
        var k = 0
        while (i < s.length && k < n) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            k++
        }
        return s.substring(0, i)
    }

    /** Python's `str < str` (code point order); Kotlin's compareTo orders UTF-16 units. */
    val codePointOrder: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = codePointAt(a, i)
            val cb = codePointAt(b, j)
            if (ca != cb) return@Comparator ca.compareTo(cb)
            i += if (ca >= 0x10000) 2 else 1
            j += if (cb >= 0x10000) 2 else 1
        }
        (a.length - i).compareTo(b.length - j)
    }

    private fun codePointAt(s: String, i: Int): Int {
        val c = s[i]
        return if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate())
            ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
        else c.code
    }

    /** `[^\W_]` search: any character Python considers alphanumeric. */
    fun hasWord(s: String): Boolean = s.any { it.isLetterOrDigit() || isOtherNumeric(it) }

    private fun isOtherNumeric(c: Char): Boolean {
        val cat = c.category
        return cat == CharCategory.LETTER_NUMBER || cat == CharCategory.OTHER_NUMBER
    }

    /** `_norm`: collapse whitespace runs to one space and strip. */
    private val wsRun = Regex("$S+")
    fun norm(s: String): String = strip(wsRun.replace(s, " "))
}
