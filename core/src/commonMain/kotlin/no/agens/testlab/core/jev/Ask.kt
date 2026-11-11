package no.agens.testlab.core.jev

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The HTTP POST the decision client needs; the JVM side provides a Ktor implementation. */
fun interface HttpPost {
    /** Returns (status, body). Throws on transport errors (connect failure, timeout). */
    suspend fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long, connectTimeoutMs: Long?): HttpReply
}

data class HttpReply(val status: Int, val text: String)

/**
 * The only network call of the engine: one System One request with both questions (or the same
 * two questions as one OpenAI Decisions request, see [OpenAIDecisions]).
 * Retries, error messages and Ollama failover follow jevpick.py `ask_jev` /
 * `_ask_ollama_failover`. One instance per app: it remembers which Ollama hosts are down.
 */
class DecisionClient(
    private val config: JevConfig,
    private val http: HttpPost,
    /** TypeSafe key, read when needed so a changed key applies without a restart. */
    private val apiKey: () -> String?,
    private val nowMs: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** OpenAI key for [Backend.OPENAI], read when needed like [apiKey]. */
    private val openaiKey: () -> String? = { null },
) {
    private val ollamaDownUntil = HashMap<String, Long>()
    private val downLock = Mutex()
    private val json = Json { encodeDefaults = true }

    suspend fun ask(
        state: JsonObject,
        question: JsonObject,
        backend: Backend = config.backend,
        stub: Boolean = false,
        model: String? = null,
        url: String? = null,
    ): Decision {
        if (stub) return StubDecider.decide(state, question, config.stubConfidence)
        val nOpts = question.values.maxOf { q -> ((q as? JsonObject)?.get("criteria") as? JsonObject)?.size ?: 0 }
        val reqUrl: String
        val reqModel: String
        val timeoutS: Double
        val key: String
        when (backend) {
            Backend.OLLAMA -> {
                if (nOpts > OLLAMA_MAX_OPTIONS) {
                    throw JevError("ollama backend allows at most $OLLAMA_MAX_OPTIONS options per question, got $nOpts")
                }
                if (url == null) return askOllamaFailover(state, question, model)
                reqUrl = url
                reqModel = model ?: config.ollamaModel
                timeoutS = config.ollamaTimeoutS
                key = ""
            }
            Backend.OPENAI -> return askOpenAI(state, question, model, url)
            Backend.TYPESAFE -> {
                reqUrl = url ?: config.typesafeUrl
                reqModel = model ?: config.model
                timeoutS = config.timeoutS
                key = apiKey().orEmpty()
                if (key.isEmpty()) {
                    throw JevError("TypeSafe API key not set - add it in Settings (or run with the offline stub)")
                }
            }
        }
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("state", state); put("model", reqModel); put("questions", question) },
        )
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (key.isNotEmpty()) put("Authorization", "Bearer $key")
        }
        val connectMs = if (backend == Backend.OLLAMA) (config.ollamaConnectTimeoutS * 1000).toLong() else null
        val (text, latency) = postRetrying(reqUrl, headers, body, timeoutS, connectMs, "TypeSafe API key missing or invalid")
        try {
            val data = Json.parseToJsonElement(text).jsonObject
            val answers = data["answers"]!!.jsonObject
            val ans = answers["next"]!!.jsonObject
            val probs = LinkedHashMap<String, Double>()
            (ans["probabilities"] as? JsonObject)?.forEach { (k, v) -> probs[k] = v.jsonPrimitive.number() }
            val doneAns = answers["done"] as? JsonObject
            val done = doneAns?.get("noul")?.jsonPrimitive?.number()
            val conf = ans["confidence"]?.jsonPrimitive?.number() ?: (probs.values.maxOrNull() ?: 0.0)
            val raw = JsonObject(data + ("_backend" to JsonPrimitive(backend.id)))
            return Decision(
                choice = ans["choice"]!!.jsonPrimitive.content,
                confidence = conf,
                probabilities = probs,
                model = (data["model"] as? JsonPrimitive)?.content ?: reqModel,
                usage = (data["usage"] as? JsonObject) ?: JsonObject(emptyMap()),
                latencyMs = latency,
                raw = raw,
                done = done,
            )
        } catch (e: JevError) {
            throw e
        } catch (e: Exception) {
            throw JevError("Jev response not understood: ${e.message}: ${PyText.take(text, 300)}", e)
        }
    }

    /** POST with the 429/529 backoff; returns (body, latency) of a 200, else throws a [JevError] naming the status. */
    private suspend fun postRetrying(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutS: Double,
        connectMs: Long?,
        unauthorized: String,
    ): Pair<String, Long> {
        val t0 = nowMs()
        var status = 0
        var text = ""
        val waits = listOf(0.0) + BACKOFF_S
        for ((attempt, wait) in waits.withIndex()) {
            if (wait > 0) sleep((wait * 1000).toLong())
            try {
                val r = http.post(url, headers, body, (timeoutS * 1000).toLong(), connectMs)
                status = r.status
                text = r.text
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (attempt == BACKOFF_S.size) throw JevError("Jev request failed: ${e.message ?: e::class.simpleName}", e)
                continue
            }
            if (status == 429 || status == 529) continue
            break
        }
        val latency = nowMs() - t0
        if (status == 429 || status == 529) throw JevError("Jev $status after ${BACKOFF_S.size + 1} attempts (rate limit / overloaded)")
        if (status == 401) throw JevError("Jev 401: $unauthorized")
        if (status != 200) throw JevError("Jev HTTP $status: ${PyText.take(text, 300)}")
        return text to latency
    }

    /** The two questions as one OpenAI Decisions request; the answer comes back in System One's shape. */
    private suspend fun askOpenAI(state: JsonObject, question: JsonObject, model: String?, url: String?): Decision {
        val key = openaiKey().orEmpty()
        if (key.isEmpty()) throw JevError("OpenAI API key not set - add it in Settings (or set OPENAI_API_KEY)")
        val reqModel = model ?: config.openaiModel
        val body = json.encodeToString(JsonObject.serializer(), OpenAIDecisions.request(state, question, reqModel))
        val headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $key")
        val (text, latency) = postRetrying(url ?: config.openaiUrl, headers, body, config.openaiTimeoutS, null, "OpenAI API key missing or invalid")
        return OpenAIDecisions.parse(text, reqModel, latency, question)
    }

    /** Tries each URL in order; connect failures, timeouts and 404s mark a host down for the backoff. */
    private suspend fun askOllamaFailover(state: JsonObject, question: JsonObject, model: String?): Decision {
        val errors = mutableListOf<String>()
        for (entry in config.ollamaUrls) {
            val (u, entryModel) = splitEntry(entry)
            val downUntil = downLock.withLock { ollamaDownUntil[u] ?: 0L }
            if (downUntil > nowMs()) {
                errors += "$u: skipped (down until ${(downUntil - nowMs()) / 1000}s)"
                continue
            }
            try {
                val d = ask(state, question, Backend.OLLAMA, stub = false, model = model ?: entryModel, url = u)
                return Decision(
                    d.choice, d.confidence, d.probabilities, d.model, d.usage, d.latencyMs,
                    JsonObject(d.raw + ("_url" to JsonPrimitive(u))), d.done,
                )
            } catch (e: JevError) {
                val msg = e.message.orEmpty()
                val transient = "request failed" in msg || "HTTP 404" in msg || ("after" in msg && "attempts" in msg)
                errors += "$u: ${PyText.take(msg, 120)}"
                if (transient) downLock.withLock { ollamaDownUntil[u] = nowMs() + (config.ollamaBackoffS * 1000).toLong() }
                else throw e
            }
        }
        throw JevError("no System One backend reachable: " + errors.joinToString(" | "))
    }

    companion object {
        /** `url|model` → (url, model or null). */
        fun splitEntry(entry: String): Pair<String, String?> {
            val url = entry.substringBefore('|').trim()
            val model = if ('|' in entry) entry.substringAfter('|').trim().ifEmpty { null } else null
            return url to model
        }
    }
}

