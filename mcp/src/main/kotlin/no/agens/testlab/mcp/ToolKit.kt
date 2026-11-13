package no.agens.testlab.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.agens.testlab.services.runtime.LabEvent
import no.agens.testlab.services.runtime.LabRuntime
import java.util.Base64

/** A tool parameter; [type] is a JSON-schema type. Optional parameters carry their default. */
class Param(
    val name: String,
    val type: String,
    val description: String,
    val default: JsonElement? = null,
    val enum: List<String>? = null,
) {
    val required: Boolean get() = default == null

    fun schema(): JsonObject = buildJsonObject {
        when (type) {
            // Accepts a JSON string or the array itself: clients differ in how they send nested JSON.
            "plan" -> { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("array")) } }
            "array" -> { put("type", "array"); put("items", buildJsonObject { put("type", "object") }) }
            "strings" -> { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) }
            else -> put("type", type)
        }
        put("description", description)
        default?.takeIf { it !is JsonNull }?.let { put("default", it) }
        enum?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
    }

    companion object {
        fun str(name: String, description: String, default: String? = null, enum: List<String>? = null) =
            Param(name, "string", description, default?.let(::JsonPrimitive), enum)
        fun int(name: String, description: String, default: Int? = null) = Param(name, "integer", description, default?.let(::JsonPrimitive))
        fun num(name: String, description: String, default: Double? = null) = Param(name, "number", description, default?.let(::JsonPrimitive))
        fun bool(name: String, description: String, default: Boolean? = null) = Param(name, "boolean", description, default?.let(::JsonPrimitive))
        fun plan(name: String, description: String) = Param(name, "plan", description)
        /** A list of strings; optional ones default to empty. */
        fun strings(name: String, description: String, required: Boolean = false) =
            Param(name, "strings", description, if (required) null else kotlinx.serialization.json.JsonArray(emptyList()))
        /** The optional adb serial every device tool takes. */
        /** Which platform's repo, builds and project a call without a device means (ToolBox scopes the call to it). */
        val PLATFORM = str("platform", "android or ios: whose repo and builds (empty = the device in use, or the ticket's platform)", "")
        val SERIAL = str("serial", "the device's serial (an iOS Simulator's UDID; see handsets_list_adb_devices); empty = the active device", "")
    }
}

class BadArgument(message: String) : IllegalArgumentException(message)

/** A call's arguments with the declared defaults applied; lenient about numbers sent as strings. */
class Args(private val raw: JsonObject, private val params: Map<String, Param>) {
    private fun value(name: String): JsonElement? = raw[name]?.takeIf { it !is JsonNull } ?: params[name]?.default

    private fun prim(name: String): JsonPrimitive? = value(name) as? JsonPrimitive

    fun str(name: String): String = prim(name)?.content ?: throw BadArgument("missing argument '$name'")
    fun strOrNull(name: String): String? = prim(name)?.content?.takeIf { it.isNotEmpty() }
    fun int(name: String): Int = prim(name)?.let { p -> p.content.toIntOrNull() ?: p.doubleOrNull?.toInt() } ?: throw BadArgument("argument '$name' must be an integer")
    fun double(name: String): Double = prim(name)?.let { p -> p.doubleOrNull ?: p.content.toDoubleOrNull() } ?: throw BadArgument("argument '$name' must be a number")
    fun bool(name: String): Boolean = prim(name)?.let { p -> p.booleanOrNull ?: when (p.content.lowercase()) { "1", "yes", "true" -> true; "0", "no", "false", "" -> false; else -> null } }
        ?: throw BadArgument("argument '$name' must be true or false")
    fun json(name: String): JsonElement? = value(name)

    /** A string list: a JSON array, a JSON-encoded array in a string, or one item per line. */
    fun strings(name: String): List<String> = when (val v = value(name)) {
        null, is JsonNull -> emptyList()
        is kotlinx.serialization.json.JsonArray -> v.map { (it as? JsonPrimitive)?.content ?: it.toString() }
        is JsonPrimitive -> v.content.trim().let { t ->
            if (t.startsWith("[")) runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(t) as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content } }.getOrNull()
                ?: throw BadArgument("argument '$name' is not a JSON list")
            else t.lines()
        }
        else -> throw BadArgument("argument '$name' must be a list of strings")
    }.map { it.trim() }.filter { it.isNotEmpty() }
    /** Whether the caller passed [name] (as opposed to the declared default applying). */
    fun has(name: String): Boolean = raw[name] != null && raw[name] !is JsonNull
    val serial: String? get() = strOrNull("serial")

    /** A short, single-line description of the call for the activity feed (redacted by the caller). */
    fun summary(): String = raw.entries.filter { it.key != "serial" && it.value !is JsonNull }.joinToString(" ") { (k, v) ->
        val s = (v as? JsonPrimitive)?.content ?: v.toString()
        "$k=" + (if (s.length > 60) s.take(57) + "…" else s).replace('\n', ' ')
    }
}

