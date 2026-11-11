package no.agens.testlab.services.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.services.SpaceRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpaceClaudeTest {
    private fun deny(o: JsonObject) = o["permissions"]!!.jsonObject["deny"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun theProfileSeesOnlyItsSpaceAndNotTheOthersCode() {
        val others = mapOf("default" to SpaceRules(), "beta" to SpaceRules(codeRoots = listOf("/Users/me/code/beta-app")))
        val s = SpaceClaude.settings(null, "alpha", others)
        assertEquals("alpha", s["env"]!!.jsonObject["TESTLAB_SPACE"]!!.jsonPrimitive.content)
        assertEquals(setOf("mcp__testlab", "mcp__testlab-beta", "Read(//Users/me/code/beta-app/**)", "Edit(//Users/me/code/beta-app/**)"), deny(s).toSet())
        assertEquals(listOf("/Users/me/code/beta-app"), s["sandbox"]!!.jsonObject["filesystem"]!!.jsonObject["denyRead"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("enabled" in s["sandbox"]!!.jsonObject, "the sandbox only with --sandbox")
    }

    @Test
    fun rerunningKeepsTheUsersOwnAndDropsWhatIsGone() {
        val user = Json.parseToJsonElement("""{"model":"opus","permissions":{"allow":["Bash(ls:*)"],"deny":["Read(//etc/secret/**)"]}}""").jsonObject
        val first = SpaceClaude.settings(user, "alpha", mapOf("beta" to SpaceRules(codeRoots = listOf("/c/beta"))))
        val second = SpaceClaude.settings(first, "alpha", mapOf("gamma" to SpaceRules()), sandbox = true)
        assertEquals(setOf("Read(//etc/secret/**)", "mcp__testlab-gamma"), deny(second).toSet())
        assertEquals("opus", second["model"]!!.jsonPrimitive.content)
        assertEquals(listOf("Bash(ls:*)"), second["permissions"]!!.jsonObject["allow"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(second["sandbox"]!!.jsonObject["filesystem"]!!.jsonObject["denyRead"]!!.jsonArray.isEmpty())
        assertEquals("true", second["sandbox"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
    }
}
