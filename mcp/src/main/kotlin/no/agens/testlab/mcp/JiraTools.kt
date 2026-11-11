package no.agens.testlab.mcp

import no.agens.testlab.AppIdentity

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.agens.testlab.mcp.Param.Companion.PLATFORM
import no.agens.testlab.mcp.Param.Companion.SERIAL
import no.agens.testlab.mcp.Param.Companion.bool
import no.agens.testlab.mcp.Param.Companion.int
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.Param.Companion.strings
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.jira.Candidates
import no.agens.testlab.services.jira.Jira
import no.agens.testlab.services.jira.JiraIssue
import no.agens.testlab.services.jira.Outcome
import no.agens.testlab.services.runtime.LabRuntime
import java.io.File

private val KEY = str("key", "Jira issue key (PROJ-123) or its browse URL")

private fun issueMarkdown(i: JiraIssue, warnings: List<String>): String = buildString {
    appendLine("# ${i.key} · ${i.summary}")
    appendLine()
    appendLine("- Type: ${i.type} · Status: ${i.status}" + (i.priority?.let { " · Priority: $it" } ?: ""))
    appendLine("- Assignee: ${i.assignee?.displayName ?: "unassigned"} · Reporter: ${i.reporter?.displayName ?: "?"}")
    appendLine("- Labels: ${i.labels.joinToString().ifEmpty { "none" }}")
    if (i.components.isNotEmpty()) appendLine("- Components: ${i.components.joinToString()}")
    if (i.fixVersions.isNotEmpty()) appendLine("- Fix versions: ${i.fixVersions.joinToString()}")
    i.parent?.let { appendLine("- Parent: $it") }
    if (i.links.isNotEmpty()) appendLine("- Links: ${i.links.joinToString()}")
    appendLine("- Updated: ${i.updated} · ${i.url}")
    if (warnings.isNotEmpty()) appendLine("- Devtest: ${warnings.joinToString("; ")}")
    appendLine()
    appendLine("## Description")
    appendLine()
    appendLine(i.description.ifBlank { "(empty)" })
    if (i.attachments.isNotEmpty()) {
        appendLine()
        appendLine("## Attachments")
        i.attachments.forEach { appendLine("- ${it.filename} (${it.mimeType}, ${it.size / 1024} KB, ${it.created.take(10)}, ${it.author ?: "?"})") }
    }
    appendLine()
    appendLine("## Comments (${i.comments.size} of ${i.commentTotal}, oldest first)")
    for (c in i.comments) {
        appendLine()
        appendLine("### ${c.author ?: "?"} · ${c.created.take(16).replace('T', ' ')}")
        appendLine()
        appendLine(c.markdown)
    }
}.trimEnd()

