package no.agens.testlab.services.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlanItemsTest {
    private val pass = PlanItem("1", "a", PlanItem.TESTED, PlanItem.PASS)
    private val skipped = PlanItem("2", "b", PlanItem.NOT_TESTED, note = "needs a second device")

    @Test
    fun theResultLineFollowsTheItems() {
        assertEquals("All items passed. No findings.", PlanItems.resultLine(listOf(pass)))
        assertEquals("No failures in what was tested. 1 item(s) not tested.", PlanItems.resultLine(listOf(pass, skipped)))
        val bug = PlanItem("3", "c", PlanItem.TESTED, PlanItem.FAIL, PlanItem.BUG)
        assertEquals("1 finding(s). 1 item(s) not tested.", PlanItems.resultLine(listOf(pass, skipped, bug)))
        val env = PlanItem("4", "d", PlanItem.TESTED, PlanItem.FAIL, "environment")
        assertEquals("No findings. 1 failure(s) not counted as findings (by design, test environment, or also on the control).", PlanItems.resultLine(listOf(pass, env)), "a test-environment failure is not a finding, nor a pass")
        assertEquals("No failures in what was tested. 1 item(s) unclear.", PlanItems.resultLine(listOf(pass, PlanItem("5", "e", PlanItem.TESTED, PlanItem.UNCLEAR))))
    }

    @Test
    fun markingNeedsWhatTheReportMustSay() {
        val open = PlanItem("1", "a")
        assertFailsWith<IllegalArgumentException> { PlanItems.mark(open, PlanItem.NOT_TESTED, null, null, null, emptyList()) }
        assertFailsWith<IllegalArgumentException> { PlanItems.mark(open, PlanItem.TESTED, null, null, null, emptyList()) }
        assertFailsWith<IllegalArgumentException> { PlanItems.mark(open, PlanItem.TESTED, PlanItem.FAIL, null, null, emptyList()) }
        val done = PlanItems.mark(open, PlanItem.TESTED, PlanItem.PASS, "bug", "fine", listOf("01-a.jpg"))
        assertEquals(null, done.verdict, "a verdict belongs to failures only")
        assertEquals(listOf("01-a.jpg"), done.evidence)
    }

    @Test
    fun theSectionListsEveryItemAndProfilesCanRewordIt() {
        val s = PlanItems.section(listOf(pass, skipped, PlanItem("9", "never looked at")))
        assertTrue("### Tested\n- **1** a — passed" in s && "### Not tested\n- **2** b: needs a second device" in s && "### Not accounted for\n- **9** never looked at" in s, s)
        val nb = PlanItems.section(listOf(pass)) { k -> if (k == "allPassed") "Alle tester bestått." else PlanItems.TEXTS.getValue(k) }
        assertTrue(nb.trimEnd().endsWith("Alle tester bestått."), nb)
    }
}
