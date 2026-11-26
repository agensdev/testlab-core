package no.agens.testlab.services.update

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.builds.MockGitHub
import no.agens.testlab.services.runtime.LabEvent
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoUpdateTest {
    private val tmp = Files.createTempDirectory("tl-auto").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val gh = MockGitHub()
    private val settings = SettingsStore(File(dirs.data, "settings.json"))
    // No GitHub token at all: a public repo's releases need none.
    private val rt = LabRuntime(dirs, settings, MemorySecretStore(emptyMap()), adbOverride = FakeAdb().cli).also { it.githubHttp = gh.client }
    private val repo = "/repos/${AppIdentity.REPO}"
    private val home = File(tmp, "opt/${AppIdentity.SLUG}")
    private val log = ByteArrayOutputStream()
    private val notices = mutableListOf<String>()
    private var restarts = 0
    private var clock = 1_000_000_000_000L
    private val unix = HostOs.current() != HostOs.WINDOWS

    @AfterTest
    fun tearDown() { rt.close(); tmp.deleteRecursively() }

    /** An install that answers `--version` with [version]. */
    private fun cli(dir: File, version: String) = dir.apply {
        File(this, "lib").mkdirs()
        File(this, "bin").mkdirs()
        File(this, "bin/${AppIdentity.SLUG}").apply { writeText("#!/bin/sh\necho '${AppIdentity.SLUG} $version'\n"); setExecutable(true) }
    }

    private fun release(version: String) {
        val pack = File(tmp, "pack-$version").apply { mkdirs() }
        cli(File(pack, AppIdentity.SLUG), version)
        val tar = File(tmp, "${AppIdentity.SLUG}-cli-$version-linux-x64.tar.gz")
        check(ProcessBuilder("tar", "czf", tar.path, "-C", pack.path, AppIdentity.SLUG).start().waitFor() == 0)
        gh.json("GET $repo/releases?", """[{"tag_name":"v$version","name":"","draft":false,"prerelease":false,"html_url":"https://example.com/v$version",
            "assets":[{"id":7,"name":"${tar.name}","size":${tar.length()},"url":"https://api.github.com$repo/releases/assets/7"}]}]""")
        gh.on("GET $repo/releases/assets/7") { respond(tar.readBytes(), HttpStatusCode.OK) }
    }

    private fun auto(supervised: Boolean = true) = AutoUpdate(
        rt, PrintStream(log, true), restart = { restarts++ }, supervised = supervised, upgrader = Upgrader(rt, HostOs.LINUX), home = home,
        notice = { title, _ -> notices += title }, now = { clock },
    )

    private fun version() = ProcessBuilder(File(home, "bin/${AppIdentity.SLUG}").path, "--version").start().inputStream.bufferedReader().readText().trim()

    @Test
    fun aNewReleaseIsSaidOnceWhenUpdatesAreNotAutomatic(): Unit = runBlocking {
        if (!unix) return@runBlocking
        cli(home, "0.0.1"); release("99.0.0")
        val a = auto()
        a.tick(); a.tick()
        assertEquals(listOf("${AppIdentity.NAME} 99.0.0 is available"), notices)
        assertEquals("${AppIdentity.SLUG} 0.0.1", version(), "nothing is installed without the setting")
        assertEquals(0, restarts)
        assertTrue(gh.seen.none { "Authorization" in it.headers }, "asked without a token")
    }

    @Test
    fun withTheSettingTheIdleServiceInstallsItAndRestarts(): Unit = runBlocking {
        if (!unix) return@runBlocking
        cli(home, "0.0.1"); release("99.0.0")
        File(dirs.data, "settings.json").apply { parentFile.mkdirs() }.writeText("""{"autoUpdate":true}""")
        val a = auto()

        rt.emit(LabEvent.ToolCall("handsets_tap", "tap", 5, true))
        clock = System.currentTimeMillis() + 60_000
        a.tick()
        assertEquals("${AppIdentity.SLUG} 0.0.1", version(), "a tool ran a minute ago: not now")

        clock += AutoUpdate.QUIET_MS
        a.tick()
        assertEquals("${AppIdentity.SLUG} 99.0.0", version())
        assertEquals(1, restarts)
        assertEquals(listOf("${AppIdentity.NAME} updated to 99.0.0"), notices)
        assertTrue(File(dirs.data, "previous/cli/bin/${AppIdentity.SLUG}").isFile, "the old version is kept for a rollback")
    }

    @Test
    fun aVersionThatWasRolledBackIsNotInstalledAgain(): Unit = runBlocking {
        if (!unix) return@runBlocking
        cli(home, "0.0.1"); release("99.0.0")
        File(dirs.data, "settings.json").apply { parentFile.mkdirs() }.writeText("""{"autoUpdate":true}""")
        auto().tick()
        assertEquals(1, restarts)
        Upgrader(rt, HostOs.LINUX).let { up -> System.setProperty("testlab.home", home.path); try { up.rollback() } finally { System.clearProperty("testlab.home") } }
        assertEquals("${AppIdentity.SLUG} 0.0.1", version())

        // The old version starts again and looks for updates.
        val again = auto()
        again.tick()
        assertEquals("${AppIdentity.SLUG} 0.0.1", version())
        assertEquals(1, restarts)
        assertEquals("${AppIdentity.NAME} 99.0.0 is available", notices.last(), "said, not installed")
    }

    @Test
    fun aServerWhoseInstallWasReplacedRestarts(): Unit = runBlocking {
        if (!unix) return@runBlocking
        cli(home, "0.0.1")
        val a = auto()
        a.tick()
        assertEquals(0, restarts)
        // Another space's server, or `upgrade` in a terminal, swapped the files.
        File(home.parentFile, "old").let { home.renameTo(it) }
        cli(home, "0.0.2")
        a.tick()
        assertEquals(1, restarts)
        assertFalse(gh.seen.any { "assets" in it.url })
    }

    @Test
    fun aServerStartedByHandOnlySaysSo(): Unit = runBlocking {
        if (!unix) return@runBlocking
        cli(home, "0.0.1"); release("99.0.0")
        File(dirs.data, "settings.json").apply { parentFile.mkdirs() }.writeText("""{"autoUpdate":true}""")
        auto(supervised = false).tick()
        assertEquals("${AppIdentity.SLUG} 0.0.1", version())
        assertEquals(0, restarts)
        assertEquals(listOf("${AppIdentity.NAME} 99.0.0 is available"), notices)
    }
}
