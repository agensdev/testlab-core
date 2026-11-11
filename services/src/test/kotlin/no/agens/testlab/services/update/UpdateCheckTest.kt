package no.agens.testlab.services.update

import kotlinx.coroutines.runBlocking
import no.agens.testlab.services.builds.MockGitHub
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {
    private val gh = MockGitHub()
    private val f = File(Files.createTempDirectory("tl-upd").toFile(), "update.json")
    private var clock = Instant.parse("2026-10-02T10:00:00Z")

    private fun check(token: String? = "ghp_x", current: String = "0.9.0") =
        UpdateCheck(gh.client, f, { token }, current = current, repo = "example/testlab", now = { clock })

    @Test
    fun versionsCompareNumerically() {
        assertTrue(UpdateCheck.newer("0.10.0", "0.9.0"))
        assertTrue(UpdateCheck.newer("v1.0.0", "0.9.9"))
        assertFalse(UpdateCheck.newer("0.9.0", "0.9.0"))
        assertFalse(UpdateCheck.newer("0.8.9", "0.9.0"))
    }

    @Test
    fun aNewerReleaseIsReportedAndCachedForADay() = runBlocking {
        gh.json("GET /repos/example/testlab/releases?", """[{"tag_name":"v0.9.1","draft":false,"prerelease":true,"html_url":"u091"},{"tag_name":"v1.0.0","draft":false,"prerelease":false,"html_url":"https://github.com/example/testlab/releases/tag/v1.0.0"},{"tag_name":"v2.0.0","draft":true,"html_url":"draft"}]""")
        assertEquals("https://github.com/example/testlab/releases/tag/v1.0.0", check().check()?.url, "the highest version that is not a draft")
        assertEquals(1, gh.seen.size)
        clock = clock.plusSeconds(3600)
        assertEquals("1.0.0", check().check()?.version)
        assertEquals(1, gh.seen.size, "answered from the cache")
        assertNull(check(current = "1.0.0").check(), "nothing newer than this build")
        clock = clock.plusSeconds(86400)
        check().check()
        assertEquals(2, gh.seen.size)
    }

    @Test
    fun aPreReleaseCountsForThePilot() = runBlocking {
        gh.json("GET /repos/example/testlab/releases?", """[{"tag_name":"v0.9.1","draft":false,"prerelease":true,"html_url":"u091"}]""")
        assertEquals("0.9.1", check().check()?.version)
    }

    @Test
    fun noTokenOrNoAccessMeansNoAnswer() = runBlocking {
        assertNull(check(token = null).check())
        assertTrue(gh.seen.isEmpty())
        gh.json("GET /repos/example/testlab/releases?", """{"message":"Not Found"}""", 404)
        assertNull(check().check(force = true))
    }
}
