package no.agens.testlab.services.jira

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.agens.testlab.AppIdentity
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.core.profile.Template
import no.agens.testlab.services.builds.InstalledBuild
import no.agens.testlab.services.github.GitHubError
import no.agens.testlab.services.i18n.tr
import no.agens.testlab.services.runtime.LabRuntime
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A ticket in the devtest queue, with what is off about it (no clearance label, already approved…). */
/** [platform] is that of the profile whose project the ticket is in (several are active: Android and iOS). */
data class QueueItem(val issue: JiraIssue, val warnings: List<String>, val mine: Boolean, val platform: no.agens.testlab.core.Platform? = null)

/** A PR that may belong to a ticket, and why we think so. Shown to the user, never picked silently. */
data class PrCandidate(
    val number: Int?,
    val title: String?,
    val branch: String?,
    val state: String?,
    val draft: Boolean,
    val url: String?,
    val updatedAt: String?,
    val sources: List<String>,
)

data class Candidates(val candidates: List<PrCandidate>, val problems: List<String>) {
    /** The one open PR, when there is exactly one; otherwise the user chooses. */
    val single: PrCandidate? get() = candidates.filter { it.number != null && it.state == "open" }.singleOrNull()
}

enum class Outcome(val code: String) {
    APPROVED("approved"), CHANGES("changes"), NONE("none");
    companion object { fun parse(s: String?): Outcome = entries.firstOrNull { it.code == s?.trim()?.lowercase() } ?: NONE }
}

/** The devtest comment's values; [markdown] fills the profile's template (Profile.Report). */
data class DevtestReport(
    val tested: List<String>,
    val notTested: List<String>,
    val result: String,
    val env: String,
    val testedBy: String,
    val build: String?,
    val branch: String?,
    val approvedBy: String,
    val date: LocalDate = LocalDate.now(),
    val report: String? = null,
    val format: Profile.Report = Profile.Report(),
    /** The device it was tested on («iPhone 17 (simulator), iOS 26.5»), for {{device}}. */
    val device: String? = null,
) {
    fun markdown(): String = Template.render(
        format.template,
        mapOf(
            "date" to date.format(DateTimeFormatter.ofPattern(format.dateFormat)),
            "tester" to testedBy, "build" to (build ?: format.unknown), "branch" to (branch?.let { "`$it`" } ?: format.unknown),
            "env" to env, "result" to result.trim(), "report" to report, "approver" to approvedBy, "device" to device,
        ),
        mapOf(
            "tested" to tested.map { it.trim().removePrefix("- ") }.filter { it.isNotEmpty() },
            "notTested" to notTested.map { it.trim().removePrefix("- ") }.filter { it.isNotEmpty() },
        ),
    ).trim()
}

/** What the app remembers about a devtest in progress (devtests.json). */
@Serializable
data class DevtestState(
    val key: String,
    /** The assignee before we took the ticket: it goes back to them when changes are needed. */
    val previousAssignee: String? = null,
    val previousAssigneeName: String? = null,
    val pr: Int? = null,
    val branch: String? = null,
    val reportId: String? = null,
    val serial: String? = null,
    val startedAt: String = LocalDateTime.now().withNano(0).toString(),
    /** When its report was posted: the device is free for the next ticket. */
    val postedAt: String? = null,
) {
    /** A device is on it: claimed or chosen in the last 12 hours, with no report posted yet. */
    fun testing(now: LocalDateTime = LocalDateTime.now()): Boolean =
        serial != null && postedAt == null && runCatching { LocalDateTime.parse(startedAt) }.getOrNull()?.isAfter(now.minusHours(12)) == true
}

/**
 * The devtest flow on top of [Jira] and GitHub: the queue of cleared tickets, taking a ticket,
 * finding its PR, and the report comment with attachments and the outcome's label or assignee.
 * Every Jira write goes through [LabRuntime.confirmWrite], which shows the exact payload.
 */
