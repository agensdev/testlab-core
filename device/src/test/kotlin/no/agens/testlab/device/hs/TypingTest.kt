package no.agens.testlab.device.hs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Typed text is checked against the focused field; lost leading keys are put back. */
class TypingTest {
    private fun screen(flags: String = "cfFev") = """{"ts":1,"root":{"cls":"android.widget.FrameLayout","bounds":[0,0,1080,2400],"flags":"ev","children":[
        {"cls":"android.widget.EditText","rid":"x:id/f","text":"%FIELD%","hint":"Søk","bounds":[0,100,1080,200],"flags":"$flags"}]}}"""

    private fun phone(flags: String = "cfFev") = FakePhone(screen(flags)).apply { fieldHint = "Søk" }

    @Test
    fun lostLeadingKeysAreRepaired() = runTest {
        phone().use { p ->
            p.field = "Abc "
            p.dropKeys = 1
            HsDevice("x") { HsEndpoint("x", p.port) }.use { it.typeText("7Xq-abc") }
            assertEquals("Abc 7Xq-abc", p.field)
            assertTrue(p.nodeCommands.contains("node_set_text value=\"Abc 7Xq-abc\""), p.nodeCommands.toString())
        }
    }

    @Test
    fun anEmptyFieldReportsItsHint() = runTest {
        phone().use { p ->
            p.dropKeys = 2
            HsDevice("x") { HsEndpoint("x", p.port) }.use { it.typeText("123456") }
            assertEquals("123456", p.field)
        }
    }

    @Test
    fun correctTypingNeedsNoRepair() = runTest {
        phone().use { p ->
            HsDevice("x") { HsEndpoint("x", p.port) }.use { it.typeText("hello") }
            assertEquals("hello", p.field)
            assertTrue(p.nodeCommands.none { it.startsWith("node_set_text") })
        }
    }

    @Test
    fun formattedInputAndPasswordsAreLeftAlone() = runTest {
        phone().use { p ->
            p.format = { it.replace(" ", "").chunked(3).joinToString(" ") } // e.g. an amount or account-number mask
            HsDevice("x") { HsEndpoint("x", p.port) }.use { it.typeText("123456789") }
            assertEquals("123 456 789", p.field)
            assertTrue(p.nodeCommands.none { it.startsWith("node_set_text") }, "the app's formatting is not overwritten")
        }
        phone(flags = "cfFevp").use { p ->
            p.dropKeys = 1
            HsDevice("x") { HsEndpoint("x", p.port) }.use { it.typeText("secret1") }
            assertTrue(p.nodeCommands.none { it.startsWith("node_set_text") }, "password fields can't be read back, so they are not checked")
        }
    }

    @Test
    fun aRefusedPasteSetsTheFieldsTextInstead() = runTest {
        phone().use { p ->
            p.field = "Finn "
            p.rejectPaste = true
            val via = HsDevice("x") { HsEndpoint("x", p.port) }.use { it.typeText("Røde Kors") }
            assertEquals("Finn Røde Kors", p.field)
            assertTrue("refused a paste" in via, via)
        }
    }

    @Test
    fun replaceClearsTheFocusedFieldFirst() = runTest {
        phone().use { p ->
            p.field = "Jakobsnes"
            HsDevice("x") { HsEndpoint("x", p.port) }.use { it.replaceFocused("Odd Fellow") }
            assertEquals("Odd Fellow", p.field)
        }
    }
}
