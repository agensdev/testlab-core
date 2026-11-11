package no.agens.testlab.core.jev

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The `openai` backend: System One's two questions as one OpenAI Decisions request, and back. */
class OpenAIDecisionsTest {
    private class Post(private val replies: List<Pair<Int, String>>) : HttpPost {
        val seen = mutableListOf<Triple<String, Map<String, String>, JsonObject>>()
        override suspend fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long, connectTimeoutMs: Long?): HttpReply {
            seen += Triple(url, headers, Json.parseToJsonElement(body).jsonObject)
            val (s, t) = replies[minOf(seen.size - 1, replies.size - 1)]
            return HttpReply(s, t)
        }
    }

    private val question = Json.parseToJsonElement(
        """{"next":{"type":"choice","instructions":"pick","criteria":{"n01":"Tap View \"Spill\"","goal_done":"done already"}},"done":{"type":"noul","instructions":"done?"}}""",
    ).jsonObject
    private val state = buildJsonObject { put("goal", "Open the Spill tab") }
    private val answer = """{"answers":[
        {"type":"choice","name":"next","choice":"Tap View \"Spill\"","probabilities":[{"value":"Tap View \"Spill\"","probability":0.9},{"value":"goal_done","probability":0.1}],"confidence":0.85},
        {"type":"predicate","name":"done","probability":0.12}],"usage":{"input_tokens":640}}"""

    private fun client(post: HttpPost, key: String? = "sk-test") =
        DecisionClient(JevConfig(backend = Backend.OPENAI), post, { "typesafe-key" }, { 0L }, { }, openaiKey = { key })

    @Test
    fun request_maps_choice_and_noul() {
        val r = OpenAIDecisions.request(state, question, "gpt-6-luna")
        assertEquals("gpt-6-luna", r["model"]!!.jsonPrimitive.content)
        assertTrue("Open the Spill tab" in r["input"]!!.jsonPrimitive.content)
        val (next, done) = r["questions"]!!.jsonArray.map { it.jsonObject }
        assertEquals("choice", next["type"]!!.jsonPrimitive.content)
        assertEquals("next", next["name"]!!.jsonPrimitive.content)
        // a tap's value is its description (bare n01 keys made the model pick neighbours); meta keys stay
        assertEquals(listOf("Tap View \"Spill\"", "goal_done"), next["choices"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content })
        assertEquals("Tap View \"Spill\"", next["choices"]!!.jsonArray[0].jsonObject["description"]!!.jsonPrimitive.content)
        val dup = Json.parseToJsonElement("""{"n01":"Tap Button \"OK\"","n02":"Tap Button \"OK\"","back":"go back"}""").jsonObject
        assertEquals(listOf("Tap Button \"OK\"", "Tap Button \"OK\" (2)", "back"), OpenAIDecisions.choiceValues(dup).values.toList())
        assertEquals("predicate", done["type"]!!.jsonPrimitive.content)
        assertTrue("choices" !in done && "criteria" !in done)
    }

    @Test
    fun ask_posts_to_decisions_and_reads_back() = runTest {
        val post = Post(listOf(200 to answer))
        val d = client(post).ask(state, question)
        val (url, headers, body) = post.seen.single()
        assertEquals("https://api.openai.com/v1/decisions", url)
        assertEquals("Bearer sk-test", headers["Authorization"])
        assertEquals("gpt-6-luna", body["model"]!!.jsonPrimitive.content)
        assertTrue(d.choice == "n01" && d.confidence == 0.85 && d.probabilities == mapOf("n01" to 0.9, "goal_done" to 0.1))
        assertEquals(0.12, d.done)
        // traces keep System One's shape
        val next = d.raw["answers"]!!.jsonObject["next"]!!.jsonObject
        assertEquals(0.9, next["probabilities"]!!.jsonObject["n01"]!!.jsonPrimitive.content.toDouble())
        assertEquals("openai", d.raw["_backend"]!!.jsonPrimitive.content)
        assertEquals(0.12, d.raw["answers"]!!.jsonObject["done"]!!.jsonObject["noul"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun model_override_and_missing_done() = runTest {
        val noDone = """{"answers":[{"type":"choice","name":"next","choice":"goal_done","probabilities":[{"value":"goal_done","probability":1.0}]}]}"""
        val post = Post(listOf(200 to noDone))
        val d = client(post).ask(state, question, model = "gpt-6-luna-mini")
        assertEquals("gpt-6-luna-mini", post.seen.single().third["model"]!!.jsonPrimitive.content)
        assertTrue(d.choice == "goal_done" && d.confidence == 1.0)
        assertNull(d.done)
    }

    @Test
    fun risky_step_question_allows_paying() {
        val cands = listOf(Candidate("n01", CandidateKind.TAP, "Tap View \"Pay 75\""))
        val plain = Questions.build(cands, Backend.OPENAI)["next"]!!.jsonObject["instructions"]!!.jsonPrimitive.content
        val risky = Questions.build(cands, Backend.OPENAI, allowRisky = true)["next"]!!.jsonObject["instructions"]!!.jsonPrimitive.content
        assertTrue("paying, or confirming a purchase" in plain)
        assertTrue("paying, or confirming a purchase" !in risky && "IS allowed in this step" in risky)
    }

    @Test
    fun errors() = runTest {
        suspend fun expect(needle: String, block: suspend () -> Unit) {
            try { block(); fail("expected '$needle'") } catch (e: JevError) { assertTrue(needle in e.message!!, e.message) }
        }
        expect("OpenAI API key not set") { client(Post(listOf(200 to answer)), key = null).ask(state, question) }
        expect("OpenAI API key missing or invalid") { client(Post(listOf(401 to "{}"))).ask(state, question) }
        expect("refused") { client(Post(listOf(200 to """{"answers":[{"type":"refusal","name":"next"}]}"""))).ask(state, question) }
        expect("not understood") { client(Post(listOf(200 to "not json"))).ask(state, question) }
        // 429, then the answer
        val p = Post(listOf(429 to "", 200 to answer))
        assertEquals("n01", client(p).ask(state, question).choice)
        assertEquals(2, p.seen.size)
        expect("unknown backend") { Backend.parse("gpt") }
        assertEquals(Backend.OPENAI, Backend.parse("openai"))
    }
}
