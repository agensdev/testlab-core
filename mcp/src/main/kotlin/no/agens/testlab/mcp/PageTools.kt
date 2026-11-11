package no.agens.testlab.mcp

import no.agens.testlab.AppIdentity
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.runtime.Browser
import no.agens.testlab.services.runtime.LabRuntime

/**
 * `lab_open_page`: one of the browser pages, signed in, in the person's default browser (the one
 * registered for https: a report's local file page would otherwise open in whatever app owns .html
 * files, often Safari). [serving] says whether this process serves the pages; [pageUrl] makes a link.
 */
internal fun ToolBox.pageTools(rt: LabRuntime, serving: () -> Boolean, pageUrl: (String, Pair<String, String>?) -> String) {
    tool(
        "lab_open_page",
        """
        Open one of ${AppIdentity.NAME}'s pages in the user's default web browser, signed in: report (a
        written report with its video and screenshots; `id` = report_id, empty = the newest), reports,
        screen (the phone live; `id` = serial), devices, setup, approvals, profiles, handback,
        settings, guide. Use it when the user wants to look at something there, e.g. after
        lab_report_write. Opens on the computer ${AppIdentity.NAME} runs on.
        """,
        str("page", "which page", "report", UiPages.PAGES.sorted()),
        str("id", "report_id for report, serial for screen; empty = the newest report / the active device", ""),
        readOnly = true,
    ) { a ->
        val page = a.str("page")
        if (page !in UiPages.PAGES) throw BadArgument("no page '$page': ${UiPages.PAGES.sorted().joinToString()}")
        val id = a.strOrNull("id") ?: when (page) {
            "report" -> rt.reports.list().firstOrNull { it.written }?.id ?: throw BadArgument("no written report yet: lab_report_write first")
            else -> null
        }
        if (page == "report") rt.reports.folder(id!!) // an unknown id says so here, not on the page
        val query = id?.let { (if (page == "screen") "serial" else "id") to it }
        if (!serving()) {
            // No pages served by this process (a stdio-only server): a report still opens from its folder, in the default browser.
            if (page == "report") {
                val file = java.io.File(rt.reports.folder(id!!), "index.html")
                if (Browser.open(file.toURI().toString())) return@tool text("Opened the report in the default browser: ${file.path}")
            }
            return@tool text("The pages need ${AppIdentity.NAME}'s server: ask the user to run `${AppIdentity.SLUG} open $page${id?.let { " $it" } ?: ""}`.")
        }
        val url = pageUrl(page, query)
        text(if (Browser.open(url)) "Opened $page${id?.let { " $it" } ?: ""} in the default browser." else "No browser here to open it: the user can run `${AppIdentity.SLUG} open $page${id?.let { " $it" } ?: ""}` (the link works once, within a minute).")
    }
}
