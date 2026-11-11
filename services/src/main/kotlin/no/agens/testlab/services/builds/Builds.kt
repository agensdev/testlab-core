package no.agens.testlab.services.builds

import no.agens.testlab.core.Platform
import no.agens.testlab.core.profile.Profile

import no.agens.testlab.AppIdentity

import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.agens.testlab.core.apk.ManifestInfo
import no.agens.testlab.services.github.Artifact
import no.agens.testlab.services.github.GitHub
import no.agens.testlab.services.github.GitHubError
import no.agens.testlab.services.github.PullRequest
import no.agens.testlab.services.github.Release
import no.agens.testlab.services.github.ReleaseAsset
import no.agens.testlab.services.github.WorkflowRun
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.SecretStore
import java.io.File
import java.time.Instant

/** The newest successful build of one type for a branch (or a PR's branch). */
data class BranchBuild(
    val type: BuildType,
    val branch: String,
    val pr: PullRequest?,
    val run: WorkflowRun,
    val artifact: Artifact?,
    /** A run for a newer commit that is still going, if any. */
    val building: WorkflowRun?,
) {
    /** Whether the build is from the branch's newest commit (only known for PRs). */
    val isLatest: Boolean get() = pr == null || pr.headSha == run.headSha
}

class NoBuild(message: String) : RuntimeException(message)

/** One row of the Builds screen: the newest build of a type on a branch. */
@Serializable
data class CatalogEntry(
    val type: String,
    val branch: String,
    val headSha: String,
    val runId: Long,
    val artifactId: Long,
    val name: String,
    val bytes: Long,
    val createdAt: String,
    val expiresAt: String?,
)

@Serializable
private data class CatalogCache(val newestId: Long, val entries: List<CatalogEntry>, val repo: String = "")

/** An install as the UI follows it. */
data class BuildJob(
    val key: String,
    val title: String,
    val serial: String?,
    val phase: Phase,
    val done: Long = 0,
    val total: Long? = null,
    val message: String? = null,
    val problem: InstallProblem? = null,
) {
    enum class Phase { DOWNLOADING, INSTALLING, DONE, FAILED }
}

/** A PR's control build: the merge-base commit, a CI build of it when one exists, and the newest release's relation to it. */
data class Control(
    val pr: PullRequest, val baseRef: String, val mergeBase: String, val behindBy: Int,
    val run: WorkflowRun?, val artifact: Artifact?, val releaseTag: String?,
    /** The PR's files that changed between [releaseTag] and the merge base (null: unknown). Empty: the control is what users have for these files. */
    val changedSinceRelease: List<String>?,
)

data class InstallOutcome(
    val apk: File, val info: ManifestInfo, val result: InstallResult, val build: BranchBuild? = null, val release: Release? = null,
    /** Set when a newer copy was uninstalled first (an older build over newer data hangs apps). */
    val downgrade: String? = null,
)

/**
 * GitHub builds of the repo: a branch or PR number finds the newest successful run
 * and its APK artifact; the catalog pages through the repo's artifacts newest first until the
 * first expired *build* (test reports expire after a day, builds after 30, so stopping at any
 * expired artifact would only see a day of builds); downloads are cached; installs are recorded
 * per device.
 */
class Builds(private val rt: LabRuntime, private val http: HttpClient = GitHub.httpClient()) {
    /** The GitHub HTTP client (shared with the project set-up). */
    val httpClient: HttpClient get() = http
    val files = BuildFiles(rt.dirs.builds)
    val installed = InstalledBuilds(File(rt.dirs.data, "installed-builds.json"))
    val installer = Installer(rt)
    private val cacheFile = File(rt.dirs.builds, "catalog.json")
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    private val jobFlow = MutableStateFlow<List<BuildJob>>(emptyList())
    val jobs: StateFlow<List<BuildJob>> = jobFlow.asStateFlow()