sealed interface Out {
    data class Text(val text: String) : Out
    /** Text sent as is, not through the redactor: base64 of a file the tool already vetted (a report's video). */
    class Raw(val text: String) : Out
    class Image(val bytes: ByteArray, val mimeType: String = "image/jpeg", val caption: String? = null) : Out
}

class ToolDef(
    val name: String,
    val description: String,
    val params: List<Param>,
    val annotations: ToolAnnotations?,
    val run: suspend (Args) -> Out,
    /** An MCP Apps view (`ui://…`) the host shows for this tool's result. */
    val ui: String? = null,
    /**
     * A view's own tool (frame polling): kept out of the activity feed. Not marked app-only
     * (`visibility: ["app"]`), because hosts that honour that hide it from their plugins as well
     * (Claude Code's panes call these tools through its MCP connection).
     */
    val appOnly: Boolean = false,
) {
    /** `_meta.ui` for MCP Apps hosts (plus the older flat key some hosts still read). */
    fun meta(): JsonObject? = if (ui == null) null else kotlinx.serialization.json.buildJsonObject {
        put("ui", kotlinx.serialization.json.buildJsonObject {
            put("resourceUri", JsonPrimitive(ui))
            put("visibility", kotlinx.serialization.json.JsonArray(listOf("model", "app").map(::JsonPrimitive)))
        })
        put("ui/resourceUri", JsonPrimitive(ui))
    }

    val paramMap: Map<String, Param> = params.associateBy { it.name }

    /** Tools that act get `why`: the agent's reason, shown to the person watching (not passed to the tool). */
    val takesWhy: Boolean get() = annotations?.readOnlyHint != true && !appOnly

    fun schema(): ToolSchema = ToolSchema(
        properties = JsonObject(params.associate { it.name to it.schema() } + (if (takesWhy) mapOf(ToolBox.WHY to ToolBox.WHY_SCHEMA) else emptyMap())),
        required = params.filter { it.required }.map { it.name },
    )
}

/**
 * The registry: every tool result and error message goes through the runtime's redactor, and
 * every call is reported to the activity feed. Exceptions become `isError` results with a plain
 * message, never a stack trace.
 */
class ToolBox(private val rt: LabRuntime) {
    private val defs = LinkedHashMap<String, ToolDef>()
    val tools: Collection<ToolDef> get() = defs.values

    fun tool(
        name: String,
        description: String,
        vararg params: Param,
        readOnly: Boolean = false,
        destructive: Boolean = false,
        ui: String? = null,
        appOnly: Boolean = false,
        run: suspend (Args) -> Out,
    ) {
        require(name !in defs) { "duplicate tool $name" }
        val ann = ToolAnnotations(readOnlyHint = readOnly, destructiveHint = destructive.takeIf { !readOnly }, openWorldHint = false)
        defs[name] = ToolDef(name, description.trimIndent(), params.toList(), ann, run, ui, appOnly)
    }

    fun registerAll(server: Server) {
        for (d in defs.values) {
            server.addTool(d.name, d.description, d.schema(), toolAnnotations = d.annotations, meta = d.meta()) { req ->
                // The calling client goes along, so a write that needs a yes can ask through it (ClientApproval).
                kotlinx.coroutines.withContext(McpCaller(this)) { call(d.name, req.arguments ?: JsonObject(emptyMap())) }
            }
        }
    }