class Devtest(private val rt: LabRuntime) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val stateFile get() = File(rt.dirs.data, "devtests.json")

    private val jira get() = rt.jira
    private val cfg get() = rt.profile.tracker

    /**
     * The active profile a ticket belongs to, by its project key (ANDR-… to the Android profile,
     * IOS-… to the iOS one); the profile in focus when none lists the project.
     */
    fun profileFor(key: String): Profile {
        val project = key.substringBefore('-').uppercase()
        return queueProfiles().firstOrNull { p -> p.tracker.projects.any { it.equals(project, ignoreCase = true) } } ?: rt.profile
    }

    /**
     * The active profiles whose tickets the queue lists: on the same Jira site as the one in focus,
     * the focused one first. A profile for both platforms counts once per platform.
     */
    private fun queueProfiles(): List<Profile> {
        val site = rt.profile.tracker.site
        return (listOf(rt.profile) + rt.profiles.activeAll().values.map { it.profile })
            .filter { it.id !in Profile.GENERIC_IDS && it.tracker.site == site }.distinctBy { it.id to it.platform }
            .ifEmpty { listOf(rt.profile) }
    }

    // --- queue ---

    /** The queue's JQL: each active profile's, joined with OR when there are several. */
    fun queueJql(): String {
        val each = queueProfiles().map { queueJql(it.tracker) }.distinct()
        if (each.size == 1) return each[0]
        return each.joinToString(" OR ") { "(" + it.substringBefore(" ORDER BY") + ")" } + " ORDER BY updated DESC"
    }

    private fun queueJql(c: Profile.Tracker): String {
        val labels = c.queueLabels.joinToString(",") { "\"$it\"" }
        val required = c.requiredLabels.joinToString(",") { "\"$it\"" }
        val projects = c.projects.joinToString(",")
        c.queueJql?.let { return it.replace("{projects}", projects).replace("{labels}", labels).replace("{required}", required).replace("{approved}", "\"${c.approvedLabel}\"") }
        val scope = when (c.projects.size) { 0 -> ""; 1 -> "project = $projects AND "; else -> "project in ($projects) AND " }
        val alsoRequired = if (c.requiredLabels.isEmpty()) "" else "labels in ($required) AND "
        return "${scope}labels in ($labels) AND ${alsoRequired}labels not in (\"${c.approvedLabel}\") AND statusCategory != Done ORDER BY updated DESC"
    }

    suspend fun queue(max: Int = 50): List<QueueItem> {
        val me = runCatching { jira.myself() }.getOrNull()
        return jira.search(queueJql(), max).issues.map {
            val p = profileFor(it.key)
            QueueItem(it, warnings(it), me != null && it.assignee?.accountId == me.accountId, p.platform)
        }
    }

    fun warnings(i: JiraIssue): List<String> = buildList {
        val c = profileFor(i.key).tracker
        if (i.labels.none { l -> c.queueLabels.any { it.equals(l, ignoreCase = true) } }) add(tr("Not cleared for devtest: no {0} label", c.queueLabels.joinToString("/")))
        if (c.requiredLabels.isNotEmpty() && i.labels.none { l -> c.requiredLabels.any { it.equals(l, ignoreCase = true) } }) add(tr("Not ready for devtest: needs one of {0}", c.requiredLabels.joinToString(", ")))
        if (i.labels.any { it.equals(c.approvedLabel, ignoreCase = true) }) add(tr("Already approved ({0})", c.approvedLabel))
        if (i.statusCategory == "done") add(tr("The ticket is {0}", i.status))
    }

    private val claims = kotlinx.coroutines.sync.Mutex()

    /**
     * The next ticket for [serial]: the first in the queue for that device's platform that no other
     * device is testing, the user's own first. It is remembered as this device's, so a devtest
     * running beside it on another device gets the one after. Null when there is none.
     */
    suspend fun next(serial: String): QueueItem? = claims.withLock {
        val platform = rt.platformOf(serial)
        val busy = state().values.filter { it.serial != serial && it.testing() }.map { it.key }.toSet()
        val item = queue().filter { it.platform == platform && it.warnings.isEmpty() && it.issue.key !in busy }
            .sortedByDescending { it.mine }.firstOrNull() ?: return@withLock null
        remember(item.issue.key) { it.copy(serial = serial, postedAt = null, startedAt = LocalDateTime.now().withNano(0).toString()) }
        item
    }

    // --- take ---

    sealed interface TakeResult {
        data class Taken(val issue: JiraIssue, val alreadyMine: Boolean) : TakeResult
        data class SomeoneElse(val issue: JiraIssue, val assignee: JiraUser) : TakeResult
    }

    /** Assigns the ticket to you. Refuses when someone else has it unless [force]. */
    suspend fun take(key: String, force: Boolean = false): TakeResult {
        val issue = jira.issue(key, maxComments = 0)
        val me = jira.myself()
        val current = issue.assignee
        if (current?.accountId == me.accountId) { remember(issue.key) { it }; return TakeResult.Taken(issue, alreadyMine = true) }
        if (current != null && !force) return TakeResult.SomeoneElse(issue, current)
        rt.confirmWrite("Assign ${issue.key} to you", "Set the assignee of ${issue.key} «${issue.summary}» to ${me.displayName}" + (current?.let { " (now: ${it.displayName})" } ?: " (now unassigned)") + ".")
        jira.assign(issue.key, me.accountId)
        remember(issue.key) { it.copy(previousAssignee = current?.accountId, previousAssigneeName = current?.displayName) }
        return TakeResult.Taken(issue.copy(assignee = me), alreadyMine = false)
    }

    // --- PRs ---

    suspend fun candidates(key: String): Candidates = coroutineScope {
        val k = Jira.checkKey(key)
        val problems = java.util.Collections.synchronizedList(mutableListOf<String>())
        val issue = runCatching { jira.issue(k, maxComments = 0) }.onFailure { problems += it.message.orEmpty() }.getOrNull()
        val dev = async { issue?.let { jira.devLinks(it.id) }.orEmpty() }
        val owner = profileFor(k)
        val gh = runCatching { rt.builds.github(owner) }.getOrNull()
        val titled = async {
            try { gh?.searchPulls("\"$k\" in:title").orEmpty() } catch (e: GitHubError) { problems += "GitHub: ${e.message}"; emptyList() }
        }
        val branches = async {
            try {
                branchPrefixes(owner).map { p -> async { gh?.branchesWithPrefix("$p$k").orEmpty() } }.awaitAll().flatten().distinct()
                    .filter { b -> b.substringAfter(k, "").firstOrNull()?.isDigit() != true }
            } catch (e: GitHubError) { problems += "GitHub: ${e.message}"; emptyList() }
        }
        val byKey = LinkedHashMap<String, PrCandidate>()
        fun add(c: PrCandidate) {
            val id = c.number?.let { "#$it" } ?: "b:${c.branch}"
            val old = byKey[id] ?: c.branch?.let { b -> byKey.entries.firstOrNull { it.value.branch == b }?.also { byKey.remove(it.key) }?.value }
            byKey[id] = if (old == null) c else PrCandidate(
                c.number ?: old.number, c.title ?: old.title, c.branch ?: old.branch, c.state ?: old.state, c.draft || old.draft,
                c.url ?: old.url, c.updatedAt ?: old.updatedAt, (old.sources + c.sources).distinct(),
            )
        }
        for (d in dev.await()) {
            if (d.kind == "pr") add(PrCandidate(d.number, d.name, d.branch, d.status?.lowercase()?.let { if (it == "declined") "closed" else it }, false, d.url, null, listOf("Jira development panel")))
            else add(PrCandidate(null, null, d.branch, null, false, d.url, null, listOf("Jira development panel")))
        }
        for (h in titled.await()) add(PrCandidate(h.number, h.title, null, h.state, h.draft, h.htmlUrl, h.updatedAt, listOf("PR title mentions $k")))
        for (b in branches.await()) {
            val prs = try { gh?.pullsForBranch(b).orEmpty() } catch (e: GitHubError) { problems += "GitHub: ${e.message}"; emptyList() }
            if (prs.isEmpty()) add(PrCandidate(null, null, b, null, false, null, null, listOf("branch name")))
            for (h in prs) add(PrCandidate(h.number, h.title, b, h.state, h.draft, h.htmlUrl, h.updatedAt, listOf("branch name")))
        }
        // PRs found by number only need their branch (the build is looked up by it).
        val list = byKey.values.map { c ->
            if (c.number != null && c.branch == null && gh != null) runCatching { gh.pull(c.number) }.getOrNull()?.let { p -> c.copy(branch = p.headRef, state = c.state ?: p.state, draft = p.draft) } ?: c else c
        }
        Candidates(
            list.sortedWith(compareBy<PrCandidate>({ rank(it) }, { -it.sources.size }).thenByDescending { it.updatedAt.orEmpty() }),
            problems.distinct(),
        )
    }

    private fun rank(c: PrCandidate) = when {
        c.state == "open" && !c.draft -> 0
        c.state == "open" -> 1
        c.number == null -> 2
        else -> 3
    }

    /** Remembers the chosen PR (and the report) for this ticket. */
    fun choose(key: String, pr: Int?, branch: String?, serial: String?) = remember(Jira.checkKey(key)) { it.copy(pr = pr ?: it.pr, branch = branch ?: it.branch, serial = serial ?: it.serial) }
    fun linkReport(key: String, reportId: String) = remember(Jira.checkKey(key)) { it.copy(reportId = reportId) }

    // --- report ---

    /** The build line from what the app installed on [serial] (the newest build of the default package). */
    fun installed(serial: String?): InstalledBuild? {
        val all = serial?.let { rt.builds.installed.on(it) } ?: rt.builds.installed.all()
        val pkg = rt.defaultPackage(serial)
        return all.filter { it.packageName == pkg }.maxByOrNull { it.installedAt } ?: all.maxByOrNull { it.installedAt }
    }

    fun buildLine(b: InstalledBuild?, p: Profile = rt.profile): String? = b?.let {
        val f = p.report
        val source = when {
            it.tag != null -> "${f.releaseWord} ${it.tag}"
            it.headSha != null -> "${f.ciBuildWord} @ ${it.headSha.take(8)}"
            else -> it.source
        }
        f.buildLine.replace("{version}", it.versionName ?: "?").replace("{code}", it.versionCode?.toString() ?: "?")
            .replace("{package}", it.packageName).replace("{source}", source).replace(" (?)", "")
    }

    /** «OnePlus Nord, Android 12» / «iPhone 17 (simulator), iOS 26.5», from the device itself; null when it can't be reached. */
    suspend fun deviceLine(serial: String): String? = runCatching {
        val st = rt.device(serial).deviceState()
        fun s(k: String) = (st[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        val sim = rt.listDevices().firstOrNull { it.serial == serial }?.isEmulator == true
        val name = listOfNotNull(s("manufacturer")?.takeIf { it != "Apple" && s("model")?.startsWith(it, ignoreCase = true) != true }, s("model")).joinToString(" ")
        val kind = if (sim) (if (rt.platformOf(serial) == no.agens.testlab.core.Platform.IOS) " (simulator)" else " (emulator)") else ""
        listOfNotNull(name.ifEmpty { null }?.plus(kind), s("release")?.let { "${s("os_name") ?: "Android"} $it" }).joinToString(", ").ifEmpty { null }
    }.getOrNull()

    suspend fun compose(key: String, tested: List<String>, notTested: List<String>, result: String, env: String, serial: String? = null): DevtestReport {
        val k = Jira.checkKey(key)
        val me = jira.myself()
        val st = state()[k]
        val b = installed(serial ?: st?.serial)
        val p = profileFor(k)
        return DevtestReport(tested, notTested, result, env, me.displayName, buildLine(b, p), st?.branch ?: b?.branch, me.displayName, report = st?.reportId, format = p.report,
            device = (serial ?: st?.serial)?.let { deviceLine(it) })
    }

    /** What [post] will do, in words, for the approval dialog and the MCP preview. */
    fun outcomeActions(key: String, outcome: Outcome): List<String> {
        val st = state()[key]
        val c = profileFor(key).tracker
        val o = when (outcome) { Outcome.APPROVED -> c.onApproved; Outcome.CHANGES -> c.onChanges; Outcome.NONE -> return emptyList() }
        return buildList {
            o.addLabels.forEach { add("add label $it") }
            o.removeLabels.forEach { add("remove label $it") }
            if (o.handBack) add(st?.previousAssignee?.let { "assign back to ${st.previousAssigneeName ?: it}" } ?: "leave the assignee (nobody had it before you)")
        }
    }

    data class Posted(val commentId: String, val attachments: List<JiraAttachment>, val actions: List<String>, val url: String)

    /** Posts the report comment, the attachments and the outcome, after one approval of the whole payload. */
    suspend fun post(key: String, report: DevtestReport, attachments: List<File>, outcome: Outcome): Posted {
        val k = Jira.checkKey(key)
        val markdown = rt.redactor.apply(report.markdown())
        val actions = outcomeActions(k, outcome)
        rt.confirmWrite(
            "Post the devtest report on $k",
            buildString {
                appendLine(markdown)
                if (attachments.isNotEmpty()) { appendLine(); appendLine("Attachments: " + attachments.joinToString { "${it.name} (${it.length() / 1024} KB)" }) }
                if (actions.isNotEmpty()) { appendLine(); appendLine("Then: " + actions.joinToString("; ")) }
            },
        )
        val atts = if (attachments.isNotEmpty()) jira.attach(k, attachments) else emptyList()
        val id = jira.addComment(k, markdown)
        remember(k) { it.copy(postedAt = LocalDateTime.now().withNano(0).toString()) }
        val c = profileFor(k).tracker
        val o = when (outcome) { Outcome.APPROVED -> c.onApproved; Outcome.CHANGES -> c.onChanges; Outcome.NONE -> null }
        if (o != null) {
            if (o.addLabels.isNotEmpty() || o.removeLabels.isNotEmpty()) jira.updateLabels(k, o.addLabels, o.removeLabels)
            if (o.handBack) state()[k]?.previousAssignee?.let { jira.assign(k, it) }
        }
        return Posted(id, atts, actions, jira.browseUrl(k) + "?focusedCommentId=$id")
    }

    /** The report files worth attaching: the standalone page, the session video(s) and screenshots. */
    fun reportFiles(reportId: String, screenshots: Boolean = false): List<File> {
        val d = rt.reports.folder(reportId)
        val page = File(d, "report-standalone.html")
        val videos = d.listFiles().orEmpty().filter { it.name.endsWith(".mp4") && !it.name.startsWith("session_annotated") }.sortedBy { it.name }
        val shots = if (screenshots) d.listFiles().orEmpty().filter { it.name.matches(Regex("\\d\\d-.*\\.(jpe?g|png)")) }.sortedBy { it.name } else emptyList()
        return listOfNotNull(page.takeIf { it.isFile }) + videos + listOfNotNull(File(d, "flows.har").takeIf { it.isFile }) + shots
    }

    // --- state ---

    fun state(): Map<String, DevtestState> = try {
        if (stateFile.isFile) json.decodeFromString<List<DevtestState>>(stateFile.readText()).associateBy { it.key } else emptyMap()
    } catch (e: Exception) { emptyMap() }

    @Synchronized
    private fun remember(key: String, change: (DevtestState) -> DevtestState) {
        val all = state().toMutableMap()
        all[key] = change(all[key] ?: DevtestState(key))
        stateFile.parentFile?.mkdirs()
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        tmp.writeText(json.encodeToString(all.values.sortedByDescending { it.startedAt }.take(200)))
        if (!tmp.renameTo(stateFile)) { stateFile.writeText(tmp.readText()); tmp.delete() }
    }

    /** Branch prefixes searched for `{prefix}{KEY}…`: the profile's, else the build types' auto prefixes; plus bare `{KEY}…`. */
    fun branchPrefixes(p: Profile = rt.profile): List<String> {
        val prefixes = p.linking.branchPrefixes.ifEmpty { p.builds.flatMap { it.autoPrefixes }.distinct() }
        return prefixes + if (p.linking.bareKeyBranches) listOf("") else emptyList()
    }

    /** Whether [workDir] has the team's own devtest command (the profile names it and how to detect it). */
    fun teamCommandIn(workDir: File?): Boolean {
        val d = rt.profile.devtest
        if (d.teamCommand == null || workDir == null || !workDir.isDirectory) return false
        return d.teamCommandPaths.isEmpty() || d.teamCommandPaths.any { File(workDir, it).exists() }
    }

    /** The Claude Code prompt: the team's own command in a checkout that has it, this app's devtest prompt otherwise. */
    fun prompt(key: String, summary: String?, inCheckout: Boolean): String {
        val team = rt.profile.devtest.teamCommand
        return if (inCheckout && team != null) team.replace("{key}", key) + (summary?.let { " — $it" } ?: "") else "/mcp__${AppIdentity.SLUG}__devtest $key"
    }
}
