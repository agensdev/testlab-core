package no.agens.testlab.core.autopilot

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Wall clock and sleep for the engine; tests pass a virtual clock. */
interface EngineClock {
    fun nowMs(): Long
    suspend fun sleep(ms: Long)

    companion object {
        val SYSTEM = object : EngineClock {
            override fun nowMs() = currentTimeMs()
            override suspend fun sleep(ms: Long) = kotlinx.coroutines.delay(ms)
        }
    }
}

internal expect fun currentTimeMs(): Long

/** One step's outcome: the tool-result JSON plus what the loop needs internally. */
class StepOutcome(val json: JsonObject, val chosen: Candidate, val afterNodes: List<Node>?) {
    val status: String get() = (json["status"] as JsonPrimitive).content
    val changed: Boolean? get() = (json["changed"] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content == "true" }
}

/** Appends " -> call use_device…" when the error means the daemon is not running (autopilot.py `_hint`). */
fun hint(msg: String): String =
    if ("no forwarded daemon" in msg || "no daemon on" in msg) "$msg -> call use_device(serial) first (starts the daemon on the device)" else msg

internal fun errorText(e: Throwable): String = e.message ?: e::class.simpleName ?: "error"

/** Accumulates numeric usage/timing fields like Python's Counter.update. */
internal class NumberCounter {
    private val values = LinkedHashMap<String, Double>()
    fun add(obj: JsonObject?) {
        obj?.forEach { (k, v) -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.let { values[k] = (values[k] ?: 0.0) + it } }
    }
    fun add(map: Map<String, Long>) = map.forEach { (k, v) -> values[k] = (values[k] ?: 0.0) + v }
    fun get(k: String): Long = (values[k] ?: 0.0).toLong()
    fun toJson(): JsonObject = buildJsonObject { values.forEach { (k, v) -> if (v == kotlin.math.floor(v)) put(k, v.toLong()) else put(k, v) } }
}

internal fun timingJson(tm: Map<String, Long>): JsonObject = buildJsonObject { tm.forEach { (k, v) -> put(k, v) } }

private fun JsonObject.usage(): JsonObject? = this["usage"] as? JsonObject

/** Policy knobs per call; the others come from [AutopilotConfig]. */
data class StepOptions(
    val minConfidence: Double = 0.5,
    val decide: DecideOptions = DecideOptions(),
)

/**
 * Pick + execute one action + verify the screen changed (autopilot.py `step`). The policy decides
 * whether the model's answer is acted on, accepted as "done" or handed back to the caller.
 */
