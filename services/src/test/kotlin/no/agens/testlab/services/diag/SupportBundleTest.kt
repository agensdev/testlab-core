package no.agens.testlab.services.diag

import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupportBundleTest {
    @Test
    fun theZipHasTheFactsAndNoSecrets() {
        val tmp = Files.createTempDirectory("tl-bundle").toFile()
        val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
        val token = "ghp_secret_value_0123456789"
        val rt = LabRuntime(dirs, SettingsStore(dirs.settings), MemorySecretStore(mapOf("github.token" to token, "jira.email" to "kari@example.com", "device_pin" to "4711")),
            adbOverride = FakeAdb { args -> if (args.firstOrNull() == "devices") "List of devices attached\nR5CT123 device usb:1-1 model:SM_S918B" else "Android Debug Bridge version 1.0.41" }.cli).also { no.agens.testlab.services.profile.ExampleProfile.install(it) }
        try {
            File(dirs.data, "logs").mkdirs()
            File(dirs.data, "logs/app.log").writeText("calling with $token for kari@example.com, pin 4711, ticket SHOP-4711\n")
            dirs.traces.mkdirs(); File(dirs.traces, "t1.jsonl").writeText("""{"tool":"handsets_tap"}""")
            val r = rt.supportBundle.write(File(tmp, "out.zip"), listOf("handsets_tap Neste (12 ms)"))
            assertTrue(listOf("about.txt", "secrets.txt", "tools.txt", "devices.txt", "logs/app.log", "traces/t1.jsonl", "activity.txt").all { it in r.files }, r.files.toString())
            ZipFile(r.zip).use { z ->
                val all = z.entries().toList().joinToString("\n") { e -> z.getInputStream(e).readBytes().decodeToString() }
                assertTrue(token !in all && "kari@" !in all, "no token, no e-mail")
                assertTrue("pin ‹redacted›, ticket SHOP-4711" in all, all)
                assertTrue("github.token: set" in all && "typesafe.key: not set" in all)
                assertTrue("R5CT123 device" in all)
            }
            assertEquals(r.files.size, ZipFile(r.zip).use { it.size() })
        } finally { rt.close(); tmp.deleteRecursively() }
    }
}
