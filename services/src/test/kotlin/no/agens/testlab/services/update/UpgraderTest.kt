package no.agens.testlab.services.update

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.builds.MockGitHub
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpgraderTest {
    private val tmp = Files.createTempDirectory("tl-upgrade").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val gh = MockGitHub()
    private val rt = LabRuntime(dirs, SettingsStore(File(dirs.data, "settings.json")), MemorySecretStore(mapOf("github.token" to "ghp_upgrade_0123456789")), adbOverride = FakeAdb().cli)
        .also { it.githubHttp = gh.client }
    private val repo = "/repos/${AppIdentity.REPO}"
    private val up = Upgrader(rt, HostOs.LINUX)

    @AfterTest
    fun tearDown() { rt.close(); tmp.deleteRecursively() }

    private fun asset(id: Int, name: String) = """{"id":$id,"name":"$name","size":3,"url":"https://api.github.com$repo/releases/assets/$id"}"""

    @Test
    fun theNewestReleaseForThisPlatform(): Unit = runBlocking {
        gh.json("GET $repo/releases?", """[
            {"tag_name":"v0.9.1","name":"","prerelease":true,"assets":[${asset(1, "testlab-cli-0.9.1-linux-x64.tar.gz")}]},
            {"tag_name":"v0.9.5","name":"","prerelease":true,"assets":[${asset(2, "Testlab-0.9.5-macos-arm64.dmg")},${asset(3, "testlab-cli-0.9.5-macos-arm64.tar.gz")},${asset(4, "testlab-cli-0.9.5-linux-x64.tar.gz")}]},
            {"tag_name":"nightly","name":"","prerelease":true,"assets":[]}]""")
        gh.on("GET $repo/releases/assets/4") { respond("cli", HttpStatusCode.OK) }
        val b = up.latestRelease("0.9.0")!!
        assertEquals("0.9.5", b.version)
        assertEquals("testlab-cli-0.9.5-linux-x64.tar.gz", b.cli?.name)
        assertEquals("cli", b.cli?.readText())
        assertTrue(gh.seen.none { "assets/2" in it.url || "assets/3" in it.url }, "only this platform's file is downloaded")
        assertNull(up.latestRelease("0.9.5"), "nothing newer")
    }

    @Test
    fun theNewestCiArtifact(): Unit = runBlocking {
        val zip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                for (n in listOf("testlab-cli-0.9.7-linux-x64.tar.gz", "testlab_0.9.7_amd64.deb", "SHA256SUMS")) { z.putNextEntry(ZipEntry(n)); z.write(n.toByteArray()); z.closeEntry() }
            }
        }.toByteArray()
        gh.json("GET $repo/actions/artifacts?per_page=20&page=1&name=dist-linux", """{"total_count":2,"artifacts":[
            {"id":71,"name":"dist-linux","size_in_bytes":9,"expired":true,"created_at":"2026-09-01T00:00:00Z","workflow_run":{}},
            {"id":72,"name":"dist-linux","size_in_bytes":9,"expired":false,"created_at":"2026-10-05T12:00:00Z","workflow_run":{}}]}""")
        gh.on("GET $repo/actions/artifacts/72/zip") { respond(zip, HttpStatusCode.OK) }
        val b = up.latestCi()
        assertEquals("0.9.7", b.version)
        assertEquals("testlab-cli-0.9.7-linux-x64.tar.gz", b.cli?.name)
        assertTrue("2026-10-05" in b.label, b.label)
        gh.json("GET $repo/actions/artifacts?per_page=20&page=1&name=dist-linux", """{"total_count":0,"artifacts":[]}""")
        assertTrue("run Actions → package" in assertFailsWith<Upgrader.Failed> { up.latestCi() }.message!!)
    }

    @Test
    fun downloadedFiles() {
        val d = File(tmp, "dist-macos").apply { mkdirs() }
        File(d, "testlab-cli-1.2.3-linux-x64.tar.gz").writeText("x")
        assertEquals("1.2.3", up.fromPath(d).version)
        assertTrue("no linux-x64" in assertFailsWith<Upgrader.Failed> { up.fromPath(File(tmp, "work").apply { mkdirs() }) }.message!!)
        assertTrue("nothing to roll back" in assertFailsWith<Upgrader.Failed> { up.rollback() }.message!!)
    }

    @Test
    fun theTokenForTheTestlabRepoNotTheProfiles(): Unit = runBlocking {
        val scoped = LabRuntime(dirs, SettingsStore(File(dirs.data, "settings2.json")),
            MemorySecretStore(mapOf("github.token" to "ghp_work_account_0123", "github.token@${AppIdentity.REPO.substringBefore('/')}" to "ghp_personal_0123456")), adbOverride = FakeAdb().cli)
            .also { it.githubHttp = gh.client }
        gh.json("GET $repo/releases?", "[]")
        assertNull(Upgrader(scoped, HostOs.LINUX).latestRelease("0.9.0"))
        assertEquals("Bearer ghp_personal_0123456", gh.seen.last().headers["Authorization"]?.single())
        scoped.close()
    }

    @Test
    fun aPublicReposReleaseNeedsNoTokenAndSurvivesARefusedOne(): Unit = runBlocking {
        val releases = """[{"tag_name":"v0.9.5","name":"","prerelease":false,"assets":[${asset(4, "testlab-cli-0.9.5-linux-x64.tar.gz")}]}]"""
        gh.on("GET $repo/releases?") { req ->
            if (req.headers["Authorization"] != null) respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized) else respond(releases, HttpStatusCode.OK)
        }
        gh.on("GET $repo/releases/assets/4") { respond("cli", HttpStatusCode.OK) }
        // An expired token: asked again without it.
        assertEquals("0.9.5", up.latestRelease("0.9.0")!!.version)
        val none = LabRuntime(dirs, SettingsStore(File(dirs.data, "settings3.json")), MemorySecretStore(emptyMap()), adbOverride = FakeAdb().cli).also { it.githubHttp = gh.client }
        gh.seen.clear()
        assertEquals("cli", Upgrader(none, HostOs.LINUX).latestRelease("0.9.0")!!.cli.readText())
        assertTrue(gh.seen.none { s -> s.headers.keys.any { it.equals("Authorization", ignoreCase = true) } }, "no token: none is sent, also for the download")
        none.close()
    }

    @Test
    fun theNewCliUpdatesTheClaudeCodePluginAfterAnUpgrade() {
        // A stand-in launcher that records how it was run and answers like the real one.
        val home = File(tmp, "cli-home").apply { mkdirs() }
        val log = File(tmp, "launcher.log")
        File(home, "bin").mkdirs()
        File(home, "bin/${AppIdentity.SLUG}").apply {
            writeText("#!/bin/sh\necho \"$@\" >> ${log.path}\necho 'Claude Code plugin: updated to 9.9.9 (restart Claude Code, or /reload-plugins)'\n")
            setExecutable(true)
        }
        assertEquals("Claude Code plugin: updated to 9.9.9 (restart Claude Code, or /reload-plugins)", up.refreshClaudePlugin(home))
        assertEquals("claude-plugin --if-installed", log.readText().trim(), "only where it was installed")
        assertEquals(null, up.refreshClaudePlugin(File(tmp, "no-such-home")), "no launcher: nothing, and no failure")
    }
}
