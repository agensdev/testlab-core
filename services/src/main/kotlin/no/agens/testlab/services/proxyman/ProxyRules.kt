package no.agens.testlab.services.proxyman

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import no.agens.testlab.services.runtime.LabRuntime
import java.io.File
import java.security.MessageDigest

/**
 * The Proxyman rules Testlab makes, with a lifecycle: each one is named with the session's tag
 * (`[tl <session>] …`), every proxy change is logged with its time (so a report can say which
 * evidence came from mocked data), and cleanup deletes them by that tag, lists again, and asks the
 * mocked URLs once more through the proxy. Proxyman reassigns rule ids, so a rule «deleted» by an old
 * id can survive: only the second listing and a real request show it is gone.
 */
class ProxyRules(private val rt: LabRuntime, private val px: Proxyman) {
    data class Rule(val id: String, val type: String, val name: String, val url: String) {
        val ours: Boolean get() = name.startsWith(TAG_PREFIX)
    }

    data class Change(val epoch: Double, val tool: String, val summary: String, val session: String)

    data class Cleanup(val deleted: List<Rule>, val survivors: List<Rule>, val checks: List<String>, val others: Int) {
        val clean: Boolean get() = survivors.isEmpty() && checks.none { it.startsWith("STILL MOCKED") }
        fun summary(): String = buildString {
            append("deleted ${deleted.size} Testlab rule(s)")
            if (survivors.isNotEmpty()) append("; ${survivors.size} SURVIVED the delete: ${survivors.joinToString { "${it.type} «${it.name}»" }} (delete them in Proxyman by hand)")
            if (others > 0) append("; $others rule(s) not made by Testlab left alone")
            checks.forEach { append("\n").append(it) }
            if (clean) append("\nverified: listed again, none of Testlab's rules are left" + if (checks.isEmpty()) "" else ", and the mocked URLs get real answers")
        }
    }

    private val logFile get() = File(rt.dirs.data, "proxy-changes.jsonl")
    private val sessionFile get() = File(rt.dirs.data, "proxy-session.txt")

