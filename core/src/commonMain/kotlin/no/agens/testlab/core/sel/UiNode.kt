package no.agens.testlab.core.sel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One node of the daemon's dump JSON (`{cls, pkg, rid, text, desc, hint, flags, bounds, children}`)
 * with a parent link, so selectors can look at ancestors. Only string attributes count, like
 * upstream `hs`: a missing or non-string attribute reads as "".
 */
class UiNode private constructor(val json: JsonObject, val parent: UiNode?) {
    val children: List<UiNode> = (json["children"] as? JsonArray).orEmpty().mapNotNull { c -> (c as? JsonObject)?.let { UiNode(it, this) } }

    fun str(key: String): String = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    val cls: String get() = str("cls")
    val rid: String get() = str("rid")
    val text: String get() = str("text")
    val desc: String get() = str("desc")
    val flags: String get() = str("flags")
    val simpleClass: String get() = cls.substringAfterLast('.')

    /** `[left, top, right, bottom]`, or null when the node has no four numeric bounds. */
    val bounds: Bounds? = (json["bounds"] as? JsonArray)?.takeIf { it.size == 4 }?.let { a ->
        val n = a.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.let { p -> p.longOrNull ?: p.content.toDoubleOrNull()?.toLong() } }
        if (n.any { it == null }) null else Bounds(n[0]!!.toInt(), n[1]!!.toInt(), n[2]!!.toInt(), n[3]!!.toInt())
    }

    /** Pre-order walk (the node itself, then its children), as upstream's tree walks. */
    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (c in children) yieldAll(c.walk())
    }

    /** Ancestors from the parent up to the root. */
    fun ancestors(): Sequence<UiNode> = generateSequence(parent) { it.parent }

    override fun toString(): String = "UiNode($simpleClass \"$text\" #$rid $bounds)"

    companion object {
        /** The dump's window roots: `root`, every `windows[].root`, or the value itself when it is a node. */
        fun roots(dump: JsonElement): List<UiNode> {
            val o = dump as? JsonObject ?: return emptyList()
            if ("cls" in o && "root" !in o && "windows" !in o) return listOf(UiNode(o, null))
            val out = mutableListOf<UiNode>()
            (o["root"] as? JsonObject)?.let { out += UiNode(it, null) }
            (o["windows"] as? JsonArray)?.forEach { w -> ((w as? JsonObject)?.get("root") as? JsonObject)?.let { out += UiNode(it, null) } }
            return out
        }

        /** The single root `hs tap` searches: `root` when present, else the value itself. */
        fun primaryRoot(dump: JsonElement): UiNode? {
            val o = dump as? JsonObject ?: return null
            return UiNode((o["root"] as? JsonObject) ?: o, null)
        }
    }
}

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Long get() = (right - left).coerceAtLeast(0).toLong() * (bottom - top).coerceAtLeast(0).toLong()
    val hasArea: Boolean get() = right > left && bottom > top
    override fun toString(): String = "[$left,$top,$right,$bottom]"
}