    /** GitHub for [p]'s repo (the active profile's by default; a device's own with [Builds.profileOf]). */
    fun github(p: Profile = rt.profile): GitHub {
        val repo = p.github.repo
        if (repo.isBlank()) throw NoBuild("The profile «${p.name}» has no GitHub repo: set github.repo (`testlab open profiles`)")
        rt.rules.checkRepo(repo)
        val api = (rt.settings.current.connections.githubApiUrl ?: p.github.apiUrl).trimEnd('/')
        return GitHub(http, { SecretStore.scoped(rt.secrets, SecretStore.GITHUB_TOKEN, p.github.owner) }, repo, api)
    }

    /** The profile for builds installed on [serial]: its platform's (an iOS simulator gets the iOS profile's repo and types). */
    fun profileOf(serial: String?): Profile = serial?.takeIf { it.isNotBlank() }?.let(rt::profileFor) ?: rt.profile

    /** The build types of the active profile (or [p]). */
    fun types(p: Profile = rt.profile): List<BuildType> = BuildType.all(p)
    fun type(s: String?, p: Profile = rt.profile, realDevice: Boolean = false): BuildType = BuildType.parse(p, s, realDevice)
    fun defaultType(p: Profile = rt.profile): BuildType = BuildType.default(p)

    private fun workflowFor(type: BuildType): String = type.workflowFile
        ?: throw NoBuild("${type.label} builds come from other workflows, not one of their own: pick one in the Builds list, or install a release")

    /** "1234", "#1234", a PR URL, or a branch name. */
    suspend fun resolveRef(gh: GitHub, ref: String): Pair<String, PullRequest?> {
        val r = ref.trim()
        val prNumber = Regex("^#?(\\d+)$").matchEntire(r)?.groupValues?.get(1)?.toInt()
            ?: Regex("/pull/(\\d+)").find(r)?.groupValues?.get(1)?.toInt()
        if (prNumber != null) {
            val pr = gh.pull(prNumber)
            return pr.headRef to pr
        }
        if (r.isEmpty()) throw IllegalArgumentException("give a branch name or a PR number")
        return r.removePrefix("refs/heads/") to null
    }

    suspend fun branchBuild(ref: String, type: BuildType = defaultType(), p: Profile = rt.profile): BranchBuild {
        val gh = github(p)
        val (branch, pr) = resolveRef(gh, ref)
        if (type.workflowFile == null) return branchBuildAnyWorkflow(gh, type, branch, pr)
        val file = workflowFor(type)
        val runs = gh.runs(file, branch, status = "success", perPage = 5)
        val pending = gh.runs(file, branch, status = "in_progress", perPage = 3) + gh.runs(file, branch, status = "queued", perPage = 3)
        val run = runs.firstOrNull() ?: throw NoBuild(noBuildReason(type, branch, pr, pending.firstOrNull()))
        val artifact = gh.runArtifacts(run.id).firstOrNull { type.matches(it.name) }
        val newer = pending.firstOrNull { it.createdAt > run.createdAt }
        return BranchBuild(type, branch, pr, run, artifact, newer)
    }

    /**
     * A build type without a workflow of its own (several workflows make it, as device builds named by
     * bundle id often are): the newest successful run on [branch] that has a matching artifact.
     */
    private suspend fun branchBuildAnyWorkflow(gh: GitHub, type: BuildType, branch: String, pr: PullRequest?): BranchBuild {
        val runs = gh.allRuns(branch, status = "success", perPage = 20)
        for (run in runs) {
            val artifact = gh.runArtifacts(run.id).firstOrNull { type.matches(it.name) && !it.expired } ?: continue
            return BranchBuild(type, branch, pr, run, artifact, null)
        }
        val who = pr?.let { "PR #${it.number} ($branch)" } ?: "branch $branch"
        throw NoBuild("No ${type.label} build for $who in its last ${runs.size} successful run(s) (GitHub keeps builds for 30 days)")
    }

