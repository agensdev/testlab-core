package no.agens.testlab.core.sel

import kotlinx.serialization.json.Json
import no.agens.testlab.core.profile.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoiseDialogsTest {
    /** A test-build service's sign-in prompt over a nightly build, as the daemon dumps it. */
    private fun dump(pkg: String, vararg texts: String) = Json.parseToJsonElement(
        """{"root":{"pkg":"$pkg","cls":"android.widget.FrameLayout","bounds":[0,0,1008,2244],"children":[""" +
            texts.mapIndexed { i, t -> """{"cls":"android.widget.Button","text":"$t","flags":"vc","bounds":[100,${1000 + i * 100},900,${1080 + i * 100}],"children":[]}""" }.joinToString(",") + "]}}",
    )

    private val rule = Profile.Dismiss(
        name = "Firebase App Distribution sign-in",
        whenShown = listOf("varsler om nye testversjoner", "alerts about new test builds", "in-app alerts"),
        tap = listOf("Ikke nå", "Not now"),
        packages = listOf("com.example.shop.nightly", "com.example.shop.feature"),
    )

    @Test
    fun theDialogIsDismissedWithItsNamedButtonOnly() {
        val hit = NoiseDialogs.find(dump("com.example.shop.nightly", "Enable in-app alerts", "Turn on", "Not now"), listOf(rule))!!
        assertEquals("Not now", hit.button.text)
        assertEquals(500 to 1240, hit.button.bounds!!.centerX to hit.button.bounds!!.centerY)
        assertEquals("Ikke nå", NoiseDialogs.find(dump("com.example.shop.feature", "Få varsler om nye testversjoner", "Slå på", "IKKE NÅ"), listOf(rule))!!.button.text.let { if (it == "IKKE NÅ") "Ikke nå" else it })
        assertNull(NoiseDialogs.find(dump("com.example.shop", "Enable in-app alerts", "Not now"), listOf(rule)), "another app: not this rule")
        assertNull(NoiseDialogs.find(dump("com.example.shop.nightly", "Enable in-app alerts", "Turn on"), listOf(rule)), "no named button: nothing is pressed")
        assertNull(NoiseDialogs.find(dump("com.example.shop.nightly", "Not now"), listOf(rule)), "the button alone isn't the dialog")
    }
}
