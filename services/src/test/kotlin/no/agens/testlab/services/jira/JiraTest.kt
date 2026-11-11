package no.agens.testlab.services.jira

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.builds.InstalledBuild
import no.agens.testlab.services.builds.MockGitHub
import no.agens.testlab.services.runtime.Approver
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.runtime.NotApproved
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JiraTest {
    private val tmp = Files.createTempDirectory("tl-jira").toFile()
    private val dirs = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
    private val jira = MockJira()
    private val gh = MockGitHub()
    private val rt = LabRuntime(
        dirs, SettingsStore(File(dirs.data, "settings.json")),
        MemorySecretStore(mapOf("github.token" to "ghp_test_token_1234567890", "jira.email" to "test@example.com", "jira.token" to "jira-secret-token-xyz")),
        adbOverride = FakeAdb { "" }.cli,
    ).also { no.agens.testlab.services.profile.ExampleProfile.install(it) }.also { it.githubHttp = gh.client; it.jiraHttp = jira.client }
    private val repo = "/repos/example-org/shop-android"
    private var asked = mutableListOf<Pair<String, String>>()

    init { rt.approver = Approver { a, d -> asked += a to d; true } }

    @AfterTest
    fun tearDown() { rt.close(); tmp.deleteRecursively() }

    @Test
    fun issueComesBackAsMarkdown() = runBlocking {
        val i = rt.jira.issue("shop-1234")
        assertEquals("SHOP-1234", i.key)
        assertEquals("Utvikler Utviklersen", i.assignee?.displayName)
        assertEquals("## Akseptansekriterier\n\n- The cart viser **items**\n- Se [Figma](https://figma.com/x)", i.description)
        assertEquals(listOf("blocks SHOP-1300"), i.links)
        assertEquals("Klar for test på INT", i.comments.single().markdown)
        assertEquals("https://example.atlassian.net/browse/SHOP-1234", i.url)
        val auth = jira.seen.first().headers["Authorization"]!!.single()
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("test@example.com:jira-secret-token-xyz".toByteArray()), auth)
    }

    @Test
    fun keysAreParsedFromUrlsAndRefusedOtherwise() {
        assertEquals("SHOP-12", Jira.parseKey("https://example.atlassian.net/browse/SHOP-12?focusedCommentId=1"))
        assertEquals("SHOP-7", Jira.parseKey(" shop-7 "))
        assertNull(Jira.parseKey("hello"))
        assertFailsWith<IllegalArgumentException> { Jira.checkKey("../etc") }
    }

    @Test
    fun aRejectedLoginIsSaidAsSuchNotAsAnonymousNotFound() = runBlocking {
        // Jira Cloud answers reads with a bad token as anonymous: 404 and empty searches.
        jira.json("GET ${MockJira.API}/myself", "", 401)
        jira.json("GET ${MockJira.API}/issue/SHOP-9", """{"errorMessages":["Issue does not exist or you do not have permission to see it."]}""", 404)
        val e = assertFailsWith<JiraError> { rt.jira.issue("SHOP-9") }
        assertEquals(401, e.status)
        assertTrue("rejected the e-mail or API token" in e.message!!, e.message)
    }

    @Test
    fun errorsSayWhatToFix() = runBlocking {
        jira.json("GET ${MockJira.API}/issue/SHOP-9", """{"errorMessages":["Issue does not exist or you do not have permission to see it."]}""", 404)
        assertTrue("Not found: read SHOP-9" in assertFailsWith<JiraError> { rt.jira.issue("SHOP-9") }.message!!)
        jira.json("GET ${MockJira.API}/myself", "", 401)
        assertTrue("the setup page" in assertFailsWith<JiraError> { rt.jira.myself() }.message!!)
    }

    @Test
    fun queueUsesTheClearanceLabelsAndFlagsProblems() = runBlocking {
        val q = rt.devtest.queue()
        val body = jira.seen.first { it.url.endsWith("/search/jql") }.body!!
        val jql = (Json.parseToJsonElement(body) as JsonObject)["jql"].toString()
        assertTrue("labels in (\\\"devtest\\\",\\\"devTest\\\")" in jql, jql)
        assertTrue("labels not in (\\\"devtest-approved\\\")" in jql, jql)
        assertEquals(emptyList(), q.single().warnings)
        val approved = q.single().issue.copy(labels = listOf("devtest-approved"))
        assertEquals(2, rt.devtest.warnings(approved).size, "no clearance label, and already approved")
    }

    @Test
    fun requiredLabelsKeepATicketOutUntilItHasOneOfThem() = runBlocking {
        val p = no.agens.testlab.services.profile.ExampleProfile.PROFILE
        no.agens.testlab.services.profile.ExampleProfile.install(rt, p.copy(tracker = p.tracker.copy(requiredLabels = listOf("ui-review-skipped", "ui-review-approved"))))
        val jql = rt.devtest.queueJql()
        assertTrue("labels in (\"devtest\",\"devTest\") AND labels in (\"ui-review-skipped\",\"ui-review-approved\")" in jql, jql)
        val q = rt.devtest.queue()
        val notReviewed = q.single().issue.copy(labels = listOf("devtest"))
        assertTrue(rt.devtest.warnings(notReviewed).any { "ui-review-skipped, ui-review-approved" in it })
        assertEquals(emptyList(), rt.devtest.warnings(notReviewed.copy(labels = listOf("devtest", "UI-Review-Approved"))), "either one, any case")
    }

    @Test
    fun takingATicketRefusesSomeoneElsesUnlessForced() = runBlocking {
        val r = rt.devtest.take("SHOP-1234")
        assertIs<Devtest.TakeResult.SomeoneElse>(r)
        assertTrue(asked.isEmpty(), "nothing asked, nothing written")
        assertTrue(jira.seen.none { it.method == "PUT" })
        val forced = rt.devtest.take("SHOP-1234", force = true)
        assertIs<Devtest.TakeResult.Taken>(forced)
        assertTrue("Utvikler Utviklersen" in asked.single().second)
        assertEquals("""{"accountId":"me-1"}""", jira.seen.single { it.method == "PUT" }.body)
        assertEquals("dev-9", rt.devtest.state()["SHOP-1234"]?.previousAssignee)
    }

    @Test
    fun candidatesMergeJiraAndGitHubWithoutGuessing() = runBlocking {
        gh.json("GET /search/issues", """{"items":[{"number":4321,"title":"SHOP-1234: New cart view","state":"open","draft":false,"user":{"login":"dev"},"updated_at":"2026-10-01T10:00:00Z","html_url":"https://github.com/example-org/shop-android/pull/4321","pull_request":{}}]}""")
        gh.json("GET $repo/git/matching-refs/heads/feature/SHOP-1234", """[{"ref":"refs/heads/feature/SHOP-1234-cart"},{"ref":"refs/heads/feature/SHOP-12345-other"}]""")
        gh.json("GET $repo/git/matching-refs/heads/", "[]")
        gh.json("GET $repo/pulls?state=all&head=example-org%3Afeature%2FSHOP-1234-cart", """[{"number":4321,"title":"SHOP-1234: New cart view","state":"open","draft":false,"user":{"login":"dev"},"updated_at":"2026-10-01T10:00:00Z","html_url":"u"}]""")
        val c = rt.devtest.candidates("SHOP-1234")
        assertEquals(emptyList(), c.problems)
        val one = c.candidates.single()
        assertEquals(4321, one.number)
        assertEquals("feature/SHOP-1234-cart", one.branch)
        assertEquals(3, one.sources.size, one.sources.toString())
        assertEquals(4321, c.single?.number)
        assertTrue(gh.seen.none { "SHOP-12345" in it.url }, "SHOP-12345 is a different ticket")
    }

    @Test
    fun theReportIsPostedOnlyAfterApprovalAndInTheSkillsShape() = runBlocking {
        rt.devtest.take("SHOP-1234", force = true)
        rt.devtest.choose("SHOP-1234", 4321, "feature/SHOP-1234-cart", "emulator-5554")
        rt.builds.installed.record(InstalledBuild("emulator-5554", "com.example.shop.feature", 51200, "5.12.0", "branch", "feature/SHOP-1234-cart", "abc1234def56", 502, null, "sha", "2026-10-02T10:00:00"))
        val report = rt.devtest.compose("SHOP-1234", listOf("✅ The cart shows the items"), listOf("Mørk modus"), "Godkjent.", "INT")
        val md = report.markdown()
        assertTrue(md.startsWith("## Devtest "), md)
        assertTrue("**Build:** 5.12.0 (51200), com.example.shop.feature, CI build @ abc1234d" in md, md)
        assertTrue("**Branch:** `feature/SHOP-1234-cart`" in md, md)
        assertTrue("### Not tested\n- Mørk modus" in md, md)
        assertTrue("\n\n### Tested\n- ✅" in md, "blank line before a section, none inside the list: $md")
        assertTrue(md.endsWith("🤖 Written by Claude, approved by Test Testesen"), md)
        val onSim = report.copy(device = "iPhone 17 (simulator), iOS 26.5").markdown()
        assertTrue("**Build:** 5.12.0 (51200), com.example.shop.feature, CI build @ abc1234d  \n**Device:** iPhone 17 (simulator), iOS 26.5" in onSim || "**Device:** iPhone 17 (simulator), iOS 26.5" in onSim, onSim)
        assertTrue("Device" !in report.copy(device = null).markdown(), "no device, no line")

        asked.clear()
        rt.approver = Approver { a, d -> asked += a to d; false }
        assertFailsWith<NotApproved> { rt.devtest.post("SHOP-1234", report, emptyList(), Outcome.APPROVED) }
        assertTrue(jira.seen.none { it.url.endsWith("/comment") && it.method == "POST" }, "declined: nothing posted")
        assertTrue("add label devtest-approved" in asked.single().second)

        rt.approver = Approver { _, _ -> true }
        val f = File(tmp, "report-standalone.html").apply { writeText("<html></html>") }
        val posted = rt.devtest.post("SHOP-1234", report, listOf(f), Outcome.APPROVED)
        assertEquals("10500", posted.commentId)
        val comment = jira.seen.last { it.url.endsWith("/comment") }.body!!
        assertTrue("\"type\":\"heading\"" in comment && "Devtest" in comment && "\"hardBreak\"" in comment, comment)
        val att = jira.seen.single { it.url.endsWith("/attachments") }
        assertEquals("no-check", att.headers["X-Atlassian-Token"]?.single())
        assertTrue(att.contentType!!.startsWith("multipart/form-data"))
        assertEquals("""{"update":{"labels":[{"add":"devtest-approved"}]}}""", jira.seen.last { it.method == "PUT" }.body)
    }

    @Test
    fun theProfilesTemplateAndOutcomesAreUsed() = runBlocking {
        val p = no.agens.testlab.services.profile.ExampleProfile.PROFILE
        no.agens.testlab.services.profile.ExampleProfile.install(rt, p.copy(
            report = p.report.copy(template = "## Test {{date}}\nAv: {{tester}}\n{{#tested}}\n* {{.}}\n{{/tested}}\n{{^notTested}}\nAlt testet.\n{{/notTested}}", dateFormat = "dd.MM.yyyy", ciBuildWord = "CI-bygg"),
            tracker = p.tracker.copy(onApproved = no.agens.testlab.core.profile.Profile.Outcome(addLabels = listOf("qa-ok"), removeLabels = listOf("devtest"))),
        ))
        val md = rt.devtest.compose("SHOP-1234", listOf("A", "B"), emptyList(), "ok", "INT").markdown()
        assertEquals("## Test ${java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))}\nAv: Test Testesen\n* A\n* B\nAlt testet.", md)
        assertEquals(listOf("add label qa-ok", "remove label devtest"), rt.devtest.outcomeActions("SHOP-1234", Outcome.APPROVED))
        rt.devtest.post("SHOP-1234", rt.devtest.compose("SHOP-1234", listOf("A"), emptyList(), "ok", "INT"), emptyList(), Outcome.APPROVED)
        assertEquals("""{"update":{"labels":[{"add":"qa-ok"},{"remove":"devtest"}]}}""", jira.seen.last { it.method == "PUT" }.body)
    }

    @Test
    fun changesNeededHandsTheTicketBack() = runBlocking {
        rt.devtest.take("SHOP-1234", force = true)
        val report = rt.devtest.compose("SHOP-1234", listOf("❌ The cart"), emptyList(), "Ikke godkjent: krasj ved åpning.", "INT")
        rt.devtest.post("SHOP-1234", report, emptyList(), Outcome.CHANGES)
        assertEquals("""{"accountId":"dev-9"}""", jira.seen.last { it.method == "PUT" }.body)
    }

    @Test
    fun attachmentsOverTheLimitAreRefusedBeforeUpload() = runBlocking {
        jira.json("GET ${MockJira.API}/attachment/meta", """{"enabled":true,"uploadLimit":10}""")
        val f = File(tmp, "session.mp4").apply { writeBytes(ByteArray(100)) }
        assertTrue("at most" in assertFailsWith<JiraError> { rt.jira.attach("SHOP-1234", listOf(f)) }.message!!)
        assertTrue(jira.seen.none { it.url.endsWith("/attachments") })
    }
}
