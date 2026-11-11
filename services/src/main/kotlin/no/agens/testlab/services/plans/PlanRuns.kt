package no.agens.testlab.services.plans

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import no.agens.testlab.core.jev.Backend
import no.agens.testlab.core.jev.DecideOptions
import no.agens.testlab.core.jev.PlanContext
import no.agens.testlab.core.jev.PlanProgress
import no.agens.testlab.core.jev.ShotStore
import no.agens.testlab.core.jev.TIMING_KEYS
import no.agens.testlab.core.jev.runPlan
import no.agens.testlab.services.report.DirShotStore
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.SecretStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** What the user does about a hand-back. */
sealed interface Resolution {
    /** Tapped [x],[y] (the step is done by hand): go on with the next step. */
    data class Tapped(val x: Int, val y: Int) : Resolution
    /** Done by hand some other way: go on with the next step. */
    data object Skip : Resolution
    /** Run the same step again (the screen was not ready). */
    data object Retry : Resolution
    data object Stop : Resolution
}

/** A plan run as the Plans screen shows it. */
data class PlanRun(
    val id: Int,
    val name: String,
    val serial: String,
    val backend: String,
    val plan: JsonArray,
    val state: State,
    val current: Int? = null,
    val steps: Map<Int, JsonObject> = emptyMap(),
    val handBack: JsonObject? = null,
    val pausedAt: Int? = null,
    val result: JsonObject? = null,
    val startedMs: Long = System.currentTimeMillis(),
    val operatorMs: Long = 0,
) {
    enum class State { RUNNING, PAUSED, COMPLETED, STOPPED, ERROR }
}

/** Decision options for one run: the backend by name ("typesafe", "ollama", "openai", "stub", "ollama|url|model" or "openai|model"). */
data class BackendChoice(val id: String) {
    val label: String get() = when {
        id == "typesafe" -> "TypeSafe Jev"
        id == "stub" -> "Stub (first option)"
        id.startsWith("ollama|") -> "Ollama ${id.substringAfterLast('|')}"
        id == "ollama" -> "Ollama"
        id.startsWith("openai|") -> "OpenAI ${id.substringAfter('|')}"
        id == "openai" -> "OpenAI Decisions"
        else -> id
    }
}

/**
 * Runs plans for the app: every step reported as it starts and ends, and a hand-back that
 * waits for the user instead of returning. The user taps on the screenshot (or skips, retries,
 * stops) and the run goes on from there; the time waiting is the operator's, counted apart.
 */
class PlanRuns(private val rt: LabRuntime) {
    private val ids = AtomicInteger()
    private val runFlow = MutableStateFlow<List<PlanRun>>(emptyList())
    val runs: StateFlow<List<PlanRun>> = runFlow.asStateFlow()
    private val waiting = HashMap<Int, CompletableDeferred<Resolution>>()

    private fun update(id: Int, f: (PlanRun) -> PlanRun) = runFlow.update { l -> l.map { if (it.id == id) f(it) else it } }

    fun resolve(id: Int, r: Resolution) { synchronized(waiting) { waiting.remove(id) }?.complete(r) }

    /** Engine and decision options for [backend]; "ollama|url|model" points one run at a specific endpoint. */
    private fun engineFor(backend: BackendChoice) = when {
        backend.id.startsWith("ollama|") -> {
            val entry = backend.id.removePrefix("ollama|")
            val cfg = rt.settings.current.jev.toConfig().copy(backend = Backend.OLLAMA, ollamaUrls = listOf(entry))
            no.agens.testlab.core.jev.JevEngine(cfg, no.agens.testlab.core.jev.DecisionClient(cfg, no.agens.testlab.services.http.KtorHttpPost(), apiKey = { null }, nowMs = System::currentTimeMillis))
        }
        backend.id.startsWith("openai|") -> {
            val cfg = rt.settings.current.jev.toConfig().copy(backend = Backend.OPENAI, openaiModel = backend.id.substringAfter('|'))
            no.agens.testlab.core.jev.JevEngine(cfg, no.agens.testlab.core.jev.DecisionClient(
                cfg, no.agens.testlab.services.http.KtorHttpPost(), apiKey = { null }, nowMs = System::currentTimeMillis,
                openaiKey = { rt.secrets.get(no.agens.testlab.services.secrets.SecretStore.OPENAI_KEY) ?: System.getenv("OPENAI_API_KEY") },
            ))
        }
        else -> rt.engine()
    } to when {
        backend.id == "typesafe" -> Backend.TYPESAFE
        backend.id == "ollama" -> Backend.OLLAMA
        backend.id == "openai" || backend.id.startsWith("openai|") -> Backend.OPENAI
        else -> null
    }

