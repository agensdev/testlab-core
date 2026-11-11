package no.agens.testlab.services.testrepos

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.core.profile.problems
import no.agens.testlab.services.AppDirs
import io.ktor.client.engine.mock.respond
import no.agens.testlab.services.builds.MockGitHub
import no.agens.testlab.services.profile.ExampleProfile
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestReposTest {
    private val tmp = Files.createTempDirectory("tl-testrepos").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val gh = MockGitHub()
    private val rt = LabRuntime(dirs, SettingsStore(File(dirs.data, "settings.json")), MemorySecretStore(mapOf("github.token" to "ghp_testrepos_0123456789"))).also {
        it.githubHttp = gh.client
        ExampleProfile.install(it, ExampleProfile.PROFILE.copy(testRepos = listOf(
            Profile.TestRepo("guides", "example-org/qa-guides", description = "how we write test cases"),
            Profile.TestRepo("e2e", "example-org/e2e-tests", kind = "maestro"),
        )))
    }
    private var sha = "aaaa1111"
    private var downloads = 0

    /** A repo tarball as GitHub serves it: one top folder `<owner>-<repo>-<sha>/`. */
    private fun tarball(files: Map<String, String>): ByteArray {
        val src = File(tmp, "src-${System.nanoTime()}"); val top = File(src, "example-org-repo-$sha")
        files.forEach { (p, t) -> File(top, p).apply { parentFile.mkdirs(); writeText(t) } }
        val out = File(tmp, "t-${System.nanoTime()}.tar.gz")
        ProcessBuilder("tar", "czf", out.path, "-C", src.path, top.name).start().waitFor()
        return out.readBytes()
    }

    private val guides = mapOf(
        "SKILL.md" to "# Test case management\nFields and conventions.",
        ".claude/skills/review/SKILL.md" to "---\nname: review\ndescription: Review a PR against the test cases\n---\n# Review",
        "references/writing.md" to "Write steps one action at a time.\nExpected results quote the exact text: \"Spillet er kjøpt\".",
    )
    private val e2e = mapOf(
        "tests/games/ageLimit.yaml" to "# Verify age limit\n\nappId: \${APP_ID}\nname: \"Age limit info\"\nproperties:\n  testinyId: TC-6845\ntags:\n  - regtest\n---\n- launchApp",
        "tests/login/login.yaml" to "appId: x\nname: Login\ntags: [smoke, login]\n---\n- tapOn: Log in",
        "tests/fixtures/mock.yaml" to "some: fixture",
    )

    init {
        for ((id, files) in listOf("qa-guides" to guides, "e2e-tests" to e2e)) {
            gh.on("GET /repos/example-org/$id/repos") { respond("", HttpStatusCode.NotFound) }
            gh.on("GET /repos/example-org/$id") { respond("""{"full_name":"example-org/$id","default_branch":"main","private":true,"html_url":"u"}""", HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")) }
            gh.on("GET /repos/example-org/$id/commits/main") { respond("""{"sha":"$sha"}""", HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")) }
            gh.on("GET /repos/example-org/$id/tarball/") { downloads++; respond(tarball(files), HttpStatusCode.OK) }
        }
    }

    @AfterTest
    fun tearDown() { rt.close(); tmp.deleteRecursively() }

    @Test
    fun fetchesReadsSearchesAndIndexes() = runBlocking {
        val tr = rt.testRepos
        val (g, e) = tr.configured()
        val s = tr.sync(g)
        assertTrue(s.changed && s.problem == null && s.state!!.files == 3, s.toString())
        assertEquals(listOf("guides:review", "guides"), tr.skills(g).map { it.name })
        assertEquals("Review a PR against the test cases (from example-org/qa-guides)", tr.skills(g)[0].description)
        assertTrue("one action at a time" in tr.read(g, "references/writing.md"))
        assertEquals(listOf("references/writing.md"), tr.files(g, "", "*.md").filter { "references" in it })
        assertFailsWith<IllegalArgumentException> { tr.read(g, "../../../data/settings.json") }
        tr.sync(e)
        val hits = tr.search(listOf(g, e), "Spillet er kjøpt|TC-6845")
        assertEquals(listOf("guides:references/writing.md", "e2e:tests/games/ageLimit.yaml"), hits.map { "${it.repo}:${it.path}" })
        val flows = tr.flows(e)
        assertEquals(listOf("tests/games/ageLimit.yaml", "tests/login/login.yaml"), flows.map { it.path }, "fixtures without appId are not flows")
        assertEquals("TC-6845", flows[0].testCase); assertEquals(listOf("regtest"), flows[0].tags); assertEquals("Age limit info", flows[0].name)
        assertEquals(listOf("smoke", "login"), flows[1].tags)
    }

    @Test
    fun fetchesAgainOnlyWhenTheBranchMoved() = runBlocking {
        val tr = TestRepos(rt, maxAgeMs = 0)
        val g = tr.configured().first()
        tr.sync(g); assertEquals(1, downloads)
        assertTrue(!tr.sync(g).changed); assertEquals(1, downloads, "same commit: no download")
        sha = "bbbb2222"
        val s = tr.sync(g)
        assertTrue(s.changed && s.state!!.sha == "bbbb2222"); assertEquals(2, downloads)
        gh.on("GET /repos/example-org/qa-guides/commits/main") { respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }
        val failed = tr.sync(g)
        assertTrue(failed.problem != null && "using the copy" in failed.problem!! && tr.skills(g).isNotEmpty(), "a failure keeps the copy: $failed")
    }

    @Test
    fun theProfileChecksItsTestRepos() {
        val bad = ExampleProfile.PROFILE.copy(testRepos = listOf(Profile.TestRepo("Bad Id", "not-a-repo", kind = "other", skills = listOf("../x"))))
        val p = bad.problems()
        assertTrue(p.any { "id must be lower-case" in it } && p.any { "is not owner/name" in it } && p.any { "kind must be docs or maestro" in it } && p.any { "inside the repo" in it }, p.toString())
    }
}
