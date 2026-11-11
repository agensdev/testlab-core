package no.agens.testlab.services.builds

import io.ktor.http.HttpStatusCode
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import no.agens.testlab.core.Platform
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.device.adb.LabDevice
import no.agens.testlab.device.hs.HsEndpoint
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.profile.ExampleProfile
import no.agens.testlab.services.runtime.DeviceBackend
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.runtime.PlatformOps
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A simulator build of the iOS profile's repo, installed on a simulator in a space that also has an Android profile. */
class IosBuildsTest {
    private val tmp = Files.createTempDirectory("tl-ios-builds").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val gh = MockGitHub()
    private val installed = mutableListOf<File>()

    private val iosProfile = ExampleProfile.PROFILE.copy(
        id = "example-shop-ios", name = "Example Shop iOS", platform = Platform.IOS,
        app = Profile.App(defaultPackage = "com.example.shop.ios", family = "com.example.shop"),
        github = Profile.GitHub(repo = "example-org/shop-ios"),
        builds = listOf(
            Profile.BuildDef("simulator", "Simulator", "build_simulator.yml", "Shop-Simulator", "com.example.shop.ios", default = true),
            Profile.BuildDef("feature", "Feature", null, ".*__id=com\\.example\\.shop\\.feature__.*\\.ipa", "com.example.shop.feature", target = "device"),
        ),
        tracker = ExampleProfile.PROFILE.tracker.copy(projects = listOf("IOS")),
        releases = Profile.Releases(),
    )

    private val backend = object : DeviceBackend {
        override fun devices() = listOf(
            LabDevice("phone-1", "device"), LabDevice("SIM-1", "device", model = "iPhone 17", platform = Platform.IOS, os = "26.5"),
            LabDevice("IPHONE-1", LabDevice.INSTALL_ONLY, model = "Test iPhone", platform = Platform.IOS, os = "18.6", kind = "iphone"),
        )
        override fun connect(serial: String, takeOver: Boolean) = HsEndpoint(serial, 1)
        override fun release(serial: String) {}
    }

    private val rt = LabRuntime(
        dirs, SettingsStore(File(dirs.data, "settings.json")), MemorySecretStore(mapOf("github.token" to "ghp_test_token_1234567890")),
        adbOverride = FakeAdb { "" }.cli, backendOverride = backend,
    ).also { ExampleProfile.install(it); it.githubHttp = gh.client }

    private val onDevice = mutableListOf<File>()

    init {
        rt.profiles.save(iosProfile)
        rt.profiles.activate("example-shop-ios")
        rt.profiles.activate("example-shop")
        rt.iosOps = object : PlatformOps {
            override val platform = Platform.IOS
            override suspend fun openUrl(serial: String, url: String, pkg: String) = "opened $url"
            override suspend fun install(serial: String, file: File): InstallResult { installed += file; return InstallResult(true, "Success", null) }
            override suspend fun isInstalled(serial: String, pkg: String) = true
            override suspend fun uninstall(serial: String, pkg: String) = "Success"
            override suspend fun clearData(serial: String, pkg: String) {}
            override suspend fun packages(serial: String, prefix: String) = emptyList<String>()
            override fun fontScale(serial: String) = 1.0
            override fun setFontScale(serial: String, scale: Double) = scale
            override fun resetFontScale(serial: String) = 1.0
            override fun originalFontScale(serial: String): Double? = null
            override suspend fun unlock(serial: String, pin: String?) = "not locked"
        }
        rt.iosDeviceOps = object : PlatformOps by rt.iosOps!! {
            override suspend fun install(serial: String, file: File): InstallResult { onDevice += file; return InstallResult(true, "Success", null) }
        }
    }

    @AfterTest
    fun tearDown() { rt.close(); tmp.deleteRecursively() }

