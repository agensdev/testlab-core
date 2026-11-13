package no.agens.testlab.core.autopilot

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Parity with the Python engine itself. tools/golden/gen.py runs the pinned autopilot.py over the
 * lab fixtures and the emulator dumps and records its parsed nodes, candidates, request state and
 * question, fingerprints, and 400 step-policy outcomes; this test recomputes all of it in Kotlin.
 * JSON is compared with key order, because criteria order is model input.
 */
class PythonGoldenTest {
    private val dir = File("src/jvmTest/resources/goldens/autopilot")
    private val goal = "Open the Sport tab"
    private val history = listOf("1: Tap View \"Shop\" [#fragment_games] -> no change", "2: Tap Button \"Books\" -> screen changed")

    private fun JsonElement.s() = jsonPrimitive.content

    /** Ordered deep equality: object key order matters, numbers compare by value. */
    private fun diff(path: String, a: JsonElement, b: JsonElement, out: MutableList<String>) {
        when {
            a is JsonObject && b is JsonObject -> {
                if (a.keys.toList() != b.keys.toList()) { out += "$path keys ${a.keys} != ${b.keys}"; return }
                a.keys.forEach { diff("$path.$it", a.getValue(it), b.getValue(it), out) }
            }
            a is JsonArray && b is JsonArray -> {
                if (a.size != b.size) { out += "$path size ${a.size} != ${b.size}"; return }
                a.indices.forEach { diff("$path[$it]", a[it], b[it], out) }
            }
            a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a !is JsonNull && b !is JsonNull -> {
                val x = a.content.toDoubleOrNull()
                val y = b.content.toDoubleOrNull()
                if (x != null && y != null) { if (x != y) out += "$path $a != $b" } else if (a.content != b.content) out += "$path $a != $b"
            }
            a != b -> out += "$path ${a.toString().take(80)} != ${b.toString().take(80)}"
        }
    }

    private fun nodeJson(n: Node) = buildJsonObject {
        put("verb", n.verb); put("widget", n.widget); put("text", n.text); put("rid", n.rid); put("x", n.x); put("y", n.y)
        put("flags", n.flags); put("desc", n.desc); put("depth", n.depth)
    }

    private fun statsJson(s: CandidateStats) = buildJsonObject {
        put("dropped_risky", JsonArray(s.droppedRisky.map(::JsonPrimitive))); put("dropped_unlabeled", s.droppedUnlabeled)
        put("dropped_excluded", s.droppedExcluded); put("truncated", s.truncated); put("n_actionable", s.nActionable)
    }

