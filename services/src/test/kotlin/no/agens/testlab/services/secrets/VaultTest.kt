package no.agens.testlab.services.secrets

import java.io.File
import kotlinx.coroutines.launch
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun roundTripsAndNeverStoresPlaintext() {
        val dir = createTempDirectory().toFile()
        val f = File(dir, "secrets.vault")
        val v = VaultSecretStore(f, StaticKeyProvider(key))
        v.put("github.token", "ghp_supersecret123")
        v.put("account.demo.nnin", "01019912345")
        assertFalse("supersecret" in f.readText() || "01019912345" in f.readText())
        val reopened = VaultSecretStore(f, StaticKeyProvider(key))
        assertEquals("ghp_supersecret123", reopened.get("github.token"))
        assertEquals(setOf("github.token", "account.demo.nnin"), reopened.keys())
        reopened.delete("github.token")
        assertNull(VaultSecretStore(f, StaticKeyProvider(key)).get("github.token"))
    }

    @Test
    fun wrongKeyOrTamperingIsLockedNotGarbage() {
        val dir = createTempDirectory().toFile()
        val f = File(dir, "secrets.vault")
        VaultSecretStore(f, StaticKeyProvider(key)).put("jev.key", "apikey_123456")
        assertTrue(VaultSecretStore(f, StaticKeyProvider(ByteArray(32))).isLocked())
        f.writeText(f.readText().replace("\"ct\":\"", "\"ct\":\"AAAA"))
        assertFailsWith<VaultLocked> { VaultSecretStore(f, StaticKeyProvider(key)).get("jev.key") }
    }

    @Test
    fun passphraseVaultCarriesItsSalt() {
        val dir = createTempDirectory().toFile()
        val f = File(dir, "secrets.vault")
        VaultSecretStore(f, PassphraseKeyProvider { "correct horse".toCharArray() }).put("jira.token", "ATATT3xFfGF0abc")
        assertTrue("\"salt\"" in f.readText())
        assertEquals("ATATT3xFfGF0abc", VaultSecretStore(f, PassphraseKeyProvider { "correct horse".toCharArray() }).get("jira.token"))
        assertTrue(VaultSecretStore(f, PassphraseKeyProvider { "wrong".toCharArray() }).isLocked())
    }

    @Test
    fun redactorMasksStoredValuesButNotShortOnesOrEmail() {
        val s = MemorySecretStore(mapOf("jev.key" to "apikey_abcdef", "device_pin" to "1234", "x" to "abc", "jira.email" to "me@example.com"))
        val r = Redactor(s)
        assertEquals("key=‹redacted› pin=‹redacted› abc me@example.com", r.apply("key=apikey_abcdef pin=1234 abc me@example.com"))
        s.put("github.token", "ghp_newtoken99")
        assertEquals("‹redacted›", r.apply("ghp_newtoken99"), "a secret added later is masked without a refresh")
        s.put("account.a.login_password", "pa\"ss\\word")
        assertEquals("{\"v\":\"‹redacted›\"}", r.apply("{\"v\":\"pa\\\"ss\\\\word\"}"), "the JSON-escaped spelling is masked too")
        assertTrue(r.leaks("x pa\"ss\\word y"))
        assertEquals("SHOP-1234, PR #1234, /pull/1234, 12345, pin ‹redacted›.", r.apply("SHOP-1234, PR #1234, /pull/1234, 12345, pin 1234."), "a PIN is masked only as a number of its own")
        assertTrue(!r.leaks("SHOP-1234") && r.leaks("typed 1234"))
    }

    @Test
    fun credentialRefsResolveByNameAndPerSerialPin() {
        val s = MemorySecretStore(mapOf("account.demo.nnin" to "01019912345", "device_pin" to "1111", "device_pin.0349da42" to "2222"))
        assertEquals("01019912345", CredentialResolver(s).resolve("demo.nnin"))
        assertEquals("1111", CredentialResolver(s).resolve("device_pin"))
        assertEquals("2222", CredentialResolver(s) { "0349da42" }.resolve("device_pin"))
        val e = assertFailsWith<no.agens.testlab.core.jev.JevError> { CredentialResolver(s).resolve("demo.login_password") }
        assertTrue("demo.login_password" in e.message!!)
        assertEquals(setOf("demo.nnin", "device_pin"), CredentialResolver(s).knownRefs())
    }

    @Test
    fun importsDotEnvAndLabCredentials() {
        val dir = createTempDirectory().toFile()
        val env = File(dir, ".env.local").apply {
            writeText("# host-app\nGITHUB_TOKEN=ghp_x\nexport JIRA_EMAIL=\"me@example.com\"\nATLASSIAN_API_TOKEN='ATATT_y'\nUNRELATED=1\n")
        }
        val s = MemorySecretStore()
        assertEquals(listOf("github.token", "jira.email", "jira.token"), Imports.dotEnv(env, s).imported)
        assertEquals("ATATT_y", s.get("jira.token"))
        val creds = File(dir, "lab-credentials.json").apply {
            writeText("""{"device_pin":"0000","accounts":{"demo":{"nnin":"1","login_password":"pw"},"other":{"nnin":"2"}}}""")
        }
        val (res, accounts) = Imports.labCredentials(creds, s)
        assertEquals(listOf("demo", "other"), accounts)
        assertEquals(4, res.imported.size)
        assertEquals("pw", s.get("account.demo.login_password"))
    }

    @Test
    fun twoProcessesShareTheVault() {
        // The app (or Claude Desktop's server) holds the vault open while the CLI writes to it.
        val f = File(createTempDirectory("tl-vault2").toFile(), "secrets.vault")
        val k = ByteArray(32) { 7 }
        val app = VaultSecretStore(f, StaticKeyProvider(k))
        app.put("github.token", "ghp_one")
        val changed = mutableListOf<Set<String>>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { app.changes.collect { changed += it } }
        VaultSecretStore(f, StaticKeyProvider(k)).put("account.demo.app_personal_code", "2468")
        assertEquals("2468", app.get("account.demo.app_personal_code"), "the running process sees the CLI's secret")
        assertTrue(setOf("account.demo.app_personal_code") in changed, "and tells its screens")
        app.put("jev.key", "apikey_x")
        val cli = VaultSecretStore(f, StaticKeyProvider(k))
        assertEquals("2468", cli.get("account.demo.app_personal_code"), "the app's next write keeps it")
        assertEquals("apikey_x", cli.get("jev.key"))
        job.cancel()
    }
}
