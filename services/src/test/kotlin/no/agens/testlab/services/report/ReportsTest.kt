package no.agens.testlab.services.report

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import no.agens.testlab.device.adb.AdbDevice
import no.agens.testlab.device.hs.FakePhone
import no.agens.testlab.device.hs.HsEndpoint
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.runtime.DeviceBackend
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportsTest {
    private val tmp = Files.createTempDirectory("tl-reports").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val phone = FakePhone("""{"ts":1,"root":{"cls":"android.widget.FrameLayout","bounds":[0,0,160,320],"children":[]}}""", 160, 320)
    private val rt = LabRuntime(dirs, SettingsStore(File(dirs.data, "settings.json")), MemorySecretStore(), backendOverride = object : DeviceBackend {
        override fun devices() = listOf(AdbDevice("phone-1", "device"), AdbDevice("phone-2", "device"))
        override fun connect(serial: String, takeOver: Boolean) = HsEndpoint(serial, phone.port)
        override fun release(serial: String) {}
    }).also { no.agens.testlab.services.profile.ExampleProfile.install(it) }

    @AfterTest
    fun tearDown() { rt.close(); phone.close(); tmp.deleteRecursively() }

    @Test
    fun aRecordedReportEndToEnd(): Unit = runBlocking {
        rt.use("phone-1")
        assertTrue(rt.recordings.start("phone-1"))
        assertFalse(rt.recordings.start("phone-1"), "idempotent")
        val id = rt.reports.start("devtest SHOP-1: ny cart")
        assertTrue(id.endsWith("-devtest-shop-1-ny-cart"), id)
        delay(300)
        val a = rt.reports.screenshot(id, "The cart", null)
        rt.reports.note(id, "Legger til Gifts for å sjekke summen")
        val b = rt.reports.screenshot(id, "Betalt", null)
        val unreferenced = rt.reports.screenshot(id, "Ekstra", null)
        assertEquals(listOf("01-the-cart.jpg", "02-betalt.jpg", "03-ekstra.jpg"), listOf(a, b, unreferenced))
        delay(200)
        val md = "## Resultat\n\nFungerer.\n\n![The cart]($a)\n![Betalt]($b)\n\n<script>alert(1)</script>\n"
        val r = rt.reports.write(id, md, "phone-1")
        assertEquals(3, r.shots)
        assertEquals(1, r.videos.size)
        assertTrue(File(r.dir, "session.mp4").length() > 1000)
        assertFalse(rt.recordings.isRecording("phone-1"), "writing stops the recording")
        val html = r.page.readText()
        assertTrue("<div class=\"shots\">" in html, "the two adjacent screenshots sit side by side")
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html && "<script>alert(1)" !in html, "raw HTML from the agent is escaped")
        assertTrue("\"kind\":\"chapter\",\"title\":\"The cart\"" in html, "screenshots are chapters, inline (no fetch from file://)")
        assertTrue("\"kind\":\"note\",\"title\":\"Legger til Gifts" in html)
        assertTrue("<h2>Screenshots</h2>" in html && "03-ekstra.jpg" in html, "unreferenced shots go to the gallery")
        assertTrue("fetch(" !in html, "no network: works from file://")
        val alone = r.standalone.readText()
        assertTrue("data:image/jpeg;base64," in alone && "src=\"01-the-cart.jpg\"" !in alone)
        val reportMd = File(r.dir, "report.md").readText()
        assertTrue(reportMd.startsWith("# devtest SHOP-1: ny cart") && "[session.mp4](session.mp4)" in reportMd)
        assertEquals(listOf(id), rt.reports.list().map { it.id })
        assertTrue(rt.reports.list().single().video)
        val zip = rt.reports.zip(id)
        assertTrue(ZipFile(zip).use { z -> z.getEntry("$id/session.mp4") != null && z.getEntry("$id/index.html") != null })
        assertFailsWith<UnknownReport> { rt.reports.folder("../etc") }
    }

    @Test
    fun aReportTakesOnlyTheRecordingsOfTheDevicesItShows(): Unit = runBlocking {
        // Two sessions side by side, one per phone; phone-1 is the active device.
        rt.use("phone-1")
        rt.use("phone-2", activate = false)
        assertTrue(rt.recordings.start("phone-1") && rt.recordings.start("phone-2"))
        val id = rt.reports.start("on phone-2")
        delay(300)
        rt.reports.screenshot(id, "Phone two", "phone-2")
        delay(200)
        val r = rt.reports.write(id, "Checked on phone-2.")
        assertEquals(listOf("session.mp4"), r.videos.map { it.file.name }, "only phone-2's recording, as the main clip")
        assertEquals("phone-2", (r.dir.resolve("manifest.json").readText().let { kotlinx.serialization.json.Json.parseToJsonElement(it) } as kotlinx.serialization.json.JsonObject)["videos"].toString().substringAfter("\"serial\":\"").substringBefore('"'))
        assertTrue(rt.recordings.isRecording("phone-1"), "the other session's recording goes on")
        assertFalse(rt.recordings.isRecording("phone-2"))
        rt.recordings.discard("phone-1")
    }

    @Test
    fun aReportWithoutARecordingAndAnExplicitTimeline() = runBlocking {
        val id = rt.reports.start("Analyse")
        val r = rt.reports.write(id, "Bare kode og Crashlytics.")
        assertTrue(r.videos.isEmpty())
        assertFalse("<div class=\"player\"" in r.page.readText())
        assertEquals(1, rt.reports.annotate(id, listOf(TimelineEvent(3.0, "chapter", "Start", "Start"))))
        assertTrue("\"t\":3.00" in File(r.dir, "timeline.json").readText())
    }

    @Test
    fun discardDeletesTheRecording() = runBlocking {
        rt.use("phone-1")
        rt.recordings.start("phone-1")
        delay(200)
        assertEquals(listOf("phone-1"), rt.recordings.discard())
        assertTrue(rt.recordings.status().isEmpty())
        assertTrue(File(dirs.data, "recordings").walkTopDown().none { it.name == "video.h264" })
    }
}
