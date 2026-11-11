package no.agens.testlab.core.jev

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The engine talks to a device the way jevpick.py talked to the `hs` CLI: a list of arguments
 * in, text out (`["ui","--tree"]`, `["show","top"]`, `["tap","540","960"]`, `["swipe","up"]`,
 * `["go","back"]`, `["type", text]`, `["open", pkg]`, `["see", path, "--size", "1080"]`).
 * The device module maps these onto the daemon's wire verbs; tests use a fake. Keeping the
 * shape identical keeps the port and its tests line-for-line comparable with the Python.
 */
fun interface HsRunner {
    suspend operator fun invoke(args: List<String>): String
}

/** Options for one decision; the defaults mirror jevpick's `decide()` keyword arguments. */
data class DecideOptions(
    val maxCandidates: Int = 60,
    val allowRisky: Boolean = false,
    val exclude: Set<Pair<String, String>> = emptySet(),
    val stepIndex: Int = 1,
    val maxSteps: Int = 1,
    val backend: Backend? = null,
    val stub: Boolean = false,
    val model: String? = null,
    val geometry: Geometry = Geometry.REFERENCE,
)

data class Decided(val decision: Decision, val candidates: List<Candidate>, val state: JsonObject, val stats: CandidateStats)

data class Screen(val dump: String, val activity: String, val nodes: List<Node>)

class JevEngine(val config: JevConfig, val client: DecisionClient) {

    suspend fun decide(goal: String, dump: String, activity: String, history: List<String>, opts: DecideOptions = DecideOptions()): Decided {
        val parsed = DumpParser.parse(dump)
        if (parsed.nodes.isEmpty()) throw JevError("empty ui dump (FLAG_SECURE screen or app not loaded) - refusing to guess")
        val backend = opts.backend ?: config.backend
        val cap = Candidates.maxOptionsFor(backend, opts.maxCandidates)
        val (cands, stats0) = Candidates.build(parsed.nodes, cap, opts.allowRisky, opts.exclude, opts.geometry, config.riskyRe, config.meta)
        val stats = stats0.copy(unparsed = parsed.unparsed.size, backend = backend.id)
        val state = ScreenState.build(goal, activity, parsed.nodes, cands, history, opts.stepIndex, opts.maxSteps, config, opts.geometry)
        val question = Questions.build(cands, backend, config, opts.allowRisky)
        val decision = client.ask(state, question, backend, opts.stub, opts.model)
        val criteria = question["next"]!!.jsonObject["criteria"]!!.jsonObject
        if (decision.choice !in criteria) throw JevError("Jev chose unknown key '${decision.choice}'")
        return Decided(decision, cands, state, stats)
    }

    /** `hs ui --tree` + `hs show top`, parsed. */
    suspend fun readScreen(runHs: HsRunner): Screen {
        val dump = runHs(listOf("ui", "--tree"))
        val activity = try {
            PyText.take(PyText.norm(runHs(listOf("show", "top"))), 160)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ""
        }
        val nodes = DumpParser.parse(dump).nodes
        return Screen(dump, activity, nodes)
    }

    companion object {
        /** What executing a candidate means in `hs` terms; empty for terminal options. */
        fun toHsArgs(c: Candidate): List<String> = when (c.kind) {
            CandidateKind.TERMINAL -> emptyList()
            CandidateKind.META -> when (c.key) {
                "scroll_down" -> listOf("swipe", "up")
                "scroll_up" -> listOf("swipe", "down")
                "back" -> listOf("go", "back")
                else -> error("unknown meta option ${c.key}")
            }
            CandidateKind.TAP -> {
                val n = c.node!!
                if (n.x != 0 || n.y != 0) listOf("tap", n.x.toString(), n.y.toString())
                else if (n.rid.isNotEmpty()) listOf("tap", "#${n.rid}") else listOf("tap", n.text)
            }
        }
    }
}

/** Screen identity with live numbers (odds, balances, clocks) normalised away. */
object ScreenDiff {
    private val DIGITS = Regex("(?U)\\d+")

    data class Sig(val widget: String, val rid: String, val text: String)

    /** Insertion-ordered (screen order), so added/removed lists are deterministic; see PARITY.md. */
    fun sig(nodes: List<Node>): LinkedHashSet<Sig> =
        nodes.mapTo(LinkedHashSet()) { Sig(it.widget, it.rid, DIGITS.replace(it.text, "#")) }

    fun fingerprint(nodes: List<Node>): String =
        PyText.take(sha1Hex(sig(nodes).map { "${it.widget}/${it.rid}/${it.text}" }.sortedWith(PyText.codePointOrder).joinToString("|")), 12)

    /**
     * [changed]: the screen is another one (Jaccard below [JACCARD_SAME], or another activity), or a
     * switch or tab on it flipped ([toggled]). [any]: anything at all differs, which is what a wait
     * for a tap's effect stops on.
     */
    data class Change(
        val changed: Boolean, val jaccard: Double, val activityChanged: Boolean, val added: List<String>, val removed: List<String>,
        val toggled: List<String> = emptyList(), val any: Boolean = changed,
    )

    private val STATE_WORDS = setOf("checked", "selected")

    /** checked/selected per element, for the elements that have either word somewhere. */
    private fun states(nodes: List<Node>): Map<Sig, Set<String>> =
        nodes.associate { Sig(it.widget, it.rid, DIGITS.replace(it.text, "#")) to it.flags.split(' ', ',').filterTo(HashSet()) { w -> w in STATE_WORDS } }

    fun changed(before: List<Node>, after: List<Node>, beforeAct: String = "", afterAct: String = ""): Change {
        val sb = sig(before)
        val sa = sig(after)
        val union = sb + sa
        val jac = if (union.isNotEmpty()) (sb intersect sa).size.toDouble() / union.size else 1.0
        val added = (sa - sb).map { it.text }.filter { it.isNotEmpty() }.take(8)
        val removed = (sb - sa).map { it.text }.filter { it.isNotEmpty() }.take(8)
        val actChanged = beforeAct.isNotEmpty() && afterAct.isNotEmpty() && beforeAct != afterAct
        val stb = states(before); val sta = states(after)
        val toggled = (sb intersect sa).filter { stb[it] != sta[it] }.map { it.text.ifEmpty { "#" + it.rid } }.take(8)
        val changed = actChanged || jac < JACCARD_SAME || toggled.isNotEmpty()
        return Change(changed, pyRound(jac, 3), actChanged, added, removed, toggled, changed || sb != sa)
    }
}
