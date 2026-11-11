package no.agens.testlab.services.proxyman

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Sets values in a JSON document by dotted key path (`features.0.enabled`, `count`): array indexes are
 * numbers, `*` means every element of an array. A path whose parent is missing is an error, so a typo
 * doesn't quietly serve the unchanged response.
 */
object JsonPatch {
    fun apply(doc: JsonElement, patches: JsonObject): JsonElement =
        patches.entries.fold(doc) { d, (path, value) -> set(d, path.split('.').filter { it.isNotEmpty() }, value, path) }

    private fun set(node: JsonElement, keys: List<String>, value: JsonElement, path: String): JsonElement {
        if (keys.isEmpty()) return value
        val k = keys.first()
        val rest = keys.drop(1)
        return when (node) {
            is JsonObject -> {
                if (rest.isNotEmpty() && k !in node) throw IllegalArgumentException("$path: there is no «$k» in the response")
                JsonObject(node + (k to (node[k]?.let { set(it, rest, value, path) } ?: value)))
            }
            is JsonArray -> when {
                k == "*" -> JsonArray(node.map { set(it, rest, value, path) })
                k.toIntOrNull() != null -> {
                    val i = k.toInt()
                    if (i !in node.indices) throw IllegalArgumentException("$path: index $i is outside the list (${node.size} element(s))")
                    JsonArray(node.mapIndexed { j, e -> if (j == i) set(e, rest, value, path) else e })
                }
                else -> throw IllegalArgumentException("$path: «$k» is not an index, and this part of the response is a list")
            }
            else -> throw IllegalArgumentException("$path: «$k» is below a value, not an object or list")
        }
    }
}
