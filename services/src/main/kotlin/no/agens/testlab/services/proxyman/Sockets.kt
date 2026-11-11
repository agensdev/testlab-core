package no.agens.testlab.services.proxyman

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * WebSockets through Proxyman: forced states on the messages (Proxyman's `onWebsocket` scripting hook,
 * checked on Proxyman 26.2.0 for Linux: edit, drop, inject and close, kept to one phone by address or
 * User-Agent), and the messages read back with GraphQL-over-WebSocket decoded (graphql-ws: AppSync,
 * Apollo; graphql-transport-ws).
 */
object Sockets {
    val KINDS = listOf("ws_rewrite", "ws_drop", "ws_delay", "ws_inject", "ws_close")

    val EFFECT = mapOf(
        "ws_rewrite" to "The script changes those key paths in each matching JSON message from the server.",
        "ws_drop" to "The script drops each matching message from the server: the app never gets it (stale data, a missed event).",
        "ws_delay" to "The script holds each matching message from the server before the app gets it.",
        "ws_inject" to "The script sends the message to the app when a matching message comes from the server (the server never sees it).",
        "ws_close" to "The script closes the socket at the first matching message from the server: the app has to notice and reconnect (every new socket is closed again while the rule exists).",
    )

    /** Proxyman (Linux 26.2.0 and earlier, checked 2026-11-02) doesn't pass the client's subprotocol on: what that breaks. */
    const val SUBPROTOCOL_NOTE = "\n\nNOTE: Proxyman doesn't pass the WebSocket subprotocol (Sec-WebSocket-Protocol) on to the server. " +
        "Sockets that need one (GraphQL over WebSocket: AWS AppSync, Apollo) fail while decrypted: the server answers NoProtocolError and closes, " +
        "so the app reconnects in a loop, with or without this rule. lab_ws_messages says when that happens."

    private fun js(s: String) = Json.encodeToString(JsonElement.serializer(), JsonPrimitive(s))

    /** A `/regex/flags` as it is, plain text as a substring test. */
    private fun matcher(m: String?): String = when {
        m.isNullOrBlank() -> "null"
        Regex("^/.+/[a-z]*$").matches(m) -> m
        else -> "{ test: (s) => s.includes(${js(m)}) }"
    }

    /**
     * The `onWebsocket` script for [kind]: [guard] lets other phones' sockets through; [op] keeps it to
     * one GraphQL subscription (its id learned from the app's start/subscribe message); [match] to the
     * server messages whose text matches.
     */
    fun script(kind: String, guard: String, op: String?, match: String?, patches: JsonObject?, body: String?, delayMs: Long?): String {
        val action = when (kind) {
            "ws_rewrite" -> "  if (!j) return message;\n  const patches = ${patches ?: JsonObject(emptyMap())};\n" +
                "  for (const path of Object.keys(patches)) put(j, path.split('.').filter(k => k.length > 0), patches[path]);\n" +
                "  message.data = JSON.stringify(j);\n  return message;"
            "ws_drop" -> "  return null;"
            "ws_delay" -> "  await sleep(${delayMs ?: 0});\n  return message;"
            "ws_inject" -> "  sendToClient(${js(body.orEmpty())}.split('{{id}}').join((j && j.id) || ''));\n  return message;"
            "ws_close" -> "  abort();\n  return null;"
            else -> throw IllegalArgumentException("not a WebSocket kind: $kind")
        }
        return """
            |const OP = ${op?.let(::js) ?: "null"};
            |const MATCH = ${matcher(match)};
            |var ops = {};
            |function opOf(j) {
            |  const p = j.payload || {};
            |  let d = p.data;
            |  if (typeof d === 'string') { try { d = JSON.parse(d); } catch (e) {} }
            |  const src = (d && typeof d === 'object') ? d : p;
            |  return src.operationName || (String(src.query || '').match(/(?:subscription|query|mutation)\s+(\w+)/) || [])[1] || '';
            |}
            |function put(node, keys, value) {
            |  if (node === null || typeof node !== 'object' || keys.length === 0) return;
            |  const k = keys[0], rest = keys.slice(1);
            |  if (Array.isArray(node) && k === '*') { for (let i = 0; i < node.length; i++) { if (rest.length === 0) node[i] = value; else put(node[i], rest, value); } return; }
            |  const key = Array.isArray(node) ? Number(k) : k;
            |  if (Array.isArray(node) && !(key >= 0 && key < node.length)) return;
            |  if (rest.length === 0) node[key] = value; else put(node[key], rest, value);
            |}
            |async function onWebsocket(context, url, request, response, message) {
            |$guard  if (message.type !== 'text') return message;
            |  let j = null;
            |  try { j = JSON.parse(message.data); } catch (e) {}
            |  if (message.from === 'client') {
            |    if (j && j.id && (j.type === 'start' || j.type === 'subscribe')) ops[j.id] = opOf(j);
            |    return message;
            |  }
            |  if (OP && !(j && j.id && ops[j.id] === OP)) return message;
            |  if (MATCH && !MATCH.test(message.data)) return message;
            |$action
            |}
            |""".trimMargin()
    }

