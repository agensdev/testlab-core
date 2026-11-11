package no.agens.testlab.core.jev

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Where plan screenshots go. With a report open, shots are numbered and registered in its
 * manifest (`NN-<slug>.jpg`, caption becomes a chapter); without one they are timestamped files.
 */
interface ShotStore {
    fun allocate(caption: String, slug: String): ShotSlot
    /** Called after the screenshot was written to [ShotSlot.path]. */
    fun commit(slot: ShotSlot, caption: String)
}

data class ShotSlot(val path: String, val fileName: String, val registered: Boolean)

/** Everything a plan needs besides the `hs` runner. */
class PlanContext(
    /** Resolves `{"type": {"cred": "acct.field"}}` references; never logged or returned. */
    val cred: ((String) -> String)? = null,
    /** Hides the soft keyboard; null falls back to tapping the lab's chevron position via [runAdb]. */
    val hideKeyboard: (suspend () -> Unit)? = null,
    /** `adb shell …` runner, only for the chevron fallback (lab parity). */
    val runAdb: ((List<String>) -> String)? = null,
    val shots: ShotStore? = null,
    /** Clears an app's data (`"open": …, "fresh": true`); null = a plan can't ask for it here. */
    val clearData: (suspend (pkg: String) -> Unit)? = null,
)

/** Follows a run step by step (the app's Plans screen); the MCP tool doesn't need it. */
interface PlanProgress {
    fun started(index: Int, step: JsonElement) {}
    fun finished(index: Int, record: JsonObject) {}
}

val TIMING_KEYS = listOf("read_ms", "decide_ms", "act_ms", "verify_ms", "wait_ms", "sleep_ms", "type_ms", "other_ms")

object PlanSteps {
    private val XY = Regex("(\\d+)\\s*,\\s*(\\d+)")
    private val SLUG = Regex("[^a-z0-9]+")

    /** Whether [selector] (`#rid`, or a label: exact or contained text/desc) is on screen; never throws. */
    fun present(nodes: List<Node>, selector: String): Boolean {
        val sel = PyText.strip(selector)
        if (sel.startsWith("#")) { val rid = sel.substring(1); return nodes.any { it.rid == rid || it.rid.endsWith("/$rid") } }
        val low = sel.lowercase()
        return nodes.any { low in it.text.lowercase() || low in it.desc.lowercase() }
    }