suspend fun AutopilotEngine.step(
    goal: String,
    runHs: HsRunner,
    opts: StepOptions = StepOptions(),
    history: List<String> = emptyList(),
    trace: TraceSink? = null,
    clock: EngineClock = EngineClock.SYSTEM,
): StepOutcome {
    val tm = LinkedHashMap<String, Long>()
    var t = clock.nowMs()
    val screen = readScreen(runHs)
    tm["read_ms"] = clock.nowMs() - t
    t = clock.nowMs()
    val d = decide(goal, screen.dump, screen.activity, history, opts.decide)
    tm["decide_ms"] = clock.nowMs() - t
    val decision = d.decision
    val cands = d.candidates
    val out = LinkedHashMap<String, JsonElement>(pickDict(decision, cands, d.stats, screen.activity, trace, config.backend))
    out["timing"] = JsonNull // placeholder keeps the key position; filled at the end like Python's shared dict
    val byKey = cands.associateBy { it.key }
    var chosen = byKey.getValue(decision.choice)
    out["executed"] = JsonPrimitive(false)
    out["changed"] = JsonNull
    out["diff"] = JsonNull
    out["after_activity"] = JsonPrimitive(screen.activity)
    out["fingerprint"] = JsonPrimitive(ScreenDiff.fingerprint(screen.nodes))
    val rec = LinkedHashMap<String, JsonElement>()
    rec["tool"] = JsonPrimitive("step"); rec["step"] = JsonPrimitive(opts.decide.stepIndex); rec["goal"] = JsonPrimitive(goal)
    rec["activity"] = JsonPrimitive(screen.activity); rec["state"] = d.state; rec["stats"] = d.stats.toJson()
    rec["criteria"] = buildJsonObject { cands.forEach { put(it.key, it.desc) } }
    rec["response"] = decision.raw; rec["latency_ms"] = JsonPrimitive(decision.latencyMs); rec["choice"] = JsonPrimitive(decision.choice)
    val done = decision.done ?: -1.0
    val conf = decision.confidence
    val minConf = opts.minConfidence

    fun harmless(c: Candidate): Boolean =
        c.kind == CandidateKind.TAP && !c.risky && !(c.node != null && config.leaveRe.containsMatchIn(c.node.text.ifEmpty { c.node.desc }))

    fun bestHarmless(floor: Double): Candidate? {
        for ((k, p) in decision.top(cands.size)) {
            val c = byKey[k] ?: continue
            if (c.kind == CandidateKind.TAP) return if (p >= floor && harmless(c)) c else null
        }
        return null
    }

    fun switchTo(c: Candidate, why: String) {
        chosen = c
        out["choice"] = JsonPrimitive(c.key)
        out["fallback_from"] = JsonPrimitive(why)
        out["action"] = buildJsonObject { put("hs", JsonArray(AutopilotEngine.toHsArgs(c).map(::JsonPrimitive))); put("desc", c.desc) }
    }

    val action = isActionGoal(goal) // "Tap X" / "Press X": the done-question is meaningless, never gate on it
    when {
        done >= config.doneThreshold && !action -> out["status"] = JsonPrimitive("goal_done") // the yes/no is the authority on completion
        chosen.key == "goal_done" && action -> {
            val alt = bestHarmless(config.fallbackMin) // an action step must act: take the best real candidate
            if (alt != null) switchTo(alt, "goal_done") else out["status"] = JsonPrimitive("needs_decision")
        }
        chosen.key == "goal_done" -> {
            // "which option is best": judge by the option's probability (confidence only measures spread)
            val pDone = maxOf(conf, decision.probabilities["goal_done"] ?: 0.0)
            if (pDone >= minConf && (decision.done == null || done >= config.doneVeto)) {
                out["status"] = JsonPrimitive("goal_done")
            } else { // the choice says done but the yes/no disagrees: best real candidate, else hand back
                val alt = bestHarmless(config.fallbackMin)
                if (alt != null) switchTo(alt, "goal_done") else out["status"] = JsonPrimitive("needs_decision")
            }
        }
        chosen.key == "blocked" -> {
            if (conf >= minConf) out["status"] = JsonPrimitive("blocked")
            else { // a weak "none of these" loses to a plausible harmless tap
                val alt = bestHarmless(config.harmlessMin)
                if (alt != null) { switchTo(alt, "blocked"); out["low_confidence_act"] = JsonPrimitive(true) }
                else out["status"] = JsonPrimitive("needs_decision")
            }
        }
        chosen.kind == CandidateKind.META -> if (conf < minConf) out["status"] = JsonPrimitive("needs_decision")
        chosen.kind == CandidateKind.TAP -> when {
            chosen.risky && conf < minConf -> out["status"] = JsonPrimitive("needs_decision") // payments never act below the threshold
            conf < minConf -> {
                if (conf >= config.harmlessMin && harmless(chosen)) out["low_confidence_act"] = JsonPrimitive(true) // reversible argmax tap
                else out["status"] = JsonPrimitive("needs_decision")
            }
        }
    }
    var afterNodes: List<Node>? = null
    if ("status" !in out) {
        var args = AutopilotEngine.toHsArgs(chosen)
        t = clock.nowMs()
        try {
            runHs(args)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The tree lists nodes below (or above) the visible screen: scroll towards it, and the next
            // step chooses again on the screen that brings, instead of failing the goal.
            val off = OFF_SCREEN.find(e.message.orEmpty()) ?: throw e
            val (y, h) = off.groupValues[1].toInt() to off.groupValues[2].toInt()
            args = listOf("swipe", if (y >= h) "up" else "down")
            runHs(args)
            out["scrolled_toward"] = JsonPrimitive(chosen.desc)
        }
        tm["act_ms"] = clock.nowMs() - t
        t = clock.nowMs()
        val v = verify(runHs, screen.nodes, screen.activity, clock)
        tm["verify_ms"] = clock.nowMs() - t
        out["executed"] = JsonPrimitive(true)
        out["status"] = JsonPrimitive("acted")
        out["changed"] = JsonPrimitive(v.change.changed)
        out["diff"] = v.change.toDiffJson()
        out["after_activity"] = JsonPrimitive(v.afterActivity)
        afterNodes = v.after
        rec["action"] = buildJsonObject { put("hs", JsonArray(args.map(::JsonPrimitive))); put("desc", chosen.desc) }
        rec["verify"] = buildJsonObject {
            put("changed", v.change.changed)
            v.change.toDiffJson().forEach { (k, e) -> put(k, e) }
        }
    }
    out["timing"] = timingJson(tm)
    rec["outcome"] = out["status"]!!
    rec["timing"] = timingJson(tm)
    trace?.write(JsonObject(rec))
    return StepOutcome(JsonObject(out), chosen, afterNodes)
}

