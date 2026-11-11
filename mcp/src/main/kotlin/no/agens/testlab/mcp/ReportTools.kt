package no.agens.testlab.mcp

import no.agens.testlab.AppIdentity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.agens.testlab.mcp.Param.Companion.SERIAL
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.report.TimelineEvent
import no.agens.testlab.services.runtime.LabRuntime

private fun mb(b: Long) = "%.1f MB".format(b / 1_000_000.0)

internal fun ToolBox.reportTools(rt: LabRuntime) {
    tool(
        "lab_session_start",
        """
        Start recording a device's screen. Call it at the start of a session THAT USES THE DEVICE
        (device-prep does). The recording is attached AUTOMATICALLY when you write a report
        (lab_report_write), with every report screenshot as a chapter and every lab_session_note as
        a caption. `serial` targets one device (default: the active one); recordings on several
        devices run at the same time. Don't record analysis-only work. If the user declines a
        report, call lab_session_discard. Idempotent while recording.
        """,
        SERIAL,
    ) { a ->
        val dev = rt.device(a.serial)
        val started = rt.recordings.start(dev.serial)
        val extra = if (!started) "" else buildString {
            // A new session: its own Proxyman rule tag, a snapshot of the phone's settings, and the rig's health.
            if (rt.settings.current.proxyman.enabled) append("\nproxy rule tag: ${rt.proxyman.rules.tag(rt.proxyman.rules.newSession())}")
            if (rt.platformOf(dev.serial) == no.agens.testlab.core.Platform.ANDROID) {
                runCatching { rt.deviceState.snapshot(dev.serial) }.onSuccess { append("\nsettings snapshot taken (lab_device_state diff/restore)") }
                runCatching { rt.rigHealth.run(dev.serial, full = true) }.onSuccess { append("\n" + healthSummary(it)) }
            }
        }
        text((if (started) "Recording ${dev.serial}: the whole session is captured and attached when you call lab_report_write; lab_session_discard deletes it if no report is wanted."
        else "Already recording ${dev.serial}.") + extra)
    }

    tool("lab_session_status", "Running session recordings: duration, frames and size so far. No serial = all devices.", SERIAL, readOnly = true) { a ->
        val list = rt.recordings.status(a.serial)
        text(if (list.isEmpty()) "not recording" else list.joinToString("\n") { s ->
            "${s.serial}: recording %.0f s, %d frames, %s".format(s.seconds, s.frames, mb(s.bytes)) + (s.error?.let { " (last stream error: $it; reconnecting)" } ?: "")
        })
    }

    tool("lab_session_discard", "Stop and DELETE session recording(s): use when the user declines a report. No serial = every device.", SERIAL) { a ->
        val gone = rt.recordings.discard(a.serial)
        text(if (gone.isEmpty()) "nothing was recording" else "discarded: ${gone.joinToString()}")
    }

    tool(
        "lab_session_note",
        """
        Annotate the running recording with a reasoning note (or a chapter marker) at this moment.
        In the report the note is a caption on the video from now until the next event, so the
        recording narrates itself; kind="chapter" also adds a jump point. Say WHAT you are doing and
        WHY between screenshots, e.g. "Reproducing the double wager by adding Argentina again".
        """,
        str("report_id", "from lab_report_start"),
        str("text", "the note"),
        str("kind", "note | chapter", "note", listOf("note", "chapter")),
    ) { a ->
        rt.reports.note(a.str("report_id"), a.str("text"), a.str("kind"))
        text("noted (${a.str("kind")}): appears as a ${if (a.str("kind") == "chapter") "chapter and caption" else "caption"} on the recording")
    }

    tool(
        "lab_report_start",
        """
        Begin a saved report. Returns a report_id. Then lab_report_screenshot(report_id, caption) at
        each notable step (and handsets_jev_plan(..., report_id=…) puts its screenshot steps there),
        and finally lab_report_write(report_id, markdown). Only start one when the user wants a report.
        """,
        str("title", "the report's title"),
        str("ticket", "Jira key this report belongs to (devtests), e.g. PROJ-123", ""),
    ) { a ->
        val ticket = a.strOrNull("ticket")?.let { no.agens.testlab.services.jira.Jira.checkKey(it) }
        val id = rt.reports.start(a.str("title"), ticket)
        ticket?.let { rt.devtest.linkReport(it, id) }
        text("report_id: $id\nCapture frames with lab_report_screenshot(\"$id\", \"<caption>\"), then finish with lab_report_write(\"$id\", <markdown>).\nFolder: ${rt.reports.folder(id).path}")
    }

    tool(
        "lab_report_items",
        """
        Give a report the test plan's items, so it accounts for every one: [{"id": "1", "text": "Pay
        with saved card"}, …]. Then mark each with lab_report_item as it is tested or found untestable;
        lab_report_write refuses while an item is unaccounted for, and writes the «Plan items» section
        (tested, not tested with reasons) and a result line that matches them. Calling it again
        replaces the list (items with the same id keep their status).
        """,
        str("report_id", "from lab_report_start"),
        Param("items", "array", "the plan's items: {id, text}"),
    ) { a ->
        val given = (a.json("items") as? JsonArray).orEmpty().map { e ->
            val o = e as? JsonObject ?: throw BadArgument("items are objects {id, text}")
            fun s(k: String) = (o[k] as? JsonPrimitive)?.content?.trim().orEmpty()
            (s("id").ifEmpty { throw BadArgument("every item needs an id") }) to s("text").ifEmpty { throw BadArgument("item ${s("id")} needs a text") }
        }
        if (given.isEmpty()) throw BadArgument("give at least one item")
        val items = rt.reports.setItems(a.str("report_id"), given)
        text("${items.size} item(s): " + items.joinToString("; ") { "${it.id} ${it.status}" })
    }

    tool(
        "lab_report_item",
        """
        Account for one plan item: status tested with result pass, fail or unclear (a fail needs a
        verdict: bug = in scope, back to the author; by_design; environment = the test environment did
        it; existing = also on the control build, its own ticket), or status not_tested with the reason
        in note («needs a second device», «no suitable data on the environment»). note says what was
        seen; evidence names this report's screenshot files. Report only what was exercised: an item
        checked by reading code is not tested.
        """,
        str("report_id", "from lab_report_start"),
        str("item", "the item's id"),
        str("status", "tested | not_tested", enum = listOf("tested", "not_tested")),
        str("result", "for tested: pass | fail | unclear", ""),
        str("verdict", "for a fail: bug | by_design | environment | existing", ""),
        str("note", "what was seen, or why it was not tested", ""),
        Param.strings("evidence", "screenshot files of this report that show it"),
    ) { a ->
        val i = rt.reports.markItem(a.str("report_id"), a.str("item"), a.str("status"), a.strOrNull("result"), a.strOrNull("verdict"), a.strOrNull("note"), a.strings("evidence"))
        val left = rt.reports.items(a.str("report_id")).count { it.status == no.agens.testlab.services.report.PlanItem.OPEN }
        text("${i.id}: ${i.status}" + (i.result?.let { " $it" } ?: "") + (i.verdict?.let { " ($it)" } ?: "") + "; $left item(s) left to account for")
    }

    tool(
        "lab_report_screenshot",
        "Capture the device screen into the report; returns the markdown image line to embed in lab_report_write. `item` adds it to a plan item's evidence.",
        str("report_id", "from lab_report_start"),
        str("caption", "what the screenshot shows"),
        str("item", "plan item id it is evidence for (lab_report_items)", ""),
        SERIAL,
    ) { a ->
        val file = rt.reports.screenshot(a.str("report_id"), a.str("caption"), a.serial, a.strOrNull("item"))
        text("saved $file. Embed this line in the report:\n![${a.str("caption")}]($file)")
    }

    tool(
        "lab_report_write",
        """
        Write the report and return where it is. Cover what was checked, what was found, and the
        evidence: embed the `![caption](file.jpg)` lines from lab_report_screenshot next to the text
        they belong to; lines on CONSECUTIVE lines (no blank line) render side by side. Screenshots
        you don't reference are added as a gallery. The recordings of the devices the report is about
        (`serial`, and the devices its screenshots show) are stopped and embedded with chapters and
        captions; recordings on other devices keep running. Produces index.html (opens offline), report-standalone.html (images
        inlined: attach it to Jira together with session.mp4) and report.md.
        With plan items (lab_report_items) it adds the «Plan items» section and the result line from
        them, and refuses while one is unaccounted for. It also deletes this session's own Proxyman rules
        (checked: listed again, mocked URLs asked once more) and adds a «Rig» section: the proxy changes
        made during the session, and phone settings that differ from the session's snapshot.
        """,
        str("report_id", "from lab_report_start"),
        str("markdown", "the report body in Markdown"),
        Param.bool("allow_open", "write even though plan items are unaccounted for (a draft: they are listed as such)", false),
        SERIAL,
    ) { a ->
        val r = rt.reports.write(a.str("report_id"), a.str("markdown"), a.serial, allowOpen = a.bool("allow_open"))
        val vids = r.videos.joinToString("; ") { v -> "${v.file.name} (${"%.0f".format(v.durationMs / 1000.0)} s, ${mb(v.file.length())}${if (v.recovered) ", recovered" else ""})" }
        text(buildString {
            appendLine("Report written: ${r.page.toURI()} (to show it, lab_open_page(page=report, id=${a.str("report_id")}) opens it in the default browser)")
            appendLine("${r.shots} screenshot(s)" + if (r.videos.isEmpty()) ", no session recording (none was running)" else ", recording: $vids" + if (r.annotated) ", session_annotated.mp4 with chapters" else "")
            r.flows?.let { appendLine("Network log: ${it.count} request(s) to ${it.hosts.size} host(s) → flows.har") }
            r.flowsNote?.let { appendLine("Proxyman: $it") }
            r.result?.let { appendLine("Result (from the plan items): $it") }
            r.proxyCleanup?.let { appendLine("Proxy rules: " + it.lines().joinToString("; ")) }
            if (r.rigChanges.isNotEmpty()) appendLine("Phone settings changed during the session (listed in the report): ${r.rigChanges.joinToString("; ")}. lab_device_state(restore) puts them back.")
            appendLine("For Jira: ${r.standalone.path}" + if (r.videos.isNotEmpty()) " and ${r.videos.first().file.path}" else "")
        }.trimEnd())
    }

    tool(
        "lab_report_annotate",
        """
        Re-render a written report's chapters and captions. `timeline` (optional, JSON list) replaces
        the automatic events: [{"t": seconds from the recording's start, "kind": "chapter"|"note",
        "title": "...", "text": "..."}]. Without it the events come from the screenshots and notes.
        """,
        str("report_id", "a written report"),
        Param("timeline", "array", "events to use instead of the automatic ones", JsonArray(emptyList())),
    ) { a ->
        val tl = (a.json("timeline") as? JsonArray)?.takeIf { it.isNotEmpty() }?.map { e ->
            val o = e as? JsonObject ?: throw BadArgument("timeline entries are objects")
            fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
            TimelineEvent((o["t"] as? JsonPrimitive)?.doubleOrNull ?: throw BadArgument("each timeline entry needs t (seconds)"), if (s("kind") == "chapter") "chapter" else "note", s("title"), s("text"))
        }
        val n = rt.reports.annotate(a.str("report_id"), tl)
        text("annotated ${a.str("report_id")}: $n event(s); reload ${rt.reports.folder(a.str("report_id")).resolve("index.html").toURI()}")
    }

    tool("lab_list_reports", "The saved reports, newest first: id, title, when, screenshots, whether a recording is attached, and the page to open.", readOnly = true) {
        val list = rt.reports.list()
        text(if (list.isEmpty()) "(no reports yet)" else buildJsonObject {
            putJsonArray("reports") { for (r in list.take(50)) addJsonObject {
                put("id", r.id); put("title", r.title); put("created", r.created); put("shots", r.shots); put("video", r.video); put("written", r.written)
                if (r.written) put("page", r.page.toURI().toString())
            } }
        }.encode())
    }

    tool(
        "lab_compare_backends",
        """
        Benchmark a plan: run it once per decision backend, each run recorded, and write one report
        with a table (time, operator time, hand-backs, decisions, tokens, cost, and where the time
        went) plus a player per run, and a side-by-side clip when ffmpeg is installed. `backends`:
        typesafe, stub, ollama, openai, "ollama|<url>|<model>" for a specific endpoint, or "openai|<model>". clear_package
        clears that app's data before each run (logged out, like a fresh install: needs approval).
        on_hand_back: stop (end that run, go on with the next), skip (go on with the next step), or
        wait (for the user to resolve it on the hand-back page, `${AppIdentity.SLUG} open handback`, up to 10 minutes).
        """,
        Param.plan("plan", "JSON list of steps, or empty to use `name` from the library"),
        str("name", "a saved plan's name (when plan is empty), or the label for the report", ""),
        Param("backends", "array", "the arms, e.g. [\"typesafe\", \"ollama\"]", JsonArray(listOf(JsonPrimitive("typesafe"), JsonPrimitive("ollama")))),
        str("clear_package", "package whose data is cleared before each run", ""),
        str("on_hand_back", "stop | skip | wait", "stop", listOf("stop", "skip", "wait")),
        SERIAL,
    ) { a ->
        val name = a.strOrNull("name") ?: "plan"
        val raw = a.json("plan")
        val plan = if (raw == null || (raw is JsonPrimitive && raw.content.isBlank())) PlanLibrary(rt).read(name) else parsePlan(raw)
        checkPlan(rt, plan)
        val arms = (a.json("backends") as? JsonArray).orEmpty().map { no.agens.testlab.services.plans.BackendChoice((it as JsonPrimitive).content) }
        if (arms.isEmpty()) throw BadArgument("backends needs at least one entry")
        val dev = rt.device(a.serial)
        val clear = a.strOrNull("clear_package")
        if (clear != null && rt.settings.current.mcp.confirmWrites && !rt.approver.approve("Clear app data", "Clear the data of $clear on ${dev.serial} before each of ${arms.size} runs? It logs the app out.")) {
            throw no.agens.testlab.services.runtime.NotApproved("not approved: clearing $clear needs a yes (`${AppIdentity.SLUG} open approvals`)")
        }
        val policy: (suspend (no.agens.testlab.services.plans.PlanRun) -> no.agens.testlab.services.plans.Resolution)? = when (a.str("on_hand_back")) {
            "skip" -> { _ -> no.agens.testlab.services.plans.Resolution.Skip }
            "wait" -> null
            else -> { _ -> no.agens.testlab.services.plans.Resolution.Stop }
        }
        val r = kotlinx.coroutines.withTimeout(3_600_000) { rt.comparison.run(plan, name, dev.serial, arms, clear, policy) }
        text(buildString {
            appendLine("Report: ${r.page.toURI()}")
            r.sideBySide?.let { appendLine("Side by side: ${it.path}") }
            for (x in r.arms) {
                val o = x.run.result
                fun n(k: String) = (o?.get(k) as? JsonPrimitive)?.content ?: "?"
                appendLine("${x.label}: ${x.run.state.name.lowercase()} in ${n("elapsed_s")} s, ${n("hand_backs")} hand-back(s), ${n("decisions")} decisions, ${n("input_tokens")} tokens")
            }
        }.trimEnd())
    }
}