/**
 * OpenAI's Decisions API (`POST /v1/decisions`) for the engine's two questions: System One's
 * `choice` with `criteria` becomes a `choice` with `choices` ({value, description}), its `noul`
 * yes/no becomes a `predicate`; the state goes in as JSON text in `input`. [parse] maps the
 * answers back to System One's shape, so traces, replay and benchmark tools see one format
 * (the original answers are kept under `_openai`).
 *
 * A tap option's value is its own description, not its key: with bare keys (`n05`) the model
 * picked the neighbouring option in 9 of 28 replayed decisions, with the descriptions it matched
 * TypeSafe's Jev on all 28 (see [choiceValues]).
 */
object OpenAIDecisions {
    private val TAP_KEY = Regex("n\\d+")

    /** Option key → the value sent to OpenAI: a tap's description (made unique), other keys as they are. */
    fun choiceValues(criteria: JsonObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        for ((k, d) in criteria) {
            var v = if (TAP_KEY.matches(k)) d.jsonPrimitive.content else k
            while (v in used) v += " (2)"
            used += v
            out[k] = v
        }
        return out
    }

    fun request(state: JsonObject, question: JsonObject, model: String): JsonObject = buildJsonObject {
        put("model", model)
        put("input", "The current screen and goal, as JSON:\n$state")
        put("questions", JsonArray(question.map { (name, q) ->
            val qo = q.jsonObject
            val instructions = qo["instructions"]?.jsonPrimitive?.content.orEmpty()
            when (val type = qo["type"]?.jsonPrimitive?.content) {
                "choice" -> buildJsonObject {
                    put("type", "choice"); put("name", name); put("instructions", instructions)
                    val criteria = qo["criteria"]!!.jsonObject
                    val values = choiceValues(criteria)
                    put("choices", JsonArray(criteria.map { (k, d) ->
                        buildJsonObject { put("value", values.getValue(k)); put("description", d.jsonPrimitive.content) }
                    }))
                }
                "noul" -> buildJsonObject { put("type", "predicate"); put("name", name); put("instructions", instructions) }
                else -> throw JevError("question '$name': type '$type' has no OpenAI Decisions equivalent")
            }
        }))
    }

