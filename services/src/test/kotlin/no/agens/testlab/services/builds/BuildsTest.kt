package no.agens.testlab.services.builds

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.github.GitHub
import no.agens.testlab.services.github.GitHubError
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildsTest {
    private val tmp = Files.createTempDirectory("tl-builds").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val gh = MockGitHub()
    private var installOutput = "Performing Streamed Install\nSuccess"
    private val adb = FakeAdb { args ->
        when {
            args.getOrNull(2) == "install" -> installOutput
            args.getOrNull(2) == "shell" && args[3].startsWith("pm path") -> "package:/data/app/x/base.apk"
            else -> ""
        }
    }
    private val rt = LabRuntime(dirs, SettingsStore(File(dirs.data, "settings.json")), MemorySecretStore(mapOf("github.token" to "ghp_test_token_1234567890")), adbOverride = adb.cli).also { no.agens.testlab.services.profile.ExampleProfile.install(it) }
        .also { it.githubHttp = gh.client }
    private val repo = "/repos/example-org/shop-android"
    private val now = Instant.now()

    @AfterTest
    fun tearDown() { rt.close(); tmp.deleteRecursively() }

    private fun run(id: Long, sha: String, branch: String = "feature/SHOP-1-x", status: String = "completed", created: Instant = now.minusSeconds(3600)) =
        """{"id":$id,"name":"Build Feature","path":".github/workflows/build_feature.yml","head_branch":"$branch","head_sha":"$sha","status":"$status","conclusion":${if (status == "completed") "\"success\"" else "null"},"event":"pull_request","run_number":${id % 1000},"created_at":"$created","html_url":"https://github.com/example-org/shop-android/actions/runs/$id"}"""

    private fun artifact(id: Long, name: String, branch: String = "feature/SHOP-1-x", sha: String = "abc1234def", expired: Boolean = false, created: Instant = now.minusSeconds(3600)) =
        """{"id":$id,"name":"$name","size_in_bytes":${MockGitHub.FIXTURE.length() + 200},"expired":$expired,"created_at":"$created","expires_at":"${created.plusSeconds(30L * 86400)}","workflow_run":{"id":900,"head_branch":"$branch","head_sha":"$sha"}}"""

    private fun featurePr() {
        gh.json("GET $repo/pulls/42", """{"number":42,"title":"SHOP-1 Ny cart","draft":false,"state":"open","html_url":"https://github.com/x/pull/42","head":{"ref":"feature/SHOP-1-x","sha":"abc1234def"}}""")
        gh.json("GET $repo/actions/workflows/build_feature.yml/runs?branch=feature%2FSHOP-1-x&status=success", """{"workflow_runs":[${run(900, "abc1234def")}]}""")
        gh.json("GET $repo/actions/workflows/build_feature.yml/runs?branch=feature%2FSHOP-1-x&status=", """{"workflow_runs":[]}""")
        gh.json("GET $repo/actions/runs/900/artifacts", """{"artifacts":[${artifact(501, "unit-test-results", expired = true)},${artifact(502, "app-feature-firebase.apk")}]}""")
    }

    @Test
    fun prNumberFindsItsNewestBuild() = runBlocking {
        featurePr()
        val b = rt.builds.branchBuild("#42")
        assertEquals("feature/SHOP-1-x", b.branch)
        assertEquals(42, b.pr?.number)
        assertEquals(502L, b.artifact?.id, "the APK artifact, not the test report")
        assertTrue(b.isLatest)
        assertNull(b.building)
        val auth = gh.seen.first().headers[HttpHeaders.Authorization]
        assertEquals(listOf("Bearer ghp_test_token_1234567890"), auth)
    }

    @Test
    fun aMissingBuildSaysWhy() = runBlocking {
        gh.json("GET $repo/actions/workflows/build_feature.yml/runs", """{"workflow_runs":[]}""")
        assertTrue("only builds PRs from feature/" in assertFailsWith<NoBuild> { rt.builds.branchBuild("chore/cleanup") }.message!!)
        gh.json("GET $repo/pulls/7", """{"number":7,"title":"t","draft":true,"state":"open","html_url":"u","head":{"ref":"feature/SHOP-7","sha":"s"}}""")
        assertTrue("skips draft PRs" in assertFailsWith<NoBuild> { rt.builds.branchBuild("7") }.message!!)
    }

    @Test
    fun aNewerRunningBuildIsReported() = runBlocking {
        gh.json("GET $repo/pulls/42", """{"number":42,"title":"t","draft":false,"state":"open","html_url":"u","head":{"ref":"feature/SHOP-1-x","sha":"fff0000"}}""")
        gh.json("GET $repo/actions/workflows/build_feature.yml/runs?branch=feature%2FSHOP-1-x&status=success", """{"workflow_runs":[${run(900, "abc1234def")}]}""")
        gh.json("GET $repo/actions/workflows/build_feature.yml/runs?branch=feature%2FSHOP-1-x&status=in_progress", """{"workflow_runs":[${run(901, "fff0000", status = "in_progress", created = now)}]}""")
        gh.json("GET $repo/actions/workflows/build_feature.yml/runs?branch=feature%2FSHOP-1-x&status=queued", """{"workflow_runs":[]}""")
        gh.json("GET $repo/actions/runs/900/artifacts", """{"artifacts":[${artifact(502, "app-feature-firebase.apk")}]}""")
        val b = rt.builds.branchBuild("42")
        assertFalse(b.isLatest)
        assertEquals(901L, b.building?.id)
    }

    @Test
    fun installDownloadsWithoutLeakingTheTokenToStorage() = runBlocking {
        featurePr()
        gh.on("GET $repo/actions/artifacts/502/zip") { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://productionresultssa1.blob.core.windows.net/actions/502.zip?sig=abc")) }
        gh.on("GET https://productionresultssa1.blob.core.windows.net/actions/502.zip") { respond(MockGitHub.artifactZip(MockGitHub.FIXTURE, "app-feature-firebase.apk"), HttpStatusCode.OK) }
        val out = rt.builds.installBranchBuild("42", rt.builds.defaultType(), "phone-1")
        assertTrue(out.result.ok, out.result.output)
        assertEquals("no.agens.testlab.fixture", out.info.packageName)
        assertEquals("1.0-fixture", out.info.versionName)
        val storage = gh.seen.single { "blob.core.windows.net" in it.url }
        assertNull(storage.headers[HttpHeaders.Authorization], "the token must not go to the storage host")
        assertFalse(storage.headers[HttpHeaders.Accept].orEmpty().any { "github" in it }, "nor GitHub's Accept header")
        assertTrue(adb.calls.any { it.startsWith("-s phone-1 install -r -d ") && it.endsWith("app-feature-firebase.apk") }, adb.calls.toString())
        val rec = rt.builds.installed.on("phone-1").single()
        assertEquals("feature/SHOP-1-x" to 502L, rec.branch to rec.artifactId)
        // A second install uses the downloaded file.
        val downloads = gh.seen.count { "/zip" in it.url }
        rt.builds.installBranchBuild("42", rt.builds.defaultType(), "phone-1")
        assertEquals(downloads, gh.seen.count { "/zip" in it.url })
        assertEquals(BuildJob.Phase.DONE, rt.builds.jobs.value.last().phase)
    }

    @Test
    fun installProblemsComeWithAdvice() = runBlocking {
        featurePr()
        gh.on("GET $repo/actions/artifacts/502/zip") { respond(MockGitHub.artifactZip(MockGitHub.FIXTURE), HttpStatusCode.OK) }
        installOutput = "Performing Streamed Install\nadb: failed to install x.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package no.agens.testlab.fixture signatures do not match newer version; ignoring!]"
        val out = rt.builds.installBranchBuild("42", rt.builds.defaultType(), "phone-1")
        assertFalse(out.result.ok)
        assertEquals(InstallProblem.SIGNATURE, out.result.problem)
        assertTrue("Uninstalling it deletes its data" in out.result.advice!!)
        assertTrue(rt.builds.installed.on("phone-1").isEmpty(), "a failed install is not recorded")
        installOutput = "Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]"
        assertEquals(InstallProblem.USB_INSTALL_OFF, rt.builds.installBranchBuild("42", rt.builds.defaultType(), "phone-1").result.problem)
    }

    @Test
    fun githubErrorsAreSpecific() = runBlocking {
        gh.json("GET $repo/pulls/1", """{"message":"Resource protected by organization SAML enforcement."}""", 403, mapOf("X-GitHub-SSO" to "required; url=https://github.com/orgs/example-org/sso?authorization_request=x"))
        val sso = assertFailsWith<GitHubError> { rt.builds.branchBuild("1") }
        assertEquals("https://github.com/orgs/example-org/sso?authorization_request=x", sso.fixUrl)
        gh.json("GET $repo/pulls/2", """{"message":"API rate limit exceeded"}""", 403, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${now.epochSecond + 600}"))
        assertTrue("limit for this token is used up until" in assertFailsWith<GitHubError> { rt.builds.branchBuild("2") }.message!!)
        gh.json("GET $repo/pulls/3", """{"message":"Bad credentials"}""", 401)
        assertEquals(GitHub.TOKEN_PAGE, assertFailsWith<GitHubError> { rt.builds.branchBuild("3") }.fixUrl)
        gh.on("GET $repo/actions/artifacts/77/zip") { respond("""{"message":"Artifact has expired"}""", HttpStatusCode.Gone) }
        val gone = assertFailsWith<GitHubError> { rt.builds.github().downloadArtifact(77, File(tmp, "x.zip")) }
        assertTrue("expired" in gone.message!!)
        rt.secrets.delete("github.token")
        assertTrue("No GitHub token" in assertFailsWith<GitHubError> { rt.builds.branchBuild("4") }.message!!)
    }

    @Test
    fun catalogKeepsTheNewestBuildPerBranchAndStopsAtTheFirstExpiredBuild() = runBlocking {
        gh.json(
            "GET $repo/actions/artifacts?per_page=100&page=1",
            """{"total_count":6,"artifacts":[
              ${artifact(10, "app-feature-firebase.apk", "feature/A")},
              ${artifact(9, "lint-report", "feature/A", expired = true)},
              ${artifact(8, "app-mainFlavor-firebase.apk", "main")},
              ${artifact(7, "app-feature-firebase.apk", "feature/A")},
              ${artifact(6, "app-releaseDebug-minifiedDebug-1a2b3c4.apk", "main")},
              ${artifact(5, "app-feature-firebase.apk", "feature/OLD", expired = true)},
              ${artifact(4, "app-feature-firebase.apk", "feature/NEVER")}]}""",
        )
        val c = rt.builds.catalog()
        assertEquals(listOf("feature" to "feature/A", "main" to "main", "releasedebug" to "main"), c.map { it.type to it.branch })
        assertEquals(10L, c.first().artifactId, "the newest build of a branch")
        assertTrue(c.none { it.branch == "feature/NEVER" }, "paging stops at the first expired build, not at the expired lint report")
        // Incremental: only artifacts newer than the cached newest are new.
        gh.json("GET $repo/actions/artifacts?per_page=100&page=1", """{"total_count":1,"artifacts":[${artifact(11, "app-feature-firebase.apk", "feature/B")},${artifact(10, "app-feature-firebase.apk", "feature/A")}]}""")
        val next = MockGitHub().let { rt.builds.catalog() }
        assertEquals(listOf("feature/B", "feature/A", "main", "main"), next.map { it.branch })
    }
}
