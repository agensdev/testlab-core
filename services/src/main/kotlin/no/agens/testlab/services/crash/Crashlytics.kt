package no.agens.testlab.services.crash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.tools.AndroidCli

/**
 * The app's crashes in the field (Firebase Crashlytics), read with Google's Android CLI
 * (`android signals`) as the user's own Google account: whether a crash seen on the rig also hits
 * real users, how often, and on which versions and devices. Which Firebase app a package is comes
 * from the profile ([no.agens.testlab.core.profile.Profile.Crashlytics]).
 */
class Crashlytics(private val rt: LabRuntime, private val cli: () -> AndroidCli = { rt.androidCli }) {
    data class Issue(val id: String?, val score: Int, val json: JsonObject)

    /** The Firebase app for [pkg] (default: the production package, else the only one configured). */
    fun appFor(pkg: String?): Pair<String, String> {
        val c = rt.profile.crashlytics
        if (c.apps.isEmpty()) throw IllegalStateException("The profile «${rt.profile.name}» doesn't say which Firebase apps the app is: add crashlytics.apps (package → Firebase app id, from google-services.json) in profile.json; lab_crashlytics_apps lists the apps this Google account can read")
        val want = pkg?.takeIf { it.isNotBlank() } ?: rt.profile.app.productionPackage?.takeIf { it in c.apps } ?: c.apps.keys.singleOrNull() ?: rt.profile.app.defaultPackage.takeIf { it in c.apps }
            ?: throw IllegalArgumentException("which app? package is one of ${c.apps.keys.joinToString()}")
        return want to (c.apps[want] ?: throw IllegalArgumentException("crashlytics.apps has no Firebase app for $want: it has ${c.apps.keys.joinToString()}"))
    }

    private suspend fun signals(args: List<String>): JsonElement = withContext(Dispatchers.IO) {
        val r = cli().run(listOf("signals") + args + "--format=json", 180)
        val parsed = runCatching { Json.parseToJsonElement(r.out.trim()) }.getOrNull()
        if (!r.ok || parsed == null) throw IllegalStateException(explain(r.problem))
        parsed
    }

    /** The Firebase projects and apps the signed-in Google account can read. */
    suspend fun apps(project: String? = null): JsonElement = signals(listOf("context") + listOfNotNull(project?.takeIf { it.isNotBlank() }?.let { "--firebase-project=$it" }))

    /**
     * Crash, ANR and non-fatal issue clusters. With [match] (a crash from the rig: its exception
     * line, or the whole stack trace) the issues are ranked by how much of it they share, best first,
     * and those sharing nothing are left out.
     */
    suspend fun issues(
        pkg: String?, type: String? = null, appVersion: String? = null, device: String? = null, apiLevel: String? = null,
        timeframe: String? = null, limit: Int = 50, match: String? = null,
    ): Triple<String, String, List<Issue>> {
        val (p, app) = appFor(pkg)
        val tf = timeframe?.takeIf { it.isNotBlank() } ?: rt.profile.crashlytics.timeframe
        val args = listOf("crashes", "--firebase-app=$app", "list", "--timeframe=$tf", "--limit=${if (match.isNullOrBlank()) limit else 500}", "--sort-by=users") +
            listOfNotNull(type?.takeIf { it.isNotBlank() }?.let { "--type=$it" }, appVersion?.takeIf { it.isNotBlank() }?.let { "--app-version=$it" },
                device?.takeIf { it.isNotBlank() }?.let { "--device=$it" }, apiLevel?.takeIf { it.isNotBlank() }?.let { "--api-level=$it" })
        val all = objects(signals(args)).map { Issue(idOf(it), 0, it) }
        if (match.isNullOrBlank()) return Triple(p, tf, all.take(limit))
        val terms = terms(match)
        return Triple(p, tf, rank(all, terms).take(limit))
    }

    /** One issue: metadata, version/OS/device spread, a sample's stack trace, custom keys and logs. */
    suspend fun issue(id: String, pkg: String?, include: String? = null, timeframe: String? = null, sampleId: String? = null): JsonElement {
        val (_, app) = appFor(pkg)
        val tf = timeframe?.takeIf { it.isNotBlank() } ?: rt.profile.crashlytics.timeframe
        return signals(listOf("crashes", "--firebase-app=$app", "describe", "--timeframe=$tf") +
            listOfNotNull(include?.takeIf { it.isNotBlank() }?.let { "--include=$it" }, sampleId?.takeIf { it.isNotBlank() }?.let { "--sample-id=$it" }) + id)
    }

    /** Whether it can be used now, and if not why: for diagnostics and the setup steps. */
    fun status(): String? {
        val c = cli()
        if (!c.installed) return AndroidCli.MISSING
        if (c.account() == null) return NOT_SIGNED_IN
        if (rt.profile.crashlytics.apps.isEmpty()) return "the profile has no crashlytics.apps"
        return null
    }

