package no.agens.testlab.services.report

import no.agens.testlab.services.files.readTextCloud
import no.agens.testlab.services.files.readBytesCloud
import no.agens.testlab.services.files.readLinesCloud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import no.agens.testlab.core.autopilot.PlanSteps
import no.agens.testlab.device.ToolLocator
import no.agens.testlab.device.rec.Recording
import no.agens.testlab.services.report.DirShotStore
import no.agens.testlab.services.runtime.LabRuntime
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UnknownReport(id: String) : IllegalArgumentException("unknown report_id '$id' (call lab_report_start first)")

data class ReportSummary(val id: String, val title: String, val created: String, val shots: Int, val video: Boolean, val written: Boolean, val dir: File, val ticket: String? = null) {
    val page: File get() = File(dir, "index.html")
}

data class WriteResult(
    val dir: File, val page: File, val standalone: File, val shots: Int, val videos: List<Recording.Result>, val annotated: Boolean,
    /** The Proxyman traffic attached as flows.har (the Proxyman settings), or why not. */
    val flows: no.agens.testlab.services.proxyman.FlowExport? = null,
    val flowsNote: String? = null,
    /** The plan items' result line, when the report has items. */
    val result: String? = null,
    /** What the session's Proxyman cleanup did (and whether it checked out), when it ran. */
    val proxyCleanup: String? = null,
    /** Phone settings that differ from the session's snapshot (also listed in the report). */
    val rigChanges: List<String> = emptyList(),
)

/**
 * Reports in the lab's folder layout, in the workspace: `<id>/manifest.json`, `NN-slug.jpg`,
 * `timeline.jsonl` (notes) and `timeline.json` (events in seconds), `session.mp4`, `report.md`,
 * `index.html` (opens from file://) and `report-standalone.html` (images inlined, for Jira).
 * Writing a report stops the session recordings of the devices it is about and attaches them, with
 * every screenshot as a chapter and every note as a caption at the moment it was taken.
 */
class Reports(private val rt: LabRuntime) {
    private val json = Json { prettyPrint = true }
    private val dir: File get() = rt.reportsDir()

    fun folder(id: String): File {
        if (id.isBlank() || '/' in id || '\\' in id || ".." in id) throw UnknownReport(id)
        val d = File(dir, id)
        if (!File(d, "manifest.json").isFile) throw UnknownReport(id)
        return d
    }

    private fun manifest(d: File): JsonObject = Json.parseToJsonElement(File(d, "manifest.json").readTextCloud()) as JsonObject
    private fun saveManifest(d: File, m: JsonObject) {
        val tmp = File(d, "manifest.json.tmp"); tmp.writeText(json.encodeToString(JsonObject.serializer(), m))
        if (!tmp.renameTo(File(d, "manifest.json"))) { File(d, "manifest.json").writeText(tmp.readText()); tmp.delete() }
    }

    /** [ticket] links the report to a Jira issue (the Devtest screen and the comment use it). */
    fun start(rawTitle: String, ticket: String? = null): String {
        // What the agent writes into a report goes through the redactor first: a secret (this space's,
        // or another's by hash) in a title would otherwise end up in the folder name and the page.
        val title = rt.redactor.apply(rawTitle)
        val id = LocalDateTime.now().format(STAMP) + "-" + (PlanSteps.slug(title).ifEmpty { "report" }.take(48))
        val d = File(dir, id).apply { mkdirs() }
        saveManifest(d, buildJsonObject { put("title", title); ticket?.let { put("ticket", it) }; put("created", LocalDateTime.now().withNano(0).toString()); put("shots", JsonArray(emptyList())); put("started_epoch", System.currentTimeMillis() / 1000.0) })
        return id
    }

    /** A screenshot of [serial] into the report; returns the file name (the `![caption](file)` target). */
    suspend fun screenshot(id: String, rawCaption: String, serial: String?, item: String? = null): String {
        val caption = rt.redactor.apply(rawCaption)
        val d = folder(id)
        val dev = rt.device(serial)
        val jpeg = dev.screenshot(1080, quality = 85)
        val store = DirShotStore(d, serial = dev.serial)
        val slot = store.allocate(caption, PlanSteps.slug(caption))
        withContext(Dispatchers.IO) { File(slot.path).writeBytes(jpeg) }
        store.commit(slot, caption)
        if (item != null) {
            val all = items(id)
            if (all.none { it.id == item }) throw IllegalArgumentException("saved ${slot.fileName}, but report $id has no item «$item» to attach it to")
            saveItems(d, all.map { if (it.id == item) it.copy(evidence = (it.evidence + slot.fileName).distinct()) else it })
        }
        return slot.fileName
    }

