package no.agens.testlab.mcp

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.agens.testlab.mcp.Param.Companion.int
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.runtime.LabRuntime

/** A PR's screens before and after, from the reference images of the app's screenshot tests. */
internal fun ToolBox.screenTools(rt: LabRuntime) {
    tool(
        "lab_pr_screens",
        """
        The screens a PR changes, before and after, without building it: the reference images of the
        app's screenshot tests (Paparazzi, Roborazzi, swift-snapshot-testing; profile.snapshotImages)
        that the PR adds, changes or removes, fetched from GitHub at the merge base and the PR's head,
        and set side by side. Use them when planning: they show which screens and states the change
        touches, and what they should look like. They are rendered by tests, not on a phone: fonts,
        data and system bars differ, and a screen without a screenshot test isn't there. `changed`
        says how much of an image differs and where (as a share of its height); look there with show=N.
        A re-recorded test often differs by a hair (rendering noise) while the real change is elsewhere,
        e.g. semantics or accessibility: then test that, not the looks. show=N
        returns the Nth pair as an image; report_id copies the pairs into a report and gives the
        Markdown to put in lab_report_write.
        """,
        int("pr", "the PR number"),
        str("filter", "only images whose path contains this", ""),
        int("limit", "at most this many images", 30),
        int("show", "return pair N (1-based) as an image", 0),
        str("report_id", "a report to copy the pairs into", ""),
        str("platform", "android | ios: whose project the PR is in; empty = the active device's", "", listOf("", "android", "ios")),
        readOnly = true,
    ) { a ->
        val r = rt.prScreens.forPr(a.int("pr"), a.strOrNull("filter"), a.int("limit").coerceIn(1, 200))
        val show = a.int("show")
        if (show > 0) {
            val s = r.screens.getOrNull(show - 1) ?: throw BadArgument("PR #${r.number} has ${r.screens.size} changed screenshot image(s)")
            val f = s.pair ?: s.after ?: s.before ?: throw IllegalStateException("${s.path} couldn't be downloaded")
            return@tool Out.Image(f.readBytes(), "image/png", "${s.name} (${s.status}) · ${s.path}")
        }
        val links = a.strOrNull("report_id")?.let { id ->
            val dir = java.io.File(rt.reports.folder(id), "screens").apply { mkdirs() }
            r.screens.mapNotNull { s -> s.pair?.let { p -> val dest = java.io.File(dir, "pr${r.number}-${p.name}"); p.copyTo(dest, overwrite = true); "![${s.name} (${s.status})](screens/${dest.name})" } }
        }
        text(buildJsonObject {
            put("pr", r.number); put("title", r.title); put("base", r.base.take(10)); put("head", r.head.take(10))
            put("count", r.screens.size)
            if (r.more > 0) put("more", "${r.more} more: raise limit or narrow with filter")
            if (r.screens.isEmpty()) put("note", "the PR changes no screenshot-test images (looked in ${r.patterns.joinToString()}): its screens have no tests, or it doesn't change how they look")
            putJsonArray("screens") {
                r.screens.forEachIndexed { i, s -> addJsonObject {
                    put("n", i + 1); put("name", s.name); put("status", s.status); put("path", s.path)
                    s.resized?.let { put("changed", "the size changed: $it") }
                    s.diff?.let { put("changed", it.toString()) }
                    s.pair?.let { put("pair", it.path) }
                    if (s.pair == null) put("problem", "couldn't download it")
                } }
            }
            links?.let { l -> putJsonArray("markdown") { l.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
        }.encode())
    }
}
