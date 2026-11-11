package no.agens.testlab.services.claude

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.services.SpaceRules
import no.agens.testlab.services.Spaces
import java.io.File

/**
 * A Claude Code profile per space (`CLAUDE_CONFIG_DIR=~/.claude-profiles/<space>`): its settings.json
 * gets `env.TESTLAB_SPACE`, deny rules for the other spaces' Testlab servers (`mcp__testlab-globex`)
 * and their code roots (`Read`/`Edit(//path/…)`), and the sandbox's `denyRead` for the same roots,
 * which also holds for Bash. Whatever else the file holds is kept; rules this wrote before for spaces
 * that are gone are dropped.
 */
object SpaceClaude {
    /** Where the space's Claude profile goes by default. */
    fun defaultDir(space: String, home: File = File(System.getProperty("user.home"))) = File(home, ".claude-profiles/$space")

    /** The permission rule path for an absolute [root]: `//Users/me/code/x/` and everything under it. */
    fun rulePath(root: String): String = "//" + root.replace('\\', '/').trimStart('/').trimEnd('/') + "/**"

    /**
     * [existing] settings with this space's: [others] are the other spaces' rules by name,
     * [sandbox] also turns the sandbox on (it's off by default in Claude Code).
     */
    fun settings(existing: JsonObject?, space: String, others: Map<String, SpaceRules>, sandbox: Boolean = false): JsonObject {
        val base = existing ?: JsonObject(emptyMap())
        val roots = others.values.flatMap { it.codeRoots }.distinct().sorted()
        val servers = others.keys.map { "mcp__" + Spaces.mcpName(it) }.sorted()
        val ours = (servers + roots.flatMap { listOf("Read(${rulePath(it)})", "Edit(${rulePath(it)})") }).toSet()

        val env = (base["env"] as? JsonObject).orEmpty() + ("TESTLAB_SPACE" to JsonPrimitive(space))
        val perms = (base["permissions"] as? JsonObject).orEmpty()
        val mark = (base[MARK] as? JsonObject).orEmpty()
        fun marked(k: String) = (mark[k] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
        val deny = ((perms["deny"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.filterNot { it in marked("deny") } + ours).distinct()
        val sb = (base["sandbox"] as? JsonObject).orEmpty()
        val fs = (sb["filesystem"] as? JsonObject).orEmpty()
        val oldDenyRead = (fs["denyRead"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        val denyRead = (oldDenyRead.filterNot { it in marked("denyRead") } + roots).distinct()
        val sandboxObj = JsonObject(sb + ("filesystem" to JsonObject(fs + ("denyRead" to strings(denyRead)))) +
            (if (sandbox) mapOf<String, JsonElement>("enabled" to JsonPrimitive(true)) else emptyMap()))

        return JsonObject(base + mapOf(
            "env" to JsonObject(env),
            "permissions" to JsonObject(perms + ("deny" to strings(deny))),
            "sandbox" to sandboxObj,
            // What this wrote last time, so a removed space's or root's rules go away on the next run.
            MARK to JsonObject(mapOf("deny" to strings(ours.sorted()), "denyRead" to strings(roots))),
        ))
    }

    /** The rules this wrote, so only those are replaced; the user's own stay. */
    private const val MARK = "testlabSpaceRules"

    private fun strings(l: List<String>) = JsonArray(l.map(::JsonPrimitive))
    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
