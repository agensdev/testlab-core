package no.agens.testlab.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import no.agens.testlab.AppIdentity
import no.agens.testlab.services.settings.Settings

/**
 * `config list | get KEY | set KEY VALUE | unset KEY`: settings.json by dotted key (`proxyman.host`).
 * A value is read as the key's type (true/false, a number, a JSON list or object, else text); a key that
 * doesn't exist is refused with the nearest ones, and the result must still be valid settings. Other
 * processes (the service, `serve`, Claude's server) pick the change up by themselves.
 */
private val configJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; prettyPrint = false }

internal fun config(opts: Opts): Int {
    val pos = opts.positional()
    val store = runtime.settings
    val tree = configJson.encodeToJsonElement(Settings.serializer(), store.current) as JsonObject
    val defaults = configJson.encodeToJsonElement(Settings.serializer(), Settings()) as JsonObject
    when (pos.getOrNull(0)) {
        null, "list" -> { flatten(tree).forEach { (k, v) -> println("$k = ${show(v)}") }; return 0 }
        "get" -> {
            val key = pos.getOrNull(1) ?: throw CliError("usage: ${AppIdentity.SLUG} config get KEY (keys: ${AppIdentity.SLUG} config list)")
            val v = lookup(tree, key) ?: throw CliError(unknownKey(tree, key))
            println(if (v is JsonPrimitive && v.isString) v.content else configJson.encodeToString(JsonElement.serializer(), v))
            return 0
        }
        "set", "unset" -> {
            val key = pos.getOrNull(1) ?: throw CliError("usage: ${AppIdentity.SLUG} config set KEY VALUE")
            val old = lookup(tree, key) ?: throw CliError(unknownKey(tree, key))
            if (old is JsonObject) throw CliError("$key is a section: set one of its keys (${old.keys.joinToString { "$key.$it" }})")
            val value = if (pos[0] == "unset") lookup(defaults, key) ?: JsonNull
            else parse(pos.drop(2).joinToString(" ").ifEmpty { throw CliError("usage: ${AppIdentity.SLUG} config set $key VALUE") }, old, lookup(defaults, key))
            val next = try {
                configJson.decodeFromJsonElement(Settings.serializer(), replace(tree, key.split('.'), value))
            } catch (e: Exception) {
                throw CliError("$key can't be ${show(value)}: ${e.message?.lineSequence()?.firstOrNull()}")
            }
            store.update { next }
            println("$key = ${show(lookup(configJson.encodeToJsonElement(Settings.serializer(), store.current) as JsonObject, key)!!)}")
            return 0
        }
        else -> throw CliError("usage: ${AppIdentity.SLUG} config list | get KEY | set KEY VALUE | unset KEY")
    }
}

private fun show(v: JsonElement): String = when {
    v is JsonNull -> "(not set)"
    v is JsonPrimitive && v.isString -> "\"${v.content}\""
    else -> v.toString()
}

private fun flatten(o: JsonObject, prefix: String = ""): List<Pair<String, JsonElement>> = o.flatMap { (k, v) ->
    if (v is JsonObject && v.isNotEmpty()) flatten(v, "$prefix$k.") else listOf("$prefix$k" to v)
}

private fun lookup(o: JsonObject, key: String): JsonElement? =
    key.split('.').fold(o as JsonElement?) { acc, part -> (acc as? JsonObject)?.get(part) }

private fun replace(o: JsonObject, path: List<String>, value: JsonElement): JsonObject =
    JsonObject(o + (path.first() to if (path.size == 1) value else replace(o[path.first()] as JsonObject, path.drop(1), value)))

/** The text as the key's type: what it holds now, else its default. */
private fun parse(text: String, old: JsonElement, default: JsonElement?): JsonElement {
    val like = if (old is JsonNull) default ?: JsonNull else old
    if (text == "null") return JsonNull
    return when {
        like is JsonArray || like is JsonObject || text.startsWith("[") || text.startsWith("{") ->
            runCatching { Json.parseToJsonElement(text) }.getOrElse {
                // A plain comma list for a list of strings: `config set mcp.allowedHosts a,b`.
                if (like is JsonArray) JsonArray(text.split(',').map { JsonPrimitive(it.trim()) }.filter { it.content.isNotEmpty() })
                else throw CliError("not valid JSON: $text")
            }
        like is JsonPrimitive && !like.isString && like.booleanOrNull != null ->
            JsonPrimitive(text.lowercase().toBooleanStrictOrNull() ?: throw CliError("expected true or false, got $text"))
        like is JsonPrimitive && !like.isString && (like.longOrNull != null || like.doubleOrNull != null) ->
            text.toLongOrNull()?.let(::JsonPrimitive) ?: text.toDoubleOrNull()?.let(::JsonPrimitive) ?: throw CliError("expected a number, got $text")
        else -> JsonPrimitive(text)
    }
}

private fun unknownKey(tree: JsonObject, key: String): String {
    val keys = flatten(tree).map { it.first }
    val near = keys.filter { k -> k.endsWith("." + key.substringAfterLast('.')) || k.startsWith(key.substringBefore('.') + ".") }.take(6)
    return "no setting $key" + (if (near.isNotEmpty()) "; did you mean: ${near.joinToString(", ")}" else "") + " (all: ${AppIdentity.SLUG} config list)"
}