    /** A note (caption on the recording) or a chapter at this moment. */
    fun note(id: String, rawText: String, kind: String = "note") {
        val text = rt.redactor.apply(rawText)
        val d = folder(id)
        val line = buildJsonObject { put("epoch", System.currentTimeMillis() / 1000.0); put("kind", if (kind == "chapter") "chapter" else "note"); put("text", text) }
        File(d, "timeline.jsonl").appendText(line.toString() + "\n")
    }

    // --- plan items ---

    private val itemJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun items(id: String): List<PlanItem> = (manifest(folder(id))["items"] as? JsonArray).orEmpty().mapNotNull { runCatching { itemJson.decodeFromJsonElement(PlanItem.serializer(), it) }.getOrNull() }

    private fun saveItems(d: File, items: List<PlanItem>) =
        saveManifest(d, JsonObject(manifest(d) + ("items" to JsonArray(items.map { itemJson.encodeToJsonElement(PlanItem.serializer(), it) }))))

    /** The plan's items ([id] to text); items already there keep their status, ids not given are dropped unless [keep]. */
    fun setItems(id: String, given: List<Pair<String, String>>, keep: Boolean = false): List<PlanItem> {
        val d = folder(id)
        val old = items(id).associateBy { it.id }
        val ids = given.map { it.first }
        if (ids.size != ids.toSet().size) throw IllegalArgumentException("item ids must be unique: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys.joinToString()}")
        val next = given.map { (iid, text) -> old[iid]?.copy(text = rt.redactor.apply(text)) ?: PlanItem(iid, rt.redactor.apply(text)) } +
            if (keep) old.values.filter { it.id !in ids } else emptyList()
        saveItems(d, next)
        return next
    }

