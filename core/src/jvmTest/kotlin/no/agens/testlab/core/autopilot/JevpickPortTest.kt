package no.agens.testlab.core.autopilot

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The lab's opt/hs-mcp/tests/test_jevpick.py (27 tests), ported one-to-one with the same names
 * and the same fixtures. Where the port deliberately differs, the test says so and PARITY.md
 * explains why.
 */
class JevpickPortTest {
    companion object {
        val FX = File("src/jvmTest/resources/fixtures")
        val HOME = File(FX, "shop-home.table").readText()
        val SPORT = File(FX, "shop-sport.table").readText()
        val BIKES = File(FX, "shop-bikes.table").readText()
        val EMPTY = File(FX, "empty.table").readText()
        val P8_HOME = File(FX, "shop-p8-home.tree").readText()
    }

    // ------------------------------------------------------------- helpers --

    class VClock : EngineClock {
        var t = 0L
        override fun nowMs() = t
        override suspend fun sleep(ms: Long) { t += ms }
    }

    /** Serves successive screens on `ui`; records every executed hs command. */
    open class FakeRunner(val screens: List<Pair<String, String>>, private val advanceOn: Set<String> = setOf("tap", "swipe", "go")) : HsRunner {
        var i = 0
        val calls = mutableListOf<List<String>>()
        override suspend fun invoke(args: List<String>): String {
            calls += args
            if (args.take(1) == listOf("ui")) return screens[i].first
            if (args.take(2) == listOf("show", "top")) return screens[i].second
            if (args[0] in advanceOn && i < screens.size - 1) i++
            return "ok"
        }
    }

    /** FakeRunner that also accepts the plan runner's non-UI verbs without advancing. */
    class Dev(screens: List<Pair<String, String>>) : FakeRunner(screens) {
        override suspend fun invoke(args: List<String>): String {
            if (args.take(1) in listOf(listOf("wait"), listOf("open"), listOf("type"), listOf("go"), listOf("see"))) {
                calls += args
                if (args[0] == "see") File(args[1]).apply { parentFile?.mkdirs() }.writeBytes("jpg".toByteArray())
                return "ok"
            }
            return super.invoke(args)
        }
    }

