package no.agens.testlab.core.autopilot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Shared result fields of pick/step (jevpick `_pick_dict`). */
internal fun pickDict(decision: Decision, cands: List<Candidate>, stats: CandidateStats, activity: String, trace: TraceSink?, defaultBackend: Backend): JsonObject {
    val byKey = cands.associateBy { it.key }
    val chosen = byKey[decision.choice]
    return buildJsonObject {
        put("choice", decision.choice)
        put("confidence", pyRound(decision.confidence, 3))
        put("done_prob", decision.done?.let { JsonPrimitive(pyRound(it, 3)) } ?: JsonNull)
        if (chosen != null && chosen.kind != CandidateKind.TERMINAL) {
            putJsonObject("action") {
                put("hs", JsonArray(AutopilotEngine.toHsArgs(chosen).map(::JsonPrimitive)))
                put("desc", chosen.desc)
            }
        } else put("action", JsonNull)
        put("top", buildJsonArray {
            for ((k, p) in decision.top()) add(buildJsonArray { add(JsonPrimitive(k)); add(JsonPrimitive(pyRound(p, 3))); add(JsonPrimitive(byKey[k]?.desc ?: "?")) })
        })
        put("n_candidates", cands.count { it.kind == CandidateKind.TAP })
        put("dropped_risky", JsonArray(stats.droppedRisky.map(::JsonPrimitive)))
        put("truncated", stats.truncated)
        put("activity", activity)
        put("model", decision.model)
        val backend = (decision.raw["_backend"] as? JsonPrimitive)?.content
            ?: if (decision.raw["stub"] != null) "stub" else defaultBackend.id
        put("backend", backend)
        put("usage", decision.usage)
        put("latency_ms", decision.latencyMs)
        put("trace", trace?.path?.let(::JsonPrimitive) ?: JsonNull)
    }
}

/** Ask which node advances the goal. No device action. */
suspend fun AutopilotEngine.pick(goal: String, runHs: HsRunner, serial: String = "", opts: DecideOptions = DecideOptions(), traces: TraceFactory? = null): JsonObject {
    val trace = traces?.open(serial, "pick")
    val screen = readScreen(runHs)
    val d = decide(goal, screen.dump, screen.activity, emptyList(), opts)
    val out = pickDict(d.decision, d.candidates, d.stats, screen.activity, trace, config.backend)
    trace?.write(buildJsonObject {
        put("tool", "pick"); put("goal", goal); put("activity", screen.activity); put("state", d.state)
        put("stats", d.stats.toJson())
        putJsonObject("criteria") { d.candidates.forEach { put(it.key, it.desc) } }
        put("response", d.decision.raw); put("latency_ms", d.decision.latencyMs); put("choice", d.decision.choice)
    })
    return out
}