    @Test
    fun screensMatchPython() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") && f.name != "policy.json" }!!.sortedBy { it.name }
        assertTrue(files.size >= 30, "only ${files.size} screen goldens")
        val problems = mutableListOf<String>()
        for (f in files) {
            val g = Json.parseToJsonElement(f.readText()).jsonObject
            val dump = g.getValue("dump").s()
            val parsed = DumpParser.parse(dump)
            val p = mutableListOf<String>()
            diff("nodes", JsonArray(parsed.nodes.map(::nodeJson)), g.getValue("nodes"), p)
            diff("unparsed", JsonArray(parsed.unparsed.map(::JsonPrimitive)), g.getValue("unparsed"), p)
            diff("fingerprint", JsonPrimitive(ScreenDiff.fingerprint(parsed.nodes)), g.getValue("fingerprint"), p)
            diff("title", JsonPrimitive(ScreenState.title(parsed.nodes)), g.getValue("title"), p)
            diff("selected", JsonArray(ScreenState.selectedLabels(parsed.nodes).map(::JsonPrimitive)), g.getValue("selected"), p)
            diff("visible_texts", JsonArray(ScreenState.visibleTexts(parsed.nodes).map(::JsonPrimitive)), g.getValue("visible_texts"), p)
            for ((variant, vg) in g.getValue("variants").jsonObject) {
                val backend = if (variant == "ollama") Backend.OLLAMA else Backend.TYPESAFE
                val (cands, stats) = Candidates.build(parsed.nodes, Candidates.maxOptionsFor(backend, 60), allowRisky = variant == "typesafe-risky")
                val v = vg.jsonObject
                diff("$variant.criteria", buildJsonObject { cands.forEach { put(it.key, it.desc) } }, v.getValue("criteria"), p)
                diff("$variant.risky", JsonArray(cands.filter { it.risky }.map { JsonPrimitive(it.key) }), v.getValue("risky"), p)
                diff("$variant.stats", statsJson(stats), v.getValue("stats"), p)
                diff("$variant.state", ScreenState.build(goal, "com.example.shop/.Main", parsed.nodes, cands, history, 2, 5), v.getValue("state"), p)
                diff("$variant.question", Questions.build(cands, backend, allowRisky = variant == "typesafe-risky"), v.getValue("question"), p)
            }
            if (p.isNotEmpty()) problems += "${f.name}: ${p.take(3).joinToString("; ")}"
        }
        if (problems.isNotEmpty()) fail("${problems.size} of ${files.size} screens differ from Python:\n" + problems.joinToString("\n"))
    }

    private class Runner(private val screens: List<String>) : HsRunner {
        var i = 0
        val calls = mutableListOf<List<String>>()
        override suspend fun invoke(args: List<String>): String {
            calls += args
            if (args.take(1) == listOf("ui")) return screens[i]
            if (args.take(2) == listOf("show", "top")) return ""
            if (args[0] in setOf("tap", "swipe", "go") && i < screens.size - 1) i++
            return "ok"
        }
    }

    @Test
    fun stepPolicyMatchesPython() = runTest {
        val g = Json.parseToJsonElement(File(dir, "policy.json").readText()).jsonObject
        val before = g.getValue("matrix_dump").s()
        val after = g.getValue("after_dump").s()
        val cases = g.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 300)
        val problems = mutableListOf<String>()
        for (c in cases) {
            val case = c.getValue("case").jsonObject
            val done = case["done"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double
            val body = buildJsonObject {
                put("model", "jev-1.13.0")
                put("answers", buildJsonObject {
                    put("next", buildJsonObject {
                        put("type", "choice"); put("choice", case.getValue("choice")); put("probabilities", c.getValue("probabilities"))
                        put("confidence", case.getValue("confidence"))
                    })
                    if (done != null) put("done", buildJsonObject { put("type", "noul"); put("noul", done) })
                })
                put("usage", buildJsonObject { put("input_tokens", 100); put("output_tokens", 0) })
            }.toString()
            val engine = AutopilotEngine(AutopilotConfig(), DecisionClient(AutopilotConfig(), { _, _, _, _, _ -> HttpReply(200, body) }, { "k" }, { 0L }, { }))
            val r = Runner(listOf(before, after))
            val clock = PythonPortTest.VClock()
            val out = engine.step(case.getValue("goal").s(), r, StepOptions(decide = DecideOptions(allowRisky = true)), clock = clock).json
            val p = mutableListOf<String>()
            fun cmp(k: String, mine: JsonElement?, theirs: JsonElement?) {
                val a = mine ?: JsonNull
                val b = theirs ?: JsonNull
                if (a != b && !(a is JsonPrimitive && b is JsonPrimitive && a.content == b.content)) p += "$k: kotlin=$a python=$b"
            }
            cmp("status", out["status"], c["status"])
            cmp("choice", out["choice"], c["choice"])
            cmp("fallback_from", out["fallback_from"], c["fallback_from"])
            cmp("low_confidence_act", out["low_confidence_act"] ?: JsonPrimitive(false), c["low_confidence_act"])
            cmp("executed", out["executed"], c["executed"])
            val calls = JsonArray(r.calls.filter { it[0] != "ui" && it[0] != "show" }.map { JsonArray(it.map(::JsonPrimitive)) })
            cmp("calls", calls, c["calls"])
            if (p.isNotEmpty()) problems += "$case → ${p.joinToString("; ")}"
        }
        if (problems.isNotEmpty()) fail("${problems.size} of ${cases.size} policy cases differ:\n" + problems.take(15).joinToString("\n"))
    }
}
