package no.agens.testlab.mcp

import no.agens.testlab.services.files.readTextCloud
import no.agens.testlab.services.files.readBytesCloud
import no.agens.testlab.services.files.readLinesCloud
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.agens.testlab.AppIdentity
import no.agens.testlab.mcp.Param.Companion.SERIAL
import no.agens.testlab.mcp.Param.Companion.bool
import no.agens.testlab.mcp.Param.Companion.int
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.runtime.LabRuntime
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP Apps views: a host that renders them (Claude Desktop) shows the phone's screen live in the
 * conversation, and the person can tap it. The view polls [FRAME]; hosts without MCP Apps just get
 * the text result.
 */
object Views {
    const val SCREEN = "ui://testlab/screen"
    const val REPORT = "ui://testlab/report"
    const val MIME = "text/html;profile=mcp-app"
    const val FRAME = "handsets_view_frame"
    const val ACTIVITY = "handsets_view_activity"
    const val REPORT_PAGE = "lab_view_report"
    const val REPORT_FILE = "lab_view_report_file"

    /** Raw bytes per [REPORT_FILE] call (base64 makes it a third larger). */
    const val CHUNK = 1 shl 20

    fun html(name: String): String = Views::class.java.getResourceAsStream("/ui/$name.html")!!.readBytes().decodeToString()
        .replace("{{APP}}", AppIdentity.NAME)
        .replace("{{SLUG}}", AppIdentity.SLUG)
        .replace("/*{{REPORT_CSS}}*/", no.agens.testlab.services.report.ReportHtml.CSS)
        .replace("/*{{PLAYER_JS}}*/", no.agens.testlab.services.report.ReportHtml.PLAYER_JS)

    fun register(server: Server) {
        server.addResource(SCREEN, "live screen", "The phone's screen, live, with tap, swipe and keys (MCP Apps view)", MIME) { req ->
            ReadResourceResult(listOf(TextResourceContents(html("screen"), req.params.uri, MIME)))
        }
        server.addResource(REPORT, "report", "A test report inline: screenshots, notes and the recording with a chapter per action (MCP Apps view)", MIME) { req ->
            ReadResourceResult(listOf(TextResourceContents(html("report"), req.params.uri, MIME)))
        }
    }
}

