package no.agens.testlab.core.autopilot

import kotlinx.serialization.json.JsonObject

/**
 * Decision traces: one JSON object per line, the same records jevpick.py writes
 * (`tool`, `goal`, `state`, `criteria`, `response`, `action`, `verify`, `outcome`, `timing`,
 * and a final `summary`), so the lab's jev-replay.py and stats scripts read them unchanged.
 * The JVM implementation adds the `ts` field and writes under the app's private traces folder.
 */
interface TraceSink {
    /** Where the trace lives, reported back in tool results; null when not persisted. */
    val path: String?
    fun write(rec: JsonObject)
}

/** Creates a trace for one tool call: `<yyyyMMdd-HHmmss>-<serial|device>-<tool>.jsonl`. */
fun interface TraceFactory {
    fun open(serial: String, tool: String): TraceSink?
}

object NoTrace : TraceSink {
    override val path: String? = null
    override fun write(rec: JsonObject) {}
}
