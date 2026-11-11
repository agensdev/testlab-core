package no.agens.testlab.core.jev

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs

/** What [Candidates.build] dropped or capped, for traces and tool results. */
data class CandidateStats(
    val droppedRisky: List<String> = emptyList(),
    val droppedUnlabeled: Int = 0,
    val droppedExcluded: Int = 0,
    val truncated: Boolean = false,
    val nActionable: Int = 0,
    val unparsed: Int = 0,
    val backend: String = "",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("dropped_risky", buildJsonArray { droppedRisky.forEach { add(JsonPrimitive(it)) } })
        put("dropped_unlabeled", droppedUnlabeled)
        put("dropped_excluded", droppedExcluded)
        put("truncated", truncated)
        put("n_actionable", nActionable)
        put("unparsed", unparsed)
        put("backend", backend)
    }
}

private val readingOrder = compareBy<Node>({ it.y }, { it.x })

object Candidates {
    /** Turn actionable nodes into decision options, then the meta and terminal options. */
    fun build(
        nodes: List<Node>,
        maxCandidates: Int = 60,
        allowRisky: Boolean = false,
        exclude: Set<Pair<String, String>> = emptySet(),
        geometry: Geometry = Geometry.REFERENCE,
        riskyRe: Regex = RISKY_RE,
        meta: Map<String, String> = META,
    ): Pair<List<Candidate>, CandidateStats> {
        var nActionable = 0
        var unlabeled = 0
        var excluded = 0
        val seen = HashSet<Pair<String, String>>()
        var picked = mutableListOf<Node>()
        for (n in nodes.filter { it.actionable }.sortedWith(readingOrder)) {
            nActionable++
            if (n.text.isEmpty() && n.rid.isEmpty()) { unlabeled++; continue }
            val ident = n.text to n.rid
            if (ident in seen) continue
            if (ident in exclude) { excluded++; continue }
            seen += ident
            picked += n
        }
        fun risky(n: Node) = riskyRe.containsMatchIn("${n.text} ${n.rid}")

        val droppedRisky = mutableListOf<String>()
        if (!allowRisky) {
            val keep = mutableListOf<Node>()
            for (n in picked) if (risky(n)) droppedRisky += n.label else keep += n
            picked = keep
        }
        var truncated = false
        if (picked.size > maxCandidates) {
            truncated = true
            // drop id-only nodes first, then the lowest on screen
            val withText = picked.filter { it.text.isNotEmpty() }
            val idOnly = picked.filter { it.text.isEmpty() }
            picked = (withText + idOnly).take(maxCandidates).sortedWith(readingOrder).toMutableList()
        }
        val cands = mutableListOf<Candidate>()
        for ((i, n) in picked.withIndex()) {
            val sb = StringBuilder("Tap ").append(n.widget)
            if (n.text.isNotEmpty()) sb.append(" \"").append(PyText.take(n.text, DESC_CUT)).append('"')
            if (n.rid.isNotEmpty()) sb.append(" [#").append(n.rid).append(']')
            if (n.text.isEmpty()) {
                val near = nearbyLabel(n, nodes, geometry)
                if (near.isNotEmpty()) sb.append(" near \"").append(PyText.take(near, DESC_CUT)).append('"')
            }
            // Risk stays on the candidate for the threshold policy, not in the text (a marker biased picks).
            cands += Candidate("n" + (i + 1).toString().padStart(2, '0'), CandidateKind.TAP, sb.toString(), n, risky(n))
        }
        meta.forEach { (k, d) -> cands += Candidate(k, CandidateKind.META, d) }
        TERMINAL.forEach { (k, d) -> cands += Candidate(k, CandidateKind.TERMINAL, d) }
        check(cands.size <= MAX_CRITERIA) { "too many criteria for a choice question" }
        return cands to CandidateStats(droppedRisky, unlabeled, excluded, truncated, nActionable)
    }

    /** Nearest label above an unlabelled input (web forms put the label in a sibling). */
    fun nearbyLabel(n: Node, nodes: List<Node>, geometry: Geometry = Geometry.REFERENCE): String {
        val labelDy = geometry.sy(450)
        val maxDx = geometry.sx(600)
        var best = ""
        var bestDy = labelDy + 1
        for (m in nodes) {
            val t = m.text.ifEmpty { m.desc }
            if (t.isEmpty() || m === n || m.actionable) continue
            val dy = n.y - m.y
            if (dy in 0 until bestDy && abs(m.x - n.x) < maxDx) { best = t; bestDy = dy }
        }
        return best
    }

