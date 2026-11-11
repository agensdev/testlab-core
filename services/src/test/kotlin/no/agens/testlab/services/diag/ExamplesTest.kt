package no.agens.testlab.services.diag

import no.agens.testlab.core.Platform
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.services.github.UserRepo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExamplesTest {
    @Test
    fun promptsUseTheProjectsOwnScreensAndJira() {
        val p = Profile(id = "shop", name = "Shop", tracker = Profile.Tracker(site = "x.atlassian.net", projects = listOf("SHOP")),
            navigation = Profile.Navigation(destinations = mapOf("cart" to Profile.Destination(path = "/cart"))))
        val texts = Examples.prompts(listOf(p)).map { it.prompt }
        assertTrue("Devtest SHOP-123" in texts && texts.any { "Go to cart" in it }, texts.toString())
        assertTrue(texts.none { "iOS" in it }, "no iOS prompt for an Android-only project")
        val bare = Examples.prompts(listOf(Profile(id = "a", name = "A"))).map { it.prompt }
        assertTrue(bare.none { "Devtest" in it }, "no devtest prompt without Jira")
        assertTrue(Examples.prompts(listOf(p, Profile(id = "i", name = "I", platform = Platform.IOS))).any { "iOS" in it.prompt })
    }

    @Test
    fun appReposComeFirstWithAPlatformHint() {
        fun r(n: String, lang: String?, pushed: String) = UserRepo(n, true, null, lang, pushed, false, false)
        val sorted = Examples.appFirst(listOf(r("o/docs", "Markdown", "2026-10-20"), r("o/ios", "Swift", "2026-09-01"), r("o/android", "Kotlin", "2026-10-01")))
        assertEquals(listOf("o/android", "o/ios", "o/docs"), sorted.map { it.fullName })
        assertEquals("iOS?", Examples.platformHint(sorted[1]))
        assertEquals(null, Examples.platformHint(sorted[2]))
    }
}
