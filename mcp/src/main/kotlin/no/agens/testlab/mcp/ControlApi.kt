package no.agens.testlab.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import no.agens.testlab.AppIdentity
import no.agens.testlab.services.builds.BuildJob
import no.agens.testlab.services.builds.buildError
import no.agens.testlab.services.jira.Jira
import no.agens.testlab.services.runtime.LabEvent
import no.agens.testlab.services.runtime.Approvals
import no.agens.testlab.services.runtime.LabRuntime
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap

/** A request the API can't do, worded for the calling app's user. */
class ApiError(val status: HttpStatusCode, message: String) : RuntimeException(message)

/**
 * The local control API: a small JSON/REST surface on the MCP server's port (`/api/v1`) for host
 * apps such as a team's own desktop tool — list devices, install a PR's build, start a devtest on
 * a ticket (with the prompt for the host's own Claude), follow progress as server-sent events.
 * Same bearer token and loopback-only binding as MCP, plus a Host check and no browser origins.
 * Writes to Jira still wait for a yes (the approvals page, `testlab approve`, or this API's own
 * /approvals). See docs/control-api.yaml.
 */
class ControlApi(
    private val rt: LabRuntime,
    /** Runs a view tool as MCP would (the panes' frames, activity, reports and keys). */
    private val viewCall: (suspend (String, JsonObject) -> io.modelcontextprotocol.kotlin.sdk.types.CallToolResult)? = null,
    /** The browser pages' own routes (profiles, setup, tickets), under the same `/api/v1`. */
    private val ui: UiPages? = null,
) {
    private val json = Json { prettyPrint = false; explicitNulls = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Install jobs started through the API, by id. */
    private val started = ConcurrentHashMap<String, String>()

    fun Route.mount() = route("/api/v1") {
        get("/status") { ok { status() } }
        get("/devices") { ok { devices() } }
        post("/devices/{serial}/connect") { ok { val d = rt.use(param("serial")); buildJsonObject { put("serial", d.serial); put("active", true) } } }
        get("/profiles") { ok { profiles() } }
        post("/profiles/{id}/activate") { ok { rt.profiles.activate(param("id")); profiles() } }
        get("/builds/branch") { ok { branch(call.request.queryParameters["ref"] ?: throw ApiError(HttpStatusCode.BadRequest, "ref is required (a PR number, PR URL or branch)"), call.request.queryParameters["type"]) } }
        post("/builds/install") { ok { installBuild(body()) } }
        get("/jobs") { ok { jobs() } }
        get("/jobs/{id}") { ok { job(param("id")) } }
        post("/devtest") { ok { startDevtest(body()) } }
        get("/devtest/{key}") { ok { devtest(Jira.checkKey(param("key"))) } }
        get("/approvals") { ok { approvals() } }
        get("/approvals/{id}") { ok { approvalJson(rt.approvals.get(param("id")) ?: throw ApiError(HttpStatusCode.NotFound, "no pending approval ${param("id")} (answered or timed out)")) } }
        post("/approvals/{id}") { ok { answerApproval(param("id"), body()) } }
        get("/events") { events(call) }
        // The live views' own calls, for a host whose tool calls go through permission prompts
        // (Claude Code's panes): only these tools, the same results as over MCP.
        // Not redacted again here: the tools redact their own text, and their raw payloads (base64 of a
        // frame or a video) must reach the page whole; a PIN like 1234 turns up inside base64.
        post("/view/call") { ok(redact = false) { viewTool(body()) } }
        ui?.let { with(it) { mountApi() } }
    }

    // --- handlers ---

    private fun status() = buildJsonObject {
        put("app", AppIdentity.NAME); put("version", AppIdentity.VERSION); put("api", 1)
        put("mode", mode)
        put("pending_approvals", rt.approvals.pending.value.size)
        putJsonObject("profile") { val p = rt.profile; put("id", p.id); put("name", p.name); put("repo", p.github.repo); put("jira", p.tracker.site) }
        put("active_device", rt.active.value?.let(::JsonPrimitive) ?: JsonNull)
        putJsonArray("recording") { rt.recordings.active.value.forEach { add(JsonPrimitive(it)) } }
    }

    /** Always "service" now (there is no window); kept in the answer for host apps that read it. */
    val mode: String get() = "service"

    private fun approvalJson(p: Approvals.Pending) = buildJsonObject {
        put("id", p.id); put("action", p.action); put("detail", p.detail)
        put("created", p.created.toString()); put("expires", p.expires.toString())
    }

    private fun approvals() = buildJsonObject {
        putJsonArray("pending") { rt.approvals.pending.value.forEach { add(approvalJson(it)) } }
        putJsonArray("resolved") {
            rt.approvals.resolved.value.take(10).forEach { r -> add(buildJsonObject { put("id", r.id); put("action", r.action); put("approved", r.approved); put("by", r.by); put("at", r.at.toString()) }) }
        }
    }

    private fun answerApproval(id: String, b: JsonObject): JsonObject {
        val approved = (b["approve"] as? JsonPrimitive)?.booleanOrNull ?: throw ApiError(HttpStatusCode.BadRequest, "body must be {\"approve\": true|false}")
        val by = b.s("by")?.take(40)?.let { "api:$it" } ?: "api"
        if (!rt.approvals.answer(id, approved, by)) throw ApiError(HttpStatusCode.NotFound, "no pending approval $id (answered or timed out)")
        return buildJsonObject { put("id", id); put("approved", approved); put("by", by) }
    }

    private fun devices() = buildJsonObject {
        put("active", rt.active.value?.let(::JsonPrimitive) ?: JsonNull)
        putJsonArray("devices") {
            for (d in runCatching { rt.listDevices() }.getOrElse { throw ApiError(HttpStatusCode.ServiceUnavailable, it.message ?: "adb is not available") }) addJsonObject {
                put("serial", d.serial); put("state", d.state); put("transport", d.transport); put("ready", d.ready)
                d.model?.let { put("model", it) }; d.hint?.let { put("hint", it) }
                put("lease", rt.leases.state(d.serial))
            }
        }
    }

    private fun profiles() = buildJsonObject {
        put("active", rt.profile.id)
        putJsonArray("profiles") { for (e in rt.profiles.all()) addJsonObject { put("id", e.profile.id); put("name", e.profile.name); put("bundled", e.bundled) } }
    }

    private suspend fun branch(ref: String, type: String?) = rt.builds.branchBuild(ref, rt.builds.type(type)).let { b ->
        buildJsonObject {
            put("type", b.type.id); put("branch", b.branch); put("run_id", b.run.id); put("head_sha", b.run.headSha); put("latest_commit", b.isLatest)
            put("artifact", b.artifact?.let { a -> buildJsonObject { put("id", a.id); put("name", a.name); put("bytes", a.bytes); put("expired", a.expired) } } ?: JsonNull)
            put("pr", b.pr?.let { pr -> buildJsonObject { put("number", pr.number); put("title", pr.title); put("draft", pr.draft) } } ?: JsonNull)
            put("building", b.building?.let { r -> buildJsonObject { put("run_id", r.id); put("url", r.htmlUrl) } } ?: JsonNull)
            put("run_url", b.run.htmlUrl)
        }
    }

    private suspend fun installBuild(b: JsonObject): JsonObject {
        val ref = b.s("ref") ?: throw ApiError(HttpStatusCode.BadRequest, "ref is required (a PR number, PR URL or branch)")
        val type = rt.builds.type(b.s("type"))
        val serial = b.s("serial") ?: rt.active.value ?: rt.use().serial
        val id = "i" + System.currentTimeMillis().toString(36) + (0..999).random()
        started[id] = "pending@$serial"
        scope.launch {
            try {
                val o = rt.builds.installBranchBuild(ref, type, serial)
                started[id] = "a${o.build?.artifact?.id}@$serial"
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                started[id] = "failed:" + buildError(e)
            }
        }
        return buildJsonObject { put("job", id); put("serial", serial); put("type", type.id); put("ref", ref) }
    }

    private fun jobJson(j: BuildJob) = buildJsonObject {
        put("key", j.key); put("title", j.title); put("serial", j.serial ?: ""); put("phase", j.phase.name.lowercase())
        put("done", j.done); put("total", j.total?.let(::JsonPrimitive) ?: JsonNull); put("message", j.message ?: ""); j.problem?.let { put("problem", it.code) }
    }

    private fun jobs() = buildJsonObject { putJsonArray("jobs") { rt.builds.jobs.value.forEach { add(jobJson(it)) } } }

    private fun job(id: String): JsonObject {
        val ref = started[id] ?: throw ApiError(HttpStatusCode.NotFound, "no job '$id'")
        if (ref.startsWith("failed:")) return buildJsonObject { put("job", id); put("phase", "failed"); put("message", ref.removePrefix("failed:")) }
        val serial = ref.substringAfter('@')
        // Until the artifact is known, a job still going on that device is this one; a finished one
        // there is an earlier install's, and must not make this one look done before it began.
        val j = rt.builds.jobs.value.lastOrNull { it.key == ref }
            ?: rt.builds.jobs.value.lastOrNull { it.serial == serial && it.phase != BuildJob.Phase.DONE && it.phase != BuildJob.Phase.FAILED }
        return j?.let { JsonObject(jobJson(it) + ("job" to JsonPrimitive(id))) } ?: buildJsonObject { put("job", id); put("phase", "starting") }
    }

    private suspend fun startDevtest(b: JsonObject): JsonObject {
        val key = Jira.checkKey(b.s("key") ?: throw ApiError(HttpStatusCode.BadRequest, "key is required (a Jira issue key)"))
        val pr = b.s("pr")?.trimStart('#')?.toIntOrNull()
        val serial = b.s("serial")
        val start = b.s("start") ?: "none"
        if (start !in setOf("none", "report", "claude")) throw ApiError(HttpStatusCode.BadRequest, "start is none, report or claude")
        rt.devtest.choose(key, pr, b.s("branch"), serial)
        var reportId: String? = null
        if (start == "report" || start == "claude") {
            val s = serial ?: rt.active.value
            if (s != null && s !in rt.recordings.active.value) runCatching { rt.recordings.start(s) }
            reportId = rt.reports.start("${rt.devtest.profileFor(key).report.title} $key", key).also { rt.devtest.linkReport(key, it) }
        }
        // The host starts Claude itself (its own terminal or agent), with the prompt the devtest needs.
        val prompt = if (start == "claude") rt.devtest.prompt(key, null, rt.devtest.teamCommandIn(rt.settings.current.claude.workDir?.let(::File))) else null
        return JsonObject(devtest(key) + ("report_id" to (reportId?.let(::JsonPrimitive) ?: JsonNull)) + ("claude_prompt" to (prompt?.let(::JsonPrimitive) ?: JsonNull)))
    }

    private fun devtest(key: String): JsonObject {
        val st = rt.devtest.state()[key]
        return buildJsonObject {
            put("key", key)
            put("url", rt.jira.browseUrl(key))
            put("pr", st?.pr?.let(::JsonPrimitive) ?: JsonNull)
            put("branch", st?.branch?.let(::JsonPrimitive) ?: JsonNull)
            put("serial", st?.serial?.let(::JsonPrimitive) ?: JsonNull)
            put("report_id", st?.reportId?.let(::JsonPrimitive) ?: JsonNull)
            put("report_page", st?.reportId?.let { id -> runCatching { File(rt.reports.folder(id), "index.html").takeIf { it.isFile }?.toURI()?.toString() }.getOrNull() }?.let(::JsonPrimitive) ?: JsonNull)
            put("build", rt.devtest.buildLine(rt.devtest.installed(st?.serial))?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    /** Server-sent events: tool calls, hand-backs, device changes and job progress, as they happen. */
    private suspend fun events(call: ApplicationCall) {
        call.respondTextWriter(ContentType.Text.EventStream) {
            write(": ${AppIdentity.SLUG} events\n\n"); flush()
            merge(
                rt.events.map { e ->
                    when (e) {
                        is LabEvent.ToolCall -> "tool" to buildJsonObject { put("tool", e.tool); put("summary", e.summary); put("ms", e.ms); put("ok", e.ok); e.title?.let { put("title", it) }; e.why?.let { put("why", it) } }
                        is LabEvent.HandBack -> "hand_back" to buildJsonObject { put("tool", e.tool); put("serial", e.serial ?: ""); put("result", e.result) }
                        is LabEvent.DeviceChanged -> "device" to buildJsonObject { put("active", e.active?.let(::JsonPrimitive) ?: JsonNull) }
                    }
                },
                rt.builds.jobs.drop(1).map { list -> "jobs" to buildJsonObject { putJsonArray("jobs") { list.forEach { add(jobJson(it)) } } } },
                rt.approvals.pending.drop(1).map { list -> "approvals" to buildJsonObject { putJsonArray("pending") { list.forEach { add(approvalJson(it)) } } } },
            ).collect { (type, data) ->
                write("event: $type\ndata: ${rt.redactor.apply(data.toString())}\n\n")
                flush()
            }
        }
    }

    // --- plumbing ---

    private suspend fun viewTool(b: JsonObject): JsonElement {
        val run = viewCall ?: throw ApiError(HttpStatusCode.NotFound, "no views here")
        val tool = (b["tool"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: throw ApiError(HttpStatusCode.BadRequest, "tool is required")
        if (tool !in VIEW_TOOLS) throw ApiError(HttpStatusCode.Forbidden, "$tool is not a view tool (${VIEW_TOOLS.joinToString()})")
        val r = run(tool, b["args"] as? JsonObject ?: JsonObject(emptyMap()))
        return buildJsonObject {
            put("isError", r.isError == true)
            put("content", kotlinx.serialization.json.JsonArray(r.content.mapNotNull { c ->
                when (c) {
                    is io.modelcontextprotocol.kotlin.sdk.types.TextContent -> buildJsonObject { put("type", "text"); put("text", c.text) }
                    is io.modelcontextprotocol.kotlin.sdk.types.ImageContent -> buildJsonObject { put("type", "image"); put("data", c.data); put("mimeType", c.mimeType) }
                    else -> null
                }
            }))
        }
    }

    private fun io.ktor.server.routing.RoutingContext.param(name: String) = call.parameters[name] ?: throw ApiError(HttpStatusCode.BadRequest, "missing $name")

    private suspend fun io.ktor.server.routing.RoutingContext.body(): JsonObject {
        val t = call.receiveText().ifBlank { "{}" }
        return runCatching { json.parseToJsonElement(t) as JsonObject }.getOrElse { throw ApiError(HttpStatusCode.BadRequest, "the body is not a JSON object") }
    }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun io.ktor.server.routing.RoutingContext.ok(redact: Boolean = true, work: suspend () -> JsonElement) {
        val (status, body) = try {
            HttpStatusCode.OK to work()
        } catch (e: CancellationException) { throw e } catch (e: ApiError) {
            e.status to err(e.message)
        } catch (e: no.agens.testlab.services.runtime.NotApproved) {
            HttpStatusCode.Forbidden to err(e.message)
        } catch (e: IllegalArgumentException) {
            HttpStatusCode.BadRequest to err(ToolErrors.describe(e))
        } catch (e: no.agens.testlab.services.builds.NoBuild) {
            HttpStatusCode.NotFound to err(e.message)
        } catch (e: Exception) {
            HttpStatusCode.BadGateway to err(ToolErrors.describe(e))
        }
        call.respondText(if (redact) rt.redactor.apply(body.toString()) else body.toString(), ContentType.Application.Json, status)
    }

    private fun err(message: String?) = buildJsonObject { putJsonObject("error") { put("message", message ?: "error") } }

    companion object {
        /**
         * `<data>/control.json` (0600): where a host app finds the API and its token. Written while
         * the server runs, removed when it stops.
         */
        /** What `/view/call` runs: reading frames, activity and reports, and the panes' keys. */
        val VIEW_TOOLS = setOf("handsets_show_screen", "lab_show_report", "handsets_view_frame", "handsets_view_activity", "handsets_navigate", "handsets_swipe", "lab_view_report", "lab_view_report_frame", "lab_view_report_file")

        fun writeDiscovery(file: File, url: String, token: String, mode: String = "service") {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.createNewFile()
            runCatching { Files.setPosixFilePermissions(file.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
            file.writeText(buildJsonObject {
                put("app", AppIdentity.NAME); put("version", AppIdentity.VERSION); put("api", url.removeSuffix("/mcp") + "/api/v1")
                put("mcp", url); put("token", token); put("pid", ProcessHandle.current().pid()); put("mode", mode)
            }.toString())
        }
    }
}
