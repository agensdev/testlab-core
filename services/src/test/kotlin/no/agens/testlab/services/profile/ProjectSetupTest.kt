package no.agens.testlab.services.profile

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import no.agens.testlab.core.profile.problems
import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.builds.MockGitHub
import no.agens.testlab.services.jira.MockJira
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectSetupTest {
    private val tmp = Files.createTempDirectory("tl-setup").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val gh = MockGitHub()
    private val jira = MockJira()
    private val rt = LabRuntime(
        dirs, SettingsStore(File(dirs.data, "settings.json")),
        MemorySecretStore(mapOf("github.token" to "ghp_setup_token_0123456789", "jira.email" to "a@example.com", "jira.token" to "jira-setup-token-0123")),
        adbOverride = FakeAdb().cli,
    ).also { it.githubHttp = gh.client; it.jiraHttp = jira.client }
    private val repo = "/repos/acme/app-android"
    private val now = Instant.now()

    @AfterTest
    fun tearDown() { rt.close(); tmp.deleteRecursively() }

    private fun run(id: Long, event: String) =
        """{"id":$id,"name":"x","path":"p","head_branch":"main","head_sha":"abc1234","status":"completed","conclusion":"success","event":"$event","run_number":1,"created_at":"$now","html_url":"u$id"}"""

    private fun art(id: Long, name: String) =
        """{"id":$id,"name":"$name","size_in_bytes":${MockGitHub.FIXTURE.length() + 200},"expired":false,"created_at":"$now","expires_at":"${now.plusSeconds(86400)}","workflow_run":{"id":1}}"""

    private val prYaml = """
        name: PR build
        on:
          pull_request:
            branches:
              - main
          workflow_dispatch:
            inputs:
              flavor:
                description: Backend
                default: staging
                type: choice
                options:
                  - staging
                  - production
        jobs:
          build:
            if: startsWith(github.head_ref, 'feature/') || startsWith(github.head_ref, 'fix/')
    """.trimIndent()

    private fun github() {
        gh.on("GET $repo") { req ->
            if (req.url.encodedPath == repo) respond("""{"full_name":"acme/app-android","default_branch":"main","private":true,"html_url":"h"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
        }
        gh.json("GET $repo/actions/workflows?", """{"workflows":[
            {"id":1,"name":"PR build","path":".github/workflows/pr-build.yml","state":"active"},
            {"id":2,"name":"Nightly","path":".github/workflows/nightly.yml","state":"active"},
            {"id":3,"name":"Old","path":".github/workflows/old.yml","state":"disabled_manually"}]}""")
        gh.json("GET $repo/actions/workflows/pr-build.yml/runs", """{"workflow_runs":[${run(11, "pull_request")}]}""")
        gh.json("GET $repo/actions/workflows/nightly.yml/runs", """{"workflow_runs":[${run(12, "schedule")}]}""")
        gh.json("GET $repo/actions/runs/11/artifacts", """{"artifacts":[${art(101, "app-debug-apk")},${art(102, "test-results")}]}""")
        gh.json("GET $repo/actions/runs/12/artifacts", """{"artifacts":[${art(103, "app-nightly-1.4.2-abcdef12.apk")}]}""")
        gh.on("GET $repo/contents/.github/workflows/pr-build.yml") { respond(prYaml, HttpStatusCode.OK) }
        gh.on("GET $repo/contents/.github/workflows/nightly.yml") { respond("on:\n  schedule:\n    - cron: '0 3 * * *'\n", HttpStatusCode.OK) }
        gh.json("GET $repo/releases?", "[]")
        gh.on("GET $repo/actions/artifacts/101/zip") { respond(MockGitHub.artifactZip(MockGitHub.FIXTURE, "app-debug.apk"), HttpStatusCode.OK) }
    }

    @Test
    fun aRepoScanProposesBuildTypes(): Unit = runBlocking {
        github()
        val scan = rt.projectSetup.scanRepo("acme/app-android", "https://api.github.com")
        assertEquals("main", scan.defaultBranch)
        assertEquals(listOf("app-debug-apk", "app-nightly-1.4.2-abcdef12.apk"), scan.candidates.map { it.artifact.name }, "test-results and the disabled workflow are left out")
        val pr = scan.candidates.first { it.workflowFile == "pr-build.yml" }
        assertTrue(pr.proposal.default, "the pull_request workflow is the default")
        assertEquals(listOf("feature/", "fix/"), pr.proposal.autoPrefixes)
        assertEquals("staging", pr.proposal.dispatchInputs["flavor"]?.default)
        assertEquals(listOf("staging", "production"), pr.proposal.dispatchInputs["flavor"]?.choices)
        val nightly = scan.candidates.first { it.workflowFile == "nightly.yml" }
        assertTrue(Regex(nightly.proposal.artifact).matches("app-nightly-1.5.0-0123abcd.apk"), nightly.proposal.artifact)
        assertTrue(!Regex(nightly.proposal.artifact).matches("app-nightly-1.5.0.aab"))

        val read = rt.projectSetup.readManifest("acme/app-android", pr, "https://api.github.com")
        assertEquals("no.agens.testlab.fixture", read.proposal.packageName)
        assertEquals(listOf("https://www.example.com", "exampleshop://open"), read.manifest?.links)

        val p = rt.projectSetup.draft("acme", "Acme", scan, listOf(read, nightly), null, emptyList(), emptyList(), null, null, null)
        assertEquals(emptyList(), p.problems())
        assertEquals("no.agens.testlab.fixture", p.app.defaultPackage)
        assertEquals("https://www.example.com", p.navigation.host)
        assertEquals("acme/app-android", p.github.repo)
        rt.profiles.save(p)
    }

    @Test
    fun aJiraScanSuggestsLabels() = runBlocking {
        jira.json("GET ${MockJira.API}/project/search", """{"values":[{"key":"SHOP","name":"Shop"},{"key":"WEB","name":"Web"}]}""")
        jira.json("GET ${MockJira.API}/label", """{"values":["android","devtest","devtest-approved","ios","Sidekick"]}""")
        rt.settings.update { it.copy(connections = it.connections.copy(jiraApiUrls = mapOf("example.atlassian.net" to MockJira.SITE))) }
        val s = rt.projectSetup.scanJira("https://example.atlassian.net/")
        assertEquals(listOf("SHOP" to "Shop", "WEB" to "Web"), s.projects)
        assertEquals(listOf("devtest"), s.queueLabels)
        assertEquals("devtest-approved", s.approvedLabel)
        val auth = jira.seen.first().headers["Authorization"]!!.single()
        assertEquals("Basic " + Base64.getEncoder().encodeToString("a@example.com:jira-setup-token-0123".toByteArray()), auth)
    }

    @Test
    fun theReposTheTokenCanRead(): Unit = runBlocking {
        fun repo(i: Int, pushed: String) = """{"full_name":"acme/r$i","private":${i % 2 == 0},"description":"","language":"Kotlin","pushed_at":"$pushed","archived":false,"fork":false}"""
        val page1 = (1..100).joinToString(",", "[", "]") { repo(it, "2026-01-01T00:00:%02dZ".format(it % 60)) }
        gh.on("GET /user/repos?per_page=100&page=1") { respond(page1, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-GitHub-SSO" to listOf("partial-results; organizations=21955855,20582480"))) }
        gh.json("GET /user/repos?per_page=100&page=2", "[${repo(101, "2026-10-05T10:00:00Z")}]")
        val l = rt.projectSetup.repos("https://api.github.com")
        assertEquals(101, l.repos.size)
        assertEquals("acme/r101", l.repos.first().fullName, "newest push first")
        assertEquals(listOf("21955855", "20582480"), l.ssoHidden)
        assertTrue(!l.truncated)
        assertTrue(gh.seen.all { it.headers["Authorization"]?.single() == "Bearer ghp_setup_token_0123456789" })
    }

    @Test
    fun helpers() {
        assertEquals("pr", ProjectSetup.idFor("build-pr.yml", "x"))
        assertEquals("nightly", ProjectSetup.idFor("nightly-build.yml", "x"))
        assertEquals("com.acme.app", ProjectSetup.commonPrefix(listOf("com.acme.app.debug", "com.acme.app.beta", "com.acme.app")))
        assertEquals(null, ProjectSetup.commonPrefix(listOf("com.acme", "org.other")))
        assertTrue(ProjectSetup.looksLikeApk("release-apk") && !ProjectSetup.looksLikeApk("app-release.aab") && !ProjectSetup.looksLikeApk("apk-lint-reports"))
    }
}
