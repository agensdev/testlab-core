package no.agens.testlab.core.sel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Upstream `hs` v0.1.38 tap/fill/selector semantics (tap_text.rs, selector.rs, main.rs) and our extensions. */
class NodeQueryTest {
    private fun dump(name: String): JsonElement = Json.parseToJsonElement(File("src/jvmTest/resources/goldens/dumps/$name.json").readText())
    private val settings = dump("settings-home")

    // --- ported from tap_text.rs tests ---

    @Test
    fun rejectsAggregatorMatchForChineseLabel() {
        val d = Json.parseToJsonElement(
            """{"root":{"cls":"android.widget.FrameLayout","rid":"","text":"","desc":"Apps: Calendar, Chrome, 闲鱼, Maps","flags":"cev","bounds":[0,0,1080,2400],
               "children":[{"cls":"android.widget.TextView","rid":"com.foo:id/calendar","text":"Calendar","desc":"","flags":"cev","bounds":[100,1100,300,1300],"children":[]}]}}""",
        )
        assertTrue(NodeQuery.tapHits(d, "闲鱼").isEmpty(), "the launcher root's aggregated description must not be tapped")
    }

    @Test
    fun stillMatchesSpecificIcon() {
        val d = Json.parseToJsonElement(
            """{"root":{"cls":"android.widget.FrameLayout","bounds":[0,0,1080,2400],
               "children":[{"cls":"android.widget.TextView","rid":"com.foo:id/xianyu","text":"闲鱼","flags":"cev","bounds":[400,1500,600,1700],"children":[]}]}}""",
        )
        val hits = NodeQuery.tapHits(d, "闲鱼")
        assertEquals(1, hits.size)
        assertEquals(Bounds(400, 1500, 600, 1700), hits[0].bounds)
    }

    // --- ported from selector.rs tests ---

    @Test
    fun parsesHasTextToSubstringAttr() {
        val s = Selector.parse("Button:has-text(\"Sign in\")").single()
        assertEquals(Selector.ClassMatch.Simple("Button"), s.cls)
        assertEquals(listOf(Selector.AttrPred("text", Selector.Op.SUBSTR, "Sign in")), s.attrs)
    }

    @Test
    fun parsesTextIsToEqAttr() {
        assertEquals(listOf(Selector.AttrPred("text", Selector.Op.EQ, "OK")), Selector.parse("Button:text-is(\"OK\")").single().attrs)
    }

    @Test
    fun rejectsEmptyHasTextArgument() {
        val e = assertFailsWith<Selector.ParseError> { Selector.parse("Button:has-text(\"\")") }
        assertTrue("has-text" in e.message!!)
    }

    @Test
    fun keepsRelationalPseudoClassesIntact() {
        assertIs<Selector.Relation.Below>(Selector.parse("EditText:below(TextView[text=Email])").single().relations.single())
    }

    // --- tap on a real capture ---

    @Test
    fun labelIsPromotedToItsClickableRow() {
        val hit = NodeQuery.tapHits(settings, "Network & internet").first()
        assertEquals("Network & internet", hit.text)
        assertEquals(Bounds(0, 628, 1080, 834), hit.bounds, "the row, not the label")
        assertEquals("cfev", hit.flags)
        assertNull(NodeQuery.clickSelector(hit), "the row has no id, so it is tapped by coordinates")
        assertEquals(
            "tapped \"Network & internet\" cls=android.widget.LinearLayout flags=cfev bounds=[0,628,1080,834] at (540,731) via tap → ok",
            NodeQuery.tapReport(hit, "tap"),
        )
    }

    @Test
    fun clickByIdCarriesTheLabel() {
        val hit = NodeQuery.tapHits(settings, "Search Settings").first()
        assertEquals("com.android.settings:id/search_action_bar", hit.rid)
        assertEquals("id=com.android.settings:id/search_action_bar text=\"Search Settings\"", NodeQuery.clickSelector(hit))
    }

    @Test
    fun priorityLadder() {
        assertEquals(NodeQuery.EXACT_TEXT, NodeQuery.tapHits(settings, "Apps").first().priority)
        assertEquals(NodeQuery.IEXACT_TEXT, NodeQuery.tapHits(settings, "network & INTERNET").first().priority)
        val sub = NodeQuery.tapHits(settings, "hotspot").first()
        assertEquals(NodeQuery.SUB_TEXT, sub.priority)
        assertEquals("Mobile, Wi‑Fi, hotspot", sub.text)
        // An exact match wins over an earlier substring match ("Apps" is also inside "…default apps").
        assertEquals("Apps", NodeQuery.tapHits(settings, "apps").first().text)
    }

    @Test
    fun idShortcut() {
        val hits = NodeQuery.tapHits(settings, "#search_bar_title")
        assertEquals(listOf(Bounds(189, 208, 578, 279)), hits.map { it.bounds })
        assertEquals(1, NodeQuery.tapHits(settings, "#com.android.settings:id/search_bar_title").size, "the full id works too")
        assertTrue(NodeQuery.tapHits(settings, "#nope").isEmpty())
    }

    @Test
    fun offScreenHitsAreReportedNotHidden() {
        // "Storage" is below the fold: no 'v' flag and clipped bounds. The device layer refuses it; the query still finds it.
        val hit = NodeQuery.tapHits(settings, "Storage").first()
        assertTrue('v' !in hit.flags)
    }

