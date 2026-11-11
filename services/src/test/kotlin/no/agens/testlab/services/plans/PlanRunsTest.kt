package no.agens.testlab.services.plans

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import no.agens.testlab.device.ToolLocator
import no.agens.testlab.device.adb.AdbDevice
import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.device.hs.FakePhone
import no.agens.testlab.device.hs.HsEndpoint
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.runtime.DeviceBackend
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanRunsTest {
    private val tmp = Files.createTempDirectory("tl-runs").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val phone = FakePhone(
        """{"ts":1,"root":{"cls":"android.widget.FrameLayout","bounds":[0,0,1080,2400],"flags":"ev","children":[
           {"cls":"android.widget.Button","rid":"x:id/next","text":"Neste","bounds":[100,2000,980,2140],"flags":"cfev"}]}}""",
    )
    private val adb = FakeAdb { "Success" }
    private val rt = LabRuntime(dirs, SettingsStore(File(dirs.data, "settings.json")), MemorySecretStore(), adbOverride = adb.cli, backendOverride = object : DeviceBackend {
        override fun devices() = listOf(AdbDevice("phone-1", "device"))
        override fun connect(serial: String, takeOver: Boolean) = HsEndpoint(serial, phone.port)
        override fun release(serial: String) {}
    }).also { no.agens.testlab.services.profile.ExampleProfile.install(it) }

    @AfterTest
    fun tearDown() { rt.close(); phone.close(); tmp.deleteRecursively() }

    private fun plan(s: String) = Json.parseToJsonElement(s) as JsonArray

    @Test
    fun aHandBackWaitsAndTheRunGoesOn(): Unit = runBlocking {
        val p = plan("""[{"tap":"Neste","verify":false},{"wait_for":"Kvittering","timeout":0.3},{"tap":"Neste","verify":false}]""")
        val run = rt.planRuns.run(p, "demo", "phone-1", BackendChoice("stub"), onHandBack = { r ->
            assertEquals(PlanRun.State.PAUSED, r.state)
            assertEquals(1, r.pausedAt)
            delay(200) // the operator looks at it
            Resolution.Skip
        })
        assertEquals(PlanRun.State.COMPLETED, run.state)
        val res = run.result!!
        assertEquals("3", (res["steps"] as JsonArray).size.toString())
        assertEquals("1", res["hand_backs"].toString())
        assertTrue(res["operator_s"].toString().toDouble() >= 0.2)
        assertEquals(2, phone.taps.size)
    }

    @Test
    fun theWindowResolvesByTapping(): Unit = runBlocking {
        val p = plan("""[{"wait_for":"Kvittering","timeout":0.2},{"note":"after"}]""")
        val job = async { rt.planRuns.run(p, "click", "phone-1", BackendChoice("stub")) }
        var paused: PlanRun? = null
        repeat(100) { if (paused == null) { paused = rt.planRuns.runs.value.firstOrNull { it.state == PlanRun.State.PAUSED }; delay(50) } }
        rt.planRuns.resolve(paused!!.id, Resolution.Tapped(540, 2070))
        val run = job.await()
        assertEquals(PlanRun.State.COMPLETED, run.state)
        assertEquals(540 to 2070, phone.taps.last())
    }

    @Test
    fun aComparisonRecordsEachArmIntoOneReport(): Unit = runBlocking {
        val p = plan("""[{"tap":"Neste","verify":false},{"sleep":0.3},{"tap":"Neste","verify":false}]""")
        val r = rt.comparison.run(p, "demo", "phone-1", listOf(BackendChoice("stub"), BackendChoice("stub")), clearPackage = "no.example.app")
        assertEquals(2, r.arms.size)
        assertTrue(r.arms.all { it.run.state == PlanRun.State.COMPLETED && it.video != null })
        assertEquals(2, adb.calls.count { it == "-s phone-1 shell pm clear 'no.example.app'" }, adb.calls.toString())
        val html = r.page.readText()
        assertTrue("arm-1.mp4" in html && "arm-2.mp4" in html, "a player per arm")
        assertTrue("<td>Stub (first option)</td>" in html && "<th>Hand-backs</th>" in html)
        if (ToolLocator(appToolsDir = dirs.cache).findOptional("ffmpeg") != null) assertTrue(r.sideBySide?.isFile == true, "ffmpeg is installed, so the clip is made")
    }
}
