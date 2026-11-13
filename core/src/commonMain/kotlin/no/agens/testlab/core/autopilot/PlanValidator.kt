package no.agens.testlab.core.autopilot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Checks a plan before it runs, so the editor (and Claude, through the MCP plan tools) gets every
 * mistake at once instead of a hand-back at step 37. The step forms are the runner's, in the
 * runner's precedence order; unknown extra fields are reported as warnings, not errors.
 */
object PlanValidator {
    data class Issue(val index: Int, val message: String, val error: Boolean = true) {
        override fun toString() = "step $index: $message"
    }

    private val SWIPES = setOf("up", "down", "left", "right")

    private val KNOWN = mapOf(
        "goal" to setOf("goal", "allow_risky", "max_steps", "optional"),
        "step" to setOf("step", "allow_risky", "optional"),
        "type" to setOf("type"),
        "hide_keyboard" to setOf("hide_keyboard"),
        "wait_for" to setOf("wait_for", "timeout"),
        "open" to setOf("open", "args", "fresh"),
        "key" to setOf("key"),
        "swipe" to setOf("swipe"),
        "sleep" to setOf("sleep"),
        "screenshot" to setOf("screenshot"),
        "tap" to setOf("tap", "nth", "verify"),
        "note" to setOf("note"),
    )

    /** The step type the runner would execute, using its precedence. */
    fun kindOf(st: JsonObject): String? = when {
        "goal" in st || "step" in st -> if ("goal" in st && pyTruthy(st["goal"])) "goal" else "step"
        "type" in st -> "type"
        pyTruthy(st["hide_keyboard"]) -> "hide_keyboard"
        "wait_for" in st -> "wait_for"
        "open" in st -> "open"
        "key" in st -> "key"
        "swipe" in st -> "swipe"
        "sleep" in st -> "sleep"
        "screenshot" in st -> "screenshot"
        "tap" in st -> "tap"
        "note" in st -> "note"
        else -> null
    }

    /** `cred` references in the plan, for showing which credentials it will need. */
    fun credRefs(plan: JsonArray): List<String> = plan.mapNotNull { el ->
        ((el as? JsonObject)?.get("type") as? JsonObject)?.get("cred")?.let { (it as? JsonPrimitive)?.content }
    }.distinct()

    private fun str0(st: JsonObject, k: String) = (st[k] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }

    fun validate(plan: JsonElement, knownCreds: Set<String>? = null): List<Issue> {
        val arr = plan as? JsonArray ?: return listOf(Issue(-1, "a plan is a JSON array of steps"))
        val out = mutableListOf<Issue>()
        for ((i, el) in arr.withIndex()) {
            val st = el as? JsonObject
            if (st == null) { out += Issue(i, "each step must be an object"); continue }
            val kind = kindOf(st)
            if (kind == null) { out += Issue(i, "unknown step ${st.keys}; expected one of ${KNOWN.keys}"); continue }
            val extra = st.keys - KNOWN.getValue(kind) - "if_present"
            if ("if_present" in st && str0(st, "if_present") == null) out += Issue(i, "'if_present' needs the text, description or #id that must be on screen")
            if (extra.isNotEmpty()) out += Issue(i, "fields $extra are ignored for a '$kind' step", error = false)
            fun str(k: String) = (st[k] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }
            fun num(k: String) = (st[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.asDoubleLenient()
            when (kind) {
                "goal", "step" -> if (str(kind) == null) out += Issue(i, "'$kind' needs a non-empty text")
                "type" -> {
                    val v = st["type"]
                    if (v is JsonObject) {
                        val ref = (v["cred"] as? JsonPrimitive)?.content
                        if (ref.isNullOrBlank()) out += Issue(i, "'type' object needs a 'cred' reference")
                        else if (knownCreds != null && ref !in knownCreds) out += Issue(i, "credential '$ref' is not set in Settings")
                    } else if (v !is JsonPrimitive || v is JsonNull) out += Issue(i, "'type' needs text or {\"cred\": \"account.field\"}")
                }
                "wait_for" -> {
                    if (str("wait_for") == null) out += Issue(i, "'wait_for' needs the text, description or #id to wait for")
                    if ("timeout" in st && (num("timeout") ?: -1.0) < 0) out += Issue(i, "'timeout' must be a number of seconds")
                }
                "open" -> if (str("open") == null) out += Issue(i, "'open' needs a package or component")
                "key" -> if (str("key") == null) out += Issue(i, "'key' needs a key name (back, home, enter…)")
                "swipe" -> if (str("swipe")?.content?.lowercase() !in SWIPES) out += Issue(i, "'swipe' needs up, down, left or right (the finger's direction: up scrolls further down)")
                "sleep" -> if ((num("sleep") ?: -1.0) < 0) out += Issue(i, "'sleep' needs a number of seconds")
                "screenshot" -> if (str("screenshot") == null) out += Issue(i, "'screenshot' needs a caption")
                "tap" -> {
                    if (str("tap") == null) out += Issue(i, "'tap' needs #id, a label or x,y")
                    if ("nth" in st && (num("nth") ?: -1.0) < 0) out += Issue(i, "'nth' must be 0 or more")
                }
            }
        }
        return out
    }
}