internal fun ToolBox.viewTools(rt: LabRuntime) {
    val sizes = ConcurrentHashMap<String, Pair<Int, Int>>()
    val rings = ConcurrentHashMap<String, Frames.Ring>()
    fun ring(key: String) = rings.getOrPut(key) { Frames.Ring(java.io.File(rt.dirs.cache, "frames"), key.replace(Regex("[^A-Za-z0-9._-]"), "_")) }

    tool(
        "handsets_show_screen",
        """
        Show the phone's screen live in the conversation, where the user can watch and tap it
        themselves (Claude Desktop and other hosts with MCP Apps; elsewhere this only answers with
        the device). Use it when the user wants to see the app or follow a test: call it once, then
        keep driving with the handsets_* tools; the view updates by itself.
        """,
        SERIAL,
        readOnly = true,
        ui = Views.SCREEN,
    ) { a ->
        val dev = rt.device(a.serial)
        val (w, h) = dev.screenSize().also { sizes[dev.serial] = it }
        text(buildJsonObject {
            put("serial", dev.serial); put("width", w); put("height", h)
            put("shown", "live in the conversation, where the host supports MCP Apps")
        }.encode())
    }

    tool(
        Views.FRAME,
        """
        For the live views (MCP Apps, Claude Code panes); not needed in a conversation.
        The live view's frame plus the serial and screen size in device pixels (for mapping clicks).
        format jpeg (default): an image block. png or rgb (for terminal panes): JSON with base64
        `data`, fitted into w×h pixels; rgb is raw 8-bit RGB, row by row. file: the PNG written to
        a file on this computer (a ring of the last 240), its path in `data`.
        """,
        int("size", "long edge in pixels (jpeg)", 720),
        str("format", "jpeg | png | rgb | file", "jpeg", listOf("jpeg", "png", "rgb", "file")),
        int("w", "png/rgb: fit into this width in pixels (0 = any)", 0),
        int("h", "png/rgb: fit into this height in pixels (0 = any)", 0),
        SERIAL,
        readOnly = true,
        appOnly = true,
    ) { a ->
        val dev = rt.device(a.serial)
        val (w, h) = sizes[dev.serial] ?: dev.screenSize().also { sizes[dev.serial] = it }
        val format = a.str("format")
        if (format == "jpeg") {
            val meta = buildJsonObject { put("serial", dev.serial); put("width", w); put("height", h); put("active", rt.active.value ?: "") }
            Out.Image(dev.screenshot(a.int("size").coerceIn(240, 1600), quality = 70), caption = meta.encode())
        } else {
            val fw = a.int("w").coerceIn(0, 1600); val fh = a.int("h").coerceIn(0, 1600)
            val shot = dev.screenshot(maxOf(fw, fh, 240).coerceAtMost(1600).let { long -> if (fh > 0 && fw > 0) maxOf(fh, fw * h / maxOf(1, w)) else long }.coerceIn(120, 1600), quality = 85)
            val f = Frames.convert(shot, format, fw, fh) { png -> ring(dev.serial).write(png) }
            Out.Raw(buildJsonObject { put("serial", dev.serial); put("width", w); put("height", h); put("format", f.format); put("w", f.w); put("h", f.h); put("data", f.data) }.encode())
        }
    }

    tool(
        Views.ACTIVITY,
        "For the live views; not needed in a conversation. The last tool calls, newest first.",
        int("limit", "how many", 8),
        readOnly = true,
        appOnly = true,
    ) { a ->
        text(buildJsonObject {
            putJsonArray("calls") {
                for ((at, c) in rt.recentCalls().asReversed().take(a.int("limit").coerceIn(1, 40))) addJsonObject {
                    put("at", at); put("tool", c.tool); put("summary", c.summary.take(160)); put("ok", c.ok); put("ms", c.ms)
                    c.title?.let { put("title", it.take(160)) }; c.why?.let { put("why", it) }
                }
            }
        }.encode())
    }

    fun report(id: String) = rt.reports.list().let { all ->
        (if (id.isBlank()) all.firstOrNull { it.written } else all.firstOrNull { it.id == id })
            ?: throw BadArgument(if (id.isBlank()) "no written report yet: lab_report_write makes one" else "no report $id (lab_list_reports lists them)")
    }

    tool(
        "lab_show_report",
        """
        Show a test report inline in the conversation (Claude Desktop and other hosts with MCP
        Apps): screenshots, notes and the recording, with a chapter per action to jump through the
        video. Empty report_id = the newest written report. Elsewhere this answers with the
        report's id, title and page to open.
        """,
        str("report_id", "the report (from lab_list_reports or lab_report_write), empty = newest", ""),
        readOnly = true,
        ui = Views.REPORT,
    ) { a ->
        val r = report(a.str("report_id"))
        if (!r.written) throw BadArgument("report ${r.id} isn't written yet: lab_report_write first")
        text(buildJsonObject { put("id", r.id); put("title", r.title); put("created", r.created); put("shots", r.shots); put("video", r.video); put("page", r.page.toURI().toString()) }.encode())
    }

    tool(
        Views.REPORT_PAGE,
        "For the report views; not needed in a conversation. The report's standalone HTML (images inlined), report.md, chapters and videos.",
        str("report_id", "the report, empty = newest", ""),
        bool("html", "include the page's HTML (large: images inlined)", true),
        readOnly = true,
        appOnly = true,
    ) { a ->
        val r = report(a.str("report_id"))
        val page = java.io.File(r.dir, "report-standalone.html").takeIf { it.isFile } ?: throw BadArgument("report ${r.id} isn't written yet")
        text(buildJsonObject {
            put("id", r.id); put("title", r.title)
            if (a.bool("html")) put("html", page.readTextCloud())
            put("page", r.page.toURI().toString())
            java.io.File(r.dir, "report.md").takeIf { it.isFile }?.let { put("markdown", it.readTextCloud()) }
            val tl = listOf("timeline.override.json", "timeline.json").map { java.io.File(r.dir, it) }.firstOrNull { it.isFile }
            tl?.let { f -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(f.readTextCloud()) }.getOrNull() }?.let { el ->
                put("events", (el as? kotlinx.serialization.json.JsonObject)?.get("events") ?: el)
            }
            putJsonArray("videos") { for (f in r.dir.listFiles().orEmpty().filter { it.extension == "mp4" }.sortedBy { it.name }) addJsonObject { put("file", f.name); put("size", f.length()) } }
        }.encode())
    }

    tool(
        Views.REPORT_FILE,
        "For the report views; not needed in a conversation. Base64 of up to 1 MiB of a report's video from `offset`.",
        str("report_id", "the report"),
        str("file", "the video's file name, e.g. session.mp4"),
        int("offset", "byte offset", 0),
        readOnly = true,
        appOnly = true,
    ) { a ->
        val r = report(a.str("report_id"))
        val name = a.str("file")
        if (!name.matches(Regex("[A-Za-z0-9._-]+\\.mp4"))) throw BadArgument("only a report's .mp4 files")
        val f = java.io.File(r.dir, name).takeIf { it.isFile } ?: throw BadArgument("no $name in report ${r.id}")
        val offset = a.int("offset").toLong().coerceIn(0, f.length())
        val bytes = no.agens.testlab.services.files.CloudFiles.read(f) { java.io.RandomAccessFile(it, "r") }.use { raf ->
            raf.seek(offset); ByteArray(minOf(Views.CHUNK.toLong(), f.length() - offset).toInt()).also { raf.readFully(it) }
        }
        Out.Raw(buildJsonObject { put("file", name); put("size", f.length()); put("offset", offset); put("data", java.util.Base64.getEncoder().encodeToString(bytes)) }.encode())
    }

    tool(
        "lab_view_report_frame",
        """
        For the report views; not needed in a conversation. A picture of a report at `t` seconds into its recording, for a terminal pane: the video frame
        (needs ffmpeg), else the screenshot whose caption is `caption`, else the first screenshot.
        JSON with base64 `data` (png or rgb, as handsets_view_frame) fitted into w×h.
        """,
        str("report_id", "the report"),
        Param("t", "number", "seconds into the recording", JsonPrimitive(0)),
        str("caption", "the chapter's title, to pick its screenshot without ffmpeg", ""),
        str("file", "the video, default session.mp4", "session.mp4"),
        str("format", "png | rgb | file", "png", listOf("png", "rgb", "file")),
        int("w", "fit into this width in pixels (0 = any)", 0),
        int("h", "fit into this height in pixels (0 = any)", 0),
        readOnly = true,
        appOnly = true,
    ) { a ->
        val r = report(a.str("report_id"))
        val t = a.double("t").coerceAtLeast(0.0)
        val video = java.io.File(r.dir, a.str("file")).takeIf { it.isFile && it.name.matches(Regex("[A-Za-z0-9._-]+\\.mp4")) }
        val ffmpeg = no.agens.testlab.device.ToolLocator(appToolsDir = rt.dirs.cache).findOptional("ffmpeg")?.path
        val shots = r.dir.listFiles().orEmpty().filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }.sortedBy { it.name }
        val cap = a.str("caption").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        // A chapter that is a screenshot shows that screenshot: exact, where the recording lags.
        val shot = shots.firstOrNull { cap.isNotEmpty() && it.nameWithoutExtension.endsWith(cap) }
        var source = if (shot != null) "screenshot" else "video"
        val bytes = shot?.readBytesCloud() ?: (if (video != null && ffmpeg != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val p = ProcessBuilder(ffmpeg.path, "-hide_banner", "-loglevel", "error", "-ss", "%.2f".format(java.util.Locale.ROOT, t), "-i", video.path, "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "-")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.readBytes(); p.waitFor()
            out.takeIf { it.isNotEmpty() }
        } else null) ?: run {
            source = "screenshot"
            shots.firstOrNull()?.readBytesCloud() ?: throw BadArgument("report ${r.id} has no recording ffmpeg can read and no screenshots")
        }
        val f = Frames.convert(bytes, a.str("format"), a.int("w").coerceIn(0, 1600), a.int("h").coerceIn(0, 1600)) { png -> ring("report-" + r.id).write(png) }
        Out.Raw(buildJsonObject { put("source", source); put("t", t); put("format", f.format); put("w", f.w); put("h", f.h); put("data", f.data) }.encode())
    }
}