    /**
     * The platform a call is about, so it runs with that platform's profile even while the active
     * device is another one: its `platform`, else its device (`serial`), else its ticket's project.
     * Null = the active device's.
     */
    private fun platformOf(args: JsonObject): no.agens.testlab.core.Platform? {
        fun arg(k: String) = (args[k] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        arg("platform")?.let { return no.agens.testlab.core.Platform.of(it) ?: throw BadArgument("platform '$it': android or ios") }
        arg("serial")?.let { return rt.platformOf(it) }
        val key = listOf("key", "ticket", "query").firstNotNullOfOrNull { k -> arg(k)?.let(no.agens.testlab.services.jira.Jira::parseKey) } ?: return null
        return runCatching { rt.devtest.profileFor(key).platform }.getOrNull()
    }

    /** Runs a tool the way an MCP call does (also used by tests and the app's own buttons). */
    suspend fun call(name: String, rawArguments: JsonObject): CallToolResult {
        val d = defs[name] ?: return CallToolResult(listOf(TextContent("unknown tool $name")), isError = true)
        // `why` is for the person watching, never the tool's: taken off before the tool sees its arguments.
        val why = (rawArguments[WHY] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }?.take(200)?.let(rt.redactor::apply)
        val arguments = if (WHY in rawArguments) JsonObject(rawArguments - WHY) else rawArguments
        val args = Args(arguments, d.paramMap)
        val t0 = System.currentTimeMillis()
        val summary = rt.redactor.apply(args.summary())
        fun title(r: CallToolResult?) = runCatching { ActivityTitle.of(name, arguments, r) }.getOrNull()?.let(rt.redactor::apply)
        return try {
            unknownArgument(arguments, d)?.let { throw BadArgument(it) }
            val result = when (val out = rt.onPlatform(platformOf(arguments)) { d.run(args) }) {
                is Out.Text -> CallToolResult(listOf(TextContent(rt.redactor.apply(out.text))))
                is Out.Raw -> CallToolResult(listOf(TextContent(out.text)))
                is Out.Image -> CallToolResult(
                    listOfNotNull(
                        ImageContent(Base64.getEncoder().encodeToString(out.bytes), out.mimeType),
                        out.caption?.let { TextContent(rt.redactor.apply(it)) },
                    ),
                )
            }
            if (!d.appOnly) rt.emit(LabEvent.ToolCall(name, summary, System.currentTimeMillis() - t0, true, title(result), why))
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val msg = rt.redactor.apply(ToolErrors.describe(e))
            rt.emit(LabEvent.ToolCall(name, "$summary → $msg".take(300), System.currentTimeMillis() - t0, false, title(null)?.let { "$it → $msg".take(300) }, why))
            CallToolResult(listOf(TextContent(msg)), isError = true)
        }
    }

    /**
     * A tool whose schema comes from elsewhere (Proxyman's own MCP server): same redaction and
     * activity reporting as the built-in tools, the result passed through.
     */
    fun registerRaw(server: Server, name: String, description: String, schema: ToolSchema, run: suspend (JsonObject) -> CallToolResult) {
        server.addTool(name, description, schema) { req ->
            val args = req.arguments ?: JsonObject(emptyMap())
            val t0 = System.currentTimeMillis()
            val summary = rt.redactor.apply(Args(args, emptyMap()).summary())
            try {
                val r = run(args)
                rt.emit(LabEvent.ToolCall(name, summary, System.currentTimeMillis() - t0, r.isError != true))
                CallToolResult(r.content.map { c -> if (c is TextContent) TextContent(rt.redactor.apply(c.text)) else c }, isError = r.isError)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = rt.redactor.apply(ToolErrors.describe(e))
                rt.emit(LabEvent.ToolCall(name, "$summary → $msg".take(300), System.currentTimeMillis() - t0, false))
                CallToolResult(listOf(TextContent(msg)), isError = true)
            }
        }
    }

    private fun unknownArgument(arguments: JsonObject, d: ToolDef): String? {
        val unknown = arguments.keys.filter { it !in d.paramMap }
        return if (unknown.isEmpty()) null else "${d.name} has no argument ${unknown.joinToString { "'$it'" }} (it takes ${d.params.joinToString { it.name }.ifEmpty { "none" }})"
    }

    companion object {
        fun text(s: String) = Out.Text(s)
        const val WHY = "why"
        val WHY_SCHEMA = kotlinx.serialization.json.buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive("Optional: one short sentence on what you are doing and why (e.g. \"open the login screen to test the password field\"). Shown to the person watching the phone, not used by the tool."))
        }
    }
}

/** Turns failures into one plain sentence with the next step. */
object ToolErrors {
    fun describe(e: Throwable): String = when (e) {
        is no.agens.testlab.device.hs.AutomationBusy ->
            "${e.message} — close it, or call handsets_use_device(take_over=true) to stop it"
        is no.agens.testlab.device.hs.HsError -> when (e.tag) {
            "secure-window" -> "the screen is FLAG_SECURE (PIN and login pages): screenshots and dumps are blocked there, but taps and typing still work"
            else -> listOf(e.tag, e.detail).filter { it.isNotEmpty() }.joinToString(": ").ifEmpty { e.raw }
        }
        is no.agens.testlab.device.hs.HsDisconnected -> "${e.message} -> call handsets_use_device(serial) first (starts the daemon on the device)"
        is no.agens.testlab.core.autopilot.AutopilotError -> no.agens.testlab.core.autopilot.hint(e.message ?: "decision error")
        is no.agens.testlab.services.jira.JiraError -> e.message + (e.fixUrl?.let { " ($it)" } ?: "")
        is no.agens.testlab.services.github.GitHubError -> e.message + (e.fixUrl?.let { " ($it)" } ?: "")
        is BadArgument, is IllegalArgumentException, is IllegalStateException -> e.message ?: e::class.simpleName!!
        else -> e.message?.let { if (e is RuntimeException && e::class.simpleName != "RuntimeException") it else "${e::class.simpleName}: $it" } ?: e::class.simpleName!!
    }
}