    private fun noBuildReason(type: BuildType, branch: String, pr: PullRequest?, running: WorkflowRun?): String {
        val who = pr?.let { "PR #${it.number} ($branch)" } ?: "branch $branch"
        if (running != null) return "No finished ${type.label} build for $who yet: run ${running.runNumber} is ${running.status} (${running.htmlUrl})"
        val prefixes = type.def.autoPrefixes
        if (prefixes.isNotEmpty()) {
            if (pr?.draft == true) return "No ${type.label} build for $who: ${type.workflowFile} skips draft PRs. Start one by hand (handsets_start_branch_build); don't mark the PR ready just to get a build, that is its author's call"
            if (prefixes.none { branch.startsWith(it) }) {
                return "No ${type.label} build for $who: ${type.workflowFile} only builds PRs from ${prefixes.joinToString(", ")} branches. Start one by hand"
            }
        }
        return "No successful ${type.label} build for $who in the last runs (GitHub keeps builds for 30 days). Start one by hand"
    }

    // --- catalog ---

    /** The newest build per (type, branch). [refresh] fetches what is new since the cached list. */
    suspend fun catalog(refresh: Boolean = true, maxPages: Int = 40): List<CatalogEntry> = withContext(Dispatchers.IO) {
        // Keyed by repo: another profile's catalog (another repo) is never shown for this one.
        val repo = rt.profile.github.repo
        val cached = runCatching { json.decodeFromString<CatalogCache>(cacheFile.readText()) }.getOrNull()?.takeIf { it.repo == repo }
        if (!refresh && cached != null) return@withContext cached.entries
        val gh = github()
        val fresh = mutableListOf<CatalogEntry>()
        var newest = cached?.newestId ?: 0
        val cutoff = Instant.now().minusSeconds(31L * 24 * 3600).toString()
        page@ for (page in 1..maxPages) {
            val (arts, _) = gh.artifactsPage(page)
            if (arts.isEmpty()) break
            for (a in arts) {
                if (a.id <= (cached?.newestId ?: 0)) break@page // the rest is in the cache
                newest = maxOf(newest, a.id)
                val type = BuildType.of(rt.profile, a.name) ?: continue
                if (a.expired || (a.createdAt != null && a.createdAt < cutoff)) break@page // the first expired build ends the list
                fresh += CatalogEntry(type.id, a.headBranch ?: "?", a.headSha ?: "", a.runId ?: 0, a.id, a.name, a.bytes, a.createdAt ?: "", a.expiresAt)
            }
        }
        val now = Instant.now().toString()
        val merged = (fresh + cached?.entries.orEmpty())
            .filter { it.expiresAt == null || it.expiresAt > now }
            .groupBy { it.type to it.branch }.map { (_, v) -> v.maxBy { it.artifactId } }
            .sortedByDescending { it.artifactId }
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText(json.encodeToString(CatalogCache(newest, merged, repo)))
        files.prune(merged.map { it.artifactId }.toSet() + fresh.map { it.artifactId })
        merged
    }

    // --- releases ---

    /** Releases that carry an APK (the lab's `list_builds`). */
    suspend fun releases(): List<Pair<Release, ReleaseAsset>> = github().releases().mapNotNull { r -> apkAsset(r)?.let { r to it } }

    private fun apkAsset(r: Release): ReleaseAsset? = r.assets.firstOrNull { it.name.endsWith(".apk") } ?: r.assets.firstOrNull { it.name.endsWith(".apk.zip") }

    // --- install ---

    private fun job(key: String, title: String, serial: String?, phase: BuildJob.Phase, done: Long = 0, total: Long? = null, message: String? = null, problem: InstallProblem? = null) {
        val j = BuildJob(key, title, serial, phase, done, total, message, problem)
        jobFlow.update { list -> (list.filterNot { it.key == key } + j).takeLast(20) }
    }

