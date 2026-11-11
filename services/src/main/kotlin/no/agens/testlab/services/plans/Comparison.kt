package no.agens.testlab.services.plans

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.device.rec.Recording
import no.agens.testlab.services.report.SideBySide
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.runtime.shQuote
import java.io.File

/**
 * The same plan once per decision backend ("arm"), each run recorded from the same start
 * (optionally the app's data cleared first), then one report: a table of time, operator time,
 * hand-backs, decisions, tokens and cost per arm, a player per arm, and the side-by-side clip
 * when ffmpeg is installed. What the lab's benchmarks 4–7 did by hand.
 */
class Comparison(private val rt: LabRuntime) {
    data class ArmResult(val label: String, val run: PlanRun, val video: Recording.Result?)
    data class Result(val reportId: String, val arms: List<ArmResult>, val sideBySide: File?, val page: File)
    data class Progress(val arm: Int, val of: Int, val label: String, val phase: String)

    private val progressFlow = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = progressFlow.asStateFlow()

    suspend fun run(plan: JsonArray, name: String, serial: String, arms: List<BackendChoice>, clearPackage: String? = null, onHandBack: (suspend (PlanRun) -> Resolution)? = null): Result {
        require(arms.isNotEmpty()) { "pick at least one backend" }
        val id = rt.reports.start("Comparison: $name")
        val d = rt.reports.folder(id)
        val results = mutableListOf<ArmResult>()
        rt.recordings.discard(serial) // a stray session recording must not swallow the first arm
        try {
            for ((i, arm) in arms.withIndex()) {
                if (clearPackage != null) {
                    progressFlow.value = Progress(i + 1, arms.size, arm.label, "clearing $clearPackage")
                    rt.ops(serial).clearData(serial, clearPackage)
                }
                progressFlow.value = Progress(i + 1, arms.size, arm.label, "running")
                rt.recordings.start(serial)
                val run = rt.planRuns.run(plan, "$name · ${arm.label}", serial, arm, onHandBack = onHandBack)
                val video = rt.recordings.stop(serial, File(d, "arm-${i + 1}.mp4"))
                video?.let { rt.reports.attachVideo(id, it, arm.label) }
                results += ArmResult(arm.label, run, video)
            }
        } finally {
            progressFlow.value = null
        }
        progressFlow.value = Progress(arms.size, arms.size, "", "writing the report")
        val lanes = results.mapNotNull { r ->
            r.video?.let { SideBySide.Lane(it.file, r.label, num(r.run.result, "elapsed_s") + num(r.run.result, "operator_s")) }
        }
        val sbs = withContext(Dispatchers.IO) { SideBySide.build(lanes, File(d, "side-by-side.mp4"), rt.dirs.cache) }
        val w = rt.reports.write(id, markdown(name, serial, results, sbs, clearPackage), serial = serial, takeRecovered = false)
        progressFlow.value = null
        return Result(id, results, sbs, w.page)
    }

    private fun num(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0

    private fun markdown(name: String, serial: String, rs: List<ArmResult>, sbs: File?, cleared: String?): String = buildString {
        appendLine("Plan **$name** on `$serial`, once per decision backend" + (cleared?.let { ", the app's data (`$it`) cleared before each run" } ?: "") + ".")
        appendLine()
        appendLine("| Backend | Result | Time | Operator | Hand-backs | Decisions | Input tokens | Cost |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (r in rs) {
            val o = r.run.result
            appendLine("| ${r.label} | ${r.run.state.name.lowercase()} | %.1f s | %.1f s | %.0f | %.0f | %.0f | \$%.4f |".format(
                num(o, "elapsed_s"), num(o, "operator_s"), num(o, "hand_backs"), num(o, "decisions"), num(o, "input_tokens"), num(o, "cost_usd")))
        }
        appendLine()
        appendLine("Time is the plan's own run time; operator time is spent resolving hand-backs by hand.")
        sbs?.let { appendLine(); appendLine("Side by side: [side-by-side.mp4](side-by-side.mp4) (each lane from its first frame; «done in» when the plan finished).") }
        appendLine()
        appendLine("### Where the time went")
        appendLine()
        appendLine("| Backend | read | decide | act | verify | wait | sleep | type | other |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        for (r in rs) {
            val t = r.run.result?.get("timing_total") as? JsonObject
            fun s(k: String) = "%.1f".format(((t?.get(k) as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0) / 1000)
            appendLine("| ${r.label} | ${s("read_ms")} | ${s("decide_ms")} | ${s("act_ms")} | ${s("verify_ms")} | ${s("wait_ms")} | ${s("sleep_ms")} | ${s("type_ms")} | ${s("other_ms")} |")
        }
    }
}