    private val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>com.example.shop.ios</string>
  <key>CFBundleExecutable</key><string>Shop</string>
  <key>CFBundleShortVersionString</key><string>2.3.0</string>
  <key>CFBundleVersion</key><string>417</string>
  <key>UIDeviceFamily</key><array><integer>1</integer></array>
</dict></plist>"""

    /** What CI uploads: the bundle's contents at the zip's root (no Shop.app folder). */
    private fun contentsZip(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, body) in listOf("Info.plist" to plist, "Shop" to "binary", "Frameworks/Lib.framework/Lib" to "lib")) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private val repo = "/repos/example-org/shop-ios"

    @Test
    fun aSimulatorBuildFromTheIosRepoInstallsOnTheSimulator() = runBlocking {
        val now = Instant.now().minusSeconds(600)
        gh.json("GET $repo/pulls/7", """{"number":7,"title":"IOS-12 Ny kurv","draft":false,"state":"open","html_url":"https://github.com/x/pull/7","head":{"ref":"feature/IOS-12-kurv","sha":"cafe1234"}}""")
        gh.json("GET $repo/actions/workflows/build_simulator.yml/runs?branch=feature%2FIOS-12-kurv&status=success",
            """{"workflow_runs":[{"id":77,"name":"Sim","path":"p","head_branch":"feature/IOS-12-kurv","head_sha":"cafe1234","status":"completed","conclusion":"success","event":"pull_request","run_number":5,"created_at":"$now","html_url":"https://github.com/x/actions/runs/77"}]}""")
        gh.json("GET $repo/actions/workflows/build_simulator.yml/runs?branch=feature%2FIOS-12-kurv&status=", """{"workflow_runs":[]}""")
        gh.json("GET $repo/actions/runs/77/artifacts", """{"artifacts":[{"id":901,"name":"Shop-Simulator","size_in_bytes":400,"expired":false,"created_at":"$now","workflow_run":{"id":77,"head_branch":"feature/IOS-12-kurv","head_sha":"cafe1234"}}]}""")
        gh.on("GET $repo/actions/artifacts/901/zip") { respond(contentsZip(), HttpStatusCode.OK) }

        assertEquals("example-shop", rt.profile.id, "Android is in focus")
        val type = rt.builds.type("", rt.builds.profileOf("SIM-1"))
        val out = rt.builds.installBranchBuild("7", type, "SIM-1")
        assertTrue(out.result.ok)
        assertEquals("com.example.shop.ios", out.info.packageName)
        assertEquals("2.3.0" to 417L, out.info.versionName to out.info.versionCode)
        val app = installed.single()
        assertEquals("Shop.app", app.name, "the bundle's contents are named after its executable")
        assertTrue(File(app, "Info.plist").isFile && File(app, "Frameworks/Lib.framework/Lib").isFile)
        assertTrue(gh.seen.none { "shop-android" in it.url }, "the Android repo is not asked")
        val rec = rt.builds.installed.on("SIM-1").single()
        assertEquals("feature/IOS-12-kurv" to "com.example.shop.ios", rec.branch to rec.packageName)

        assertEquals(rec, rt.devtest.installed("SIM-1"), "the devtest's build line reads the simulator's build")
        assertTrue("2.3.0 (417), com.example.shop.ios" in rt.devtest.buildLine(rec, rt.devtest.profileFor("IOS-12"))!!)
    }

    @Test
    fun aTicketsProjectPicksItsProfileAndTheQueueCoversBoth() {
        assertEquals("example-shop-ios", rt.devtest.profileFor("IOS-12").id)
        assertEquals("example-shop", rt.devtest.profileFor("SHOP-3").id)
        val jql = rt.devtest.queueJql()
        assertTrue(" OR " in jql && "project = IOS" in jql && "project = SHOP" in jql, jql)
    }

    /** An .ipa: Payload/<name>.app with its Info.plist and signing profile. */
    private fun ipa(bundle: String, expires: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            val profile = "garbage\u0000<plist><dict><key>ExpirationDate</key>\n\t<date>$expires</date></dict></plist>\u0000sig"
            for ((name, body) in listOf("Payload/Shop.app/Info.plist" to plist.replace("com.example.shop.ios", bundle), "Payload/Shop.app/Shop" to "binary", "Payload/Shop.app/embedded.mobileprovision" to profile)) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun aDeviceBuildFromAnyWorkflowInstallsOnARealIphone() = runBlocking {
        val now = Instant.now().minusSeconds(600)
        val artifactName = "Shop__id=com.example.shop.feature__42.ipa"
        // The GitHub artifact is a zip around the .ipa.
        val wrapped = ByteArrayOutputStream().also { bos -> ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry(artifactName)); z.write(ipa("com.example.shop.feature", "2099-01-01T00:00:00Z")); z.closeEntry() } }.toByteArray()
        gh.json("GET $repo/pulls/8", """{"number":8,"title":"IOS-13 Kvittering","draft":false,"state":"open","html_url":"https://github.com/x/pull/8","head":{"ref":"feature/IOS-13-kvittering","sha":"beef5678"}}""")
        fun run(id: Int) = """{"id":$id,"name":"W$id","path":"p","head_branch":"feature/IOS-13-kvittering","head_sha":"beef5678","status":"completed","conclusion":"success","event":"pull_request","run_number":$id,"created_at":"$now","html_url":"https://github.com/x/actions/runs/$id"}"""
        gh.json("GET $repo/actions/runs?branch=feature%2FIOS-13-kvittering&status=success", """{"workflow_runs":[${run(88)},${run(89)}]}""")
        gh.json("GET $repo/actions/runs/88/artifacts", """{"artifacts":[{"id":910,"name":"lint-report","size_in_bytes":4,"expired":false,"created_at":"$now"}]}""")
        gh.json("GET $repo/actions/runs/89/artifacts", """{"artifacts":[{"id":911,"name":"$artifactName","size_in_bytes":400,"expired":false,"created_at":"$now","workflow_run":{"id":89,"head_branch":"feature/IOS-13-kvittering","head_sha":"beef5678"}}]}""")
        gh.on("GET $repo/actions/artifacts/911/zip") { respond(wrapped, HttpStatusCode.OK) }

        assertTrue(rt.installOnly("IPHONE-1") && !rt.installOnly("SIM-1"))
        val type = rt.builds.type("", rt.builds.profileOf("IPHONE-1"), realDevice = true)
        assertEquals("feature", type.id, "a real iPhone takes the first device build type")
        assertEquals("simulator", rt.builds.type("", rt.builds.profileOf("SIM-1"), realDevice = false).id)
        val out = rt.builds.installBranchBuild("8", type, "IPHONE-1")
        assertTrue(out.result.ok, out.result.output)
        assertEquals("com.example.shop.feature", out.info.packageName)
        assertEquals(artifactName.replace('=', '_'), onDevice.single().name, "the .ipa itself, kept as a file")
        assertTrue(installed.isEmpty(), "nothing went to the simulator's ops")
        assertEquals("89", out.build!!.run.id.toString(), "the run that has the build, not the newest run")

        // With an Apple team the iPhone's screen is driven: no longer install-only, still a real device.
        rt.settings.update { it.copy(device = it.device.copy(iosTeamId = "ABCDE12345")) }
        assertTrue(!rt.installOnly("IPHONE-1") && rt.realIos("IPHONE-1"))
        assertTrue(rt.phoneDriving()!!.wdaBundleId.endsWith(".wda.abcde12345.WebDriverAgentRunner"), rt.phoneDriving()!!.wdaBundleId)
    }

    @Test
    fun deviceBuildsKeepTheirIpaAndSimulatorsRefuseIt() = runBlocking {
        val zip = File(tmp, "ipa.zip").apply { writeBytes(ipa("com.example.shop.ios", "2020-01-01T00:00:00Z")) }
        val ipa = IosApp.unpack(zip, File(tmp, "out"), "Shop.ipa")
        assertEquals("Shop.ipa", ipa.name, "an artifact that is the .ipa's own zip is kept as the .ipa")
        assertEquals("com.example.shop.ios", BuildFiles.manifest(ipa).packageName)
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), IosApp.profileExpiry(ipa))
        val sim = no.agens.testlab.services.runtime.IosSimOps(rt, no.agens.testlab.device.ios.XcrunCli(exec = { _, _ -> error("not called") }))
        assertTrue("device build" in sim.install("SIM-1", ipa).output)
        val calls = mutableListOf<String>()
        val ctl = no.agens.testlab.device.ios.DevicectlCli(no.agens.testlab.device.ios.XcrunCli(exec = { args, _ -> calls += args.joinToString(" "); no.agens.testlab.device.ios.XcrunCli.Result(0, "", "") }))
        val ops = no.agens.testlab.services.runtime.IosDeviceOps(rt, ctl)
        val expired = ops.install("IPHONE-1", ipa)
        assertTrue(!expired.ok && "expired on 2020-01-01" in expired.output && calls.isEmpty(), "an expired profile is refused before devicectl: ${expired.output}")
        assertTrue("simulator build" in ops.install("IPHONE-1", File(tmp, "out").apply { mkdirs() }).output)
        ops.launch("IPHONE-1", "com.example.shop.ios", listOf("-environment", "int"))
        assertTrue(calls.single().startsWith("devicectl device process launch --device IPHONE-1 --terminate-existing com.example.shop.ios -- -environment int --json-output"), calls.toString())
    }
}