    /**
     * Runs [plan] on [serial] until it completes, errors or is stopped; hand-backs wait for
     * [resolve] (the UI) unless [onHandBack] decides. Returns the merged result.
     */
    suspend fun run(
        plan: JsonArray,
        name: String,
        serial: String,
        backend: BackendChoice,
        start: Int = 0,
        shots: ShotStore? = null,
        onHandBack: (suspend (PlanRun) -> Resolution)? = null,
    ): PlanRun {
        val id = ids.incrementAndGet()
        runFlow.update { (it + PlanRun(id, name, serial, backend.label, plan, PlanRun.State.RUNNING)).takeLast(30) }
        val dev = rt.device(serial)
        val (engine, be) = engineFor(backend)
        val ctx = PlanContext(cred = { ref -> credential(ref, serial) }, hideKeyboard = { dev.hideKeyboard() }, shots = shots ?: DirShotStore(File(rt.reportsDir(), "_jev_shots")), clearData = { pkg -> rt.ops(serial).clearData(serial, pkg) })
        val progress = object : PlanProgress {
            override fun started(index: Int, step: JsonElement) = update(id) { it.copy(current = index) }
            override fun finished(index: Int, record: JsonObject) = update(id) { it.copy(steps = it.steps + (index to record)) }
        }
        val trace = rt.traces.open(serial, "plan")
        val segments = mutableListOf<JsonObject>()
        var from = start
        var operator = 0L
        var state = PlanRun.State.COMPLETED
        while (true) {
            val r = engine.runPlan(
                plan, dev.asHsRunner(), ctx, from, rt.settings.current.jev.minConfidence,
                DecideOptions(backend = be, stub = backend.id == "stub", geometry = rt.geometry(serial)), trace, progress = progress,
            )
            segments += r
            val status = (r["status"] as JsonPrimitive).content
            if (status == "completed") break
            val pausedAt = (r["paused_at"] as? JsonPrimitive)?.longOrNull?.toInt() ?: break
            update(id) { it.copy(state = PlanRun.State.PAUSED, handBack = r["hand_back"] as? JsonObject, pausedAt = pausedAt) }
            val t = System.currentTimeMillis()
            val d = CompletableDeferred<Resolution>()
            synchronized(waiting) { waiting[id] = d }
            val res = onHandBack?.invoke(runFlow.value.first { it.id == id }) ?: d.await()
            synchronized(waiting) { waiting.remove(id) }
            operator += System.currentTimeMillis() - t
            when (res) {
                is Resolution.Tapped -> { dev.tap(res.x, res.y); from = pausedAt + 1 }
                Resolution.Skip -> from = pausedAt + 1
                Resolution.Retry -> from = pausedAt
                Resolution.Stop -> { state = if (status == "error") PlanRun.State.ERROR else PlanRun.State.STOPPED; break }
            }
            update(id) { it.copy(state = PlanRun.State.RUNNING, handBack = null, pausedAt = null) }
            if (from >= plan.size) break
        }
        val merged = merge(segments, plan.size, state, operator)
        update(id) { it.copy(state = state, result = merged, current = null, operatorMs = operator) }
        return runFlow.value.first { it.id == id }
    }

    private fun credential(ref: String, serial: String): String {
        if (ref == "device_pin") rt.secrets.get(SecretStore.devicePin(serial))?.takeIf { it.isNotEmpty() }?.let { return it }
        return rt.credentials.resolve(ref, serial)
    }

    companion object {
        /** TypeSafe's price per million input tokens (lab_jev_stats uses the same). */
        const val USD_PER_M_INPUT = 0.042

        /** One result for a run that paused and went on: all steps, summed timing and usage, operator time apart. */
        fun merge(segments: List<JsonObject>, nSteps: Int, state: PlanRun.State, operatorMs: Long): JsonObject {
            val steps = segments.flatMap { (it["steps"] as? JsonArray).orEmpty() }
            fun sum(key: String, field: String) = segments.sumOf { s -> ((s[key] as? JsonObject)?.get(field) as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0 }
            val elapsed = segments.sumOf { (it["elapsed_s"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0 }
            val tokens = sum("usage_total", "input_tokens")
            return buildJsonObject {
                put("status", state.name.lowercase())
                put("steps", JsonArray(steps))
                put("n_steps", nSteps)
                put("elapsed_s", Math.round(elapsed * 10) / 10.0)
                put("operator_s", Math.round(operatorMs / 100.0) / 10.0)
                put("hand_backs", segments.size - 1 + if (state == PlanRun.State.COMPLETED) 0 else 1)
                put("decisions", segments.sumOf { (it["decisions"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 })
                put("input_tokens", tokens.toLong())
                put("cost_usd", Math.round(tokens * USD_PER_M_INPUT / 1e6 * 1e5) / 1e5)
                putJsonObject("timing_total") { TIMING_KEYS.forEach { k -> put(k, sum("timing_total", k).toLong()) } }
                put("trace", segments.lastOrNull()?.get("trace") ?: JsonNull)
            }
        }
    }
}