/** The daemon's refusal of a tap outside the display: `OFF_SCREEN:x=720 y=3569 display=1440x3120`. */
private val OFF_SCREEN = Regex("""OFF_SCREEN\S*\s*:?.*?y=(-?\d+)\s+display=\d+x(\d+)""")

internal fun ScreenDiff.Change.toDiffJson(): JsonObject = buildJsonObject {
    put("jaccard", jaccard)
    put("activity_changed", activityChanged)
    put("added", JsonArray(added.map(::JsonPrimitive)))
    put("removed", JsonArray(removed.map(::JsonPrimitive)))
    if (toggled.isNotEmpty()) put("toggled", JsonArray(toggled.map(::JsonPrimitive)))
}

internal class Verified(val change: ScreenDiff.Change, val after: List<Node>, val afterActivity: String)

/** Sleep 0.3 s, then poll up to 4 times 0.5 s apart until the screen changed (autopilot.py `_verify`). */
/**
 * What a tap did: the screen is read every [pollMs] from [firstMs] after it until anything differs
 * (a switch flipping or a line of text appearing counts, not only a new screen), [windowMs] at
 * most. A tap that changes nothing costs the whole window, one that does costs about one read.
 */
internal suspend fun AutopilotEngine.verify(
    runHs: HsRunner, before: List<Node>, beforeAct: String, clock: EngineClock,
    firstMs: Long = 150, pollMs: Long = 200, windowMs: Long = 1_500,
): Verified {
    val t0 = clock.nowMs()
    clock.sleep(firstMs)
    var after = before
    var afterAct = beforeAct
    var change = ScreenDiff.changed(before, before)
    while (true) {
        after = try {
            val s = readScreen(runHs)
            afterAct = s.activity
            s.nodes
        } catch (e: AutopilotError) {
            emptyList()
        }
        change = ScreenDiff.changed(before, after, beforeAct, afterAct)
        if (change.any || clock.nowMs() - t0 + pollMs > windowMs) break
        clock.sleep(pollMs)
    }
    return Verified(change, after, afterAct)
}

