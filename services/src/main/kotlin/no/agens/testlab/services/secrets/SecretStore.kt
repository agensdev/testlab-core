package no.agens.testlab.services.secrets

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The user's secrets: tokens, API keys, test-account credentials, device PINs. Values never leave
 * through MCP results, traces, logs or reports (see [Redactor]); plans refer to them by name.
 *
 * Key names: `github.token`, `jira.email`, `jira.token`, `jev.key`, `mcp.token`,
 * `account.<name>.<field>` (e.g. `account.demo.password`), `device_pin`, `device_pin.<serial>`,
 * `pool.<name>.key` (a test-user pool's service key).
 */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun delete(key: String)
    fun keys(): Set<String>

    /** Emits the changed key names, so screens and the engine reload live. */
    val changes: SharedFlow<Set<String>>

    fun has(key: String): Boolean = get(key)?.isNotEmpty() == true

    companion object {
        const val GITHUB_TOKEN = "github.token"
        const val JIRA_EMAIL = "jira.email"
        const val JIRA_TOKEN = "jira.token"
        const val JEV_KEY = "jev.key"
        const val OPENAI_KEY = "openai.key"
        const val MCP_TOKEN = "mcp.token"
        const val DEVICE_PIN = "device_pin"
        fun account(name: String, field: String) = "account.$name.$field"
        fun devicePin(serial: String) = "device_pin.$serial"
        fun poolKey(name: String) = "pool.$name.key"

        /**
         * Saves [key], and `key@scope` too when that exists: it wins over the plain key ([scoped]), so
         * replacing only the plain one would change nothing. Returns the scoped key it also saved.
         */
        fun putFor(store: SecretStore, key: String, scope: String?, value: String): String? {
            store.put(key, value)
            val k = scope?.takeIf { it.isNotBlank() }?.let { "$key@$it" }?.takeIf { store.has(it) } ?: return null
            store.put(k, value)
            return k
        }

        /** `key@scope` (a token for one GitHub owner or Jira site), else plain `key`. */
        fun scoped(store: SecretStore, key: String, scope: String?): String? =
            scope?.takeIf { it.isNotBlank() }?.let { store.get("$key@$it") }?.takeIf { it.isNotEmpty() } ?: store.get(key)
    }
}

/** In-memory store for tests and for running without persistence. */
class MemorySecretStore(initial: Map<String, String> = emptyMap()) : SecretStore {
    private val map = LinkedHashMap(initial)
    private val flow = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)
    override val changes: SharedFlow<Set<String>> = flow.asSharedFlow()

    @Synchronized override fun get(key: String) = map[key]
    @Synchronized override fun put(key: String, value: String) { map[key] = value; flow.tryEmit(setOf(key)) }
    @Synchronized override fun delete(key: String) { if (map.remove(key) != null) flow.tryEmit(setOf(key)) }
    @Synchronized override fun keys(): Set<String> = map.keys.toSet()
}

/**
 * Resolves plan credential references: `acct.field` → `account.acct.field`, and `device_pin`
 * (per-serial first, then the global one). The error names the missing reference, never a value.
 */
class CredentialResolver(private val store: SecretStore, private val serial: () -> String? = { null }) {
    /** References answered outside the vault (test-user pools); null = not one of theirs. */
    interface Source {
        fun value(ref: String, serial: String?): String?
        fun refs(): Set<String>
    }

    @Volatile var source: Source? = null

    fun resolve(ref: String, forSerial: String? = serial()): String {
        source?.value(ref, forSerial)?.let { return it }
        val key = keyFor(ref)
        val v = if (ref == "device_pin") forSerial?.let { store.get(SecretStore.devicePin(it)) } ?: store.get(key) else store.get(key)
        return v?.takeIf { it.isNotEmpty() } ?: throw no.agens.testlab.core.autopilot.AutopilotError("credential '$ref' is not set — add it on the setup page (`testlab open setup`)")
    }

    /** Which references a plan may use, for listing and validation (names only). */
    fun knownRefs(): Set<String> = store.keys().mapNotNull { k ->
        when {
            k == SecretStore.DEVICE_PIN -> "device_pin"
            k.startsWith("account.") -> k.removePrefix("account.")
            else -> null
        }
    }.toSet() + source?.refs().orEmpty()

    companion object {
        fun keyFor(ref: String): String = if (ref == "device_pin") SecretStore.DEVICE_PIN else "account.$ref"
    }
}

/**
 * Masks every stored secret of four or more characters in outgoing text: tool results, traces,
 * logs and reports. Shorter values (a 4-digit PIN is the minimum) would mask ordinary numbers.
 * The vault is read on every use (it is cached in memory), so a secret added in Settings is
 * masked from the next tool result on. JSON-escaped spellings are masked too, because results
 * are JSON: a password with a quote or backslash would otherwise slip through escaped.
 * Short all-digit secrets (PINs, one-time codes) are masked only as standalone numbers: a PIN
 * of 1234 must not turn PROJ-123, PR #1234 or …/pull/1234 into ‹redacted›.
 * With [hashes], the other spaces' secrets are masked too, recognised by their salted hashes.
 */
class Redactor(private val store: SecretStore, private val hashes: SecretHashes? = null) {
    private fun raw(): List<String>? = try {
        store.keys()
            .filter { it != SecretStore.JIRA_EMAIL } // an e-mail address is not secret, and appears in Jira texts
            .mapNotNull { store.get(it) }.filter { it.length >= 4 }
    } catch (e: RuntimeException) {
        null // a locked vault: nothing can be read from it, so nothing can leak from it either
    }

    private fun values(): List<String> {
        val raw = raw() ?: return emptyList()
        // What this space publishes for the others follows the vault (written only when it changed).
        val h = hashes
        if (h != null && raw.hashCode() != published) runCatching { h.publish(raw); published = raw.hashCode() }
        return raw.flatMap { listOf(it, jsonEscaped(it)) }.distinct().sortedByDescending { it.length }
    }

    @Volatile private var published = 0

    /** Publishes this space's secret hashes for the other spaces (also done on every use). */
    fun publish() { raw()?.let { hashes?.publish(it); published = it.hashCode() } }

    fun apply(text: String): String {
        var out = text
        for (v in values()) if (v in out) out = if (shortNumber(v)) standalone(v).replace(out, MASK) else out.replace(v, MASK)
        return maskForeign(out)
    }

    /** Another space's secrets, found by hash: masked from the end so the ranges stay valid. */
    private fun maskForeign(text: String): String {
        val hits = runCatching { hashes?.find(text) }.getOrNull().orEmpty()
        if (hits.isEmpty()) return text
        val sb = StringBuilder(text)
        for (r in hits.sortedByDescending { it.first }) sb.replace(r.first, r.last + 1, MASK)
        return sb.toString()
    }

    /** True when [text] contains a stored secret, this space's or another's (tests and the leak check use it). */
    fun leaks(text: String): Boolean = values().any { v -> v in text && (!shortNumber(v) || standalone(v).containsMatchIn(text)) } ||
        runCatching { hashes?.find(text) }.getOrNull().orEmpty().isNotEmpty()

    private fun shortNumber(v: String) = v.length <= 8 && v.all { it.isDigit() }

    /** Not part of a longer number, an issue key (PROJ-123), a #ref or a URL path. */
    private fun standalone(v: String) = Regex("(?<![0-9#/])(?<![A-Za-z]-)" + Regex.escape(v) + "(?![0-9])")

    companion object {
        const val MASK = "‹redacted›"

        internal fun jsonEscaped(v: String): String = buildString {
            for (c in v) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
    }
}
