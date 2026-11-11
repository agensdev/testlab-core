package no.agens.testlab.services.service

import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import no.agens.testlab.services.vaultPassphrase
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackgroundServiceTest {
    private val tmp = Files.createTempDirectory("tl-svc").toFile()
    private val ran = mutableListOf<List<String>>()
    private var answer: (List<String>) -> BackgroundService.Result = { BackgroundService.Result(0, "") }
    private fun svc(os: HostOs) = BackgroundService(os, tmp) { ran += it; answer(it) }
    private val spec = BackgroundService.Spec(listOf("/opt/My Lab/bin/testlab", "serve", "--port", "8378"), File(tmp, "logs/service-console.log"), File(tmp, "data"))

    @AfterTest
    fun tearDown() { tmp.deleteRecursively() }

    @Test
    fun systemdQuoting() {
        assertEquals("serve", BackgroundService.systemdQuote("serve"))
        assertEquals("\"/opt/My Lab/bin/testlab\"", BackgroundService.systemdQuote("/opt/My Lab/bin/testlab"))
        assertEquals("\"100%%\"", BackgroundService.systemdQuote("100%"))
        assertEquals("\"a\\\"b\"", BackgroundService.systemdQuote("a\"b"))
        assertEquals("\"\$\$HOME\"", BackgroundService.systemdQuote("\$HOME"))
    }

    @Test
    fun aUserUnitOnLinux() {
        val s = svc(HostOs.LINUX)
        val where = s.install(spec.copy(env = mapOf("TESTLAB_VAULT_PASSPHRASE_FILE" to "/home/me/.pass")))
        assertEquals(File(tmp, ".config/systemd/user/${AppIdentity.SLUG}.service").path, where)
        val unit = File(where).readText()
        assertContains(unit, "ExecStart=\"/opt/My Lab/bin/testlab\" serve --port 8378\n")
        assertContains(unit, "Environment=TESTLAB_VAULT_PASSPHRASE_FILE=/home/me/.pass\n")
        assertContains(unit, "WantedBy=default.target")
        assertContains(unit, "KillMode=process")
        assertTrue("User=" !in unit && "LoadCredential" !in unit)
        assertEquals(listOf(listOf("systemctl", "--user", "daemon-reload"), listOf("systemctl", "--user", "enable", "--now", BackgroundService.UNIT)), ran)

        answer = { BackgroundService.Result(0, "active\n") }
        assertEquals(BackgroundService.State.RUNNING, s.status().state)
        answer = { BackgroundService.Result(3, "inactive\n") }
        assertEquals(BackgroundService.State.STOPPED, s.status().state)
        assertTrue(s.uninstall())
        assertEquals(BackgroundService.State.NOT_INSTALLED, s.status().state)
    }

    @Test
    fun aSystemUnitForARig() {
        val unit = BackgroundService.systemdUnit(spec.copy(user = "lab", passphraseFile = File("/etc/testlab/pass")), system = true)
        assertContains(unit, "User=lab\n")
        assertContains(unit, "WorkingDirectory=~\n")
        assertContains(unit, "LoadCredential=vault-passphrase:/etc/testlab/pass\n")
        assertContains(unit, "WantedBy=multi-user.target")
        assertFailsWith<IllegalArgumentException> { svc(HostOs.MAC).install(spec, system = true) }
        assertFailsWith<IllegalArgumentException> { svc(HostOs.LINUX).install(spec.copy(user = "lab"), system = false) }
    }

    @Test
    fun aFailedSystemctlIsReported() {
        answer = { c -> if ("enable" in c) BackgroundService.Result(1, "Failed to connect to bus") else BackgroundService.Result(0, "") }
        val e = assertFailsWith<IllegalStateException> { svc(HostOs.LINUX).install(spec) }
        assertEquals("Failed to connect to bus", e.message)
    }

    @Test
    fun aLaunchAgentOnMac() {
        answer = { c -> if (c == listOf("id", "-u")) BackgroundService.Result(0, "501\n") else BackgroundService.Result(0, "") }
        val s = svc(HostOs.MAC)
        val where = s.install(spec.copy(command = listOf("/Applications/A&B.app/Contents/MacOS/Testlab", "serve")))
        val plist = File(where).readText()
        assertEquals(File(tmp, "Library/LaunchAgents/${BackgroundService.LABEL}.plist").path, where)
        assertContains(plist, "<string>/Applications/A&amp;B.app/Contents/MacOS/Testlab</string>")
        assertContains(plist, "<key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>")
        assertContains(plist, "/opt/homebrew/bin")
        assertTrue(listOf("launchctl", "bootstrap", "gui/501", where) in ran)
        answer = { c -> if (c.first() == "id") BackgroundService.Result(0, "501") else BackgroundService.Result(0, "gui/501/x = {\n\tstate = running\n}") }
        assertEquals(BackgroundService.State.RUNNING, s.status().state)
    }

    @Test
    fun aLogonTaskOnWindows() {
        assertEquals("serve", BackgroundService.windowsQuote("serve"))
        assertEquals("\"C:\\Program Files\\Lab\\lib\\*\"", BackgroundService.windowsQuote("C:\\Program Files\\Lab\\lib\\*"))
        assertEquals("\"a \\\"b\\\"\"", BackgroundService.windowsQuote("a \"b\""))
        assertEquals("\"C:\\dir with space\\\\\"", BackgroundService.windowsQuote("C:\\dir with space\\"))
        val xml = BackgroundService.windowsTaskXml(spec.copy(command = listOf("C:\\Lab\\runtime\\bin\\javaw.exe", "-cp", "C:\\Lab\\lib\\*", "Main", "serve")), "DOM\\me")
        assertContains(xml, "<Command>C:\\Lab\\runtime\\bin\\javaw.exe</Command>")
        assertContains(xml, "<Arguments>-cp C:\\Lab\\lib\\* Main serve</Arguments>")
        assertContains(xml, "<UserId>DOM\\me</UserId>")
        assertContains(xml, "<RestartOnFailure>")
        val s = svc(HostOs.WINDOWS)
        val where = s.install(spec)
        val bytes = File(where).readBytes()
        assertEquals(listOf(0xFF.toByte(), 0xFE.toByte()), bytes.take(2), "UTF-16LE with a BOM")
        assertTrue(listOf("schtasks", "/Create", "/F", "/TN", BackgroundService.TASK, "/XML", where) in ran)
        answer = { BackgroundService.Result(0, "Folder: \\\nTaskName: \\x\nStatus:        Running\n") }
        assertEquals(BackgroundService.State.RUNNING, s.status().state)
    }

    @Test
    fun theVaultPassphraseFromAFile() {
        val f = File(tmp, "pass").apply { writeText("s3cret pass\n") }
        assertEquals("env", vaultPassphrase(mapOf("TESTLAB_VAULT_PASSPHRASE" to "env", "TESTLAB_VAULT_PASSPHRASE_FILE" to f.path))?.concatToString())
        assertEquals("s3cret pass", vaultPassphrase(mapOf("TESTLAB_VAULT_PASSPHRASE_FILE" to f.path))?.concatToString())
        val creds = File(tmp, "creds").apply { mkdirs(); File(this, "vault-passphrase").writeText("from-systemd") }
        assertEquals("from-systemd", vaultPassphrase(mapOf("CREDENTIALS_DIRECTORY" to creds.path))?.concatToString())
        assertEquals(null, vaultPassphrase(emptyMap()))
        assertFailsWith<IllegalStateException> { vaultPassphrase(mapOf("TESTLAB_VAULT_PASSPHRASE_FILE" to File(tmp, "missing").path)) }
    }
}