    fun markItem(id: String, item: String, status: String, result: String?, verdict: String?, note: String?, evidence: List<String> = emptyList()): PlanItem {
        val d = folder(id)
        val all = items(id)
        val cur = all.firstOrNull { it.id == item } ?: throw IllegalArgumentException("report $id has no item «$item» (it has ${all.joinToString { it.id }.ifEmpty { "none: lab_report_items sets them" }})")
        val files = (manifest(d)["shots"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("file") }.toSet()
        evidence.filter { it !in files }.takeIf { it.isNotEmpty() }?.let { throw IllegalArgumentException("not this report's screenshots: ${it.joinToString()}") }
        val next = PlanItems.mark(cur, status, result, verdict, note?.let(rt.redactor::apply), evidence)
        saveItems(d, all.map { if (it.id == item) next else it })
        return next
    }

    private fun itemTexts(): (String) -> String = { k -> rt.profile.text("report.items.$k", PlanItems.TEXTS.getValue(k)) }

    fun list(): List<ReportSummary> = dir.listFiles().orEmpty().filter { File(it, "manifest.json").isFile }.mapNotNull { d ->
        runCatching {
            val m = manifest(d)
            ReportSummary(d.name, m.str("title"), m.str("created"), (m["shots"] as? JsonArray)?.size ?: 0, File(d, "session.mp4").isFile, File(d, "report.md").isFile, d, m.str("ticket").ifEmpty { null })
        }.getOrNull()
    }.sortedByDescending { it.id }

    /** The devices [m]'s screenshots show, the one shown most first. */
    private fun devices(m: JsonObject): List<String> =
        (m["shots"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("serial")?.ifEmpty { null } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    /** Events from the screenshots' capture times and the notes, relative to the recording's first frame. */
    fun events(d: File, firstFrameEpoch: Double?): List<TimelineEvent> {
        val raw = mutableListOf<Pair<Double, TimelineEvent>>()
        for (s in (manifest(d)["shots"] as? JsonArray).orEmpty()) {
            val o = s as? JsonObject ?: continue
            val t = (o["t"] as? JsonPrimitive)?.doubleOrNull ?: continue
            val cap = o.str("caption")
            raw += t to TimelineEvent(0.0, "chapter", cap, cap)
        }
        File(d, "timeline.jsonl").takeIf { it.isFile }?.readLinesCloud()?.forEach { line ->
            val o = runCatching { Json.parseToJsonElement(line) as JsonObject }.getOrNull() ?: return@forEach
            val t = (o["epoch"] as? JsonPrimitive)?.doubleOrNull ?: return@forEach
            val text = o.str("text")
            raw += t to TimelineEvent(0.0, if (o.str("kind") == "chapter") "chapter" else "note", text, text)
        }
        if (raw.isEmpty()) return emptyList()
        val base = firstFrameEpoch ?: raw.minOf { it.first }
        return raw.sortedBy { it.first }.map { (t, e) -> e.copy(t = maxOf(0.0, t - base)) }
    }

    /**
     * Writes the report: stops the recordings of the devices it is about and attaches them (the main
     * device's becomes session.mp4, others session-<serial>.mp4), takes a recording recovered after a
     * crash when none ran, and renders report.md, index.html and report-standalone.html.
     *
     * The devices it is about are [serial] and those its screenshots show ([devices]); the main one is
     * [serial], else the one shown most. Recordings on other devices go on: they belong to other
     * sessions running beside this one. A report without either takes the only recording running,
     * or the active device's. [takeRecovered] = false leaves a recording recovered after a crash alone.
     */
    suspend fun write(id: String, rawMarkdown: String, serial: String? = null, takeRecovered: Boolean = true, allowOpen: Boolean = false): WriteResult {
        val d = folder(id)
        val items = items(id)
        items.filter { it.status == PlanItem.OPEN }.takeIf { it.isNotEmpty() && !allowOpen }?.let { throw ItemsUnaccounted(it) }
        var markdown = rt.redactor.apply(rawMarkdown)
        if (items.isNotEmpty()) markdown = markdown.trimEnd() + "\n\n" + PlanItems.section(items, itemTexts())
        val (rig, rigChanges, cleanup) = rigSection(d, serial)
        if (rig.isNotEmpty()) markdown = markdown.trimEnd() + "\n\n" + rig
        var m = manifest(d)
        val title = m.str("title").ifEmpty { id }
        val videos = mutableListOf<Pair<String, Recording.Result>>()
        val shown = devices(m)
        val about = (listOfNotNull(serial) + shown).distinct()
        val running = rt.recordings.status().map { it.serial }
        val primarySerial = serial ?: shown.firstOrNull() ?: running.singleOrNull() ?: rt.active.value
        val mine = if (about.isNotEmpty()) running.filter { it in about } else running.filter { it == primarySerial }
        val recording = mine.sortedBy { it != primarySerial }
        for ((i, s) in recording.withIndex()) {
            val name = if (i == 0) "session.mp4" else "session-${s.replace(Regex("[^A-Za-z0-9._-]"), "_")}.mp4"
            rt.recordings.stop(s, File(d, name))?.let { videos += s to it }
        }
        if (takeRecovered && videos.isEmpty() && !File(d, "session.mp4").isFile && primarySerial != null) {
            // A recording cut off by a crash belongs to the session that was going on: the next report
            // on that device takes it, if it began in the last 12 hours.
            val since = System.currentTimeMillis() - 12 * 3600_000L
            rt.recordings.recovered(primarySerial).firstOrNull { (Recording.startedAt(it.parentFile) ?: 0L) >= since }?.let { f ->
                val dest = File(d, "session.mp4")
                val info = rt.recordings.recoveredResult(f)
                f.copyTo(dest, overwrite = true); f.parentFile.deleteRecursively()
                videos += primarySerial to info.copy(file = dest)
            }
        }
        if (videos.isNotEmpty()) {
            m = JsonObject(m + ("videos" to JsonArray(videos.map { (s, r) -> buildJsonObject {
                put("file", r.file.name); put("serial", s); put("bytes", r.file.length()); put("duration", r.durationMs / 1000.0)
                put("first_frame_epoch", r.firstFrameMs / 1000.0); put("recovered", r.recovered)
            } })))
            saveManifest(d, m)
        }
        var flows: no.agens.testlab.services.proxyman.FlowExport? = null
        var flowsNote: String? = null
        var body = markdown
        val px = rt.settings.current.proxyman
        if (px.enabled && px.attachFlows && "## Network log" !in markdown) {
            try {
                flows = rt.proxyman.exportFlows(d, from = flowSources(about.ifEmpty { listOfNotNull(primarySerial) }))
                if (flows != null) body = markdown.trimEnd() + "\n\n" + networkSection(flows) else flowsNote = "no Proxyman flows captured"
            } catch (e: Exception) {
                flowsNote = "flows not attached: ${e.message}"
            }
        }
        File(d, "report.md").writeText(markdownFile(d, m, body))
        File(d, "report.input.md").writeText(markdown)
        val annotated = render(d, m, body)
        return WriteResult(d, File(d, "index.html"), File(d, "report-standalone.html"), (m["shots"] as? JsonArray)?.size ?: 0, videos.map { it.second }, annotated, flows, flowsNote,
            items.takeIf { it.isNotEmpty() }?.let { PlanItems.resultLine(it, itemTexts()) }, cleanup, rigChanges)
    }

    /**
     * «Rig» section: every proxy change made since the report started (so a reader knows which evidence
     * came from mocked traffic), the session's own Proxyman rules removed and checked, and the phone
     * settings that differ from the session's snapshot. Empty when there is nothing to say.
     */
    private suspend fun rigSection(d: File, serial: String?): Triple<String, List<String>, String?> {
        val since = (manifest(d)["started_epoch"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val px = rt.settings.current.proxyman
        val changes = if (px.enabled) runCatching { rt.proxyman.rules.changes(since) }.getOrDefault(emptyList()) else emptyList()
        val cleanup = if (px.enabled && px.cleanupOnReport && changes.any { it.tool.startsWith("create_") })
            runCatching { rt.proxyman.rules.cleanup().summary() }.getOrElse { "cleanup failed: ${it.message}" } else null
        val s = serial ?: rt.active.value
        val settings = s?.takeIf { rt.platformOf(it) == no.agens.testlab.core.Platform.ANDROID }?.let { dev ->
            runCatching { rt.deviceState.diff(dev).second.map { c -> "${c.label}: ${c.was ?: "unset"} → ${c.now ?: "unset"}" } }.getOrDefault(emptyList())
        }.orEmpty()
        if (changes.isEmpty() && settings.isEmpty()) return Triple("", settings, cleanup)
        val text = buildString {
            appendLine("## Rig")
            if (changes.isNotEmpty()) {
                appendLine(); appendLine("Proxy changes during the session (evidence after these may come from mocked traffic):"); appendLine()
                changes.forEach { c -> appendLine("- ${java.time.Instant.ofEpochMilli((c.epoch * 1000).toLong()).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)} `${c.tool}` ${c.summary.cell()}") }
            }
            cleanup?.let { appendLine(); appendLine("Cleanup: " + it.lines().joinToString("; ")) }
            if (settings.isNotEmpty()) {
                appendLine(); appendLine("Phone settings different from the start of the session (device `$s`):"); appendLine()
                settings.forEach { appendLine("- $it") }
            }
        }
        return Triple(text.trimEnd(), settings, cleanup)
    }

    /** The «Network log» section: a request table (first [cap] rows) and the HAR link. */
    fun networkSection(f: no.agens.testlab.services.proxyman.FlowExport, cap: Int = 100): String = buildString {
        appendLine("## Network log (Proxyman)")
        appendLine()
        appendLine("${f.count} request(s) to ${f.hosts.size} host(s) during the session" +
            (f.from?.let { " from this report's device(s) (${it.sorted().joinToString()}; other phones on the same Proxyman left out)" } ?: "") +
            ". Full log: [flows.har](flows.har) (${f.file.length() / 1024} KB, opens in Proxyman, Charles or a browser's dev tools).")
        appendLine()
        appendLine("| # | Method | Status | Host | Path |")
        appendLine("| --- | --- | --- | --- | --- |")
        f.rows.take(cap).forEachIndexed { i, r -> appendLine("| ${i + 1} | ${r.method} | ${r.status} | ${r.host.cell()} | ${r.path.cell()} |") }
        if (f.rows.size > cap) { appendLine(); append("… and ${f.rows.size - cap} more in flows.har.") }
    }

    private fun String.cell() = replace("|", "\\|").replace("`", "'")

    /**
     * The addresses Proxyman sees [devices]' traffic come from, so another phone's requests on a
     * shared Proxyman stay out of the report; null (every request) when any of them can't be told apart.
     */
    private fun flowSources(devices: List<String>): Set<String>? =
        devices.takeIf { it.isNotEmpty() }?.map { rt.proxyman.deviceAddress(it) ?: return null }?.toSet()

    /** lab_report_flows: (re)attaches the captured flows to a written report. */
    suspend fun attachFlows(id: String): no.agens.testlab.services.proxyman.FlowExport? {
        val d = folder(id)
        val input = File(d, "report.input.md").takeIf { it.isFile }?.readTextCloud() ?: throw IllegalStateException("$id is not written yet: call lab_report_write first")
        val f = rt.proxyman.exportFlows(d, from = flowSources(devices(manifest(d)))) ?: return null
        val body = input.replace(Regex("\n## Network log.*", RegexOption.DOT_MATCHES_ALL), "").trimEnd() + "\n\n" + networkSection(f)
        val m = manifest(d)
        File(d, "report.md").writeText(markdownFile(d, m, body))
        render(d, m, body)
        return f
    }

    /** Adds a finished video (a comparison arm) to the report, labelled [label]; lab_report_write shows a player for each. */
    fun attachVideo(id: String, r: Recording.Result, label: String) {
        val d = folder(id)
        val m = manifest(d)
        val v = buildJsonObject {
            put("file", r.file.name); put("serial", label); put("bytes", r.file.length()); put("duration", r.durationMs / 1000.0)
            put("first_frame_epoch", r.firstFrameMs / 1000.0); put("recovered", r.recovered)
        }
        saveManifest(d, JsonObject(m + ("videos" to JsonArray((m["videos"] as? JsonArray).orEmpty() + v))))
    }

    /** Re-renders a written report, optionally with an explicit timeline (seconds from the first frame). */
    fun annotate(id: String, timeline: List<TimelineEvent>? = null): Int {
        val d = folder(id)
        val m = manifest(d)
        val md = File(d, "report.input.md").takeIf { it.isFile }?.readTextCloud() ?: ""
        timeline?.let { tl -> File(d, "timeline.override.json").writeText(rt.redactor.apply(ReportHtml.timelineJson(tl))) }
        render(d, m, md)
        return eventsFor(d, m).size
    }

    private fun primaryVideo(m: JsonObject): JsonObject? = (m["videos"] as? JsonArray)?.firstOrNull() as? JsonObject

    private fun eventsFor(d: File, m: JsonObject): List<TimelineEvent> {
        File(d, "timeline.override.json").takeIf { it.isFile }?.let { f ->
            return (Json.parseToJsonElement(f.readTextCloud()) as JsonArray).map { e ->
                val o = e as JsonObject
                TimelineEvent((o["t"] as JsonPrimitive).doubleOrNull ?: 0.0, o.str("kind").ifEmpty { "note" }, o.str("title"), o.str("text"))
            }
        }
        val first = (primaryVideo(m)?.get("first_frame_epoch") as? JsonPrimitive)?.doubleOrNull
        val dur = (primaryVideo(m)?.get("duration") as? JsonPrimitive)?.doubleOrNull
        return events(d, first).map { e -> if (dur != null && dur > 0) e.copy(t = minOf(e.t, dur)) else e }
    }

    private fun referenced(markdown: String) = Regex("\\]\\(([^)\\s]+\\.(?:jpe?g|png))\\)").findAll(markdown).map { it.groupValues[1] }.toSet()

    private fun extras(m: JsonObject, markdown: String): List<Pair<String, String>> {
        val used = referenced(markdown)
        return (m["shots"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> o.str("file") to o.str("caption") } }.filter { it.first !in used }
    }

    private fun markdownFile(d: File, m: JsonObject, markdown: String): String = buildString {
        appendLine("# ${m.str("title")}")
        appendLine("_Generated ${m.str("created")} · report_id `${d.name}`_")
        appendLine()
        appendLine(markdown.trimEnd())
        val vids = (m["videos"] as? JsonArray).orEmpty().map { it as JsonObject }
        if (vids.isNotEmpty()) {
            appendLine(); appendLine("## Session recording"); appendLine()
            for (v in vids) appendLine("[${v.str("file")}](${v.str("file")}) — device `${v.str("serial")}`, ${(v["duration"] as? JsonPrimitive)?.doubleOrNull?.let { "%.0f s".format(it) } ?: ""}")
        }
        val ex = extras(m, markdown)
        if (ex.isNotEmpty()) {
            appendLine(); appendLine("## Screenshots"); appendLine()
            ex.forEach { (f, c) -> appendLine("![${c.replace("]", ")")}]($f)") }
        }
    }

    /** index.html and the standalone copy; also session_annotated.mp4 when ffmpeg is installed. Returns whether that was made. */
    private fun render(d: File, m: JsonObject, markdown: String): Boolean {
        val events = eventsFor(d, m)
        File(d, "timeline.json").writeText("{\"report_id\":\"${d.name}\",\"video_file\":\"session.mp4\",\"events\":${ReportHtml.timelineJson(events)}}\n")
        val body = StringBuilder(ReportHtml.markdown(markdown))
        val vids = (m["videos"] as? JsonArray).orEmpty().map { it as JsonObject }
        if (vids.isNotEmpty()) {
            body.append("\n<h2>Session recording</h2>\n")
            vids.forEachIndexed { i, v ->
                val dur = (v["duration"] as? JsonPrimitive)?.doubleOrNull
                val cap = "Device ${v.str("serial")}" + (dur?.takeIf { it > 0 }?.let { ", %.0f s".format(it) } ?: "") +
                    (if ((v["recovered"] as? JsonPrimitive)?.content == "true") ", recovered after the app was closed" else "") +
                    (if (i == 0 && events.isNotEmpty()) ", ${events.count { it.kind == "chapter" }} chapters" else "")
                body.append(ReportHtml.player(PlayerVideo(v.str("file"), if (i == 0) events else emptyList(), cap), i)).append('\n')
            }
        }
        val ex = extras(m, markdown)
        if (ex.isNotEmpty()) {
            body.append("\n<h2>Screenshots</h2>\n")
            body.append(ReportHtml.markdown(ex.joinToString("\n") { (f, c) -> "![${c.replace("]", ")")}]($f)" }))
        }
        val subtitle = "Generated ${m.str("created").replace('T', ' ')} · ${d.name}"
        val html = ReportHtml.page(m.str("title").ifEmpty { d.name }, subtitle, body.toString())
        File(d, "index.html").writeText(html)
        File(d, "report-standalone.html").writeText(ReportHtml.inlineImages(html, d))
        return if (vids.isNotEmpty() && events.isNotEmpty()) annotateMp4(d, events, (vids[0]["duration"] as? JsonPrimitive)?.doubleOrNull) else false
    }

    /** With ffmpeg installed: chapters and a subtitle track muxed into session_annotated.mp4 (stream copy, seconds). */
    private fun annotateMp4(d: File, events: List<TimelineEvent>, duration: Double?): Boolean {
        val ffmpeg = ToolLocator(appToolsDir = rt.dirs.cache).findOptional("ffmpeg")?.path ?: return false
        val end = duration?.takeIf { it > 0 } ?: (events.last().t + 5)
        fun ts(s: Double, sep: Char) = (Math.round(maxOf(0.0, s) * 1000)).let { ms -> "%02d:%02d:%02d%c%03d".format(ms / 3_600_000, ms % 3_600_000 / 60_000, ms % 60_000 / 1000, sep, ms % 1000) }
        val srt = StringBuilder()
        events.forEachIndexed { i, e ->
            val stop = (if (i + 1 < events.size) events[i + 1].t else end).let { if (it <= e.t) e.t + 2 else it }
            srt.append("${i + 1}\n${ts(e.t, ',')} --> ${ts(stop, ',')}\n${(e.text.ifEmpty { e.title }).replace('\n', ' ')}\n\n")
        }
        File(d, "notes.srt").writeText(srt.toString())
        val chaps = events.filter { it.kind == "chapter" }
        val meta = StringBuilder(";FFMETADATA1\n")
        chaps.forEachIndexed { i, e ->
            val stop = (if (i + 1 < chaps.size) chaps[i + 1].t else end).let { if (it <= e.t) e.t + 2 else it }
            meta.append("[CHAPTER]\nTIMEBASE=1/1000\nSTART=${(e.t * 1000).toLong()}\nEND=${(stop * 1000).toLong()}\ntitle=${e.title.replace(Regex("[=;#\\\\\\n]"), " ")}\n")
        }
        File(d, "chapters.ffmeta").writeText(meta.toString())
        val out = File(d, "session_annotated.mp4")
        val p = ProcessBuilder(ffmpeg.path, "-loglevel", "error", "-y", "-i", "session.mp4", "-f", "ffmetadata", "-i", "chapters.ffmeta", "-i", "notes.srt",
            "-map", "0:v:0", "-map", "2:0", "-map_metadata", "1", "-c:v", "copy", "-c:s", "mov_text", "-metadata:s:s:0", "language=nor", "-movflags", "+faststart", out.name)
            .directory(d).redirectErrorStream(true).redirectOutput(File(d, "annotate.log")).start()
        val ok = p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0 && out.length() > 0
        if (!ok) { p.destroyForcibly(); out.delete() }
        return ok
    }

    /** The whole report folder as one zip (to send or attach). */
    fun zip(id: String): File {
        val d = folder(id)
        val out = File(dir, "$id.zip")
        ZipOutputStream(out.outputStream()).use { z ->
            d.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                z.putNextEntry(ZipEntry("$id/" + f.relativeTo(d).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        return out
    }

    fun delete(id: String) { folder(id).deleteRecursively() }

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