    @Test
    fun caseInsensitiveBeyondAscii() {
        val d = Json.parseToJsonElement(
            """{"root":{"cls":"android.widget.FrameLayout","bounds":[0,0,1080,2400],"flags":"ev","children":[
               {"cls":"android.widget.Button","rid":"com.example.shop:id/buy","text":"KJØP","flags":"cev","bounds":[100,2000,980,2140]}]}}""",
        )
        // Upstream lowercases ASCII only, so "kjøp" never matched "KJØP"; it now does, ranked after every upstream tier.
        assertEquals("KJØP", NodeQuery.tapHits(d, "kjøp").single().text)
        assertEquals(NodeQuery.EXACT_TEXT, NodeQuery.tapHits(d, "KJØP").single().priority)
    }

    @Test
    fun cssSelectorsWhenNoTextMatches() {
        val hit = NodeQuery.tapHits(settings, "TextView:has-text(\"Network\")").first()
        assertEquals("Network & internet", hit.text)
        assertEquals("selector", hit.via)
        assertEquals("Services & preferences", NodeQuery.tapHits(settings, "TextView:below(TextView[text=\"Google\"])").first().text)
        assertEquals("Search Settings", NodeQuery.tapHits(settings, "[id\$=search_bar_title]").first().text)
        assertTrue(NodeQuery.tapHits(settings, "Button:has-text(\"Nope\")").isEmpty())
        // A word that is not on screen parses as a class name and matches nothing, instead of erroring.
        assertTrue(NodeQuery.tapHits(settings, "Continue").isEmpty())
    }

    @Test
    fun selectorRelations() {
        val roots = UiNode.roots(settings)
        val m = Matcher(roots)
        val inRow = m.findAll(Selector.parse("TextView:in(RelativeLayout):text-is(\"Apps\")"))
        assertEquals(listOf("Apps"), inRow.map { it.text })
        val near = m.findAll(Selector.parse("TextView:near(TextView[text=\"Apps\"], 250)"))
        assertTrue(near.map { it.text }.containsAll(listOf("Apps", "Assistant, recent apps, default apps")))
        val right = m.findAll(Selector.parse("TextView:right-of(ImageView[id=\"com.android.settings:id/imageView\"])"))
        assertEquals("Search Settings", right.first().text)
        assertEquals(2, m.findAll(Selector.parse("TextView[text=\"Apps\"], TextView[text=\"Modes\"]")).size, "comma is OR")
        val flags = m.findAll(Selector.parse("LinearLayout:clickable:focusable"))
        assertTrue(flags.isNotEmpty() && flags.all { 'c' in it.flags && 'f' in it.flags })
    }

    @Test
    fun selectorParseErrors() {
        assertFailsWith<Selector.ParseError> { Selector.parse("Button:bogus") }
        assertFailsWith<Selector.ParseError> { Selector.parse("Button[text=\"x\"") }
        assertFailsWith<Selector.ParseError> { Selector.parse(":near(Button)") }
        assertNull(Selector.parseOrNull("Kl. 12:00"))
    }

    // --- fill and wait ---

    @Test
    fun fillFindsTheInputByHint() {
        val dialer = dump("dialer")
        assertEquals("id=com.google.android.dialer:id/digits", NodeQuery.fillSelector(dialer, "Phone Number"))
        assertEquals("id=com.google.android.dialer:id/digits", NodeQuery.fillSelector(dialer, "phone"))
        assertEquals("text~=Phone", NodeQuery.fillSelector(dialer, "text~=Phone"), "daemon grammar passes through")
        assertNull(NodeQuery.fillSelector(settings, "Email"))
    }

    @Test
    fun fillAnchorsOnALabel() {
        val d = Json.parseToJsonElement(
            """{"root":{"cls":"android.widget.FrameLayout","bounds":[0,0,1080,2400],"children":[
               {"cls":"android.widget.TextView","text":"Fødselsnummer","bounds":[60,400,600,460],"flags":"ev"},
               {"cls":"android.widget.EditText","text":"","bounds":[60,470,1020,600],"flags":"cfev"},
               {"cls":"android.widget.TextView","text":"Passord","bounds":[60,700,400,760],"flags":"ev"},
               {"cls":"android.widget.EditText","rid":"x:id/pw","text":"","bounds":[60,770,1020,900],"flags":"cfev"}]}}""",
        )
        assertEquals("class=EditText", NodeQuery.fillSelector(d, "fødselsnummer"))
        assertEquals("id=x:id/pw", NodeQuery.fillSelector(d, "Passord"))
    }

    @Test
    fun quotingForTheDaemon() {
        assertEquals("\"Søk her\"", NodeQuery.quoted("Søk her"))
        assertNull(NodeQuery.quoted("say \"hi\""))
        assertNull(NodeQuery.quoted("a\\b"))
        assertEquals("\"say \\\"hi\\\"\"", NodeQuery.debugQuote("say \"hi\""))
    }

    @Test
    fun presentForWaits() {
        assertEquals("Network & internet", NodeQuery.present(settings, "network &"))
        assertEquals("#search_bar_title", NodeQuery.present(settings, "#search_bar_title"))
        assertEquals("Apps", NodeQuery.present(settings, "TextView:text-is(\"Apps\")"))
        assertNull(NodeQuery.present(settings, "Shopet er kjøpt"))
    }
}
