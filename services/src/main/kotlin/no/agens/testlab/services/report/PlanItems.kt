package no.agens.testlab.services.report

import kotlinx.serialization.Serializable

/**
 * A test plan's items in a report. Every item ends up tested (pass, fail or unclear) or not tested
 * with a reason, and the result line follows from them: «all passed, no findings» only when nothing
 * is untested and nothing failed. A report with an item nobody accounted for is not written.
 */
@Serializable
data class PlanItem(
    val id: String,
    val text: String,
    /** open | tested | not_tested */
    val status: String = OPEN,
    /** for tested items: pass | fail | unclear */
    val result: String? = null,
    /** for failed items: bug (in scope: back to the author) | by_design | environment | existing (also on the control: its own ticket) */
    val verdict: String? = null,
    /** what was seen, or why it was not tested */
    val note: String? = null,
    /** screenshot files that show it */
    val evidence: List<String> = emptyList(),
) {
    val finding: Boolean get() = status == TESTED && result == FAIL && (verdict == null || verdict == BUG)

    companion object {
        const val OPEN = "open"
        const val TESTED = "tested"
        const val NOT_TESTED = "not_tested"
        const val PASS = "pass"
        const val FAIL = "fail"
        const val UNCLEAR = "unclear"
        const val BUG = "bug"
        val STATUSES = listOf(TESTED, NOT_TESTED)
        val RESULTS = listOf(PASS, FAIL, UNCLEAR)
        val VERDICTS = listOf(BUG, "by_design", "environment", "existing")
    }
}

class ItemsUnaccounted(val open: List<PlanItem>) : IllegalStateException(
    "not written: ${open.size} plan item(s) are neither tested nor marked not tested: " +
        open.joinToString("; ") { "${it.id} «${it.text.take(60)}»" } +
        ". Mark each with lab_report_item (status tested + result, or not_tested + the reason), or pass allow_open=true to write a draft that lists them as not accounted for.",
)

object PlanItems {
    /** Checks a status change and returns the item updated (errors name what is missing). */
    fun mark(item: PlanItem, status: String, result: String?, verdict: String?, note: String?, evidence: List<String>): PlanItem {
        if (status !in PlanItem.STATUSES) throw IllegalArgumentException("status: tested or not_tested")
        if (status == PlanItem.TESTED && result !in PlanItem.RESULTS) throw IllegalArgumentException("a tested item needs result: pass, fail or unclear")
        if (status == PlanItem.NOT_TESTED && note.isNullOrBlank()) throw IllegalArgumentException("a not-tested item needs the reason (note): «needs a second device», «no suitable data on the environment»…")
        if (verdict != null && verdict !in PlanItem.VERDICTS) throw IllegalArgumentException("verdict: ${PlanItem.VERDICTS.joinToString()}")
        if (status == PlanItem.TESTED && result == PlanItem.FAIL && verdict == null) throw IllegalArgumentException("a failed item needs a verdict: bug (in scope), by_design, environment (the test environment did it) or existing (also on the control build)")
        return item.copy(status = status, result = if (status == PlanItem.TESTED) result else null, verdict = if (result == PlanItem.FAIL) verdict else null,
            note = note?.trim()?.ifEmpty { null } ?: item.note, evidence = (item.evidence + evidence).distinct())
    }

    /** Texts a profile may replace (`texts["report.items.<key>"]`); `{n}` is a count. */
    val TEXTS = mapOf(
        "heading" to "Plan items",
        "tested" to "Tested",
        "notTested" to "Not tested",
        "unaccounted" to "Not accounted for",
        "result" to "Result",
        "allPassed" to "All items passed. No findings.",
        "noFailures" to "No failures in what was tested.",
        "noFindings" to "No findings.",
        "notFindings" to "{n} failure(s) not counted as findings (by design, test environment, or also on the control).",
        "notTestedCount" to "{n} item(s) not tested.",
        "findings" to "{n} finding(s).",
        "unclear" to "{n} item(s) unclear.",
        "unclearItem" to "unclear",
        "pass" to "passed",
        "fail" to "failed",
        "bug" to "finding",
        "by_design" to "by design (not a finding)",
        "environment" to "test environment (not a finding)",
        "existing" to "existing bug, also on the control (separate ticket)",
    )

    /** The result line, from the items alone: «all passed» only when every item was tested and passed. */
    fun resultLine(items: List<PlanItem>, t: (String) -> String = { TEXTS.getValue(it) }): String {
        val findings = items.count { it.finding }
        val notTested = items.count { it.status != PlanItem.TESTED }
        val unclear = items.count { it.status == PlanItem.TESTED && it.result == PlanItem.UNCLEAR }
        val otherFails = items.count { it.status == PlanItem.TESTED && it.result == PlanItem.FAIL && !it.finding }
        if (findings == 0 && notTested == 0 && unclear == 0 && otherFails == 0) return t("allPassed")
        return listOfNotNull(
            when {
                findings > 0 -> t("findings").replace("{n}", "$findings")
                otherFails > 0 -> t("noFindings")
                else -> t("noFailures")
            },
            if (otherFails > 0) t("notFindings").replace("{n}", "$otherFails") else null,
            if (notTested > 0) t("notTestedCount").replace("{n}", "$notTested") else null,
            if (unclear > 0) t("unclear").replace("{n}", "$unclear") else null,
        ).joinToString(" ")
    }

    /** The «Plan items» section: tested, not tested (with reasons), anything unaccounted for, and the result line. */
    fun section(items: List<PlanItem>, t: (String) -> String = { TEXTS.getValue(it) }): String = buildString {
        appendLine("## ${t("heading")}")
        val tested = items.filter { it.status == PlanItem.TESTED }
        val notTested = items.filter { it.status == PlanItem.NOT_TESTED }
        val open = items.filter { it.status == PlanItem.OPEN }
        fun line(i: PlanItem, what: String?) = "- **${i.id}** ${i.text}" + (what?.let { " — $it" } ?: "") + (i.note?.let { ": $it" } ?: "") +
            if (i.evidence.isNotEmpty()) " (" + i.evidence.joinToString(", ") { "[$it]($it)" } + ")" else ""
        if (tested.isNotEmpty()) {
            appendLine(); appendLine("### ${t("tested")}")
            tested.forEach { i ->
                val what = when (i.result) {
                    PlanItem.PASS -> t("pass")
                    PlanItem.FAIL -> t("fail") + (i.verdict?.let { ", " + t(it) } ?: "")
                    else -> t("unclearItem")
                }
                appendLine(line(i, what))
            }
        }
        if (notTested.isNotEmpty()) { appendLine(); appendLine("### ${t("notTested")}"); notTested.forEach { appendLine(line(it, null)) } }
        if (open.isNotEmpty()) { appendLine(); appendLine("### ${t("unaccounted")}"); open.forEach { appendLine(line(it, null)) } }
        appendLine(); appendLine("### ${t("result")}"); appendLine(resultLine(items, t))
    }
}
