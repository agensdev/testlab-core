package no.agens.testlab.services.secrets

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Salted hashes of a space's secret values (never the values), in `<space data>/secret-hashes`,
 * so the other spaces' redactors can recognise and mask them. Only values of [MIN] characters or
 * more without whitespace are published: tokens, keys and most passwords. Shorter ones (PINs) would
 * mask ordinary numbers in another client's texts, and are cheap to guess from a hash.
 */
class SecretHashes(private val own: File, private val others: () -> List<File>) {
    @Serializable
    internal data class Published(val salt: String, val hashes: Map<Int, List<String>>)

    /** Another space's hashes: its salt, and for each length the hashes of that length. */
    internal class Foreign(val salt: ByteArray, val byLength: Map<Int, Set<String>>)

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: Pair<List<Pair<String, Long>>, List<Foreign>>? = null

    /** Writes [values]' hashes when they differ from what's published (the salt stays). */
    fun publish(values: Collection<String>) {
        val old = runCatching { json.decodeFromString(Published.serializer(), own.readText()) }.getOrNull()
        val salt = old?.salt ?: ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        val hashes = values.filter(::publishable).distinct()
            .groupBy({ it.length }, { hash(salt.fromHex(), it) }).mapValues { (_, v) -> v.distinct().sorted() }.toSortedMap()
        val next = Published(salt, hashes)
        if (next == old) return
        own.parentFile?.mkdirs()
        val tmp = File(own.parentFile, own.name + ".tmp")
        tmp.writeText(json.encodeToString(Published.serializer(), next))
        if (!tmp.renameTo(own)) { own.writeText(tmp.readText()); tmp.delete() }
    }

    /** The other spaces' hashes, re-read when one of their files changes. */
    internal fun foreign(): List<Foreign> {
        val files = others().map { File(it, own.name) }.filter { it.isFile }
        val key = files.map { it.path to it.lastModified() }
        cache?.takeIf { it.first == key }?.let { return it.second }
        val loaded = files.mapNotNull { f ->
            runCatching { json.decodeFromString(Published.serializer(), f.readText()) }.getOrNull()
                ?.takeIf { it.hashes.isNotEmpty() }
                ?.let { Foreign(it.salt.fromHex(), it.hashes.mapValues { (_, v) -> v.toSet() }) }
        }
        cache = key to loaded
        return loaded
    }

    /** Every stretch of [text] that is another space's secret, as index ranges (longest first where they overlap). */
    internal fun find(text: String): List<IntRange> {
        val spaces = foreign().map { sp -> mac(sp.salt) to sp.byLength }
        if (spaces.isEmpty()) return emptyList()
        val lengths = spaces.flatMap { it.second.keys }.toSortedSet().reversed()
        val out = mutableListOf<IntRange>()
        for (run in RUN.findAll(text)) {
            val r = run.range
            val n = r.last - r.first + 1
            if (n < MIN) continue
            // A secret inside a longer run (`user:TOKEN@host`, `Bearer…`): every window of a published
            // length. Very long runs (base64 images) are only checked whole.
            val windows = n <= MAX_RUN
            var i = r.first
            while (i <= r.last) {
                var hit: IntRange? = null
                for (len in lengths) {
                    if (len > r.last - i + 1 || (!windows && (i != r.first || len != n))) continue
                    val s = text.substring(i, i + len)
                    if (spaces.any { (mac, byLength) -> byLength[len]?.contains(mac.doFinal(s.toByteArray()).copyOf(16).toHex()) == true }) { hit = i until i + len; break }
                }
                if (hit != null) { out += hit; i = hit.last + 1 } else { if (!windows) break; i++ }
            }
        }
        return out
    }

    companion object {
        const val MIN = 8
        private const val MAX_RUN = 4096
        /** The characters a token or password is made of: whitespace, quotes and brackets end one. */
        private val RUN = Regex("[^\\s\"'`<>(){}\\[\\],;\\\\]+")

        fun publishable(v: String) = v.length >= MIN && v.none { it.isWhitespace() }

        private fun mac(salt: ByteArray): Mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        internal fun hash(salt: ByteArray, v: String): String = mac(salt).doFinal(v.toByteArray()).copyOf(16).toHex()

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
