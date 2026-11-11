package no.agens.testlab.services.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.core.jev.JevError
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.services.secrets.CredentialResolver
import no.agens.testlab.services.secrets.SecretStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-user pools ([Profile.Pool]): a login with a pool account fetches a user from the pool's
 * service, and plan references `<pool>.<field>` read that user, per device. The service key is the
 * vault's `pool.<name>.key`, so it is masked like every secret; the user's fields are test data
 * (an id to reproduce a run with), not secrets, and are shown.
 */
class TestPools(
    private val pools: () -> List<Profile.Pool>,
    private val secrets: SecretStore,
    private val http: Http = Http.DEFAULT,
) : CredentialResolver.Source {
    fun interface Http {
        /** (status, body) */
        suspend fun call(method: String, url: String, headers: Map<String, String>): Pair<Int, String>

        companion object {
            val DEFAULT = Http { method, url, headers ->
                withContext(Dispatchers.IO) {
                    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).method(method, HttpRequest.BodyPublishers.noBody())
                    headers.forEach { (k, v) -> b.header(k, v) }
                    val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
                    r.statusCode() to r.body()
                }
            }
        }
    }

    data class User(val pool: String, val env: String, val values: Map<String, String>, val id: String?)

    private val users = ConcurrentHashMap<String, User>() // serial → the pool user it logged in with

    fun all(): List<Profile.Pool> = pools()
    fun pool(name: String): Profile.Pool? = pools().firstOrNull { it.name == name }
    fun hasKey(name: String): Boolean = secrets.has(SecretStore.poolKey(name))
    fun current(serial: String): User? = users[serial]

    /**
     * A user from [pool] for [env] (a fresh one; with [pinned], the user whose id field is that,
     * without asking the service), now the one [serial]'s plans log in with.
     */
    suspend fun take(pool: Profile.Pool, env: String, serial: String, pinned: String? = null): User {
        val user = if (pinned != null) {
            val id = pool.id ?: throw IllegalArgumentException("the pool '${pool.name}' names no id field, so a user can't be pinned")
            User(pool.name, env, mapOf(id to pinned), pinned)
        } else fetch(pool, env)
        users[serial] = user
        return user
    }

    suspend fun fetch(pool: Profile.Pool, env: String): User {
        val key = secrets.get(SecretStore.poolKey(pool.name))?.takeIf { it.isNotEmpty() }
        fun fill(t: String): String {
            if ("\${key}" in t && key == null) throw IllegalStateException("the pool '${pool.name}' needs its service key: `testlab secrets set ${SecretStore.poolKey(pool.name)}`")
            return t.replace("\${env}", env).replace("\${key}", key.orEmpty())
        }
        val url = fill(pool.url)
        require(url.startsWith("https://")) { "the pool '${pool.name}' URL must be https" }
        val (status, body) = try {
            http.call(pool.method.uppercase(), url, pool.headers.mapValues { fill(it.value) })
        } catch (e: Exception) {
            throw IllegalStateException("the pool '${pool.name}' could not be reached for $env (${e.message ?: e::class.simpleName}): is this computer on the network the $env environment needs (VPN)?")
        }
        val shown = url.replace(key ?: "\u0000", "‹key›").substringBefore('?')
        when {
            status == 401 || status == 403 -> throw IllegalStateException("the pool '${pool.name}' refused the key ($status from $shown): check it with `testlab secrets set ${SecretStore.poolKey(pool.name)}`")
            status !in 200..299 -> throw IllegalStateException("the pool '${pool.name}' answered $status from $shown: ${body.take(200)}")
        }
        val json = runCatching { Json.parseToJsonElement(body) }.getOrElse { throw IllegalStateException("the pool '${pool.name}' did not answer with JSON: ${body.take(120)}") }
        val one = if (json is JsonArray) json.randomOrNull() ?: throw IllegalStateException("the pool '${pool.name}' has no free users in $env") else json
        val values = pool.fields.mapNotNull { (field, path) -> at(one, path)?.let { field to it } }.toMap()
        val missing = pool.fields.keys - values.keys
        if (missing.isNotEmpty()) throw IllegalStateException("the pool '${pool.name}' answer has no ${missing.joinToString { "${pool.fields[it]} (for $it)" }}; it has: ${(one as? JsonObject)?.keys?.joinToString().orEmpty()}")
        return User(pool.name, env, values, pool.id?.let { values[it] })
    }

    override fun value(ref: String, serial: String?): String? {
        val name = ref.substringBefore('.', "")
        val pool = pool(name) ?: return null
        val field = ref.substringAfter('.')
        val user = serial?.let { users[it] }?.takeIf { it.pool == name }
            ?: throw JevError("no '$name' user on this device yet: log in with handsets_login(account=\"$name\"), which takes one from the pool")
        return user.values[field] ?: throw JevError("the '$name' user has no '$field' (the pool gives ${user.values.keys.joinToString()})")
    }

    override fun refs(): Set<String> = pools().flatMap { p -> p.fields.keys.map { "${p.name}.$it" } }.toSet()

    companion object {
        /** A dotted path (`user.id`, `items.0.id`) in [e], as text; null when it isn't there. */
        fun at(e: JsonElement, path: String): String? {
            var cur: JsonElement = e
            for (part in path.split('.').filter { it.isNotEmpty() }) {
                val c = cur
                cur = when (c) {
                    is JsonObject -> c[part] ?: return null
                    is JsonArray -> part.toIntOrNull()?.let { c.getOrNull(it) } ?: return null
                    else -> return null
                }
            }
            val c = cur
            return when (c) { JsonNull -> null; is JsonPrimitive -> c.content; else -> c.toString() }
        }
    }
}