    /** [question] is the one [request] was built from: it turns the answer's values back into option keys. */
    fun parse(text: String, model: String, latencyMs: Long, question: JsonObject): Decision {
        val keyOf = (question["next"]?.jsonObject?.get("criteria") as? JsonObject)?.let { c -> choiceValues(c).entries.associate { (k, v) -> v to k } }.orEmpty()
        fun key(v: String) = keyOf[v] ?: v
        val data = try { Json.parseToJsonElement(text).jsonObject } catch (e: Exception) {
            throw JevError("OpenAI response not understood: ${PyText.take(text, 300)}", e)
        }
        try {
            val byName = (data["answers"] as JsonArray).associate { a -> a.jsonObject["name"]!!.jsonPrimitive.content to a.jsonObject }
            val next = byName["next"] ?: throw JevError("OpenAI answered no 'next' question: ${PyText.take(text, 300)}")
            if (next["type"]?.jsonPrimitive?.content == "refusal") throw JevError("OpenAI refused to decide (refusal answer)")
            val probs = LinkedHashMap<String, Double>()
            (next["probabilities"] as? JsonArray)?.forEach { p ->
                probs[key(p.jsonObject["value"]!!.jsonPrimitive.content)] = p.jsonObject["probability"]!!.jsonPrimitive.number()
            }
            val choice = key(next["choice"]!!.jsonPrimitive.content)
            val conf = next["confidence"]?.jsonPrimitive?.number() ?: (probs.values.maxOrNull() ?: 0.0)
            val done = byName["done"]?.takeIf { it["type"]?.jsonPrimitive?.content == "predicate" }?.get("probability")?.jsonPrimitive?.number()
            val usage = (data["usage"] as? JsonObject) ?: JsonObject(emptyMap())
            val respModel = (data["model"] as? JsonPrimitive)?.content ?: model
            val raw = buildJsonObject {
                put("model", respModel)
                put("answers", buildJsonObject {
                    put("next", buildJsonObject {
                        put("type", "choice"); put("choice", choice)
                        put("probabilities", buildJsonObject { probs.forEach { (k, v) -> put(k, v) } })
                        put("confidence", conf)
                    })
                    if (done != null) put("done", buildJsonObject { put("type", "noul"); put("noul", done) })
                })
                put("usage", usage)
                put("_backend", Backend.OPENAI.id)
                put("_openai", data["answers"]!!)
            }
            return Decision(choice, conf, probs, respModel, usage, latencyMs, raw, done)
        } catch (e: JevError) {
            throw e
        } catch (e: Exception) {
            throw JevError("OpenAI response not understood: ${e.message}: ${PyText.take(text, 300)}", e)
        }
    }
}

