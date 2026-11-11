package no.agens.testlab.services.runtime

import no.agens.testlab.device.HostOs
import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.SpaceRules
import no.agens.testlab.services.SpaceRulesStore
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceLeasesTest {
    private val home = Files.createTempDirectory("leases").toFile()
    @AfterTest fun tearDown() { home.deleteRecursively() }

    private fun dirs(space: String) = AppDirs.forHost(HostOs.LINUX, mapOf("XDG_DATA_HOME" to File(home, "d").path, "XDG_CACHE_HOME" to File(home, "c").path), home, space)

    private var fontScale = "1.3"
    private var http = "127.0.0.1:9090"
    private val reply: (List<String>) -> String = { args ->
        val cmd = args.drop(3).joinToString(" ")
        when {
            args.getOrNull(0) == "devices" -> "List of devices attached\nphone-1\tdevice model:Pixel\n"
            cmd.startsWith("pm list packages") -> listOf("com.example.shop", "com.example.shop.feature", "org.other.app").joinToString("\n") { "package:$it" }
            cmd.startsWith("pm clear") -> "Success"
            cmd == "settings get system font_scale" -> fontScale
            cmd.startsWith("settings put system font_scale") -> { fontScale = cmd.substringAfterLast(' '); "" }
            args.drop(2) == listOf("shell", "settings", "get", "global", "http_proxy") || cmd == "settings get global http_proxy" -> http
            cmd.startsWith("settings put global http_proxy") || args.drop(2).take(5) == listOf("shell", "settings", "put", "global", "http_proxy") -> { http = args.last().substringAfterLast(' '); "" }
            else -> ""
        }
    }

    /** One adb per runtime (each sets its own guard), one log of the calls. */
    private val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val adb = object { val calls get() = this@DeviceLeasesTest.calls }
    private fun runtime(space: String): LabRuntime {
        val fake = FakeAdb { a -> calls += a.joinToString(" "); reply(a) }
        return LabRuntime(dirs(space), SettingsStore(dirs(space).settings), MemorySecretStore(), adbOverride = fake.cli)
    }

    @Test
    fun aPhoneHeldByOneSpaceIsRefusedToAnother() {
        val alpha = runtime("alpha"); val beta = runtime("beta")
        alpha.adb().shell("phone-1", "echo hi")
        assertEquals("yours", alpha.leases.state("phone-1"))
        assertEquals("other", beta.leases.state("phone-1"))
        val e = assertFailsWith<DeviceInUse> { beta.adb().shell("phone-1", "input tap 1 1") }
        assertTrue("alpha" !in e.message!!, "the other client isn't named: ${e.message}")
        assertTrue(adb.calls.none { "input tap" in it }, "nothing ran")
        assertFailsWith<NoDevice> { beta.pickDefaultSerial() }
    }

    @Test
    fun aStaleLeaseIsTakenAfterTheHandOverOfTheSpaceThatHadIt() {
        val a = dirs("alpha")
        SpaceRulesStore(a).save(SpaceRules(githubOwners = listOf("example-org"), appFamilies = listOf("com.example.shop")))
        File(a.data, "font-scale.json").writeText("""{"phone-1":"1.0"}""")
        File(a.data, "proxied.json").writeText("""[{"serial":"phone-1","proxy":"127.0.0.1:9090","reversePort":9090}]""")
        File(home, "d/testlab/leases").mkdirs()
        File(home, "d/testlab/leases/phone-1.json").writeText("""{"serial":"phone-1","space":"alpha","pid":${deadPid()}}""")
        val beta = runtime("beta")
        beta.adb().shell("phone-1", "echo hi")
        assertEquals("yours", beta.leases.state("phone-1"))
        assertTrue(adb.calls.any { it.endsWith("pm clear 'com.example.shop'") } && adb.calls.any { it.endsWith("pm clear 'com.example.shop.feature'") }, adb.calls.joinToString("\n"))
        assertTrue(adb.calls.none { "org.other.app" in it && "clear" in it }, "only the family of the space that had it")
        assertEquals("1.0", fontScale)
        assertEquals(":0", http)
        assertTrue(adb.calls.any { "reverse --remove tcp:9090" in it })
        assertTrue(!File(a.data, "proxied.json").exists(), "its proxy record is gone too")
    }

    @Test
    fun releaseHandsOverAndFrees() {
        val a = dirs("alpha")
        SpaceRulesStore(a).save(SpaceRules(githubOwners = listOf("example-org"), appFamilies = listOf("com.example.shop"), handover = SpaceRules.Handover(proxy = false)))
        val alpha = runtime("alpha")
        alpha.leases.ensure("phone-1", sticky = true)
        val steps = alpha.leases.release("phone-1")
        assertTrue(steps.any { "cleared com.example.shop" in it }, steps.toString())
        assertTrue(steps.any { "font size back to" in it }, steps.toString())
        assertNull(alpha.leases.get("phone-1"))
        assertEquals("free", runtime("beta").leases.state("phone-1"))
    }

    @Test
    fun aStickyLeaseOutlivesItsProcessButAPlainOneDoesnt() {
        File(home, "d/testlab/leases").mkdirs()
        File(home, "d/testlab/leases/phone-1.json").writeText("""{"serial":"phone-1","space":"alpha","pid":${deadPid()},"sticky":true}""")
        val beta = runtime("beta")
        assertFailsWith<DeviceInUse> { beta.leases.ensure("phone-1") }
        assertFailsWith<DeviceInUse> { beta.leases.release("phone-1") }
        File(home, "d/testlab/leases/phone-1.json").writeText("""{"serial":"phone-1","space":"alpha","pid":${deadPid()}}""")
        beta.leases.ensure("phone-1")
        assertEquals("yours", beta.leases.state("phone-1"))
    }

    @Test
    fun lookingDoesntTakeAPhone() {
        val alpha = runtime("alpha")
        alpha.leases.peek { alpha.adb().shell("phone-1", "settings get global http_proxy") }
        assertEquals("free", alpha.leases.state("phone-1"))
    }

    /** A pid no process has. */
    private fun deadPid(): Long = generateSequence(4_000_000L) { it + 1 }.first { !ProcessHandle.of(it).isPresent }
}