    /** The tag of the session in progress: a new one per lab_session_start. */
    val session: String get() = sessionFile.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } ?: "0"

    fun newSession(): String {
        val s = java.lang.Long.toString(System.currentTimeMillis() / 1000 % 1_679_616, 36).padStart(4, '0')
        sessionFile.parentFile?.mkdirs()
        sessionFile.writeText(s)
        return s
    }

    fun tag(session: String = this.session) = "$TAG_PREFIX $session]"

    /** A create call's arguments with the session tag in front of its name (the rule's url when it has none). */
    fun tagged(tool: String, args: JsonObject): JsonObject {
        if (!tool.startsWith("create_") || !Proxyman.isRuleChange(tool)) return args
        val base = (args["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: listOf("url", "from_url", "hostname").firstNotNullOfOrNull { (args[it] as? JsonPrimitive)?.content } ?: tool.removePrefix("create_")
        val name = if (base.startsWith(TAG_PREFIX)) base else "${tag()} $base"
        val safe = safeName(name)
        return if (safe == (args["name"] as? JsonPrimitive)?.content) args else JsonObject(args + ("name" to JsonPrimitive(safe)))
    }

    /** Whether [tool] changes what the proxy does to traffic (rules, SSL lists, tool switches). */
    fun isChange(tool: String) = Proxyman.isRuleChange(tool) || tool == "delete_rule" || tool in SSL_TOOLS || tool == "toggle_tool"

    fun log(tool: String, args: JsonObject) {
        if (!isChange(tool)) return
        val body = (args["response_body"] as? JsonPrimitive)?.content
        val shown = args.filterKeys { it != "response_body" && it != "script_content" }.entries.joinToString(", ") { (k, v) ->
            "$k=" + ((v as? JsonPrimitive)?.content ?: v.toString()).take(160)
        } + (body?.let { ", response_body=${it.toByteArray().size} bytes sha ${sha(it)}" } ?: "")
        val line = buildJsonObject {
            put("epoch", System.currentTimeMillis() / 1000.0); put("tool", tool); put("summary", rt.redactor.apply(shown)); put("session", session)
            body?.let { put("body_sha", sha(it)) }
            (args["url"] as? JsonPrimitive)?.content?.let { put("url", it) }
            (args["method"] as? JsonPrimitive)?.content?.let { put("method", it) }
        }
        logFile.parentFile?.mkdirs()
        logFile.appendText(line.toString() + "\n")
    }

    /** Proxy changes since [sinceEpoch] (a report's start), oldest first. */
    fun changes(sinceEpoch: Double = 0.0): List<Change> = entries().filter { (it["epoch"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0 >= sinceEpoch }.map { o ->
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
        Change(s("epoch").toDoubleOrNull() ?: 0.0, s("tool"), s("summary"), s("session"))
    }

    private fun entries(): List<JsonObject> = logFile.takeIf { it.isFile }?.readLines()?.mapNotNull { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }.orEmpty()

    /** Every rule Proxyman lists now. */
    suspend fun list(): List<Rule> {
        val out = mutableListOf<Rule>()
        val d = StdioMcp.parseLoose(StdioMcp.text(px.call("list_rules", JsonObject(emptyMap()))))
        items(d).forEach { out += rule(it, null) ?: return@forEach }
        for ((tool, type) in listOf("list_reverse_proxies" to "reverse_proxy", "list_network_conditions" to "network_condition", "list_dns_spoofing" to "dns_spoofing")) {
            runCatching { items(StdioMcp.parseLoose(StdioMcp.text(px.call(tool, JsonObject(emptyMap()))))).forEach { out += rule(it, type) ?: return@forEach } }
        }
        return out
    }

    private fun items(d: JsonElement?): List<JsonObject> = when (d) {
        is JsonArray -> d.mapNotNull { it as? JsonObject }
        is JsonObject -> (d["rules"] as? JsonArray ?: d.values.firstOrNull { it is JsonArray } as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        else -> emptyList()
    }

    private fun rule(o: JsonObject, type: String?): Rule? {
        fun s(vararg k: String) = k.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }.orEmpty()
        val id = s("id", "ruleId", "rule_id").ifEmpty { return null }
        return Rule(id, type ?: s("type", "ruleType", "rule_type").ifEmpty { "maplocal" }, s("name", "displayName"), s("url", "from_url", "fromUrl", "hostname", "remote_host"))
    }

    /**
     * Deletes Testlab's rules of [session] (null = every Testlab rule, any session), lists again to
     * catch survivors, then asks each mocked GET URL of those rules through the proxy: an answer that
     * still has the mocked body means the rule lives on under another id.
     */
    suspend fun cleanup(session: String? = this.session): Cleanup {
        val all = list()
        val prefix = session?.let { tag(it) } ?: TAG_PREFIX
        val mine = all.filter { it.name.startsWith(prefix) }
        val deleted = mutableListOf<Rule>()
        for (r in mine) runCatching { px.call("delete_rule", buildJsonObject { put("id", r.id); put("rule_type", r.type) }); deleted += r }
        val survivors = runCatching { list().filter { it.name.startsWith(prefix) } }.getOrDefault(emptyList())
        val checks = mutableListOf<String>()
        val mocked = entries().filter { o ->
            (o["tool"] as? JsonPrimitive)?.content in Proxyman.MAP_LOCAL_TOOLS && (session == null || (o["session"] as? JsonPrimitive)?.content == session)
        }.mapNotNull { o -> (o["url"] as? JsonPrimitive)?.content?.let { u -> Triple(u, (o["method"] as? JsonPrimitive)?.content ?: "GET", (o["body_sha"] as? JsonPrimitive)?.content) } }
            .filter { (u, m, _) -> u.startsWith("http") && '*' !in u && m.uppercase() in setOf("GET", "HEAD") }.distinctBy { it.first }
        for ((url, method, bodySha) in mocked.take(5)) {
            val got = runCatching { px.fetchThrough(url, method) }.getOrElse { e -> checks += "not checked: $url (${e.message?.take(80)})"; continue }
            checks += if (bodySha != null && sha(got.second) == bodySha) "STILL MOCKED: $url answers with the rule's body after the delete: a zombie rule. Delete it in Proxyman by hand (or reset Proxyman)."
            else "real answer: $url → ${got.first}"
        }
        return Cleanup(deleted, survivors, checks, all.size - mine.size)
    }

    /**
     * Which phone a rule is for, as Proxyman can tell: the address its traffic comes from ([ip], see
     * Proxyman.deviceAddress), or a User-Agent fragment ([userAgent]) when it has no address of its own.
     */
    data class DeviceScope(val label: String, val ip: String? = null, val userAgent: String? = null) {
        /** The script lines that let every other device's traffic through unchanged. */
        fun guard(pass: String = "response"): String = when {
            ip != null -> "  const ip = ((context && context.flow) || {}).clientIpAddress || \"\";\n" +
                "  if (ip !== ${Json.encodeToString(JsonElement.serializer(), JsonPrimitive(ip))}) return $pass;\n"
            userAgent != null -> "  const ua = (request.headers['User-Agent'] || request.headers['user-agent'] || '');\n" +
                "  if (!ua.includes(${Json.encodeToString(JsonElement.serializer(), JsonPrimitive(userAgent))})) return $pass;\n"
            else -> ""
        }

        /** For the rule's name and the result: «only 198.51.100.21 (serial)». */
        val describe: String get() = "only " + (ip?.let { "$it ($label)" } ?: "User-Agent '$userAgent'")
    }

    /**
     * The common forcing recipes as one call, each made the way that works on this Proxyman:
     * - `status`: answer [url] with [status] (and [body]): a scripting rule, so the app gets the real
     *   status; where the scripting rule can't be made, Map Local (which serves 200 on Linux/Windows).
     * - `delay`: hold the response [delayMs] (a scripting rule).
     * - `rewrite`: the latest captured response of [url] with [patches] (dotted key paths → JSON values)
     *   applied, served by Map Local: works on every Proxyman.
     * - `block`: the request fails (a block-list rule).
     * [scope] keeps a scripting rule (status, delay) to one phone on a shared Proxyman; Map Local and
     * the block list can't be scoped, which the result says.
     */
    suspend fun force(
        kind: String, url: String, method: String, status: Int?, body: String?, delayMs: Long?, patches: JsonObject?, scope: DeviceScope?,
        operation: String? = null, keepBody: Boolean = false, match: String? = null,
    ): String {
        val m = method.uppercase().ifEmpty { "GET" }.let { if (it == "ANY") "GET" else it }
        fun result(r: JsonObject) = StdioMcp.text(r)
        val op = operation?.trim()?.takeIf { it.isNotEmpty() }
        // One GraphQL operation: Proxyman's own filter where it has one, else the script reads operationName itself.
        val opByProxyman = op != null && kind !in Sockets.KINDS && px.takes("create_scripting_rule", "graphql_query_name")
        val guard = scope?.guard().orEmpty() + (if (op != null && !opByProxyman && kind !in Sockets.KINDS) operationGuard(op) else "")
        val tag = scope?.let { " (${it.describe})" }.orEmpty()
        val opName = op?.let { " $it" }.orEmpty()
        val forOp = op?.let { "\n\nOnly the GraphQL operation $it" + (if (opByProxyman) " (Proxyman's operation filter)" else " (the script checks operationName in the request; Proxyman here has no operation filter)") + ": other operations on this URL pass unchanged." }.orEmpty()
        val forWhom = "\n\n" + (scope?.let { "Applies to ${it.describe}: other phones through this Proxyman get the real response." }
            ?: "Applies to every device whose traffic goes through this Proxyman.")
        val unscoped = scope?.let { "\n\nNOTE (${no.agens.testlab.AppIdentity.NAME}): this rule is Map Local or a block list, which can't be kept to one phone: it applies to every device through this Proxyman." }.orEmpty()
        val name = shortUrl(url)
        /** Proxyman's answer to the scripting rule, or why it refused it (Proxyman being unreachable is thrown as it is). */
        var refused = ""
        suspend fun script(what: String, code: String): String? {
            val r = try {
                px.call("create_scripting_rule", buildJsonObject {
                    put("url", url); put("method", m); put("name", "$what$opName $name$tag"); put("script_content", code)
                    if (opByProxyman) put("graphql_query_name", op)
                })
            } catch (e: ProxymanUnavailable) { throw e } catch (e: Exception) { refused = e.message ?: e::class.simpleName.orEmpty(); return null }
            if (StdioMcp.isError(r)) { refused = result(r).trim().take(300).ifEmpty { "an error without a message" }; return null }
            return result(r)
        }
        fun why() = "Proxyman refused the scripting rule ($refused)"
        /** A Map Local rule as the fallback; its failure is an error that names both refusals. */
        suspend fun mapLocal(args: JsonObject): String {
            if (op != null && !px.takes("create_map_local", "graphql_query_name")) throw IllegalStateException(why() + ", and this Proxyman's Map Local can't pick one GraphQL operation ($op): the rule would hit every operation on the URL, so none was made")
            val r = px.call("create_map_local", if (op != null) JsonObject(args + ("graphql_query_name" to JsonPrimitive(op))) else args)
            if (StdioMcp.isError(r)) throw IllegalStateException(why() + ", and then the Map Local rule as well: " + result(r).trim().take(300))
            return result(r)
        }
        return when (kind) {
            "status" -> {
                val code = status ?: throw IllegalArgumentException("status needs a status code")
                // An error status with the real body still carries the real data, and apps that read the body show it as if nothing failed.
                val sent = body ?: if (code >= 400 && !keepBody) errorBody(code) else null
                val bodyNote = when {
                    body != null -> ""
                    sent != null -> "\n\nBody: $sent (an error body instead of the real data; keep_body=true keeps the real body)."
                    code >= 400 -> "\n\nNOTE (${no.agens.testlab.AppIdentity.NAME}): keep_body: the real body (with its data) comes with the $code, so an app that reads the body may show the data as if nothing failed."
                    else -> ""
                }
                script("status $code", "async function onResponse(context, url, request, response) {\n$guard  response.statusCode = $code;\n" +
                    (sent?.let { "  response.headers['Content-Type'] = 'application/json';\n  response.body = ${Json.encodeToString(JsonElement.serializer(), JsonPrimitive(it))};\n" } ?: "") + "  return response;\n}\n")
                    ?.let { it + forWhom + forOp + bodyNote }
                    ?: run {
                        if (px.proxymanOs() == no.agens.testlab.device.HostOs.MAC) throw IllegalStateException(why() + ": see lab_proxyman_health")
                        mapLocal(buildJsonObject { put("url", url); put("method", m); put("status_code", code); put("response_body", sent ?: "{}"); put("name", "status $code$opName $name") }) +
                            "\n\nNOTE (${no.agens.testlab.AppIdentity.NAME}): ${why()}, so this is Map Local, which Proxyman on Linux/Windows serves with status 200 whatever status_code says: the app gets status 200 with this body. " +
                            "For a genuine $code, map the request to an endpoint that really answers $code (proxyman_create_map_remote), or block it (kind=block). Say which one the report's evidence came from." + unscoped
                    }
            }
            "delay" -> {
                val ms = delayMs ?: throw IllegalArgumentException("delay needs delay_ms")
                (script("delay ${ms}ms", "async function onResponse(context, url, request, response) {\n$guard  await sleep($ms);\n  return response;\n}\n")
                    ?: throw ProxymanUnavailable(why() + ". Use a breakpoint instead (proxyman_create_breakpoint on the response): the request is held until it is deleted")) + forWhom + forOp
            }
            "rewrite" -> {
                val p = patches?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("rewrite needs patches, e.g. {\"features.0.enabled\": false}")
                // The captured response checks the key paths first, so a typo is an error now and not a response that quietly stays the same.
                val original = body ?: if ('*' in url) null else latestResponse(url, m)
                val patched = original?.let { JsonPatch.apply(Json.parseToJsonElement(it), p) }
                if (body == null) script("rewrite ${p.keys.joinToString(",").take(40)}", rewriteScript(guard, p))?.let { r ->
                    return r + forWhom + forOp + "\n\nThe scripting rule patches each live response of this URL as it passes (${p.size} key path(s)" +
                        (if (patched == null) "; not checked against a captured response, so check the flow: a key path that isn't in the response is left alone" else ", checked against the latest captured response") +
                        "). Leave the screen and come back (or pull to refresh) so the app fetches it again."
                }
                if ('*' in url) throw IllegalArgumentException((if (body == null) why() + ". " else "") + "A Map Local rewrite needs the request's exact URL (without wildcards): it starts from that request's captured response")
                val served = patched ?: throw IllegalStateException((if (body == null) why() + ", and " else "") + "no captured response for $m $url yet: open that screen in the app once (the phone routed through Proxyman), or pass body")
                mapLocal(buildJsonObject { put("url", url); put("method", m); put("response_body", served.toString()); put("name", "rewrite ${p.keys.joinToString(",").take(40)}$opName $name") }) +
                    (if (body == null) "\n\nNOTE (${no.agens.testlab.AppIdentity.NAME}): ${why()}, so this serves a stored copy with Map Local." else "") + unscoped +
                    "\n\nrewrote ${p.size} key path(s) of the real response (${original.toByteArray().size} → ${served.toString().toByteArray().size} bytes). Leave the screen and come back (or pull to refresh) so the app fetches it again."
            }
            in Sockets.KINDS -> {
                if (kind == "ws_rewrite" && patches.isNullOrEmpty()) throw IllegalArgumentException("ws_rewrite needs patches: key paths in the server's JSON message, e.g. {\"payload.data.onScore.home\": 3}")
                if (kind == "ws_inject" && body.isNullOrBlank()) throw IllegalArgumentException("ws_inject needs body: the message to send to the app ({{id}} = the subscription's id)")
                if (kind == "ws_delay" && (delayMs ?: 0) <= 0) throw IllegalArgumentException("ws_delay needs delay_ms")
                val code = Sockets.script(kind, scope?.guard("message").orEmpty(), op, match, patches, body, delayMs)
                val r = try {
                    px.call("create_scripting_rule", buildJsonObject {
                        put("url", url); put("method", "GET"); put("name", "$kind$opName $name$tag"); put("script_content", code)
                        put("enable_request", true); put("enable_response", false); put("modify_websocket_messages", true)
                    })
                } catch (e: ProxymanUnavailable) { throw e } catch (e: Exception) { throw IllegalStateException("Proxyman refused the WebSocket scripting rule (${e.message})") }
                if (StdioMcp.isError(r)) throw IllegalStateException("Proxyman refused the WebSocket scripting rule (${result(r).trim().take(300)})")
                result(r) + forWhom + (op?.let { "\n\nOnly the GraphQL subscription $it: the script learns its id from the app's start/subscribe message, so open the screen after this rule exists." } ?: "") +
                    "\n\n" + Sockets.EFFECT.getValue(kind) + " Proxyman applies it to sockets opened from now on: leave and re-open the screen (or relaunch the app) so the app reconnects. lab_ws_messages shows the messages, with the ones the script changed marked." +
                    Sockets.SUBPROTOCOL_NOTE
            }
            "block" -> {
                if (op != null && !px.takes("create_blacklist", "graphql_query_name")) throw IllegalArgumentException("a block list can't pick one GraphQL operation ($op) on this Proxyman: it would block every operation on the URL. Force a status for the operation instead (kind=status)")
                result(px.call("create_blacklist", buildJsonObject { put("url", url); put("method", m); put("name", "block$opName $name"); if (op != null) put("graphql_query_name", op) })) + unscoped
            }
            else -> throw IllegalArgumentException("kind: status, delay, rewrite, block, or for WebSockets ${Sockets.KINDS.joinToString()}")
        }
    }

    /** The body of the newest captured response to [method] [url]. */
    private suspend fun latestResponse(url: String, method: String): String? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        val path = runCatching { java.net.URI(url).rawPath + (java.net.URI(url).rawQuery?.let { "?$it" } ?: "") }.getOrNull().orEmpty()
        val listed = StdioMcp.parseLoose(StdioMcp.text(px.call("get_flows", buildJsonObject { put("limit", 200); put("host_filter", host); put("method_filter", method) })))
        val flows = ((listed as? JsonObject)?.get("flows") as? JsonArray ?: listed as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
        val hit = flows.filter { it.s("path") == path || it.s("url") == url }.maxByOrNull { it.s("id").toLongOrNull() ?: 0 } ?: return null
        val detail = StdioMcp.parseLoose(StdioMcp.text(px.call("get_flow_detail", buildJsonObject { put("flow_id", hit.s("id")) }))) as? JsonObject ?: return null
        return findBody(detail)
    }

    private fun findBody(o: JsonObject): String? {
        for ((k, v) in o) {
            if (k.equals("responseBody", true) || k.equals("response_body", true)) (v as? JsonPrimitive)?.content?.let { return it }
            if (v is JsonObject) findBody(v)?.let { return it }
        }
        return null
    }

    companion object {
        const val TAG_PREFIX = "[tl"
        val SSL_TOOLS = setOf("enable_ssl_proxying", "disable_ssl_proxying", "toggle_ssl_proxying_domain")
        /** Hosts that stay out of SSL proxying (decrypting them breaks Google Play services, push and analytics upload). */
        val SSL_EXCLUDE_BASELINE = listOf("*.google.com", "*.googleapis.com")

        /**
         * A rule name Proxyman can store: on Linux it saves some rules (Map Local) as a file named after
         * the rule, so a `/` from a URL is a directory that doesn't exist and the rule fails. ASCII
         * letters, digits and a few marks only; the URL stays in the rule's matcher.
         */
        fun safeName(name: String): String = name.replace(Regex("[a-zA-Z][a-zA-Z0-9+.-]*://"), "").replace('/', ' ')
            .replace(Regex("[^A-Za-z0-9 _.,=+()\\[\\]'#@-]"), "").replace(Regex("\\s+"), " ").trim().take(100).trim()

        /** The URL as a rule name's last part: its host and path, without scheme or query. */
        fun shortUrl(url: String): String = url.substringAfter("://").substringBefore('?').trimEnd('/').take(60)

        /** The body an error status gets by default: GraphQL's errors list and a plain error field, so no client reads it as data. */
        fun errorBody(code: Int): String = "Testlab forced HTTP $code".let { msg ->
            buildJsonObject { put("errors", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("message", msg) }) }); put("error", msg) }.toString()
        }

        /** Script lines that let every other GraphQL operation through (operationName in the JSON body, or in the query of a GET). */
        fun operationGuard(op: String): String = "  let op = '';\n" +
            "  try { const b = typeof request.body === 'string' ? JSON.parse(request.body) : request.body; op = (b && b.operationName) || ''; } catch (e) {}\n" +
            "  if (!op && request.queries) op = request.queries.operationName || '';\n" +
            "  if (op !== ${Json.encodeToString(JsonElement.serializer(), JsonPrimitive(op))}) return response;\n"

        /** A script that patches each response (dotted key paths, `*` = every element of a list) the way [JsonPatch] does. */
        fun rewriteScript(guard: String, patches: JsonObject): String = """
            |async function onResponse(context, url, request, response) {
            |$guard  let doc = response.body;
            |  if (typeof doc === 'string') { try { doc = JSON.parse(doc); } catch (e) { return response; } }
            |  if (doc === null || typeof doc !== 'object') return response;
            |  const patches = $patches;
            |  for (const path of Object.keys(patches)) put(doc, path.split('.').filter(k => k.length > 0), patches[path]);
            |  response.body = doc;
            |  return response;
            |}
            |function put(node, keys, value) {
            |  if (node === null || typeof node !== 'object' || keys.length === 0) return;
            |  const k = keys[0], rest = keys.slice(1);
            |  if (Array.isArray(node) && k === '*') { for (let i = 0; i < node.length; i++) { if (rest.length === 0) node[i] = value; else put(node[i], rest, value); } return; }
            |  const key = Array.isArray(node) ? Number(k) : k;
            |  if (Array.isArray(node) && !(key >= 0 && key < node.length)) return;
            |  if (rest.length === 0) node[key] = value; else put(node[key], rest, value);
            |}
            |""".trimMargin()

        fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        /** A wildcard URL with `&` never matches in Proxyman (it splits the query): say so, and suggest a regex. */
        fun ampersandWarning(tool: String, args: JsonObject): String? {
            if (tool !in Proxyman.METHOD_TOOLS && tool !in Proxyman.SCRIPT_TOOLS) return null
            val url = listOf("url", "from_url").firstNotNullOfOrNull { (args[it] as? JsonPrimitive)?.content } ?: return null
            if ('&' !in url || (args["use_regex"] as? JsonPrimitive)?.content == "true") return null
            return "\n\nWARNING (${no.agens.testlab.AppIdentity.NAME}): this URL pattern contains '&'. Proxyman splits the query on it, so a wildcard pattern with '&' never matches: " +
                "use a regex without '&' instead (use_regex=true, e.g. `.*operational-messages.*page=%2F_app.*`)."
        }
    }
}
