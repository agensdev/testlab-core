package no.agens.testlab.core.profile

import no.agens.testlab.core.autopilot.AutopilotConfig
import no.agens.testlab.core.nav.Destinations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileTest {
    @Test
    fun templateSectionsListsAndBlanks() {
        val t = "# {{title}}\n{{#items}}\n- {{.}}\n{{/items}}\n{{^items}}\n- none\n{{/items}}\n{{#note}}\nNote: {{note}}\n{{/note}}\nend"
        assertEquals("# T\n- a\n- b\nend", Template.render(t, mapOf("title" to "T"), mapOf("items" to listOf("a", "b"))))
        assertEquals("# T\n- none\nNote: n\nend", Template.render(t, mapOf("title" to "T", "note" to "n"), mapOf("items" to emptyList())))
        assertTrue(runCatching { Template.render("{{#x}}", emptyMap(), emptyMap()) }.isFailure)
        assertTrue(Profile.GENERIC.problems().isEmpty())
    }

    @Test
    fun problemsAreWordedPerField() {
        val bad = Profile(
            id = "bad id", name = "x", github = Profile.GitHub(repo = "nope"),
            builds = listOf(Profile.BuildDef("a", "A", artifact = "("), Profile.BuildDef("a", "A2", artifact = "x")),
            tracker = Profile.Tracker(site = "https://x.atlassian.net", projects = listOf("lower")),
            navigation = Profile.Navigation(destinations = mapOf("d" to Profile.Destination(path = "/p")), aliases = mapOf("z" to "missing")),
        )
        val p = bad.problems()
        listOf("id 'bad id'", "github.repo", "used twice", "not a valid regex", "tracker.site", "'lower'", "navigation.host", "aliases.z").forEach { w ->
            assertTrue(p.any { w in it }, "expected a problem about $w in $p")
        }
    }

    @Test
    fun destinationsComeFromTheProfile() {
        val nav = Profile.Navigation(
            host = "https://www.example.com/",
            destinations = linkedMapOf("books" to Profile.Destination(path = "/shop/books", group = "shop", subpages = true), "home" to Profile.Destination(tab = "home_screen", group = "tabs")),
            subpages = listOf("results"), aliases = mapOf("start" to "home"),
        )
        val d = Destinations(nav)
        assertEquals(Destinations.Target.Deeplink("https://www.example.com/shop/books/results"), d.resolve("Books", "results"))
        assertEquals(Destinations.Target.Tab("home_screen"), d.resolve("start"))
        assertEquals(null, d.resolve("nowhere"))
        assertEquals("https://www.example.com/a?b=1", d.url("a?b=1"))
        assertTrue("shop : books" in d.listing() && "+ subpage: results" in d.listing(), d.listing())
        assertTrue(runCatching { Destinations(Profile.Navigation()).url("/x") }.exceptionOrNull()!!.message!!.contains("navigation.host"))

        val customScheme = Profile.Navigation(destinations = mapOf("cart" to Profile.Destination(path = "testshop://cart")))
        assertEquals("testshop://cart", Destinations(customScheme).url("testshop://cart"))
        assertTrue(Profile(id = "ios", name = "iOS", platform = no.agens.testlab.core.Platform.IOS, navigation = customScheme).problems().isEmpty())
    }

    @Test
    fun autopilotWordingFollowsTheProfile() {
        val generic = AutopilotConfig()
        assertTrue("Glossary" !in generic.questionInstructions && "'More' tab" in generic.questionInstructions)
        val c = AutopilotConfig(glossary = "Hjem=Home", labelLanguage = "Norwegian", pickNote = "mind the bets", doneExample = "`A` is not `B`", riskyWords = listOf("kjøp"), leaveWords = listOf("tilbake"))
        assertTrue(c.questionInstructions.endsWith("reversible; mind the bets. Glossary: Hjem=Home"), c.questionInstructions)
        assertTrue("labels are mostly Norwegian" in c.questionInstructions)
        assertTrue("Tab names must match exactly: `A` is not `B`." in c.doneInstructions)
        assertTrue(c.riskyRe.containsMatchIn("KJØP NÅ") && !generic.riskyRe.containsMatchIn("KJØP NÅ") && generic.riskyRe.containsMatchIn("Buy now"))
        assertTrue(c.leaveRe.matches("Tilbake") && !generic.leaveRe.matches("Tilbake"))
    }

    @Test
    fun aProfileFromBeforeTheRenameStillLoads() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(Profile.serializer(), """{"id":"a","name":"A","jev":{"glossary":"Hjem=Home"}}""")
        assertEquals("Hjem=Home", old.autopilot.glossary)
        assertEquals(old, json.decodeFromString(Profile.serializer(), """{"id":"a","name":"A","autopilot":{"glossary":"Hjem=Home"}}"""))
    }
}