    suspend fun installBranchBuild(ref: String, type: BuildType, serial: String): InstallOutcome {
        val p = profileOf(serial)
        val b = branchBuild(ref, type, p)
        val a = b.artifact ?: throw NoBuild("Run ${b.run.runNumber} of ${b.branch} has no ${type.label} artifact (${b.run.htmlUrl})")
        val key = "a${a.id}@$serial"
        val title = "${type.label} ${b.branch} ${b.run.headSha.take(7)}"
        return installArtifactJob(key, title, serial, a, p) { apk, info -> Installer.record(serial, apk, info, "artifact", b.branch, b.run.headSha, a.id) }
            .copy(build = b)
    }

    /** Saves a catalog entry's APK in [dir] without a device (Builds → Download): `<branch>-<sha7>.apk`. */
    suspend fun downloadEntry(e: CatalogEntry, dir: File): File {
        val a = Artifact(e.artifactId, e.name, e.bytes, false, e.createdAt, e.expiresAt, e.runId, e.branch, e.headSha)
        val key = "d${e.artifactId}"
        val title = "${e.branch} ${e.headSha.take(7)}"
        try {
            job(key, title, null, BuildJob.Phase.DOWNLOADING, total = a.bytes)
            val apk = files.artifact(github(), a, ios = rt.profile.platform == Platform.IOS) { done, total -> job(key, title, null, BuildJob.Phase.DOWNLOADING, done, total) }
            val out = File(dir.apply { mkdirs() }, "${e.branch.replace(Regex("[^A-Za-z0-9._-]+"), "-")}-${e.headSha.take(7)}" + if (apk.isDirectory) ".app" else ".apk")
            withContext(Dispatchers.IO) { if (apk.isDirectory) { out.deleteRecursively(); apk.copyRecursively(out, overwrite = true) } else apk.copyTo(out, overwrite = true) }
            job(key, title, null, BuildJob.Phase.DONE, message = "saved ${out.path}")
            return out
        } catch (ex: Exception) {
            job(key, title, null, BuildJob.Phase.FAILED, message = ex.message)
            throw ex
        }
    }

    /** Installs a catalog entry (the Builds screen). */
    suspend fun installEntry(e: CatalogEntry, serial: String): InstallOutcome {
        val a = Artifact(e.artifactId, e.name, e.bytes, false, e.createdAt, e.expiresAt, e.runId, e.branch, e.headSha)
        val p = profileOf(serial)
        return installArtifactJob("a${e.artifactId}@$serial", "${types(p).firstOrNull { it.id == e.type }?.label ?: e.type} ${e.branch} ${e.headSha.take(7)}", serial, a, p) { apk, info ->
            Installer.record(serial, apk, info, "artifact", e.branch, e.headSha, e.artifactId)
        }
    }

    private suspend fun installArtifactJob(key: String, title: String, serial: String, a: Artifact, p: Profile, record: (File, ManifestInfo) -> InstalledBuild): InstallOutcome {
        try {
            job(key, title, serial, BuildJob.Phase.DOWNLOADING, total = a.bytes)
            val apk = files.artifact(github(p), a, ios = p.platform == Platform.IOS) { done, total -> job(key, title, serial, BuildJob.Phase.DOWNLOADING, done, total) }
            return installFileJob(key, title, serial, apk, record)
        } catch (e: Exception) {
            if (jobFlow.value.firstOrNull { it.key == key }?.phase != BuildJob.Phase.FAILED) job(key, title, serial, BuildJob.Phase.FAILED, message = e.message)
            throw e
        }
    }