    companion object {
        const val NOT_SIGNED_IN = "the Android CLI isn't signed in to Google: run `testlab android-cli login` (a browser opens; use the account that can see the app in Firebase)"

        fun explain(problem: String): String = when {
            Regex("(?i)not (logged|signed) in|auth login").containsMatchIn(problem) -> NOT_SIGNED_IN
            Regex("(?i)permission|forbidden|403").containsMatchIn(problem) -> "Crashlytics refused: the signed-in Google account has no access to that Firebase app ($problem)"
            else -> "android signals: $problem"
        }

        /**
         * The issue objects in the CLI's answer. It groups them per source (`[{source, appId, issues: [...]}]`),
         * so an issue is an object carrying its own id (`issueId`, or a `firebase/…` value directly on it);
         * without any, the answer's first list of objects.
         */
        fun objects(e: JsonElement): List<JsonObject> {
            fun own(o: JsonObject) = o.values.any { v -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.startsWith("firebase/") == true } || "issueId" in o
            fun issues(e: JsonElement): List<JsonObject> = when (e) {
                is JsonObject -> if (own(e)) listOf(e) else e.values.flatMap(::issues)
                is JsonArray -> e.flatMap(::issues)
                else -> emptyList()
            }
            issues(e).takeIf { it.isNotEmpty() }?.let { return it }
            fun find(e: JsonElement, depth: Int): List<JsonObject>? = when (e) {
                is JsonArray -> e.filterIsInstance<JsonObject>().takeIf { it.isNotEmpty() || e.isEmpty() }
                is JsonObject -> if (depth > 2) null else e.values.firstNotNullOfOrNull { (it as? JsonArray)?.let { a -> find(a, depth + 1) } } ?: e.values.firstNotNullOfOrNull { find(it, depth + 1) }
                else -> null
            }
            return find(e, 0) ?: emptyList()
        }

        /** The id `crashes describe` takes: the `issueId`, else a `firebase/…` value, else the `id` field. */
        fun idOf(o: JsonObject): String? = listOf("issueId", "issue_id").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: strings(o).firstOrNull { it.startsWith("firebase/") }
            ?: listOf("id", "name").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

        private fun strings(e: JsonElement): List<String> = when (e) {
            is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
            is JsonArray -> e.flatMap(::strings)
            is JsonObject -> e.values.flatMap(::strings)
        }

        /**
         * What identifies a crash in [text]: exception class names (weight 3), the app's own frames
         * as Class.method (2), and the rest of the message's words (1). Framework frames and
         * common words carry nothing.
         */
        fun terms(text: String): Map<String, Int> {
            val out = linkedMapOf<String, Int>()
            // An app's own exception says a lot; IllegalStateException and the like are in half the issues.
            Regex("\\b(?:[a-z_][\\w$]*\\.)*([A-Z][\\w$]*(?:Exception|Error|Throwable))\\b").findAll(text).forEach { m ->
                val name = m.groupValues[1]
                out.merge(name.lowercase(), if (name in COMMON_EXCEPTIONS) 1 else 3, ::maxOf)
            }
            Regex("\\bat\\s+([\\w$.]+)\\.([\\w$<>]+)\\(").findAll(text).forEach { m ->
                val cls = m.groupValues[1]
                if (FRAMEWORK.containsMatchIn(cls)) return@forEach
                val simple = cls.substringAfterLast('.').substringBefore('$')
                out.merge("$simple.${m.groupValues[2]}".lowercase(), 2, ::maxOf)
                out.merge(simple.lowercase(), 1, ::maxOf)
            }
            text.lines().firstOrNull { "Exception" in it || "Error" in it }?.substringAfter(": ", "")?.let { msg ->
                Regex("[A-Za-z][\\w$]{3,}").findAll(msg).map { it.value.lowercase() }.filter { it !in STOP }.forEach { out.merge(it, 1, ::maxOf) }
            }
            return out
        }

        /** The issues sharing more than one weak detail with the crash, best first. */
        fun rank(all: List<Issue>, terms: Map<String, Int>): List<Issue> =
            all.map { it.copy(score = score(it.json, terms)) }.filter { it.score >= MIN_SCORE }.sortedByDescending { it.score }

        private const val MIN_SCORE = 2
        private val COMMON_EXCEPTIONS = setOf(
            "Exception", "RuntimeException", "IllegalStateException", "IllegalArgumentException", "NullPointerException",
            "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException", "ClassCastException", "UnsupportedOperationException",
            "ConcurrentModificationException", "NoSuchElementException", "NumberFormatException", "IOException",
            "SocketTimeoutException", "UnknownHostException", "CancellationException", "Error", "Throwable", "OutOfMemoryError",
        )

        fun score(issue: JsonObject, terms: Map<String, Int>): Int {
            val hay = strings(issue).joinToString("\n").lowercase()
            return terms.entries.sumOf { (t, w) -> if (t in hay) w else 0 }
        }

        private val FRAMEWORK = Regex("^(java|javax|kotlin|kotlinx|android|androidx|dalvik|com\\.android|com\\.google|sun|okhttp3|retrofit2|io\\.reactivex)\\.")
        private val STOP = setOf("null", "this", "that", "with", "from", "when", "have", "been", "were", "object", "reference", "attempt", "invoke", "method", "virtual", "interface", "cannot", "could", "must", "should", "java", "lang", "kotlin")
    }
}