    class FakePost(responses: List<Pair<Int, String>>) : HttpPost {
        private val it = responses.iterator()
        val seen = mutableListOf<Triple<String, Map<String, String>, JsonObject>>()
        override suspend fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long, connectTimeoutMs: Long?): HttpReply {
            seen += Triple(url, headers, Json.parseToJsonElement(body).jsonObject)
            val (s, b) = it.next()
            return HttpReply(s, b)
        }
    }

    private fun answer(choice: String, probs: Map<String, Double>, confidence: Double? = null, usage: Pair<Int, Int> = 312 to 48, done: Double? = null): String =
        buildJsonObject {
            put("model", "jev-1.13.0")
            put("answers", buildJsonObject {
                put("next", buildJsonObject {
                    put("type", "choice"); put("choice", choice)
                    put("probabilities", buildJsonObject { probs.forEach { (k, v) -> put(k, v) } })
                    put("confidence", confidence ?: probs.values.max())
                })
                if (done != null) put("done", buildJsonObject { put("type", "noul"); put("noul", done) })
            })
            put("usage", buildJsonObject { put("input_tokens", usage.first); put("output_tokens", usage.second) })
        }.toString()

    private fun engine(post: HttpPost = FakePost(emptyList()), config: AutopilotConfig = AutopilotConfig(), key: String? = "k", clock: VClock = VClock()) =
        AutopilotEngine(config, DecisionClient(config, post, { key }, clock::nowMs, { clock.sleep(it) }))

    private val STUB = StepOptions(decide = DecideOptions(stub = true))
    private fun JsonObject.str(k: String) = (this[k] as JsonPrimitive).content
    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray
    private fun JsonElement.strings() = jsonArray.map { it.jsonPrimitive.content }

    private class MemTrace : TraceSink {
        val records = mutableListOf<JsonObject>()
        override val path: String = "mem://trace"
        override fun write(rec: JsonObject) { records += rec }
    }

    // ---------------------------------------------------------------- tests --

    @Test
    fun test_parse_table() {
        val (nodes, unparsed) = DumpParser.parseTable(HOME)
        assertTrue(unparsed.isEmpty())
        assertEquals(14, nodes.size)
        val shop = nodes.filter { it.text == "Shop" }
        assertTrue(shop.isNotEmpty() && shop[0].verb == "tap" && shop[0].widget == "FrameLayout")
        assertTrue(shop[0].rid == "fragment_games" && shop[0].x == 324 && shop[0].y == 2896)
        val menu = nodes.first { it.rid == "btn_menu" }
        assertTrue(menu.text == "" && menu.actionable)
        val lbl = nodes.first { "generert" in it.text }
        assertTrue(lbl.verb == "-" && lbl.rid == "" && !lbl.actionable)
    }

    @Test
    fun test_parse_embedded_quote_and_garbage() {
        val (nodes, unparsed) = DumpParser.parseTable(BIKES + "\nthis line is garbage\n")
        assertEquals(listOf("this line is garbage"), unparsed)
        val q = nodes.first { it.rid == "see_all" }
        assertEquals("Se \\\"alle\\\" kamper", q.text) // greedy first..last quote keeps the inner ones
        val pkg = DumpParser.parseTable("tap  Button  \"OK\"  #com.example.shop:id/ok_btn  10,20").nodes
        assertEquals("ok_btn", pkg[0].rid)
    }

    @Test
    fun test_parse_real_launcher_dump_with_flags() {
        val (nodes, unparsed) = DumpParser.parseTable(File(FX, "pixel-launcher.table").readText())
        assertTrue(unparsed.isEmpty() && nodes.size == 26)
        val glance = nodes[0]
        assertTrue(glance.text == "At a glance" && glance.rid == "smartspace_card_pager" && glance.flags == "long scroll")
        assertTrue(glance.x == 720 && glance.y == 346)
        val ps = nodes.first { it.text == "Play Store" }
        assertTrue(ps.flags == "long" && ps.actionable && ps.x == 210 && ps.y == 2171)
        val (cands, _) = Candidates.build(nodes)
        assertTrue(cands.any { it.desc == "Tap TextView \"Chrome\"" })
    }

    @Test
    fun test_parse_tree_real_home() {
        assertTrue(DumpParser.looksLikeTree(P8_HOME) && !DumpParser.looksLikeTree(HOME))
        val (nodes, unparsed) = DumpParser.parseTree(P8_HOME)
        assertTrue(unparsed.isEmpty() && nodes.size == 73)
        val taps = nodes.filter { it.actionable }
        // Compose tabs: label-less click wrappers borrow the child TextView
        assertEquals(listOf("Hjelp", "Logg inn", "Bli kunde", "Hjem", "Shop", "My shop", "Lab | int"), taps.map { it.text })
        val hjelp = taps[0]
        assertTrue(hjelp.desc == "" && hjelp.flags == "click focusable" && hjelp.x == 927 && hjelp.y == 167)
        val hjem = taps.first { it.text == "Hjem" }
        assertTrue(hjem.rid == "home_screen" && hjem.x == 94 && hjem.y == 2100)
        // the selected Profil tab is focusable but not clickable → not a candidate
        assertFalse(taps.any { it.text == "Profil" })
        assertTrue(nodes.any { it.desc == "Hjelp" })
        val (cands, _) = Candidates.build(nodes)
        val descs = cands.filter { it.kind == CandidateKind.TAP }.map { it.desc }
        assertTrue("Tap View \"Shop\" [#fragment_games]" in descs && "Tap View \"Logg inn\"" in descs)
        val vt = ScreenState.visibleTexts(nodes)
        assertTrue("Hjelp" in vt && "Profil" in vt && "Bli kunde" in vt)
        assertEquals(listOf("Profil"), ScreenState.selectedLabels(nodes))
        val tree = "View  @(720,1590)\n  TextView  \"13:37\"  #clock  @(135,72)\n  View  [click]  @(98,257)\n    View  desc=\"Go back\"  @(98,257)\n  TextView  \"Transaksjoner\"  @(335,429)\n  TextView  \"Siste 30 dager\"  @(300,520)"
        val tnodes = DumpParser.parseTree(tree).nodes
        assertEquals("Transaksjoner", ScreenState.title(tnodes))
        assertEquals("Transaksjoner", ScreenState.build("g", "", tnodes, emptyList(), emptyList(), 1, 1)["screen"]!!.jsonObject.str("title"))
        val st = ScreenState.build("g", "", nodes, cands, emptyList(), 1, 1)
        assertEquals(listOf("Profil"), st["screen"]!!.jsonObject["selected"]!!.strings())
        assertEquals(listOf("Ok"), ScreenState.visibleTexts(listOf(Node("-", "View", "↵↵", "", 1, 1), Node("-", "View", "Ok", "", 1, 2))))
        assertEquals(73, DumpParser.parse(P8_HOME).nodes.size) // auto-detect
    }

    @Test
    fun test_parse_tree_dialog_ok() {
        val (nodes, unparsed) = DumpParser.parseTree(File(FX, "shop-kicked-dialog.tree").readText())
        assertTrue(unparsed.isEmpty())
        val taps = nodes.filter { it.actionable }
        assertTrue(taps.size == 1 && taps[0].text == "Ok" && taps[0].x == 1112 && taps[0].y == 1803)
        assertEquals("click focusable focused", taps[0].flags)
        val n = DumpParser.parseTree("View  desc=\"Meny\" \"Åpne\"  [click]  @(10,20)").nodes
        assertTrue(n[0].desc == "Meny" && n[0].text == "Åpne" && n[0].actionable)
        val n2 = DumpParser.parseTree("  TextView #com.example.shop:id/x  \"a \\\"q\\\" b\"  @(1,2)").nodes
        assertTrue(n2[0].rid == "x" && n2[0].text == "a \\\"q\\\" b")
    }

    @Test
    fun test_parse_dump_rejects_unknown_format() {
        try {
            DumpParser.parse("garbage\nmore garbage\ntap  X  \"y\"  1,2")
            fail("expected AutopilotError")
        } catch (e: AutopilotError) {
            assertTrue("not recognised" in e.message!!)
        }
    }

    @Test
    fun test_build_candidates() {
        val nodes = DumpParser.parseTable(HOME).nodes
        val (cands, stats) = Candidates.build(nodes)
        val taps = cands.filter { it.kind == CandidateKind.TAP }
        val descs = taps.map { it.desc }
        assertEquals(listOf("Buy Books now"), stats.droppedRisky)
        assertTrue(descs.none { "Buy" in it })
        assertEquals(0, stats.droppedUnlabeled)
        assertTrue("Tap ImageButton [#btn_menu]" in descs)
        assertEquals(1, descs.count { it == "Tap FrameLayout \"Shop\" [#fragment_games]" }) // dedup
        assertTrue(descs.none { "generert" in it }) // '-' nodes excluded
        val ys = taps.map { it.node!!.y }
        assertEquals(ys.sorted(), ys)
        val keys = cands.map { it.key }
        assertTrue(keys[0] == "n01" && keys.containsAll(META.keys) && keys.containsAll(TERMINAL.keys))
        assertTrue(cands.size <= MAX_CRITERIA)
        // allow_risky keeps it, flagged
        val (cands2, stats2) = Candidates.build(nodes, allowRisky = true)
        assertTrue(stats2.droppedRisky.isEmpty() && cands2.any { it.risky && "Buy" in it.desc })
        // exclusion by (text, rid)
        val (cands3, stats3) = Candidates.build(nodes, exclude = setOf("Sport" to "fragment_sport"))
        assertTrue(stats3.droppedExcluded == 1 && cands3.none { it.desc.endsWith("\"Sport\" [#fragment_sport]") })
        // unlabeled dropped
        val (cands4, stats4) = Candidates.build(listOf(Node("tap", "View", "", "", 1, 1)))
        assertTrue(stats4.droppedUnlabeled == 1 && cands4.none { it.kind == CandidateKind.TAP })
    }

    @Test
    fun test_candidate_cap() {
        val big = (0 until 80).joinToString("\n") { "tap  Button  \"Item $it\"  #item_$it  100,${100 + it * 10}" } + "\n" +
            (0 until 20).joinToString("\n") { "tap  ImageView  #icon_$it  900,${100 + it * 10}" }
        val nodes = DumpParser.parseTable(big).nodes
        val (cands, stats) = Candidates.build(nodes, maxCandidates = 60)
        val taps = cands.filter { it.kind == CandidateKind.TAP }
        assertTrue(stats.truncated && taps.size == 60)
        assertTrue(taps.all { it.node!!.text.isNotEmpty() }) // id-only dropped first
        assertTrue(cands.size <= MAX_CRITERIA)
    }

    @Test
    fun test_build_state_and_question() {
        val nodes = DumpParser.parseTable(HOME).nodes
        val (cands, _) = Candidates.build(nodes)
        val hist = (1..8).map { "$it: x -> no change" }
        val st = ScreenState.build("Open the Sport tab", "com.example.shop/.Main", nodes, cands, hist, 9, 12)
        assertTrue(st.str("goal") == "Open the Sport tab" && st["screen"]!!.jsonObject.str("activity") == "com.example.shop/.Main")
        val h = st["history"]!!.strings()
        assertTrue(h.size == HISTORY_TAIL && h.last().startsWith("8:"))
        val vt = st["screen"]!!.jsonObject["visible_texts"]!!.strings()
        assertTrue("Hei Kari" in vt && "Buy Books now" in vt && vt.count { it == "Shop" } == 1)
        assertTrue(vt.size <= 40)
        val stJson = st.toString()
        assertTrue("coordinates" !in stJson && "2896" !in stJson)
        assertEquals(TEXT_CUT, ScreenState.visibleTexts(listOf(Node("-", "TextView", "x".repeat(200), "", 1, 1)))[0].length)
        val q = Questions.build(cands, Backend.TYPESAFE)
        val ins = q["next"]!!.jsonObject.str("instructions")
        assertTrue("ALREADY achieved" in ins && "none of the elements" in ins) // rules by meaning, not by option key
        assertEquals("noul", q["done"]!!.jsonObject.str("type"))
        assertEquals(cands.map { it.key }.toSet(), q["next"]!!.jsonObject["criteria"]!!.jsonObject.keys)
        assertFalse("history" in ScreenState.build("g", "", nodes, cands, emptyList(), 1, 1))
    }

    @Test
    fun test_ask_jev_happy_and_errors() = runTest {
        val state = buildJsonObject { put("goal", "g") }
        val q = buildJsonObject {
            put("next", buildJsonObject {
                put("type", "choice"); put("instructions", "i")
                put("criteria", buildJsonObject { put("n01", "a"); put("n02", "b") })
            })
        }
        fun client(post: HttpPost, key: String? = "k") = DecisionClient(AutopilotConfig(), post, { key }, { 0L }, { })
        val post = FakePost(listOf(200 to answer("n02", mapOf("n01" to 0.2, "n02" to 0.8))))
        var d = client(post).ask(state, q)
        assertTrue(d.choice == "n02" && d.confidence == 0.8 && d.probabilities["n01"] == 0.2)
        assertEquals("jev-1.13.0", d.model)
        assertEquals(buildJsonObject { put("input_tokens", 312); put("output_tokens", 48) }, d.usage)
        assertNull(d.done)
        d = client(FakePost(listOf(200 to answer("n02", mapOf("n01" to 0.2, "n02" to 0.8), done = 0.07)))).ask(state, q)
        assertEquals(0.07, d.done)
        val (_, headers, body) = post.seen[0]
        assertTrue(headers["Authorization"] == "Bearer k" && body.str("model") == "jev-latest")
        assertTrue(body["state"] == state && body["questions"] == q)
        for ((status, needle) in listOf(401 to "401", 422 to "HTTP 422")) {
            try {
                client(FakePost(listOf(status to "{\"error\":\"bad\"}"))).ask(state, q)
                fail("$status")
            } catch (e: AutopilotError) {
                assertTrue(needle in e.message!!, e.message)
            }
        }
        // 429 then 200 → success
        val p2 = FakePost(listOf(429 to "", 200 to answer("n01", mapOf("n01" to 0.9, "n02" to 0.1))))
        assertEquals("n01", client(p2).ask(state, q).choice)
        assertEquals(2, p2.seen.size)
        // 529 forever → error
        try {
            client(FakePost(List(6) { 529 to "" })).ask(state, q)
            fail("expected 529 error")
        } catch (e: AutopilotError) {
            assertTrue("529" in e.message!!)
        }
        // no key (the message names Settings instead of the lab's env file: see PARITY.md)
        try {
            client(p2, key = "").ask(state, q)
            fail("expected key error")
        } catch (e: AutopilotError) {
            assertTrue("API key" in e.message!!)
        }
    }

    @Test
    fun test_stub_decide() {
        val nodes = DumpParser.parseTable(HOME).nodes
        val (cands, _) = Candidates.build(nodes)
        val q = Questions.build(cands, Backend.TYPESAFE)
        val keySport = cands.first { it.node?.text == "Sport" }.key
        val d = StubDecider.decide(ScreenState.build("Open the Sport tab", "", nodes, cands, emptyList(), 1, 1), q, 0.8)
        assertTrue(d.choice == keySport && kotlin.math.abs(d.probabilities.values.sum() - 1) < 0.01)
        val d2 = StubDecider.decide(
            ScreenState.build("Open the Sport tab", "", nodes, cands, listOf("1: Tap FrameLayout \"Sport\" [#fragment_sport] -> screen changed"), 2, 3), q, 0.8,
        )
        assertEquals("goal_done", d2.choice)
        val d3 = StubDecider.decide(ScreenState.build("Buy a Books ticket now", "", nodes, cands, emptyList(), 1, 1), q, 0.8)
        assertTrue(d3.choice == "blocked" || (d3.probabilities["n01"] ?: 0.0) <= 0.5)
    }

    @Test
    fun test_to_hs_args() {
        val nodes = DumpParser.parseTable(HOME).nodes
        val (cands, _) = Candidates.build(nodes)
        val sport = cands.first { it.node?.text == "Sport" }
        assertEquals(listOf("tap", "540", "2896"), AutopilotEngine.toHsArgs(sport))
        val by = cands.associateBy { it.key }
        assertEquals(listOf("swipe", "up"), AutopilotEngine.toHsArgs(by.getValue("scroll_down")))
        assertEquals(listOf("swipe", "down"), AutopilotEngine.toHsArgs(by.getValue("scroll_up")))
        assertEquals(listOf("go", "back"), AutopilotEngine.toHsArgs(by.getValue("back")))
        assertTrue(AutopilotEngine.toHsArgs(by.getValue("goal_done")).isEmpty() && AutopilotEngine.toHsArgs(by.getValue("blocked")).isEmpty())
        assertEquals(listOf("tap", "#ok_btn"), AutopilotEngine.toHsArgs(Candidate("n99", CandidateKind.TAP, "d", Node("tap", "B", "", "ok_btn", 0, 0))))
    }

    @Test
    fun test_changed() {
        val home = DumpParser.parseTable(HOME).nodes
        val sport = DumpParser.parseTable(SPORT).nodes
        assertFalse(ScreenDiff.changed(home, home).changed)
        val digits = HOME.replace("9 412 kr", "9 350 kr").replace("24 mill.", "26 mill.")
        assertFalse(ScreenDiff.changed(home, DumpParser.parseTable(digits).nodes).changed)
        val ch = ScreenDiff.changed(home, sport)
        assertTrue(ch.changed && "Bikes" in ch.added && "Hei Kari" in ch.removed)
        assertTrue(ScreenDiff.changed(home, home, "a/.Main", "a/.Other").changed)
        val fpDigits = ScreenDiff.fingerprint(DumpParser.parseTable(digits).nodes)
        assertEquals(ScreenDiff.fingerprint(home), fpDigits)
        assertNotEquals(fpDigits, ScreenDiff.fingerprint(sport))
    }

    @Test
    fun test_do_reaches_goal_with_stub() = runTest {
        val trace = MemTrace()
        val r = FakeRunner(listOf(HOME to "shop/.Main", SPORT to "shop/.Main", BIKES to "shop/.Bikes"))
        val out = engine().doGoal("Open the Sport tab", r, 5, STUB, trace, VClock())
        assertEquals("goal_done", out.str("status"), out.toString())
        val steps = out.arr("steps").map { it.jsonObject }
        assertTrue(steps[0].str("choice").startsWith("n") && steps[0]["changed"] == JsonPrimitive(true))
        assertTrue(steps.last().str("choice") == "goal_done" && steps.size == 2)
        assertTrue(listOf("tap", "540", "2896") in r.calls)
        assertFalse(r.calls.any { "Buy" in it.joinToString(" ") })
        assertEquals("goal_done", trace.records.last()["summary"]!!.jsonObject.str("status"))
        assertEquals("step", trace.records.first().str("tool"))
        assertFalse(trace.records.toString().contains("apikey"))
    }

    @Test
    fun test_do_stuck_needs_decision_error() = runTest {
        var r = FakeRunner(listOf(HOME to "shop/.Main")) // never changes; "Shop" matches two candidates
        var out = engine().doGoal("Open Shop", r, 6, STUB, null, VClock())
        assertEquals("stuck", out.str("status"), out.toString())
        val steps = out.arr("steps").map { it.jsonObject }
        assertTrue(steps.size == 2 && steps.all { it["changed"] == JsonPrimitive(false) })
        assertNotEquals(steps[0].str("desc"), steps[1].str("desc")) // tried node was excluded on retry
        // single matching candidate + no change → excluded → stub has nothing left → blocked
        out = engine().doGoal("Open the Sport tab", FakeRunner(listOf(HOME to "")), 6, STUB, null, VClock())
        assertTrue(out.str("status") == "blocked" && out.arr("steps").size == 2)
        // a weak answer hands back and nothing is tapped
        r = FakeRunner(listOf(HOME to "", SPORT to ""))
        out = engine(config = AutopilotConfig(stubConfidence = 0.2)).doGoal("Open the Sport tab", r, 3, STUB, null, VClock())
        assertTrue(out.str("status") == "needs_decision" && out.arr("candidates").size == 5)
        assertFalse(r.calls.any { it[0] == "tap" })
        out = engine().doGoal("anything", FakeRunner(listOf(EMPTY to "")), 12, STUB, null, VClock())
        assertTrue(out.str("status") == "error" && "empty" in out.str("error"))
        val broken = HsRunner { throw RuntimeException("hs ui failed (1): --device X: no forwarded daemon") }
        out = engine().doGoal("anything", broken, 12, STUB, null, VClock())
        assertTrue(out.str("status") == "error" && "use_device" in out.str("error"))
    }

    @Test
    fun aTapBelowTheScreenScrollsTowardsItInsteadOfFailing() = runTest {
        val r = object : FakeRunner(listOf(HOME to "shop/.Main", SPORT to "shop/.Main")) {
            override suspend fun invoke(args: List<String>): String {
                if (args.firstOrNull() == "tap") { calls += args; throw IllegalStateException("daemon error ERR:OFF_SCREEN:x=720 y=3569 display=1440x3120") }
                return super.invoke(args)
            }
        }
        val out = engine().step("Open the Sport tab", r, STUB, clock = VClock()).json
        assertEquals("acted", out.str("status"), out.toString())
        assertTrue(listOf("swipe", "up") in r.calls, r.calls.toString())
        assertTrue("scrolled_toward" in out)
    }

    @Test
    fun test_pick_and_step_dicts() = runTest {
        var r = FakeRunner(listOf(HOME to "shop/.Main", SPORT to "shop/.Main"))
        var out = engine().pick("Open the Sport tab", r, opts = DecideOptions(stub = true))
        assertEquals(listOf("tap", "540", "2896"), out["action"]!!.jsonObject["hs"]!!.strings())
        assertEquals(listOf("Buy Books now"), out["dropped_risky"]!!.strings())
        assertEquals(5, out.arr("top").size)
        assertEquals(9, out["n_candidates"]!!.jsonPrimitive.int) // 11 actionable - 1 dup "Shop" - 1 risky "Kjøp"
        assertEquals(0, r.i) // nothing executed
        out = engine().step("Open the Sport tab", r, STUB, clock = VClock()).json
        assertTrue(out.str("status") == "acted" && out["executed"] == JsonPrimitive(true) && out["changed"] == JsonPrimitive(true))
        assertTrue("Bikes" in out["diff"]!!.jsonObject["added"]!!.strings())
        // tree fixtures through the loop: tab tap on the real P8 home screen
        r = FakeRunner(listOf(P8_HOME to "shop/.Main", SPORT to "shop/.Sport"))
        out = engine().doGoal("Open the Shop tab", r, 3, STUB, null, VClock())
        assertTrue(out.str("status") == "goal_done" && listOf("tap", "299", "2100") in r.calls)
    }

    @Test
    fun test_done_noul_gates_completion() = runTest {
        // The real weak spot: goal_done gets a small share of a 27-way choice, but the yes/no says done.
        val spread = LinkedHashMap<String, Double>().apply { (1..20).forEach { put("n" + it.toString().padStart(2, '0'), 0.03) }; put("goal_done", 0.3); put("blocked", 0.1) }
        var out = engine(FakePost(listOf(200 to answer("goal_done", spread, confidence = 0.28, done = 0.91))))
            .step("Open the Shop tab", FakeRunner(listOf(SPORT to "shop/.Main")), clock = VClock()).json
        assertTrue(out.str("status") == "goal_done" && out["executed"] == JsonPrimitive(false) && out["done_prob"]!!.jsonPrimitive.double == 0.91)
        // a confident tap choice with a high done prob still counts as done (no extra tap)
        out = engine(FakePost(listOf(200 to answer("n01", mapOf("n01" to 0.8, "goal_done" to 0.2), done = 0.75)))).step("x", FakeRunner(listOf(SPORT to "")), clock = VClock()).json
        assertTrue(out.str("status") == "goal_done" && out["executed"] == JsonPrimitive(false))
        // choice says goal_done but the yes/no vetoes it → fall back to the best tap candidate
        out = engine(FakePost(listOf(200 to answer("goal_done", mapOf("goal_done" to 0.56, "n01" to 0.38, "n02" to 0.05), done = 0.16))))
            .step("Open the Shop tab", FakeRunner(listOf(HOME to "", SPORT to "")), clock = VClock()).json
        assertTrue(out.str("status") == "acted" && out["executed"] == JsonPrimitive(true) && out.str("fallback_from") == "goal_done" && out.str("choice") == "n01")
        // vetoed and no decent fallback → needs_decision
        out = engine(FakePost(listOf(200 to answer("goal_done", mapOf("goal_done" to 0.53, "n01" to 0.15, "blocked" to 0.32), done = 0.1)))).step("x", FakeRunner(listOf(SPORT to "")), clock = VClock()).json
        assertTrue(out.str("status") == "needs_decision" && out["executed"] == JsonPrimitive(false))
        // goal_done first at probability 0.52 but confidence 0.47 → accepted on probability
        out = engine(FakePost(listOf(200 to answer("goal_done", mapOf("goal_done" to 0.52, "blocked" to 0.18, "n01" to 0.15), confidence = 0.47, done = 0.27))))
            .step("Open Transaksjoner", FakeRunner(listOf(SPORT to "")), clock = VClock()).json
        assertEquals("goal_done", out.str("status"))
        // confident goal_done with a lukewarm (not contradicting) yes/no → accepted
        out = engine(FakePost(listOf(200 to answer("goal_done", mapOf("goal_done" to 0.68, "n01" to 0.08), done = 0.3)))).step("x", FakeRunner(listOf(SPORT to "")), clock = VClock()).json
        assertEquals("goal_done", out.str("status"))
        // low done prob → the choice rules as before
        out = engine(FakePost(listOf(200 to answer("n01", mapOf("n01" to 0.8, "goal_done" to 0.2), done = 0.2)))).step("x", FakeRunner(listOf(HOME to "", SPORT to "")), clock = VClock()).json
        assertTrue(out.str("status") == "acted" && out["executed"] == JsonPrimitive(true))
    }

    @Test
    fun test_cli_pick_offline() = runTest {
        // The CLI lives in another module; this is the same pick through a dump-file runner.
        val dumpRunner = HsRunner { a -> if (a.firstOrNull() == "ui") HOME.trim() else "" }
        val out = engine().pick("Open the Sport tab", dumpRunner, opts = DecideOptions(stub = true))
        assertEquals(listOf("tap", "540", "2896"), out["action"]!!.jsonObject["hs"]!!.strings())
    }

    @Test
    fun test_action_goal_finishes_after_one_tap() = runTest {
        assertTrue(isActionGoal("Press Next") && isActionGoal("choose the 1 uke quickpick"))
        assertTrue(!isActionGoal("Open Books") && !isActionGoal("Go to Bikes"))
        val r = FakeRunner(listOf(HOME to "a/.Main", SPORT to "a/.Sport"))
        val out = engine().doGoal("Select the Sport tab", r, 5, STUB, null, VClock())
        assertTrue(out.str("status") == "goal_done" && out.arr("steps").size == 1 && "action goal" in out.str("note"))
        assertEquals(3, r.calls.count { it[0] == "ui" }) // fingerprint read + step read + 1 verify poll
    }

    @Test
    fun test_harmless_argmax_and_weak_blocked_fallback() = runTest {
        // non-risky tap at 0.4 (< 0.5, >= 0.3) → acts, flagged
        var out = engine(FakePost(listOf(200 to answer("n01", mapOf("n01" to 0.4, "blocked" to 0.3, "goal_done" to 0.1), confidence = 0.4, done = 0.1))))
            .step("x", FakeRunner(listOf(HOME to "", SPORT to "")), clock = VClock()).json
        assertTrue(out.str("status") == "acted" && out["low_confidence_act"] == JsonPrimitive(true) && out["executed"] == JsonPrimitive(true))
        // weak "blocked" (0.4) loses to the Sport tab at 0.34
        val (cands, _) = Candidates.build(DumpParser.parseTable(HOME).nodes)
        val kSport = cands.first { it.node?.text == "Sport" }.key
        out = engine(FakePost(listOf(200 to answer("blocked", linkedMapOf("blocked" to 0.4, kSport to 0.34, "scroll_down" to 0.06), confidence = 0.37, done = 0.03))))
            .step("Open the Sport tab", FakeRunner(listOf(HOME to "", SPORT to "")), clock = VClock()).json
        assertTrue(out.str("status") == "acted" && out.str("fallback_from") == "blocked" && out.str("choice") == kSport)
        // a confident blocked stays blocked
        out = engine(FakePost(listOf(200 to answer("blocked", linkedMapOf("blocked" to 0.7, kSport to 0.2), confidence = 0.7, done = 0.03))))
            .step("x", FakeRunner(listOf(HOME to "")), clock = VClock()).json
        assertEquals("blocked", out.str("status"))
        // risky tap under threshold still hands back, even with allow_risky
        out = engine(FakePost(listOf(200 to answer("n01", mapOf("n01" to 0.46, "blocked" to 0.2), confidence = 0.46, done = 0.1))))
            .step("Pay", FakeRunner(listOf("tap  View  \"Pay 50 kr\"  700,2900\n-  TextView  \"Totalt\"  100,100" to "")), StepOptions(decide = DecideOptions(allowRisky = true)), clock = VClock()).json
        assertTrue(out.str("status") == "needs_decision" && out["executed"] == JsonPrimitive(false))
        // a leave-this-screen element (Go back) never acts below the threshold
        out = engine(FakePost(listOf(200 to answer("n01", mapOf("n01" to 0.49, "goal_done" to 0.3), confidence = 0.49, done = 0.2))))
            .step("Open Transaksjoner", FakeRunner(listOf("tap  View  \"Go back\"  98,257\ntap  Button  \"Transaksjoner\"  700,900" to "")), clock = VClock()).json
        assertTrue(out.str("status") == "needs_decision" && out["executed"] == JsonPrimitive(false))
        // tap below the harmless floor hands back
        out = engine(FakePost(listOf(200 to answer("n01", mapOf("n01" to 0.25, "blocked" to 0.2), confidence = 0.25, done = 0.1))))
            .step("x", FakeRunner(listOf(HOME to "")), clock = VClock()).json
        assertEquals("needs_decision", out.str("status"))
    }

    @Test
    fun test_label_borrowing_for_unlabelled_inputs() {
        val tree = "View  @(720,1600)\n  View  \"Your Login password\"  @(514,1361)\n  EditText #P0-4  [click,focusable,password]  @(775,1525)\n  Button  \"Next\"  [click,focusable]  @(719,2234)"
        val (cands, _) = Candidates.build(DumpParser.parseTree(tree).nodes)
        val descs = cands.filter { it.kind == CandidateKind.TAP }.map { it.desc }
        assertTrue("Tap EditText [#P0-4] near \"Your Login password\"" in descs)
        assertTrue("Tap Button \"Next\"" in descs) // labelled nodes untouched
    }

    private fun plan(json: String) = Json.parseToJsonElement(json) as JsonArray

    @Test
    fun test_run_plan_offline() = runTest {
        val r = Dev(listOf(HOME to "a/.Main", SPORT to "a/.Sport", BIKES to "a/.Bikes"))
        val adbCalls = mutableListOf<List<String>>()
        val creds = mapOf("demo.nnin" to "01019912345")
        val ctx = PlanContext(cred = { creds.getValue(it) }, runAdb = { adbCalls += it; "" })
        var out = engine().runPlan(
            plan("""[{"note":"start"},{"open":"pkg"},{"goal":"Open the Sport tab"},{"type":{"cred":"demo.nnin"}},
                     {"hide_keyboard":true},{"wait_for":"bikes","timeout":5},{"step":"Select Bikes"},{"sleep":0}]"""),
            r, ctx, decide = DecideOptions(stub = true), clock = VClock(),
        )
        assertTrue(out.str("status") == "completed" && out["paused_at"] is kotlinx.serialization.json.JsonNull && out.arr("steps").size == 8, out.toString())
        val steps = out.arr("steps").map { it.jsonObject }
        assertTrue(listOf("type", "01019912345") in r.calls && steps[5]["waited_s"]!!.jsonPrimitive.double == 0.0)
        assertEquals(listOf(listOf("shell", "input", "tap", "195", "2990")), adbCalls)
        assertEquals(Json.parseToJsonElement("""{"type":{"cred":"demo.nnin"}}"""), steps[3]["step"]) // value never echoed
        assertFalse("01019912345" in out.toString())
        // launch arguments (iOS): a credential among them is filled in, and never echoed
        val launch = Dev(listOf(HOME to ""))
        val opened = engine().runPlan(plan("""[{"open":"no.example.app","args":["-environment","int","-buypassId",{"cred":"demo.nnin"}]}]"""), launch, ctx, clock = VClock())
        assertEquals("completed", opened.str("status"))
        assertTrue(listOf("open", "no.example.app", "-environment", "int", "-buypassId", "01019912345") in launch.calls, launch.calls.toString())
        assertFalse("01019912345" in opened.toString())
        assertTrue(PlanValidator.validate(plan("""[{"open":"x","args":["-a"]}]""")).isEmpty())
        // "fresh": the app's data is cleared before the start; refused where nothing can clear it
        val cleared = mutableListOf<String>()
        val fresh = engine().runPlan(plan("""[{"open":"no.example.app/.Main","fresh":true}]"""), Dev(listOf(HOME to "")), PlanContext(clearData = { cleared += it }), clock = VClock())
        assertEquals("completed", fresh.str("status"))
        assertEquals(listOf("no.example.app"), cleared)
        assertEquals("error", engine().runPlan(plan("""[{"open":"x","fresh":true}]"""), Dev(listOf(HOME to "")), PlanContext(), clock = VClock()).str("status"))
        assertTrue(PlanValidator.validate(plan("""[{"open":"x","fresh":true}]""")).isEmpty())
        // a hand-back pauses the plan with a resume hint
        out = engine(config = AutopilotConfig(stubConfidence = 0.2)).runPlan(
            plan("""[{"goal":"Open the Sport tab"},{"note":"never"}]"""), Dev(listOf(HOME to "", SPORT to "")), ctx,
            decide = DecideOptions(stub = true), clock = VClock(),
        )
        assertTrue(out.str("status") == "paused" && out["paused_at"]!!.jsonPrimitive.int == 0 && out["hand_back"]!!.jsonObject.str("status") == "needs_decision")
        assertTrue("start=1" in out.str("resume_hint") && out.arr("steps").size == 1)
        // unknown step / missing cred lookup → error
        out = engine().runPlan(plan("""[{"bogus":1}]"""), Dev(listOf(HOME to "")), decide = DecideOptions(stub = true), clock = VClock())
        assertTrue(out.str("status") == "error" && "unknown plan step" in out["hand_back"]!!.jsonObject.str("error"))
        out = engine().runPlan(plan("""[{"type":{"cred":"x.y"}}]"""), Dev(listOf(HOME to "")), decide = DecideOptions(stub = true), clock = VClock())
        assertEquals("error", out.str("status"))
        // wait_for matches resource ids too (#nnin on the Login form)
        out = engine().runPlan(plan("""[{"wait_for":"#fragment_games","timeout":0.0}]"""), Dev(listOf(HOME to "")), decide = DecideOptions(stub = true), clock = VClock())
        assertEquals("completed", out.str("status"))
        // wait_for times out cleanly
        out = engine().runPlan(plan("""[{"wait_for":"never-there","timeout":0.0}]"""), Dev(listOf(HOME to "")), decide = DecideOptions(stub = true), clock = VClock())
        assertTrue(out.str("status") == "error" && "wait_for" in out["hand_back"]!!.jsonObject.str("error"))
        // if_present: a step for a screen that isn't there (an age gate accepted earlier) is skipped
        out = engine(config = AutopilotConfig(stubConfidence = 0.2)).runPlan(
            plan("""[{"step":"Accept the cookie banner","if_present":"Godta cookies"},{"note":"after"}]"""), Dev(listOf(HOME to "")),
            decide = DecideOptions(stub = true), clock = VClock(),
        )
        assertEquals("completed", out.str("status"), out.toString())
        assertEquals("skipped", out.arr("steps")[0].jsonObject.str("status"))
        // …and runs when it is there (HOME has «Jeg er over 18 år»)
        out = engine(config = AutopilotConfig(stubConfidence = 0.2)).runPlan(
            plan("""[{"step":"Accept the age gate","if_present":"over 18"}]"""), Dev(listOf(HOME to "")),
            decide = DecideOptions(stub = true), clock = VClock(),
        )
        assertEquals("needs_decision", out.arr("steps")[0].jsonObject.str("status"))
        // optional: a hand-back on that step is a skip, not a pause
        out = engine(config = AutopilotConfig(stubConfidence = 0.2)).runPlan(
            plan("""[{"goal":"Open the Sport tab","optional":true},{"note":"after"}]"""), Dev(listOf(HOME to "", SPORT to "")),
            decide = DecideOptions(stub = true), clock = VClock(),
        )
        assertTrue(out.str("status") == "completed" && out.arr("steps")[0].jsonObject.str("status") == "skipped" && out.arr("steps").size == 2, out.toString())
        assertTrue(PlanValidator.validate(plan("""[{"step":"x","if_present":"Godta","optional":true}]""")).isEmpty())
        assertTrue(PlanValidator.validate(plan("""[{"step":"x","if_present":""}]""")).any { it.error })
    }

    /** Test double with the lab's manifest semantics; the app's report store implements the same contract. */
    private class DirShots(val dir: File) : ShotStore {
        private val manifest = File(dir, "manifest.json")
        override fun allocate(caption: String, slug: String): ShotSlot {
            if (manifest.exists()) {
                val n = Json.parseToJsonElement(manifest.readText()).jsonObject["shots"]!!.jsonArray.size + 1
                val name = n.toString().padStart(2, '0') + "-$slug.jpg"
                return ShotSlot(File(dir, name).path, name, true)
            }
            val name = "20261002-120000-$slug.jpg"
            return ShotSlot(File(dir, name).path, name, false)
        }
        override fun commit(slot: ShotSlot, caption: String) {
            if (!slot.registered) return
            val man = Json.parseToJsonElement(manifest.readText()).jsonObject
            val shots = man["shots"]!!.jsonArray + buildJsonObject { put("file", slot.fileName); put("caption", caption); put("t", 1) }
            manifest.writeText(JsonObject(man + ("shots" to JsonArray(shots))).toString())
        }
    }

    @Test
    fun test_run_plan_screenshot_registers_in_report_manifest() = runTest {
        val d = kotlin.io.path.createTempDirectory().toFile()
        File(d, "manifest.json").writeText("""{"title":"t","created":"now","shots":[{"file":"01-x.jpg","caption":"x","t":1}]}""")
        var out = engine().runPlan(plan("""[{"screenshot":"Payment sheet reached"}]"""), Dev(listOf(HOME to "")), PlanContext(shots = DirShots(d)), clock = VClock())
        assertEquals("completed", out.str("status"))
        val man = Json.parseToJsonElement(File(d, "manifest.json").readText()).jsonObject
        val last = man["shots"]!!.jsonArray.last().jsonObject
        assertTrue(last.str("file") == "02-payment-sheet-reached.jpg" && last.str("caption") == "Payment sheet reached")
        assertTrue(File(d, "02-payment-sheet-reached.jpg").exists())
        assertEquals("![Payment sheet reached](02-payment-sheet-reached.jpg)", out.arr("steps")[0].jsonObject.str("markdown"))
        // no manifest → timestamped file, nothing registered
        val d2 = kotlin.io.path.createTempDirectory().toFile()
        out = engine().runPlan(plan("""[{"screenshot":"loose"}]"""), Dev(listOf(HOME to "")), PlanContext(shots = DirShots(d2)), clock = VClock())
        assertTrue(out.str("status") == "completed" && !File(d2, "manifest.json").exists() && "markdown" !in out.arr("steps")[0].jsonObject)
    }

    @Test
    fun test_ollama_backend_shape_and_cap() = runTest {
        val (cands, _) = Candidates.build(DumpParser.parseTable(HOME).nodes)
        val q = Questions.build(cands, Backend.OLLAMA)
        assertEquals(listOf("false", "true"), q["done"]!!.jsonObject["criteria"]!!.jsonObject.keys.toList())
        assertFalse("criteria" in Questions.build(cands, Backend.TYPESAFE)["done"]!!.jsonObject)
        assertTrue(Candidates.maxOptionsFor(Backend.OLLAMA, 60) == 21 && Candidates.maxOptionsFor(Backend.TYPESAFE, 60) == 60)
        // no Authorization header, ollama url/model, model name from the response
        val cfg = AutopilotConfig(ollamaUrls = listOf("http://ollama-b:11434/v1/systemone"), ollamaModel = "nimble:9b-q4_K_M")
        val good = """{"model":"nimble","answers":{"next":{"type":"choice","choice":"n02","probabilities":{"n01":0.3,"n02":0.7},"confidence":0.6},"done":{"type":"noul","noul":0.2}},"usage":{"input_tokens":500,"output_tokens":0}}"""
        val post = FakePost(listOf(200 to good))
        val client = DecisionClient(cfg, post, { null }, { 0L }, { })
        val small = Json.parseToJsonElement("""{"next":{"type":"choice","instructions":"i","criteria":{"n01":"a","n02":"b"}},"done":{"type":"noul","instructions":"d","criteria":{"false":"f","true":"t"}}}""").jsonObject
        val d = client.ask(buildJsonObject { put("goal", "g") }, small, Backend.OLLAMA)
        val (url, headers, body) = post.seen[0]
        assertTrue(url == "http://ollama-b:11434/v1/systemone" && "Authorization" !in headers && body.str("model") == "nimble:9b-q4_K_M")
        assertTrue(d.choice == "n02" && d.model == "nimble" && d.done == 0.2 && d.raw.str("_backend") == "ollama")
        // > 26 options is refused up front on ollama
        val big = buildJsonObject { put("next", buildJsonObject { put("type", "choice"); put("instructions", "i"); put("criteria", buildJsonObject { (0 until 30).forEach { put("n" + it.toString().padStart(2, '0'), "x") } }) }) }
        try {
            client.ask(buildJsonObject { put("goal", "g") }, big, Backend.OLLAMA, url = "http://x")
            fail("expected cap error")
        } catch (e: AutopilotError) {
            assertTrue("26" in e.message!!)
        }
        // decide() caps the candidate set for ollama (80 tap rows → 21 + 5)
        val bigdump = (0 until 80).joinToString("\n") { "tap  Button  \"Item $it\"  #item_$it  100,${100 + it * 10}" }
        val okOllama = """{"model":"nimble","answers":{"next":{"type":"choice","choice":"n01","probabilities":{"n01":0.9},"confidence":0.9},"done":{"type":"noul","noul":0.1}},"usage":{}}"""
        var e2 = engine(FakePost(listOf(200 to okOllama)), cfg)
        var dec = e2.decide("g", bigdump, "", emptyList(), DecideOptions(backend = Backend.OLLAMA))
        assertTrue(dec.candidates.size == 26 && dec.stats.truncated && dec.stats.backend == "ollama")
        // typesafe default still unlimited at 60
        e2 = engine(FakePost(listOf(200 to answer("n01", mapOf("n01" to 0.9)))))
        dec = e2.decide("g", bigdump, "", emptyList())
        assertTrue(dec.candidates.size == 65 && dec.stats.backend == "typesafe")
        // unknown backend
        try {
            Backend.parse("nope")
            fail()
        } catch (e: AutopilotError) {
            assertTrue("unknown" in e.message!!)
        }
    }

    @Test
    fun test_ollama_failover_list() = runTest {
        val q = Json.parseToJsonElement("""{"next":{"type":"choice","instructions":"i","criteria":{"n01":"a","n02":"b"}}}""").jsonObject
        val good = """{"model":"nimble","answers":{"next":{"type":"choice","choice":"n01","probabilities":{"n01":0.9,"n02":0.1},"confidence":0.9}},"usage":{}}"""
        val g = buildJsonObject { put("goal", "g") }
        val calls = mutableListOf<String>()
        val post = HttpPost { url, _, _, _, _ ->
            calls += url
            if ("ollama-a" in url) HttpReply(404, "404 page not found") else HttpReply(200, good)
        }
        var clock = 1000_000L
        val urls = listOf("http://ollama-a/v1/systemone", "http://ollama-b/v1/systemone")
        val client = DecisionClient(AutopilotConfig(ollamaUrls = urls), post, { null }, { clock }, { })
        var d = client.ask(g, q, Backend.OLLAMA)
        assertTrue(d.choice == "n01" && d.raw.str("_url") == "http://ollama-b/v1/systemone" && calls == urls)
        // the 404 host is skipped for the backoff
        calls.clear(); clock += 10_000
        client.ask(g, q, Backend.OLLAMA)
        assertEquals(listOf("http://ollama-b/v1/systemone"), calls)
        // after the backoff it is tried again
        calls.clear(); clock += 61_000
        client.ask(g, q, Backend.OLLAMA)
        assertEquals("http://ollama-a/v1/systemone", calls[0])
        // connection errors fail over too; all down → clear error
        val dead = HttpPost { _, _, _, _, _ -> throw java.io.IOException("connect refused") }
        try {
            DecisionClient(AutopilotConfig(ollamaUrls = listOf("http://a/v1/systemone", "http://b/v1/systemone")), dead, { null }, { 5_000_000L }, { }).ask(g, q, Backend.OLLAMA)
            fail()
        } catch (e: AutopilotError) {
            assertTrue("no System One backend reachable" in e.message!!)
        }
        // per-entry model: "url|model" wins over the default; an explicit model wins over both
        val bodies = mutableListOf<String>()
        val post2 = HttpPost { _, _, body, _, _ -> bodies += Json.parseToJsonElement(body).jsonObject.str("model"); HttpReply(200, good) }
        d = DecisionClient(AutopilotConfig(ollamaUrls = listOf("http://ollama-a/v1/systemone|nimble:9b", "http://ollama-b/v1/systemone|nimble:9b-q4_K_M")), post2, { null }, { 0L }, { }).ask(g, q, Backend.OLLAMA)
        assertTrue(bodies == listOf("nimble:9b") && d.raw.str("_url") == "http://ollama-a/v1/systemone")
        bodies.clear()
        DecisionClient(AutopilotConfig(ollamaUrls = listOf("http://ollama-b/v1/systemone"), ollamaModel = "nimble:9b-q4_K_M"), post2, { null }, { 0L }, { }).ask(g, q, Backend.OLLAMA)
        assertEquals(listOf("nimble:9b-q4_K_M"), bodies)
        bodies.clear()
        DecisionClient(AutopilotConfig(ollamaUrls = listOf("http://ollama-b/v1/systemone|nimble:9b")), post2, { null }, { 0L }, { }).ask(g, q, Backend.OLLAMA, model = "tev1")
        assertEquals(listOf("tev1"), bodies)
    }

    @Test
    fun test_action_step_ignores_done_gate() = runTest {
        // 'Open Environment' as a step: the row is visible so the noul says done — an action step must still act.
        val (cands, _) = Candidates.build(DumpParser.parseTable(HOME).nodes)
        val kSport = cands.first { it.node?.text == "Sport" }.key
        var out = engine(FakePost(listOf(200 to answer(kSport, mapOf(kSport to 0.9, "goal_done" to 0.05), confidence = 0.9, done = 0.95))))
            .step("Tap the Sport row", FakeRunner(listOf(HOME to "", SPORT to "")), clock = VClock()).json
        assertTrue(out.str("status") == "acted" && out["executed"] == JsonPrimitive(true))
        // choice goal_done on an action step → best harmless tap instead
        out = engine(FakePost(listOf(200 to answer("goal_done", linkedMapOf("goal_done" to 0.6, kSport to 0.3), confidence = 0.6, done = 0.9))))
            .step("Press Sport", FakeRunner(listOf(HOME to "", SPORT to "")), clock = VClock()).json
        assertTrue(out.str("status") == "acted" && out.str("fallback_from") == "goal_done")
        // a state goal keeps the gate
        out = engine(FakePost(listOf(200 to answer(kSport, mapOf(kSport to 0.9), confidence = 0.9, done = 0.95))))
            .step("Open the Sport tab", FakeRunner(listOf(HOME to "")), clock = VClock()).json
        assertTrue(out.str("status") == "goal_done" && out["executed"] == JsonPrimitive(false))
    }

    @Test
    fun test_scripted_tap_step_and_timing() = runTest {
        val nodes = DumpParser.parseTree(P8_HOME).nodes
        assertTrue(PlanSteps.findNode(nodes, "#fragment_games").rid.endsWith("fragment_games"))
        assertTrue(PlanSteps.findNode(nodes, "Shop").actionable && PlanSteps.findNode(nodes, "shop").x == PlanSteps.findNode(nodes, "Shop").x)
        val n = PlanSteps.findNode(nodes, "123,456")
        assertTrue(n.x == 123 && n.y == 456)
        try {
            PlanSteps.findNode(nodes, "Not on this screen")
            fail("expected AutopilotError")
        } catch (e: AutopilotError) {
            assertTrue("no such element" in e.message!!)
        }
        val r = Dev(listOf(HOME to "a/.Main", SPORT to "a/.Sport"))
        var out = engine().runPlan(plan("""[{"tap":"Sport"},{"sleep":0},{"step":"Select Bikes"}]"""), r, decide = DecideOptions(stub = true), clock = VClock())
        assertEquals("completed", out.str("status"), out.toString())
        val st0 = out.arr("steps")[0].jsonObject
        assertTrue(st0["action"]!!.jsonObject["hs"]!!.strings()[0] == "tap" && st0["changed"] == JsonPrimitive(true))
        assertTrue("read_ms" in st0["timing"]!!.jsonObject && "verify_ms" in st0["timing"]!!.jsonObject)
        assertTrue(out["timing_total"]!!.jsonObject.keys == TIMING_KEYS.toSet() && out["decisions"]!!.jsonPrimitive.int == 1)
        assertTrue("decide_ms" in out.arr("steps")[2].jsonObject["timing"]!!.jsonObject)
        // missing element → error with the selector in the message, nothing tapped
        val r2 = Dev(listOf(HOME to "a/.Main"))
        out = engine().runPlan(plan("""[{"tap":"Nope"}]"""), r2, decide = DecideOptions(stub = true), clock = VClock())
        assertTrue(out.str("status") == "error" && "Nope" in out["hand_back"]!!.jsonObject.str("error") && r2.calls.none { it.take(1) == listOf("tap") })
    }

    @Test
    fun verifyStopsOnAnyChangeAndAToggleCounts() = runTest {
        val off = listOf(Node("tap", "Switch", "Remember me", "", 10, 20, flags = "click checkable"), Node("-", "TextView", "Log in", "", 10, 5))
        val on = listOf(Node("tap", "Switch", "Remember me", "", 10, 20, flags = "click checkable checked"), off[1])
        val t = ScreenDiff.changed(off, on)
        assertTrue(t.changed && t.any && t.toggled == listOf("Remember me"), "a switch that flips is a change")
        // One new line on a screen of many is not «another screen», but it is something the tap did.
        val many = (1..20).map { Node("-", "TextView", "Row " + ('a' + it), "", 1, it) }
        val plusError = many + Node("-", "TextView", "Wrong password", "", 1, 99)
        val e = ScreenDiff.changed(many, plusError)
        assertTrue(!e.changed && e.any)

        // A tap that changes nothing waits out the window; one that changes the screen stops at the first read.
        val still = engine().runPlan(plan("""[{"tap":"Sport"}]"""), Dev(listOf(HOME to "a/.Main")), decide = DecideOptions(stub = true), clock = VClock())
        val waited = still.arr("steps")[0].jsonObject["timing"]!!.jsonObject["verify_ms"]!!.jsonPrimitive.int
        assertTrue(waited in 1_300..1_600, "no change: about the 1.5 s window, was $waited")
        val moved = engine().runPlan(plan("""[{"tap":"Sport"}]"""), Dev(listOf(HOME to "a/.Main", SPORT to "a/.Sport")), decide = DecideOptions(stub = true), clock = VClock())
        assertEquals(150, moved.arr("steps")[0].jsonObject["timing"]!!.jsonObject["verify_ms"]!!.jsonPrimitive.int, "a change: one read after 150 ms")
    }

    @Test
    fun aPlanSwipesAndTheValidatorChecksTheDirection() = runTest {
        val r = Dev(listOf(HOME to "a/.Main", SPORT to "a/.Sport"))
        val out = engine().runPlan(plan("""[{"swipe":"Up"},{"wait_for":"Bikes","timeout":2}]"""), r, decide = DecideOptions(stub = true), clock = VClock())
        assertEquals("completed", out.str("status"), out.toString())
        assertTrue(listOf("swipe", "up") in r.calls, r.calls.toString())
        val issues = PlanValidator.validate(plan("""[{"swipe":"sideways"},{"swipe":"down"}]"""))
        assertEquals(listOf(0), issues.filter { it.error }.map { it.index }, issues.toString())
    }
}