    suspend fun installRelease(tag: String, serial: String): InstallOutcome {
        val p = profileOf(serial)
        if (p.platform == Platform.IOS) throw NoBuild("iOS builds come from CI artifacts, not GitHub Releases: handsets_install_branch_build installs a PR's or branch's simulator build")
        val gh = github(p)
        val r = if (tag.isBlank() || tag == "latest") gh.releases(5).firstOrNull { apkAsset(it) != null } ?: throw NoBuild("No release with an APK in ${gh.repo}")
        else gh.releaseByTag(tag)
        val asset = apkAsset(r) ?: throw NoBuild("Release ${r.tag} has no APK asset")
        val key = "r${asset.id}@$serial"
        val title = "Release ${r.tag}"
        try {
            job(key, title, serial, BuildJob.Phase.DOWNLOADING, total = asset.bytes)
            val apk = files.asset(gh, asset) { done, total -> job(key, title, serial, BuildJob.Phase.DOWNLOADING, done, total) }
            return installFileJob(key, title, serial, apk) { f, info -> Installer.record(serial, f, info, "release", tag = r.tag) }.copy(release = r)
        } catch (e: Exception) {
            if (jobFlow.value.firstOrNull { it.key == key }?.phase != BuildJob.Phase.FAILED) job(key, title, serial, BuildJob.Phase.FAILED, message = e.message)
            throw e
        }
    }

    /** An APK from disk (drag-in or a file picker). */
    suspend fun installFile(apk: File, serial: String): InstallOutcome =
        installFileJob("f${apk.absolutePath.hashCode()}@$serial", apk.name, serial, apk) { f, info -> Installer.record(serial, f, info, "file") }

    private suspend fun installFileJob(key: String, title: String, serial: String, apk: File, record: (File, ManifestInfo) -> InstalledBuild): InstallOutcome {
        val info = withContext(Dispatchers.IO) { BuildFiles.manifest(apk) }
        val downgrade = downgradeGuard(serial, info)
        job(key, title, serial, BuildJob.Phase.INSTALLING, message = "${info.packageName} ${info.versionName ?: ""}")
        val result = rt.ops(serial).install(serial, apk)
        if (result.ok) {
            installed.record(record(apk, info))
            job(key, title, serial, BuildJob.Phase.DONE, message = "${info.packageName} ${info.versionName ?: ""} installed")
        } else {
            job(key, title, serial, BuildJob.Phase.FAILED, message = result.advice, problem = result.problem)
        }
        return InstallOutcome(apk, info, result, downgrade = downgrade)
    }

