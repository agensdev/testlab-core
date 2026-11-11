package no.agens.testlab.services.secrets

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class VaultLocked(message: String) : RuntimeException(message)

/**
 * Where the vault's 256-bit key lives. [VaultSecretStore] holds every secret in one AES-256-GCM
 * file, so only this key touches the OS keystore: one "Always allow" prompt per unsigned macOS
 * update instead of one per secret, and the same file format on every OS.
 */
interface VaultKeyProvider {
    /** A short label for Settings: "macOS Keychain", "Windows DPAPI", "passphrase"… */
    val label: String
    /** Returns the key, creating and storing a new one when [create] and none exists yet; null if absent. */
    fun key(create: Boolean, salt: ByteArray?): ByteArray?
    /** Whether the vault file must carry a salt (passphrase-derived keys). */
    val needsSalt: Boolean get() = false
}

/** PBKDF2-HMAC-SHA256 (600 000 rounds) from a passphrase, for machines without an unlocked keystore. */
class PassphraseKeyProvider(private val passphrase: () -> CharArray?) : VaultKeyProvider {
    override val label = "passphrase"
    override val needsSalt = true
    override fun key(create: Boolean, salt: ByteArray?): ByteArray? {
        val p = passphrase() ?: return null
        require(salt != null) { "a passphrase vault needs its salt" }
        val spec = PBEKeySpec(p, salt, 600_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.also { spec.clearPassword() }
    }
}

/** A fixed key, for tests. */
class StaticKeyProvider(private val bytes: ByteArray) : VaultKeyProvider {
    override val label = "static (tests)"
    override fun key(create: Boolean, salt: ByteArray?) = bytes
}

/**
 * All secrets in one AES-256-GCM file (`secrets.vault`, user-only permissions where the OS has
 * them). Writes are atomic (temp file + move). The decrypted map is cached in memory.
 */
class VaultSecretStore(private val file: File, private val keys: VaultKeyProvider) : SecretStore {
    @Serializable
    private data class Envelope(val v: Int = 1, val kdf: String, val salt: String? = null, val iv: String, val ct: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val rng = SecureRandom()
    private var cache: LinkedHashMap<String, String>? = null
    private var salt: ByteArray? = null
    private val flow = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)
    override val changes: SharedFlow<Set<String>> = flow.asSharedFlow()

    val providerLabel: String get() = keys.label

    /** What the file was when [cache] was read or written: another process (the CLI, a service) replaces it. */
    private var stamp: List<Any?>? = null

    private fun stampOf(): List<Any?>? = if (!file.isFile) null else runCatching {
        val a = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        // fileKey (the inode) changes with every atomic replace; mtime and size where there is none (Windows).
        listOf(a.fileKey(), a.lastModifiedTime().toMillis(), a.size())
    }.getOrNull()

    /**
     * The secrets, re-read when the file changed since: so `testlab secrets set` reaches a running app, service
     * or Claude Desktop server, and their next write doesn't put back a copy without it.
     */
    @Synchronized
    private fun map(): LinkedHashMap<String, String> {
        val now = stampOf()
        cache?.let { c -> if (now == stamp) return c }
        val loaded = if (file.isFile) decrypt(json.decodeFromString(Envelope.serializer(), file.readText())) else LinkedHashMap()
        val before = cache
        cache = loaded
        stamp = now
        if (before != null) {
            val changed = (before.keys + loaded.keys).filter { before[it] != loaded[it] }.toSet()
            if (changed.isNotEmpty()) flow.tryEmit(changed)
        }
        return loaded
    }

    private fun decrypt(e: Envelope): LinkedHashMap<String, String> {
        salt = e.salt?.let { Base64.getDecoder().decode(it) }
        val key = keys.key(create = false, salt = salt) ?: throw VaultLocked("the vault key is not available (${keys.label})")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(e.iv)))
        val plain = try {
            c.doFinal(Base64.getDecoder().decode(e.ct))
        } catch (ex: javax.crypto.AEADBadTagException) {
            throw VaultLocked("the vault could not be opened with the ${keys.label} key (wrong passphrase, or the key was replaced)")
        }
        return LinkedHashMap(json.decodeFromString<Map<String, String>>(plain.decodeToString()))
    }

    @Synchronized
    private fun persist(m: Map<String, String>) {
        if (keys.needsSalt && salt == null) salt = ByteArray(16).also(rng::nextBytes)
        val key = keys.key(create = true, salt = salt) ?: throw VaultLocked("no key to encrypt the vault with (${keys.label})")
        val iv = ByteArray(12).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(json.encodeToString(m).encodeToByteArray())
        val b64 = Base64.getEncoder()
        val env = Envelope(kdf = if (keys.needsSalt) "pbkdf2-sha256-600k" else "keystore", salt = salt?.let(b64::encodeToString), iv = b64.encodeToString(iv), ct = b64.encodeToString(ct))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Envelope.serializer(), env))
        restrict(tmp)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        stamp = stampOf()
    }

    private fun restrict(f: File) {
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }

    @Synchronized override fun get(key: String): String? = map()[key]
    @Synchronized override fun keys(): Set<String> = map().keys.toSet()

    @Synchronized
    override fun put(key: String, value: String) {
        val m = map()
        if (m[key] == value) return
        m[key] = value
        persist(m)
        flow.tryEmit(setOf(key))
    }

    @Synchronized
    override fun delete(key: String) {
        val m = map()
        if (m.remove(key) == null) return
        persist(m)
        flow.tryEmit(setOf(key))
    }

    /** True when the file exists but the key is not available (e.g. a passphrase vault before unlock). */
    fun isLocked(): Boolean = try { map(); false } catch (e: VaultLocked) { true }
}