private fun candidatesJson(key: String, c: Candidates) = buildJsonObject {
    put("ticket", key)
    putJsonArray("candidates") {
        for (p in c.candidates) addJsonObject {
            if (p.number != null) put("pr", p.number) else put("pr", JsonNull)
            put("title", p.title ?: "")
            put("branch", p.branch ?: "")
            put("state", p.state ?: "")
            put("draft", p.draft)
            put("url", p.url ?: "")
            putJsonArray("found_by") { p.sources.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
    }
    put("single_open_pr", c.single?.number?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
    if (c.problems.isNotEmpty()) putJsonArray("problems") { c.problems.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    put(
        "hint",
        when {
            c.single != null -> "One open PR: install its build with handsets_install_branch_build(\"${c.single!!.number}\")."
            c.candidates.isEmpty() -> "No PR found: ask the user for the PR number or branch."
            else -> "Several candidates: ask the user which one to test; never pick one yourself."
        },
    )
}

/** Files a tool may attach: only from the reports folder, so nothing else on the computer can leave it. */
private fun reportFile(rt: LabRuntime, path: String, reportId: String?): File {
    val root = rt.reportsDir().canonicalFile
    val f = (if (File(path).isAbsolute) File(path) else reportId?.let { File(rt.reports.folder(it), path) } ?: File(root, path)).canonicalFile
    if (!f.path.startsWith(root.path + File.separator)) throw BadArgument("only files inside the reports folder (${root.path}) can be attached: $path")
    if (!f.isFile) throw BadArgument("no such file: $path")
    return f
}

internal fun ToolBox.jiraTools(rt: LabRuntime) {
    tool(
        "jira_get_issue",
        """
        A Jira ticket as Markdown: summary, type, status, assignee, labels, links, the description
        (acceptance criteria), attachments and the newest comments. Also says whether it is cleared
        for a devtest (the profile's queue labels) or already approved.
        """,
        KEY,
        int("comments", "how many of the newest comments to include", 10),
        readOnly = true,
    ) { a ->
        val i = rt.jira.issue(a.str("key"), a.int("comments").coerceIn(0, 100))
        text(issueMarkdown(i, rt.devtest.warnings(i)))
    }

    tool(
        "jira_search",
        """
        Search Jira with JQL (bounded: at most `max` issues per call; pass next_page_token for more).
        Returns JSON {issues[{key, summary, type, status, assignee, labels, updated, url}], next_page_token}.
        The devtest queue is jira_devtest_queue.
        """,
        str("jql", "JQL, e.g. project = PROJ AND text ~ \"checkout\" ORDER BY updated DESC"),
        int("max", "issues per page (1–100)", 20),
        str("next_page_token", "from the previous page", ""),
        readOnly = true,
    ) { a ->
        val page = rt.jira.search(a.str("jql"), a.int("max"), a.strOrNull("next_page_token"))
        text(buildJsonObject {
            putJsonArray("issues") {
                for (i in page.issues) addJsonObject {
                    put("key", i.key); put("summary", i.summary); put("type", i.type); put("status", i.status)
                    put("assignee", i.assignee?.displayName ?: ""); putJsonArray("labels") { i.labels.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                    put("updated", i.updated); put("url", i.url)
                }
            }
            put("next_page_token", page.nextPageToken?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
        }.encode())
    }

    tool(
        "jira_devtest_queue",
        """
        Tickets waiting for a devtest: carrying one of the profile's queue labels, not its approved label,
        not done. With an Android and an iOS profile active, both projects' tickets, each with the
        platform to test it on, and `testing_on` (a device's serial) when a devtest has it.
        JSON {jql, issues[{key, summary, status, assignee, mine, platform, testing_on, warnings}]}.
        """,
        readOnly = true,
    ) {
        val q = rt.devtest.queue()
        val testing = rt.devtest.state().values.filter { it.testing() }.associate { it.key to it.serial!! }
        text(buildJsonObject {
            put("jql", rt.devtest.queueJql())
            putJsonArray("issues") {
                for (item in q) addJsonObject {
                    put("key", item.issue.key); put("summary", item.issue.summary); put("status", item.issue.status)
                    put("assignee", item.issue.assignee?.displayName ?: ""); put("mine", item.mine)
                    item.platform?.let { put("platform", it.id) }
                    testing[item.issue.key]?.let { put("testing_on", it) }
                    putJsonArray("warnings") { item.warnings.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                }
            }
        }.encode())
    }

    tool(
        "jira_devtest_next",
        """
        The next queue ticket for this device: the first one for its platform (an Android phone takes
        the Android project's, an iOS Simulator the iOS one's) that no other device is testing, yours
        first. It is marked as this device's, so devtests can run side by side, one per device, each
        taking the next. Nothing is written to Jira. JSON {key, summary, platform, serial, url} or
        {none: reason}.
        """,
        SERIAL,
    ) { a ->
        val serial = a.serial ?: rt.active.value ?: rt.pickDefaultSerial()
        val item = rt.devtest.next(serial)
        text(buildJsonObject {
            if (item == null) put("none", "no ticket left in the queue for ${rt.platformOf(serial).label} that another device isn't testing")
            else { put("key", item.issue.key); put("summary", item.issue.summary); item.platform?.let { put("platform", it.id) }; put("serial", serial); put("url", item.issue.url) }
        }.encode())
    }

    tool(
        "jira_linked_prs",
        """
        Pull requests that may belong to a ticket: from Jira's development panel, PR titles that name
        the key, and branches like feature/PROJ-123-…. JSON {candidates[{pr, title, branch, state,
        draft, url, found_by}], single_open_pr, hint}. With several candidates ASK THE USER which to
        test; never guess.
        """,
        KEY,
        readOnly = true,
    ) { a ->
        val k = Jira.checkKey(a.str("key"))
        text(candidatesJson(k, rt.devtest.candidates(k)).encode())
    }

    tool(
        "jira_add_comment",
        """
        Post a comment (Markdown, converted to Jira's format). The user approves the exact text in
        ${AppIdentity.NAME} first. For the devtest report use jira_post_devtest_report, which fills in the
        build and keeps the team's format.
        """,
        KEY,
        str("markdown", "the comment"),
        destructive = true,
    ) { a ->
        val k = Jira.checkKey(a.str("key"))
        val md = rt.redactor.apply(a.str("markdown"))
        rt.confirmWrite("Comment on $k", md)
        val id = rt.jira.addComment(k, md)
        text("Comment posted on $k: ${rt.jira.browseUrl(k)}?focusedCommentId=$id")
    }

    tool(
        "jira_attach",
        """
        Attach report files to a ticket after the user approves. Pass report_id to attach that
        report's report-standalone.html and session video, or `files` (paths inside the reports
        folder, relative to the report). Files elsewhere on the computer are refused.
        """,
        KEY,
        str("report_id", "a written report (from lab_report_start)", ""),
        strings("files", "file paths inside the report folder, e.g. 03-kupong.jpg"),
        bool("screenshots", "with report_id and no files: also attach the screenshots", false),
        destructive = true,
    ) { a ->
        val k = Jira.checkKey(a.str("key"))
        val report = a.strOrNull("report_id")
        val files = a.strings("files").map { reportFile(rt, it, report) }.ifEmpty {
            report?.let { rt.devtest.reportFiles(it, a.bool("screenshots")) } ?: throw BadArgument("pass report_id or files")
        }
        if (files.isEmpty()) throw BadArgument("report $report has nothing to attach yet: call lab_report_write first")
        rt.confirmWrite("Attach ${files.size} file(s) to $k", files.joinToString("\n") { "${it.name} (${it.length() / 1024} KB)" })
        val atts = rt.jira.attach(k, files)
        text("Attached to $k: " + atts.joinToString { it.filename })
    }

    tool(
        "jira_update_labels",
        "Add and/or remove labels (never replaces the set). The user approves first.",
        KEY,
        strings("add", "labels to add"),
        strings("remove", "labels to remove"),
        destructive = true,
    ) { a ->
        val k = Jira.checkKey(a.str("key"))
        val add = a.strings("add"); val remove = a.strings("remove")
        if (add.isEmpty() && remove.isEmpty()) throw BadArgument("nothing to change: pass add or remove")
        rt.confirmWrite("Change the labels of $k", listOfNotNull(add.takeIf { it.isNotEmpty() }?.let { "add: ${it.joinToString()}" }, remove.takeIf { it.isNotEmpty() }?.let { "remove: ${it.joinToString()}" }).joinToString("\n"))
        rt.jira.updateLabels(k, add, remove)
        text("Labels of $k: +${add.joinToString().ifEmpty { "–" }} −${remove.joinToString().ifEmpty { "–" }}")
    }

    tool(
        "jira_assign",
        """
        Assign a ticket: "me", an accountId, or "none" to unassign. Taking a ticket someone else has
        needs force=true (and the user's approval); say who has it before asking.
        """,
        KEY,
        str("assignee", "me | <accountId> | none", "me"),
        bool("force", "take it even when someone else is assigned", false),
        destructive = true,
    ) { a ->
        val k = Jira.checkKey(a.str("key"))
        when (val who = a.str("assignee").trim()) {
            "me", "" -> when (val r = rt.devtest.take(k, a.bool("force"))) {
                is no.agens.testlab.services.jira.Devtest.TakeResult.Taken -> text(if (r.alreadyMine) "$k is already yours" else "$k is now assigned to you")
                is no.agens.testlab.services.jira.Devtest.TakeResult.SomeoneElse -> text("$k is assigned to ${r.assignee.displayName}: not changed. Ask the user, then call again with force=true if they want to take it.")
            }
            else -> {
                val id = who.takeUnless { it == "none" }
                val current = rt.jira.issue(k, 0).assignee
                rt.confirmWrite("Assign $k", "Set the assignee of $k to ${id ?: "nobody"} (now: ${current?.displayName ?: "unassigned"}).")
                rt.jira.assign(k, id)
                text(if (id == null) "$k is now unassigned" else "$k is now assigned to $id")
            }
        }
    }

    tool(
        "jira_post_devtest_report",
        """
        Post the devtest result on the ticket in the profile's comment format (its template: date,
        tester (you), the build ${AppIdentity.NAME} installed on the device, branch, environment, tested,
        not tested, result, and the approval footer). Attaches the report (report_id) and applies the
        profile's outcome: approved → its labels; changes → back to whoever had the ticket before.
        With preview=true nothing is written: it returns the exact comment for the user to read. The
        user approves the whole payload in ${AppIdentity.NAME} before anything is posted. Write the
        items in the profile's report language (lab_status), each with ✅/❌/⚠️ and what was seen;
        never claim a result that wasn't observed.
        """,
        KEY,
        strings("tested", "what was tested, one item each, e.g. «✅ The cart shows the item count (screenshot 03)»", required = true),
        strings("not_tested", "what was left out and why"),
        str("result", "the conclusion in one or two sentences (approved / not approved: …)"),
        str("env", "the environment tested against; empty = the profile's first", ""),
        str("outcome", "approved | changes | none", "none", listOf("approved", "changes", "none")),
        str("report_id", "the written report to attach (lab_report_write first)", ""),
        bool("screenshots", "also attach each screenshot", false),
        bool("preview", "only show the comment; post nothing", false),
        SERIAL,
    ) { a ->
        val k = Jira.checkKey(a.str("key"))
        val report = rt.devtest.compose(k, a.strings("tested"), a.strings("not_tested"), a.str("result"), a.strOrNull("env") ?: rt.profile.devtest.environments.firstOrNull().orEmpty(), a.serial)
        val reportId = a.strOrNull("report_id")
        val withReport = reportId?.let { report.copy(report = it) } ?: report
        val files = reportId?.let { rt.devtest.reportFiles(it, a.bool("screenshots")) }.orEmpty()
        val outcome = Outcome.parse(a.str("outcome"))
        if (a.bool("preview")) {
            return@tool text(buildString {
                appendLine("PREVIEW (nothing posted):")
                appendLine()
                appendLine(withReport.markdown())
                appendLine()
                appendLine("Attachments: " + files.joinToString { it.name }.ifEmpty { "none" })
                appendLine("Outcome: " + rt.devtest.outcomeActions(k, outcome).joinToString("; ").ifEmpty { "comment only" })
            }.trimEnd())
        }
        val p = rt.devtest.post(k, withReport, files, outcome)
        text("Posted on $k: ${p.url}" + (if (p.attachments.isNotEmpty()) "\nAttached: ${p.attachments.joinToString { it.filename }}" else "") + (if (p.actions.isNotEmpty()) "\nThen: ${p.actions.joinToString("; ")}" else ""))
    }

    // --- GitHub (read-only, the repo) ---

    tool(
        "github_find_prs",
        """
        Find the repo pull requests. A Jira key (PROJ-123) gives the same candidates as
        jira_linked_prs; anything else is a GitHub search within the repo's PRs (e.g. "kupong
        is:open", "author:someone"). JSON list.
        """,
        str("query", "a Jira key or GitHub search terms"),
        PLATFORM,
        readOnly = true,
    ) { a ->
        val q = a.str("query")
        val key = Jira.parseKey(q)?.takeIf { q.trim().length <= it.length + 1 || q.contains("/browse/") }
        if (key != null) return@tool text(candidatesJson(key, rt.devtest.candidates(key)).encode())
        val hits = rt.builds.github().searchPulls(q)
        text(buildJsonObject {
            putJsonArray("prs") { for (h in hits) addJsonObject { put("pr", h.number); put("title", h.title); put("state", h.state); put("draft", h.draft); put("author", h.author); put("updated", h.updatedAt); put("url", h.htmlUrl) } }
        }.encode())
    }

    tool(
        "github_get_pr",
        "An the repo PR: title, description, author, state, branches, size, labels and reviewers.",
        int("number", "PR number"),
        PLATFORM,
        readOnly = true,
    ) { a ->
        val p = rt.builds.github().pullDetail(a.int("number"))
        text(buildString {
            appendLine("# PR #${p.number} · ${p.title}")
            appendLine()
            appendLine("- ${if (p.merged) "merged" else p.state}${if (p.draft) " (draft)" else ""} · by ${p.author} · ${p.headRef} → ${p.baseRef} @ ${p.headSha.take(10)}")
            appendLine("- ${p.changedFiles} files, +${p.additions} −${p.deletions} · updated ${p.updatedAt}")
            if (p.labels.isNotEmpty()) appendLine("- Labels: ${p.labels.joinToString()}")
            if (p.reviewers.isNotEmpty()) appendLine("- Requested reviewers: ${p.reviewers.joinToString()}")
            appendLine("- ${p.htmlUrl}")
            appendLine()
            appendLine(p.body.ifBlank { "(no description)" })
        }.trimEnd())
    }

    tool(
        "github_pr_files",
        "The files a PR changes: path, status, +/− lines. The diff itself is github_pr_diff.",
        int("number", "PR number"),
        PLATFORM,
        readOnly = true,
    ) { a ->
        val files = rt.builds.github().pullFiles(a.int("number"))
        text(buildJsonObject {
            put("count", files.size)
            putJsonArray("files") { for (f in files) addJsonObject { put("path", f.path); put("status", f.status); put("additions", f.additions); put("deletions", f.deletions); f.previousPath?.let { put("previous_path", it) } } }
        }.encode())
    }

    tool(
        "github_pr_diff",
        """
        The unified diff of a PR, optionally only the files whose path contains `path`. Long diffs
        are cut at max_chars (the result says so); narrow with path.
        """,
        int("number", "PR number"),
        str("path", "only files whose path contains this", ""),
        int("max_chars", "cut the diff here", 60_000),
        PLATFORM,
        readOnly = true,
    ) { a ->
        val diff = rt.builds.github().pullDiff(a.int("number"))
        val filter = a.strOrNull("path")
        val parts = if (filter == null) diff else diff.split(Regex("(?m)^(?=diff --git )")).filter { part -> part.lineSequence().firstOrNull()?.contains(filter) == true }.joinToString("")
        val max = a.int("max_chars").coerceIn(1000, 500_000)
        text(if (parts.isEmpty()) "(no changes${filter?.let { " in files matching '$it'" } ?: ""})" else if (parts.length <= max) parts else parts.take(max) + "\n… cut at $max of ${parts.length} chars: pass path to narrow")
    }

    tool(
        "github_read_file",
        "A file from the repo at a branch, tag or commit (default branch when ref is empty). Long files are cut at max_chars.",
        str("path", "repository path, e.g. app/src/main/AndroidManifest.xml"),
        str("ref", "branch, tag or sha", ""),
        int("max_chars", "cut the file here", 100_000),
        PLATFORM,
        readOnly = true,
    ) { a ->
        val body = rt.builds.github().readFile(a.str("path"), a.strOrNull("ref"))
        val max = a.int("max_chars").coerceIn(1000, 1_000_000)
        text(if (body.length <= max) body else body.take(max) + "\n… cut at $max of ${body.length} chars")
    }

    tool(
        "github_list_runs",
        "Recent GitHub Actions runs of the repo, optionally of one workflow file and/or branch: status, conclusion, commit, link.",
        str("workflow", "workflow file, e.g. build_feature.yml (empty = all)", ""),
        str("branch", "branch name (empty = all)", ""),
        int("limit", "how many", 10),
        PLATFORM,
        readOnly = true,
    ) { a ->
        val runs = rt.builds.github().listRuns(a.strOrNull("workflow"), a.strOrNull("branch"), a.int("limit").coerceIn(1, 50))
        text(buildJsonObject {
            putJsonArray("runs") {
                for (r in runs) addJsonObject {
                    put("id", r.id); put("workflow", r.name); put("branch", r.headBranch); put("sha", r.headSha.take(10)); put("event", r.event)
                    put("status", r.status); put("conclusion", r.conclusion ?: ""); put("created", r.createdAt); put("url", r.htmlUrl)
                }
            }
        }.encode())
    }
}