    /** Scripted selector: `#rid`, `x,y`, or a label (exact text/desc, case-insensitive, clickable first; then any; then substring). */
    fun findNode(nodes: List<Node>, selector: String, nth: Int = 0): Node {
        val sel = PyText.strip(selector)
        XY.matchEntire(sel)?.let { return Node("tap", "xy", sel, "", it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        val hits = if (sel.startsWith("#")) {
            val rid = sel.substring(1)
            nodes.filter { it.rid == rid || it.rid.endsWith("/$rid") || it.rid.endsWith(":id/$rid") }
        } else {
            val low = sel.lowercase()
            fun exact(n: Node) = n.text.lowercase() == low || n.desc.lowercase() == low
            nodes.filter { it.actionable && exact(it) }.ifEmpty { nodes.filter { exact(it) } }
                .ifEmpty { nodes.filter { it.actionable && (low in it.text.lowercase() || low in it.desc.lowercase()) } }
        }
        if (hits.size <= nth) {
            throw JevError("tap ${pyRepr(selector)}: no such element on screen" + if (hits.isNotEmpty()) " (nth=$nth, ${hits.size} hits)" else "")
        }
        return hits[nth]
    }

    fun slug(caption: String): String = PyText.take(SLUG.replace(caption.lowercase(), "-").trim('-'), 40)

    /** Python `repr()` of a str, enough for messages: quote choice and escapes as CPython does. */
    fun pyRepr(s: String): String {
        val q = if ('\'' in s && '"' !in s) '"' else '\''
        val sb = StringBuilder().append(q)
        for (c in s) when {
            c == '\\' -> sb.append("\\\\")
            c == q -> sb.append('\\').append(c)
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 || c.code == 0x7F -> sb.append("\\x").append(c.code.toString(16).padStart(2, '0'))
            else -> sb.append(c)
        }
        return sb.append(q).toString()
    }
}

/** Poll the tree until some node's text, desc or `#rid` contains [needle] (case-insensitive). */
suspend fun JevEngine.waitForText(runHs: HsRunner, needle: String, timeoutS: Double, clock: EngineClock): Double {
    val t0 = clock.nowMs()
    val n = needle.lowercase()
    while (true) {
        val nodes = try { readScreen(runHs).nodes } catch (e: JevError) { emptyList() }
        if (nodes.any { n in (it.text + " " + it.desc + " #" + it.rid).lowercase() }) return pyRound((clock.nowMs() - t0) / 1000.0, 1)
        if (clock.nowMs() - t0 > (timeoutS * 1000).toLong()) {
            throw JevError("wait_for ${PlanSteps.pyRepr(needle)}: not on screen after ${kotlin.math.round(timeoutS).toLong()}s")
        }
        clock.sleep(500)
    }
}

/**
 * Runs a JSON plan (jevpick `run_plan`): returns only when it completes or a step hands back
 * (needs_decision / blocked / stuck / error); resume with `start = paused_at + 1`.
 */
suspend fun JevEngine.runPlan(
    plan: JsonArray,
    runHs: HsRunner,
    ctx: PlanContext = PlanContext(),
    start: Int = 0,
    minConfidence: Double = 0.5,
    decide: DecideOptions = DecideOptions(),
    trace: TraceSink? = null,
    clock: EngineClock = EngineClock.SYSTEM,
    progress: PlanProgress? = null,
): JsonObject {
    val t0 = clock.nowMs()
    val results = mutableListOf<JsonObject>()
    var status = "completed"
    var pausedAt: Int? = null
    var handBack: JsonObject? = null
    val usage = NumberCounter()
    val timingTotal = NumberCounter()
    var decisions = 0
    for ((i, el) in plan.withIndex()) {
        if (i < start) continue
        val ts = clock.nowMs()
        progress?.started(i, el)
        val st = el as? JsonObject ?: JsonObject(emptyMap())
        val rec = LinkedHashMap<String, JsonElement>()
        rec["i"] = JsonPrimitive(i)
        rec["step"] = el
        var tm: Map<String, Long> = emptyMap()
        try {
            // A step for a screen that may or may not come (an age gate accepted earlier, a one-time prompt).
            val gate = (st["if_present"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val absent = gate != null && !PlanSteps.present(readScreen(runHs).nodes, gate)
            when {
                absent -> { rec["status"] = JsonPrimitive("skipped"); rec["reason"] = JsonPrimitive("not on screen: $gate") }
                "goal" in st || "step" in st -> {
                    val g = (st["goal"] as? JsonPrimitive)?.content?.takeIf { pyTruthy(st["goal"]) } ?: (st["step"] as JsonPrimitive).content
                    val opts = StepOptions(minConfidence, decide.copy(allowRisky = pyTruthy(st["allow_risky"])))
                    val r: JsonObject
                    val ok: Boolean
                    if ("step" in st) {
                        r = step(g, runHs, opts, emptyList(), trace, clock).json
                        val s = (r["status"] as JsonPrimitive).content
                        ok = s == "acted" || s == "goal_done"
                    } else {
                        val maxSteps = (st["max_steps"] as? JsonPrimitive)?.asLongLenient()?.toInt() ?: 5
                        r = doGoal(g, runHs, maxSteps, opts, trace, clock)
                        ok = (r["status"] as JsonPrimitive).content == "goal_done"
                    }
                    usage.add((r["usage_total"] as? JsonObject)?.takeIf { it.isNotEmpty() } ?: (r["usage"] as? JsonObject))
                    rec["status"] = r["status"]!!
                    val stepsArr = r["steps"] as? JsonArray
                    rec["detail"] = stepsArr?.takeIf { it.isNotEmpty() } ?: r["action"] ?: JsonNull
                    rec["low_confidence_act"] = r["low_confidence_act"] ?: JsonPrimitive(false)
                    rec["timing"] = r["timing"] ?: JsonObject(emptyMap())
                    tm = (r["timing"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).asLongLenient() ?: 0 } ?: emptyMap()
                    decisions += stepsArr?.size?.takeIf { it > 0 } ?: 1
                    if (!ok && pyTruthy(st["optional"]) && (r["status"] as JsonPrimitive).content in setOf("blocked", "needs_decision", "stuck")) {
                        rec["outcome"] = r["status"]!!
                        rec["status"] = JsonPrimitive("skipped")
                    } else if (!ok) {
                        status = "paused"
                        pausedAt = i
                        handBack = buildJsonObject {
                            for (k in listOf("status", "candidates", "top", "error", "reason", "choice", "confidence")) {
                                val v = r[k]
                                if (v != null && v !is JsonNull) put(k, v)
                            }
                        }
                    }
                }
                "type" in st -> {
                    val v = st["type"]
                    val text: String
                    if (v is JsonObject && "cred" in v) {
                        val lookup = ctx.cred ?: throw JevError("plan uses a credential but no credential lookup is available")
                        val ref = (v["cred"] as JsonPrimitive).content
                        text = lookup(ref)
                        rec["step"] = buildJsonObject { putJsonObject("type") { put("cred", ref) } } // never echo the value
                    } else {
                        text = (v as? JsonPrimitive)?.content ?: v.toString()
                    }
                    val t = clock.nowMs()
                    runHs(listOf("type", text))
                    rec["status"] = JsonPrimitive("ok")
                    tm = mapOf("type_ms" to clock.nowMs() - t)
                    rec["timing"] = timingJson(tm)
                }
                pyTruthy(st["hide_keyboard"]) -> {
                    val hide = ctx.hideKeyboard
                    if (hide != null) hide() else {
                        val adb = ctx.runAdb ?: throw JevError("hide_keyboard needs an adb runner")
                        val g = decide.geometry
                        adb(listOf("shell", "input", "tap", g.sx(195).toString(), g.sy(2990).toString()))
                    }
                    rec["status"] = JsonPrimitive("ok")
                }
                "wait_for" in st -> {
                    val t = clock.nowMs()
                    val timeout = (st["timeout"] as? JsonPrimitive)?.asDoubleLenient() ?: 15.0
                    rec["waited_s"] = JsonPrimitive(waitForText(runHs, (st["wait_for"] as JsonPrimitive).content, timeout, clock))
                    rec["status"] = JsonPrimitive("ok")
                    tm = mapOf("wait_ms" to clock.nowMs() - t)
                    rec["timing"] = timingJson(tm)
                }
                "open" in st -> {
                    // Launch arguments (iOS): strings, or {"cred": "acct.field"} from the vault, never echoed.
                    val args = (st["args"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { v ->
                        if (v is JsonObject && "cred" in v) (ctx.cred ?: throw JevError("plan uses a credential but no credential lookup is available"))((v["cred"] as JsonPrimitive).content)
                        else (v as? JsonPrimitive)?.content ?: v.toString()
                    }
                    val target = (st["open"] as JsonPrimitive).content
                    if (pyTruthy(st["fresh"])) {
                        val clear = ctx.clearData ?: throw JevError("'fresh' (clear the app's data first) is not available here")
                        clear(target.substringBefore('/'))
                        rec["cleared"] = JsonPrimitive(true)
                    }
                    runHs(listOf("open", target) + args)
                    rec["status"] = JsonPrimitive("ok")
                }
                "key" in st -> { runHs(listOf("go", (st["key"] as JsonPrimitive).content)); rec["status"] = JsonPrimitive("ok") }
                "swipe" in st -> { runHs(listOf("swipe", (st["swipe"] as JsonPrimitive).content.lowercase())); rec["status"] = JsonPrimitive("ok") }
                "sleep" in st -> {
                    val s = (st["sleep"] as JsonPrimitive).asDoubleLenient() ?: 0.0
                    clock.sleep((s * 1000).toLong())
                    rec["status"] = JsonPrimitive("ok")
                    tm = mapOf("sleep_ms" to (s * 1000).toLong())
                    rec["timing"] = timingJson(tm)
                }
                "screenshot" in st -> {
                    val caption = (st["screenshot"] as JsonPrimitive).content
                    val store = ctx.shots ?: throw JevError("screenshot needs a place to save it (no report or trace folder)")
                    val slot = store.allocate(caption, PlanSteps.slug(caption))
                    runHs(listOf("see", slot.path, "--size", "1080"))
                    store.commit(slot, caption)
                    if (slot.registered) rec["markdown"] = JsonPrimitive("![$caption](${slot.fileName})")
                    rec["status"] = JsonPrimitive("ok")
                    rec["file"] = JsonPrimitive(slot.path)
                }
                "tap" in st -> { // scripted: a known element, no decision model
                    val tmm = LinkedHashMap<String, Long>()
                    var t = clock.nowMs()
                    val screen = readScreen(runHs)
                    tmm["read_ms"] = clock.nowMs() - t
                    val nth = (st["nth"] as? JsonPrimitive)?.asLongLenient()?.toInt() ?: 0
                    val n = PlanSteps.findNode(screen.nodes, (st["tap"] as JsonPrimitive).content, nth)
                    val args = if (n.x != 0 || n.y != 0) listOf("tap", n.x.toString(), n.y.toString())
                    else if (n.rid.isNotEmpty()) listOf("tap", "#${n.rid}") else listOf("tap", n.text)
                    t = clock.nowMs()
                    runHs(args)
                    tmm["act_ms"] = clock.nowMs() - t
                    rec["status"] = JsonPrimitive("ok")
                    rec["action"] = buildJsonObject {
                        put("hs", JsonArray(args.map(::JsonPrimitive)))
                        put("desc", "Tap ${n.widget} ${PlanSteps.pyRepr(n.text.ifEmpty { n.desc })}")
                    }
                    if (st["verify"]?.let(::pyTruthy) ?: true) {
                        t = clock.nowMs()
                        val v = verify(runHs, screen.nodes, screen.activity, clock)
                        tmm["verify_ms"] = clock.nowMs() - t
                        rec["changed"] = JsonPrimitive(v.change.changed)
                        rec["diff"] = v.change.toDiffJson()
                    }
                    tm = tmm
                    rec["timing"] = timingJson(tmm)
                }
                "note" in st -> rec["status"] = JsonPrimitive("ok")
                else -> throw JevError("unknown plan step: $st")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: JevError) {
            rec["status"] = JsonPrimitive("error"); rec["error"] = JsonPrimitive(errorText(e))
            status = "error"; pausedAt = i; handBack = buildJsonObject { put("error", errorText(e)) }
        } catch (e: Exception) { // device errors
            val msg = hint(errorText(e))
            rec["status"] = JsonPrimitive("error"); rec["error"] = JsonPrimitive(msg)
            status = "error"; pausedAt = i; handBack = buildJsonObject { put("error", msg) }
        }
        val elapsedMs = clock.nowMs() - ts
        rec["elapsed_s"] = JsonPrimitive(pyRound(elapsedMs / 1000.0, 1))
        timingTotal.add(tm)
        timingTotal.add(mapOf("other_ms" to maxOf(0L, elapsedMs - tm.values.sum())))
        val recJson = JsonObject(rec)
        results += recJson
        progress?.finished(i, recJson)
        trace?.write(buildJsonObject { put("tool", "plan"); put("plan_step", recJson) })
        if (status != "completed") break
    }
    val out = buildJsonObject {
        put("status", status)
        put("paused_at", pausedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("hand_back", handBack ?: JsonNull)
        put("steps", JsonArray(results))
        put("n_steps", plan.size)
        put("usage_total", usage.toJson())
        put("elapsed_s", pyRound((clock.nowMs() - t0) / 1000.0, 1))
        putJsonObject("timing_total") { TIMING_KEYS.forEach { put(it, timingTotal.get(it)) } }
        put("decisions", decisions)
        put("trace", trace?.path?.let(::JsonPrimitive) ?: JsonNull)
        if (status == "paused") put("resume_hint", "act on the hand-back yourself, then call again with start=${pausedAt!! + 1}")
    }
    trace?.write(buildJsonObject { put("summary", out) })
    return out
}
