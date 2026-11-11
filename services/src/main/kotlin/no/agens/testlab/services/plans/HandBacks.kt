package no.agens.testlab.services.plans

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.core.jev.Candidates
import no.agens.testlab.core.jev.DumpParser
import no.agens.testlab.services.runtime.LabEvent
import no.agens.testlab.services.runtime.LabRuntime
import java.util.concurrent.ConcurrentHashMap

/**
 * The plans waiting for a person, wherever they ran: one Claude ran (handsets_jev_plan paused, the
 * rest of its plan kept here) and the runs [PlanRuns] holds paused. A person resolves one from
 * the browser (`testlab open handback`): tap what the autopilot couldn't pick, or say it was done
 * by hand, retry the step, or stop.
 */
class HandBacks(private val rt: LabRuntime) {
    data class Pending(
        val id: String,
        val serial: String,
        val step: String,
        val reason: String,
        /** What the autopilot weighed, as it named them: [key, probability, description]. */
        val top: JsonArray,
        val pausedAt: Int,
        val at: Long,
        val source: String,
    )

    /** A candidate of a paused step, where it is on the screen now. */
    data class Mark(val key: String, val label: String, val x: Int, val y: Int)

    private class FromClaude(val e: LabEvent.HandBack, val at: Long)

    private val fromClaude = ConcurrentHashMap<String, FromClaude>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A plan Claude ran paused (one per device: a newer one replaces it). */
    fun add(e: LabEvent.HandBack): String? {
        val serial = e.serial ?: return null
        e.plan ?: return null
        val id = "claude-$serial"
        fromClaude[id] = FromClaude(e, System.currentTimeMillis())
        return id
    }

    fun list(): List<Pending> = fromClaude.map { (id, c) ->
        val paused = (c.e.result["paused_at"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val hb = c.e.result["hand_back"] as? JsonObject
        Pending(id, c.e.serial!!, c.e.plan!!.getOrNull(paused)?.toString() ?: "?", reason(hb), top(hb), paused, c.at, "Claude's plan")
    } + rt.planRuns.runs.value.filter { it.state == PlanRun.State.PAUSED }.map { r ->
        Pending("run-${r.id}", r.serial, r.plan.getOrNull(r.pausedAt ?: 0)?.toString() ?: "?", reason(r.handBack), top(r.handBack), r.pausedAt ?: 0, r.startedMs, r.name)
    }

    /** The paused step's candidates on the screen as it is now (nothing ran since the pause). */
    suspend fun marks(id: String): List<Mark> {
        val p = list().firstOrNull { it.id == id } ?: return emptyList()
        val keys = p.top.mapNotNull { (it as? JsonArray)?.firstOrNull()?.let { k -> (k as JsonPrimitive).content } }.toSet()
        val dev = rt.device(p.serial)
        val (cands, _) = Candidates.build(DumpParser.parse(dev.tree()).nodes, 60, true, emptySet(), rt.geometry(p.serial))
        return cands.filter { it.key in keys && it.node != null }.map { Mark(it.key, it.desc.take(60), it.node!!.x, it.node!!.y) }
    }

    /**
     * Resolves [id]: for a run, the run goes on as [r] says; for Claude's plan, a tap is made and the
     * rest of the plan runs here (it shows up as a run, and pauses there again if it must).
     */
    suspend fun resolve(id: String, r: Resolution): String {
        if (id.startsWith("run-")) {
            val runId = id.removePrefix("run-").toIntOrNull() ?: throw IllegalArgumentException("no hand-back $id")
            if (rt.planRuns.runs.value.none { it.id == runId && it.state == PlanRun.State.PAUSED }) throw IllegalArgumentException("$id isn't waiting any more")
            rt.planRuns.resolve(runId, r)
            return "the run goes on"
        }
        val c = fromClaude.remove(id) ?: throw IllegalArgumentException("$id isn't waiting any more")
        val serial = c.e.serial!!
        val plan = c.e.plan!!
        val paused = (c.e.result["paused_at"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (r is Resolution.Tapped) rt.device(serial).tap(r.x, r.y)
        if (r == Resolution.Stop) return "stopped: Claude's plan won't go on"
        val from = if (r == Resolution.Retry) paused else paused + 1
        if (from >= plan.size) return "done: that was the plan's last step"
        scope.launch { runCatching { rt.planRuns.run(plan, "Claude's plan (resumed)", serial, BackendChoice(rt.settings.current.jev.backend), from) } }
        return "the rest of the plan runs from step $from"
    }

    private fun reason(hb: JsonObject?): String =
        (hb?.get("error") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: (hb?.get("status") as? JsonPrimitive)?.content ?: "paused"

    private fun top(hb: JsonObject?): JsonArray = (hb?.get("top") as? JsonArray) ?: (hb?.get("candidates") as? JsonArray) ?: JsonArray(emptyList())
}
