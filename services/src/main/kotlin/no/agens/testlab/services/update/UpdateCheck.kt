package no.agens.testlab.services.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.AppIdentity
import java.io.File
import java.time.Duration
import java.time.Instant

/** The newest release, when it is newer than this build. */
@Serializable
data class UpdateInfo(val version: String, val url: String, val checkedAt: String)

/**
 * Asks GitHub's Releases for a newer version, at most once a day. The repo is private, so the
 * user's GitHub token is needed (collaborators' classic tokens can read it); without one, or
 * without access, there is simply no answer and Settings links to the releases page.
 */
class UpdateCheck(
    private val http: HttpClient,
    private val stateFile: File,
    private val token: () -> String?,
    private val api: () -> String = { "https://api.github.com" },
    private val current: String = AppIdentity.VERSION,
    private val repo: String = AppIdentity.REPO,
    private val now: () -> Instant = Instant::now,
) {
    @Serializable
    private data class State(val checkedAt: String, val latest: String? = null, val url: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    val releasesPage: String get() = "https://github.com/$repo/releases"

    /** The cached answer, without a network call. */
    fun cached(): UpdateInfo? = state()?.let { s -> s.latest?.takeIf { newer(it, current) }?.let { UpdateInfo(it, s.url ?: releasesPage, s.checkedAt) } }

    suspend fun check(force: Boolean = false): UpdateInfo? {
        val s = state()
        if (!force && s != null && runCatching { Duration.between(Instant.parse(s.checkedAt), now()) < Duration.ofHours(20) }.getOrDefault(false)) return cached()
        val t = token()?.trim().orEmpty()
        if (t.isEmpty()) return null
        val r = runCatching {
            // The list, not releases/latest: that one skips pre-releases, and the 0.x pilot builds are those.
            http.get("${api().trimEnd('/')}/repos/$repo/releases?per_page=20") {
                header("Authorization", "Bearer $t"); header("Accept", "application/vnd.github+json"); header("X-GitHub-Api-Version", "2022-11-28")
            }
        }.getOrNull() ?: return cached()
        val stamp = now().toString()
        if (r.status.value != 200) { save(State(stamp)); return null }
        val list = runCatching { (json.parseToJsonElement(r.bodyAsText()) as JsonArray).map { it as JsonObject } }.getOrNull() ?: return null
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content
        val o = list.filter { it.s("draft") != "true" && it.s("tag_name")?.removePrefix("v")?.matches(Regex("\\d+\\.\\d+\\.\\d+")) == true }
            .reduceOrNull { a, b -> if (newer(b.s("tag_name")!!, a.s("tag_name")!!)) b else a }
        if (o == null) { save(State(stamp)); return null }
        val tag = o.s("tag_name")!!.removePrefix("v")
        val url = o.s("html_url") ?: releasesPage
        save(State(stamp, tag, url))
        return cached()
    }

    private fun state(): State? = runCatching { json.decodeFromString(State.serializer(), stateFile.readText()) }.getOrNull()
    private fun save(s: State) = runCatching { stateFile.parentFile?.mkdirs(); stateFile.writeText(json.encodeToString(State.serializer(), s)) }

    companion object {
        /** True when [a] is a later MAJOR.MINOR.PATCH than [b] (missing parts are 0; suffixes ignored). */
        fun newer(a: String, b: String): Boolean {
            fun parts(v: String) = (v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)
            val (x, y) = parts(a) to parts(b)
            for (i in 0..2) if (x[i] != y[i]) return x[i] > y[i]
            return false
        }
    }
}