    /** Max tap candidates that fit next to the meta/terminal options on [backend]. */
    fun maxOptionsFor(backend: Backend, maxCandidates: Int): Int =
        if (backend == Backend.OLLAMA) maxOf(2, minOf(maxCandidates, OLLAMA_MAX_OPTIONS - META.size - TERMINAL.size))
        else maxCandidates
}

object ScreenState {
    fun visibleTexts(nodes: List<Node>, cap: Int = 40): List<String> {
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        for (n in nodes.sortedWith(readingOrder)) {
            for (t in listOf(PyText.take(n.text, TEXT_CUT), PyText.take(n.desc, TEXT_CUT))) {
                if (t.isNotEmpty() && t !in seen && PyText.hasWord(t)) { seen += t; out += t }
            }
            if (out.size >= cap) break
        }
        return out.take(cap)
    }

    /** Labels of nodes flagged `selected` (the open tab); label-less ones borrow from descendants. */
    fun selectedLabels(nodes: List<Node>): List<String> {
        val out = mutableListOf<String>()
        for ((i, n) in nodes.withIndex()) {
            if ("selected" !in n.flags.split(' ').filter { it.isNotEmpty() }) continue
            var label = n.text.ifEmpty { n.desc }
            if (label.isEmpty()) {
                for (d in nodes.subList(i + 1, nodes.size)) {
                    if (d.depth <= n.depth) break
                    if (d.text.isNotEmpty() || d.desc.isNotEmpty()) { label = d.text.ifEmpty { d.desc }; break }
                }
            }
            if (label.isNotEmpty() && label !in out) out += label
        }
        return out.take(5)
    }

    /** The page title: the first short non-actionable text in the top band (below the status bar). */
    fun title(nodes: List<Node>, geometry: Geometry = Geometry.REFERENCE): String {
        val top = geometry.sy(150)
        val bottom = geometry.sy(700)
        for (n in nodes.filter { !it.actionable && it.text.isNotEmpty() && it.y > top && it.y < bottom }.sortedWith(readingOrder)) {
            val len = PyText.len(n.text)
            if (len in 2..40 && PyText.hasWord(n.text)) return n.text
        }
        return ""
    }

    fun build(
        goal: String,
        activity: String,
        nodes: List<Node>,
        candidates: List<Candidate>,
        history: List<String>,
        stepIndex: Int = 1,
        maxSteps: Int = 1,
        config: JevConfig = JevConfig(),
        geometry: Geometry = Geometry.REFERENCE,
    ): JsonObject = buildJsonObject {
        put("goal", goal)
        putJsonObject("screen") {
            put("activity", activity)
            put("title", title(nodes, geometry))
            put("selected", JsonArray(selectedLabels(nodes).map(::JsonPrimitive)))
            put("visible_texts", JsonArray(visibleTexts(nodes, config.maxTexts).map(::JsonPrimitive)))
        }
        putJsonObject("step") {
            put("index", stepIndex)
            put("max", maxSteps)
        }
        if (history.isNotEmpty()) put("history", JsonArray(history.takeLast(HISTORY_TAIL).map(::JsonPrimitive)))
        if (config.stateCandidates) {
            putJsonObject("candidates") { candidates.filter { it.kind == CandidateKind.TAP }.forEach { put(it.key, it.desc) } }
        }
    }
}

object Questions {
    /**
     * Two questions per call (fan-out is free): the action choice and a yes/no "already done?".
     * Ollama's noul wants explicit false/true criteria; TypeSafe's takes none.
     */
    fun build(candidates: List<Candidate>, backend: Backend, config: JevConfig = JevConfig(), allowRisky: Boolean = false): JsonObject = buildJsonObject {
        putJsonObject("next") {
            put("type", "choice")
            put("instructions", if (allowRisky) riskyAllowedInstructions(config.questionInstructions) else config.questionInstructions)
            putJsonObject("criteria") { candidates.forEach { put(it.key, it.desc) } }
        }
        putJsonObject("done") {
            put("type", "noul")
            put("instructions", config.doneInstructions)
            if (backend == Backend.OLLAMA) putJsonObject("criteria") {
                put("false", "No - the goal is not accomplished yet on this screen")
                put("true", "Yes - the screen/section the goal asks for is already open")
            }
        }
    }
}