private fun JsonPrimitive.number(): Double =
    doubleOrNull ?: throw IllegalArgumentException("not a number: $content")

/** Deterministic offline stand-in: goal/candidate word overlap. Plumbing only, not intelligence. */
object StubDecider {
    private val STOP = setOf(
        "the", "a", "an", "to", "go", "open", "tab", "on", "in", "og", "til", "på", "en", "et",
        "åpne", "gå", "then", "and", "screen", "page", "button", "click", "tap", "press", "view",
        "show", "me", "into", "from", "back",
    )
    private val TOKEN = Regex("(?U)[\\w'’-]+")

    fun tokens(s: String): List<String> =
        TOKEN.findAll(s.lowercase()).map { it.value }.filter { PyText.len(it) >= 3 && it !in STOP }.toList()

    fun decide(state: JsonObject, question: JsonObject, confidence: Double): Decision {
        val crit = question["next"]!!.jsonObject["criteria"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val goalToks = tokens((state["goal"] as? JsonPrimitive)?.content.orEmpty())
        val hist = (state["history"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

        fun overlap(desc: String): Int {
            val d = desc.lowercase()
            // an exact quoted label ("spill") beats a substring hit ("mine spill")
            return goalToks.sumOf { t -> if ("\"$t\"" in d) 2 else if (t in d) 1 else 0 }
        }

        val choice = if (hist.isNotEmpty() && "screen changed" in hist.last() && overlap(hist.last()) > 0) {
            "goal_done"
        } else {
            val scored = crit.filter { (k, d) -> k.startsWith("n") && !d.startsWith("RISKY") }.map { (k, d) -> overlap(d) to k }
            // Python's max() over (score, key) tuples: ties go to the larger key.
            val byScoreThenKey = compareBy<Pair<Int, String>> { it.first }
                .then(Comparator { a, b -> PyText.codePointOrder.compare(a.second, b.second) })
            val best = scored.maxWithOrNull(byScoreThenKey) ?: (0 to "")
            if (best.first > 0) best.second else "blocked"
        }
        val rest = crit.keys.filter { it != choice }
        val probs = LinkedHashMap<String, Double>()
        probs[choice] = confidence
        val share = pyRound((1 - confidence) / maxOf(rest.size, 1), 4)
        rest.forEach { probs[it] = share }
        val done = if (choice == "goal_done") 0.9 else 0.1
        val usage = buildJsonObject { put("input_tokens", 0); put("output_tokens", 0) }
        // Response-shaped (Python's stub records only {"stub": true}), so traces stay uniform for replay tools.
        val raw = buildJsonObject {
            put("stub", true)
            put("model", "stub")
            put("answers", buildJsonObject {
                put("next", buildJsonObject {
                    put("type", "choice"); put("choice", choice)
                    put("probabilities", buildJsonObject { probs.forEach { (k, v) -> put(k, v) } })
                    put("confidence", confidence)
                })
                put("done", buildJsonObject { put("type", "noul"); put("noul", done) })
            })
            put("usage", usage)
        }
        return Decision(choice, confidence, probs, "stub", usage, 0, raw, done)
    }
}

/** For callers that build plain maps (traces, tool results). */
fun JsonElement.asObject(): JsonObject = jsonObject
