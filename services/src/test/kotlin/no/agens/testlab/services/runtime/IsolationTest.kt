package no.agens.testlab.services.runtime

import kotlinx.coroutines.runBlocking
import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IsolationTest {
    private val tmp = Files.createTempDirectory("tl-iso").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val disabled = mutableSetOf<String>()
    private val adb = FakeAdb { args ->
        val cmd = args.drop(3).joinToString(" ")
        when {
            args.getOrNull(0) == "devices" -> "List of devices attached\nphone-1\tdevice model:Pixel\n"
            cmd.startsWith("pm list packages") -> listOf("com.example.shop", "com.example.shop.feature", "com.example.shop.main", "com.example.shop.releasedebug", "com.example.shopx.other")
                .joinToString("\n") { "package:$it" }
            cmd.startsWith("pm disable-user") -> cmd.substringAfterLast(' ').trim('\'').also { disabled += it }.let { "Package $it new state: disabled-user" }
            cmd.startsWith("pm enable") -> cmd.substringAfterLast(' ').trim('\'').also { disabled -= it }.let { "Package $it new state: enabled" }
            else -> ""
        }
    }

    private fun runtime() = LabRuntime(dirs, SettingsStore(File(dirs.data, "settings.json")), MemorySecretStore(), adbOverride = adb.cli).also { no.agens.testlab.services.profile.ExampleProfile.install(it) }

    @AfterTest
    fun tearDown() = tmp.deleteRecursively().let { }

    @Test
    fun othersAreDisabledAndRestored() = runBlocking {
        val iso = runtime().isolation
        val off = iso.isolate("phone-1", keep = "com.example.shop.feature")
        assertEquals(setOf("com.example.shop", "com.example.shop.main", "com.example.shop.releasedebug"), off.toSet(), "the prod app too, not look-alike packages")
        assertEquals(off.toSet(), disabled)
        assertEquals(off.toSet(), iso.pending("phone-1").toSet(), "recorded before the run")
        iso.restore("phone-1")
        assertTrue(disabled.isEmpty())
        assertTrue(iso.pending("phone-1").isEmpty())
        assertTrue(!File(dirs.data, "isolation.json").exists())
    }

    @Test
    fun aCrashIsRepairedAtTheNextStart() = runBlocking {
        runtime().isolation.isolate("phone-1", keep = "com.example.shop.main")
        // The app dies here: nothing restores. The next start finds the record.
        val restored = runtime().isolation.restoreAll()
        assertEquals(setOf("com.example.shop", "com.example.shop.feature", "com.example.shop.releasedebug"), restored.getValue("phone-1").toSet())
        assertTrue(disabled.isEmpty())
    }
}