    data class Msg(val index: Int, val from: String, val at: String, val type: String?, val id: String?, val op: String?, val text: String, val scriptAction: String?)

    /** The GraphQL-over-WebSocket reading of one message: its type, subscription id and (from start/subscribe) operation. */
    fun decode(text: String): Triple<String?, String?, String?> {
        val j = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return Triple(null, null, null)
        fun s(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
        val type = s(j, "type")
        val p = j["payload"] as? JsonObject
        val d = (p?.get("data") as? JsonPrimitive)?.content?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: p
        val op = if (type in setOf("start", "subscribe")) s(d, "operationName") ?: s(d, "query")?.let { Regex("(?:subscription|query|mutation)\\s+(\\w+)").find(it)?.groupValues?.get(1) } else null
        return Triple(type, s(j, "id"), op)
    }

    /** Why a socket died at once, when it is the subprotocol Proxyman didn't pass on. */
    fun subprotocolProblem(msgs: List<Msg>): String? =
        msgs.firstOrNull { it.from == "server" && Regex("NoProtocolError|[Ss]ubprotocol|Sec-WebSocket-Protocol").containsMatchIn(it.text) }?.let {
            "the server refused the socket for its subprotocol («${it.text.take(120)}»): Proxyman doesn't pass Sec-WebSocket-Protocol on, " +
                "so this socket fails whenever it is decrypted, and the app's realtime feature is broken by the proxy, not the app. " +
                "To use the feature while routed, exclude the host from SSL proxying (proxyman_toggle_ssl_proxying_domain, list=exclude) and say so in the report; its messages can't be read or forced then."
        }

    /** The sessions (newest first) and their messages, from Proxyman's list_websocket_sessions/get_websocket_messages. */
    suspend fun read(px: Proxyman, host: String?, sessions: Int, perSession: Int, flowId: String?): JsonObject {
        val first = px.call("list_websocket_sessions", JsonObject(emptyMap()))
        if (StdioMcp.isError(first) || !px.takes("get_websocket_messages", "flow_id")) throw ProxymanUnavailable("this Proxyman's MCP has no WebSocket message tools (list_websocket_sessions/get_websocket_messages): update Proxyman (26.x has them on Linux too), or read the messages in the Proxyman window (WebSocket tab)")
        val listed = StdioMcp.parseLoose(StdioMcp.text(first)) as? JsonObject
        val all = (listed?.get("sessions") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
        val picked = all.filter { o -> (flowId == null || o.s("flow_id") == flowId) && (host == null || host in o.s("host") || host in o.s("url")) }.take(if (flowId != null) 1 else sessions)
        val fetched = picked.map { o ->
            o to ((StdioMcp.parseLoose(StdioMcp.text(px.call("get_websocket_messages", buildJsonObject { put("flow_id", o.s("flow_id")) }))) as? JsonObject)
                ?.get("messages") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }
        return buildJsonObject {
            put("sessions_found", all.size)
            if (picked.isEmpty()) put("note", "no WebSocket session" + (host?.let { " for $it" } ?: "") + ". Is the phone routed (handsets_proxy) and the host decrypted (proxyman_get_ssl_proxying_list)? A bare CONNECT tunnel to the host in the flows means it isn't.")
            putJsonArray("sessions") {
                for ((o, raw) in fetched) {
                    val ops = mutableMapOf<String, String>()
                    val msgs = raw.map { m ->
                        val text = m.s("payload_preview")
                        val (type, id, op) = decode(text)
                        if (id != null && op != null) ops[id] = op
                        Msg(m.s("index").toIntOrNull() ?: 0, m.s("direction"), m.s("created_at"), type, id, op ?: id?.let { ops[it] }, text, m.s("script_action").ifEmpty { null })
                    }
                    add(buildJsonObject {
                        put("flow_id", o.s("flow_id")); put("url", o.s("url").substringBefore('?')); put("messages", msgs.size)
                        o.s("started_at").toLongOrNull()?.let { put("started", java.time.Instant.ofEpochMilli(it).toString()) }
                        subprotocolProblem(msgs)?.let { put("problem", it) }
                        if (ops.isNotEmpty()) put("subscriptions", ops.entries.joinToString { "${it.value} (id ${it.key})" })
                        val changed = msgs.count { it.scriptAction != null }
                        if (changed > 0) put("changed_by_script", changed)
                        putJsonArray("last") {
                            for (m in msgs.filter { it.type !in setOf("ka", "ping", "pong") }.takeLast(perSession)) add(buildJsonObject {
                                put("i", m.index); put("from", m.from); put("at", m.at)
                                m.type?.let { put("type", it) }; m.op?.let { put("operation", it) }; m.scriptAction?.let { put("script", it) }
                                put("text", if (m.text.length > 600) m.text.take(600) + "…" else m.text)
                            })
                        }
                        val ka = msgs.count { it.type in setOf("ka", "ping", "pong") }
                        if (ka > 0) put("keep_alives_left_out", ka)
                    })
                }
            }
        }
    }
}