/** Loop [step] until goal_done / blocked / needs_decision / stuck / max_steps / deadline / error (autopilot.py `do`). */
suspend fun AutopilotEngine.doGoal(
    goal: String,
    runHs: HsRunner,
    maxSteps: Int = 12,
    opts: StepOptions = StepOptions(),
    trace: TraceSink? = null,
    clock: EngineClock = EngineClock.SYSTEM,
): JsonObject {
    val t0 = clock.nowMs()
    val history = mutableListOf<String>()
    val steps = mutableListOf<JsonObject>()
    val usage = NumberCounter()
    val tried = HashMap<String, MutableSet<Pair<String, String>>>()
    val seenFp = HashMap<String, Int>()
    var nochange = 0
    var status = "max_steps"
    val extra = LinkedHashMap<String, JsonElement>()
    var finalActivity = ""
    val timing = NumberCounter()
    for (i in 1..maxSteps) {
        if (clock.nowMs() - t0 > (config.deadlineS * 1000).toLong()) { status = "deadline"; break }
        val fp: String
        try {
            val t = clock.nowMs()
            val probe = readScreen(runHs).nodes
            timing.add(mapOf("read_ms" to clock.nowMs() - t))
            fp = ScreenDiff.fingerprint(probe)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AutopilotError) {
            status = "error"; extra["error"] = JsonPrimitive(errorText(e)); break
        } catch (e: Exception) {
            status = "error"; extra["error"] = JsonPrimitive(hint(errorText(e))); break
        }
        seenFp[fp] = (seenFp[fp] ?: 0) + 1
        if (seenFp.getValue(fp) >= 3) { status = "stuck"; extra["reason"] = JsonPrimitive("same screen seen 3 times"); break }
        val r: StepOutcome
        try {
            r = step(
                goal, runHs,
                opts.copy(decide = opts.decide.copy(stepIndex = i, maxSteps = maxSteps, exclude = tried[fp].orEmpty())),
                history, trace, clock,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AutopilotError) {
            status = "error"; extra["error"] = JsonPrimitive(errorText(e)); break
        } catch (e: Exception) {
            status = "error"; extra["error"] = JsonPrimitive(hint(errorText(e))); break
        }
        usage.add(r.json.usage())
        timing.add(r.json["timing"] as? JsonObject)
        val afterAct = (r.json["after_activity"] as? JsonPrimitive)?.content.orEmpty()
        val act = (r.json["activity"] as? JsonPrimitive)?.content.orEmpty()
        finalActivity = afterAct.ifEmpty { act.ifEmpty { finalActivity } }
        steps += buildJsonObject {
            put("i", i); put("choice", r.json["choice"]!!); put("desc", r.chosen.desc); put("confidence", r.json["confidence"]!!)
            put("changed", r.json["changed"] ?: JsonNull); put("status", r.status)
        }
        if (r.status == "goal_done" || r.status == "blocked") { status = r.status; break }
        if (r.status == "needs_decision") {
            status = "needs_decision"; extra["candidates"] = r.json["top"]!!; extra["n_candidates"] = r.json["n_candidates"]!!; break
        }
        val changed = r.changed == true
        history += "$i: ${r.chosen.desc} -> ${if (changed) "screen changed" else "no change"}"
        if (changed && isActionGoal(goal) && r.chosen.kind == CandidateKind.TAP) {
            status = "goal_done"; extra["note"] = JsonPrimitive("action goal: done after the tap changed the screen"); break
        }
        if (changed) nochange = 0 else {
            nochange++
            tried.getOrPut(fp) { mutableSetOf() } += r.chosen.ident
            if (nochange >= 2) { status = "stuck"; extra["reason"] = JsonPrimitive("two consecutive actions changed nothing"); break }
        }
    }
    val out = buildJsonObject {
        put("status", status); put("goal", goal); put("steps", JsonArray(steps)); put("final_activity", finalActivity)
        put("usage_total", usage.toJson()); put("elapsed_s", pyRound((clock.nowMs() - t0) / 1000.0, 1)); put("timing", timing.toJson())
        put("trace", trace?.path?.let(::JsonPrimitive) ?: JsonNull)
        extra.forEach { (k, v) -> put(k, v) }
    }
    trace?.write(buildJsonObject { put("summary", out) })
    return out
}

/** For results built from Python-style truthiness (`bool(st.get("allow_risky"))`). */
/** Python truthiness of a JSON value (plans written for autopilot.py rely on it). */
fun pyTruthy(e: JsonElement?): Boolean = when (e) {
    null, JsonNull -> false
    is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else (e.content != "false" && e.content != "0" && e.content != "0.0")
    is JsonArray -> e.isNotEmpty()
    is JsonObject -> e.isNotEmpty()
}

internal fun JsonPrimitive.asDoubleLenient(): Double? = doubleOrNull ?: content.toDoubleOrNull()
internal fun JsonPrimitive.asLongLenient(): Long? = longOrNull ?: content.toDoubleOrNull()?.toLong()
