package no.agens.testlab.services.secrets

import com.github.javakeyring.Keyring
import com.sun.jna.platform.win32.Crypt32Util
import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * The vault key in the OS keystore through java-keyring: macOS Keychain, Windows Credential
 * Manager, Linux Secret Service (GNOME Keyring, KWallet, KeePassXC). One item per space: service =
 * [AppIdentity.ID], account `vault-key` (the default space) or `vault-key@<space>`.
 */
class KeyringKeyProvider(private val os: HostOs = HostOs.current(), private val space: String = no.agens.testlab.services.Spaces.DEFAULT) : VaultKeyProvider {
    private val account = if (space == no.agens.testlab.services.Spaces.DEFAULT) ACCOUNT else "$ACCOUNT@$space"

    override val label: String = when (os) {
        HostOs.MAC -> "macOS Keychain"
        HostOs.WINDOWS -> "Windows Credential Manager"
        HostOs.LINUX -> "Secret Service keyring"
    }

    override fun key(create: Boolean, salt: ByteArray?): ByteArray? = Keyring.create().use { k ->
        val existing = runCatching { k.getPassword(AppIdentity.ID, account) }.getOrNull()
        if (!existing.isNullOrEmpty()) return Base64.getDecoder().decode(existing)
        if (!create) return null
        val fresh = ByteArray(32).also(SecureRandom()::nextBytes)
        k.setPassword(AppIdentity.ID, account, Base64.getEncoder().encodeToString(fresh))
        fresh
    }

    /** Probes the keystore without creating anything; false on headless Linux, SSH sessions, WSL… */
    fun available(): Boolean = runCatching { Keyring.create().use { it.getPassword(AppIdentity.ID, "probe") }; true }
        .recoverCatching { e -> e::class.simpleName == "PasswordAccessException" }.getOrDefault(false)

    /** Removes this space's item (deleting a space). */
    fun delete() { runCatching { Keyring.create().use { it.deletePassword(AppIdentity.ID, account) } } }

    /** Copies the default space's key to this space's item (`space migrate`: the vault moves, its key stays valid). */
    fun copyFrom(other: KeyringKeyProvider) {
        val v = Keyring.create().use { it.getPassword(AppIdentity.ID, other.account) } ?: throw IllegalStateException("no key in the ${other.space} space's Keychain item")
        Keyring.create().use { it.setPassword(AppIdentity.ID, account, v) }
    }

    companion object {
        const val ACCOUNT = "vault-key"
    }
}

/** Windows fallback when Credential Manager is unusable: the key wrapped with DPAPI in a file next to the vault. */
class DpapiKeyProvider(private val file: File) : VaultKeyProvider {
    override val label = "Windows DPAPI"

    override fun key(create: Boolean, salt: ByteArray?): ByteArray? {
        if (file.isFile) return Crypt32Util.cryptUnprotectData(file.readBytes())
        if (!create) return null
        val fresh = ByteArray(32).also(SecureRandom()::nextBytes)
        file.parentFile?.mkdirs()
        file.writeBytes(Crypt32Util.cryptProtectData(fresh))
        return fresh
    }
}

/**
 * Picks where the vault key lives: the OS keystore when it works, DPAPI on Windows otherwise,
 * else a passphrase (`TESTLAB_VAULT_PASSPHRASE` / `_FILE` for headless use, or asked by the app).
 */
object VaultKeys {
    fun choose(dataDir: File, os: HostOs = HostOs.current(), passphrase: () -> CharArray? = { no.agens.testlab.services.vaultPassphrase() }, space: String = no.agens.testlab.services.Spaces.current()): VaultKeyProvider {
        val keyring = KeyringKeyProvider(os, space)
        if (no.agens.testlab.services.env("VAULT_PASSPHRASE") == null && no.agens.testlab.services.env("VAULT_PASSPHRASE_FILE") == null && keyring.available()) return keyring
        if (os == HostOs.WINDOWS) return DpapiKeyProvider(File(dataDir, "vault.key"))
        return PassphraseKeyProvider(passphrase)
    }
}