    /**
     * An older build over a newer one's data can wedge the app (a splash that never ends, ANRs on
     * deeplinks): the newer copy is uninstalled first, after a yes, and the result says the login went with it.
     */
    private suspend fun downgradeGuard(serial: String, info: ManifestInfo): String? {
        if (rt.platformOf(serial) != Platform.ANDROID) return null
        val apkCode = info.versionCode ?: return null
        val out = withContext(Dispatchers.IO) { rt.adb().shell(serial, "dumpsys package ${no.agens.testlab.services.runtime.shQuote(info.packageName)} | grep -m1 versionCode") }.stdout
        val there = Regex("versionCode=(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (there <= apkCode) return null
        rt.confirmWrite("Uninstall before a downgrade", "${info.packageName} on $serial is version code $there, newer than this build's $apkCode. Installing an older build over newer data can hang the app at start, so uninstall it first? Its data and login go with it.")
        rt.adbOps.uninstall(serial, info.packageName)
        installed.forget(serial, info.packageName)
        return "uninstalled ${info.packageName} $there first (an older build over newer data can hang the app): the app's data and login are gone, log in again"
    }

    /**
     * The control for a PR: its merge-base with the base branch (the cleanest A/B: the PR's commits are
     * the only difference), a CI build of exactly that commit if one exists, and what the newest release
     * tag changed in the PR's files since then (whether the control also stands for "what users have").
     */
    suspend fun control(ref: String, type: BuildType = defaultType(), p: Profile = rt.profile): Control {
        val gh = github(p)
        val (_, pr) = resolveRef(gh, ref)
        pr ?: throw IllegalArgumentException("a control build is worked out from a PR: give its number")
        val detail = gh.pullDetail(pr.number)
        val cmp = gh.compare(detail.baseRef, pr.headSha)
        val run = gh.runsForSha(cmp.mergeBase).firstNotNullOfOrNull { r -> gh.runArtifacts(r.id).firstOrNull { type.matches(it.name) && !it.expired }?.let { r to it } }
        val prFiles = runCatching { gh.pullFiles(pr.number).map { it.path } }.getOrDefault(emptyList())
        val release = runCatching { gh.releases(5).firstOrNull { !it.prerelease } }.getOrNull()
        val sinceRelease = release?.let { r -> runCatching { gh.compare(r.tag, cmp.mergeBase).files.filter { it in prFiles } }.getOrNull() }
        return Control(pr, detail.baseRef, cmp.mergeBase, cmp.behindBy, run?.first, run?.second, release?.tag, sinceRelease)
    }

    /** Installs [c]'s build of the merge-base commit. */
    suspend fun installControl(c: Control, type: BuildType, serial: String): InstallOutcome {
        val run = c.run ?: throw NoBuild("no CI build of the merge-base ${c.mergeBase.take(7)}")
        val a = c.artifact ?: throw NoBuild("run ${run.runNumber} has no ${type.label} artifact")
        val p = profileOf(serial)
        return installArtifactJob("a${a.id}@$serial", "control ${c.mergeBase.take(7)}", serial, a, p) { apk, info -> Installer.record(serial, apk, info, "control", c.baseRef, c.mergeBase, a.id) }
    }

    /** How far [branch] is from the repo's default branch (GitHub's compare): ahead, behind, or null when unknown. */
    suspend fun behindDefault(branch: String, p: Profile = rt.profile): Pair<Int, Int>? = runCatching {
        val gh = github(p)
        gh.compare(gh.repoInfo().defaultBranch, branch).let { it.aheadBy to it.behindBy }
    }.getOrNull()

    /** What ${AppIdentity.NAME} installed on [serial] that is still there. */
    suspend fun installedOn(serial: String): List<InstalledBuild> = installed.on(serial).filter { b ->
        val there = runCatching { rt.ops(serial).isInstalled(serial, b.packageName) }.getOrDefault(true)
        if (!there) installed.forget(serial, b.packageName)
        there
    }

    // --- start a build ---

    /**
     * workflow_dispatch of the type's workflow on [ref]'s branch. GitHub's answer has no run id, so
     * the new run is looked up afterwards (the newest workflow_dispatch run on the branch).
     */
    suspend fun startBuild(ref: String, type: BuildType, inputs: Map<String, String> = dispatchDefaults(type)): WorkflowRun? {
        val gh = github()
        val (branch, _) = resolveRef(gh, ref)
        val file = workflowFor(type)
        val before = Instant.now().minusSeconds(5).toString()
        gh.dispatch(file, branch, inputs)
        repeat(10) {
            delay(1_500)
            val run = (gh.runs(file, branch, status = null, perPage = 5)).firstOrNull { it.event == "workflow_dispatch" && it.createdAt >= before.substring(0, 19) }
            if (run != null) return run
        }
        return null
    }

    /** The workflow_dispatch inputs with their defaults. */
    fun dispatchDefaults(type: BuildType): Map<String, String> = type.def.dispatchInputs.mapValues { it.value.default }

    /** What starting a build costs, worded once for the MCP tool and the UI; the profile can say more (who gets notified). */
    fun startBuildCost(): String = rt.profile.text("builds.startCost", "A build takes a CI runner for several minutes")
}

/** GitHub errors and missing builds as one sentence; anything else falls back to its message. */
fun buildError(e: Throwable): String = when (e) {
    is GitHubError -> e.message + (e.fixUrl?.let { " ($it)" } ?: "")
    is no.agens.testlab.services.jira.JiraError -> e.message + (e.fixUrl?.let { " ($it)" } ?: "")
    is NoBuild -> e.message ?: "no build"
    else -> e.message ?: e::class.simpleName ?: "error"
}
