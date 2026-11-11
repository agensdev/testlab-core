package no.agens.testlab.services.profile

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.agens.testlab.core.apk.ManifestInfo
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.services.builds.BuildFiles
import no.agens.testlab.services.github.Artifact
import no.agens.testlab.services.github.GitHub
import no.agens.testlab.services.github.GitHubError
import no.agens.testlab.services.jira.Jira
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.SecretStore

/**
 * «Set up a project»: proposes a profile from what GitHub and Jira report, so nobody writes
 * profile.json by hand to get started. Nothing is guessed silently: every proposal is shown and
 * editable before it is saved.
 */
class ProjectSetup(private val rt: LabRuntime) {
    /** One APK-looking artifact of a workflow's newest successful run, as a proposed build type. */
    data class BuildCandidate(
        val workflow: String,
        val workflowFile: String,
        val event: String,
        val artifact: Artifact,
        val proposal: Profile.BuildDef,
        /** Read from the downloaded APK on request ([readManifest]). */
        val manifest: ManifestInfo? = null,
    )

    data class RepoScan(val repo: String, val defaultBranch: String, val candidates: List<BuildCandidate>, val releaseAsset: String?, val problems: List<String>)

    data class JiraScan(val site: String, val projects: List<Pair<String, String>>, val labels: List<String>, val queueLabels: List<String>, val approvedLabel: String?)

    private fun github(repo: String, api: String? = null) = rt.rules.checkRepo(repo).let { GitHub(rt.builds.httpClient, { SecretStore.scoped(rt.secrets, SecretStore.GITHUB_TOKEN, repo.substringBefore('/')) }, repo, (api ?: rt.settings.current.connections.githubApiUrl ?: "https://api.github.com").trimEnd('/')) }

    /** The repos the GitHub token in the vault can read, to pick from instead of typing owner/name. */
    suspend fun repos(api: String? = null): no.agens.testlab.services.github.RepoList {
        if (rt.secrets.get(SecretStore.GITHUB_TOKEN).isNullOrBlank()) throw no.agens.testlab.services.github.GitHubError(0, "No GitHub token: add one on the setup page (`testlab open setup`)")
        val all = GitHub(rt.builds.httpClient, { rt.secrets.get(SecretStore.GITHUB_TOKEN) }, "", (api ?: rt.settings.current.connections.githubApiUrl ?: "https://api.github.com").trimEnd('/')).userRepos()
        // A space lists only its client's repos, even when the token reaches more.
        val r = rt.rules
        return if (!r.enforced) all else all.copy(repos = all.repos.filter { r.current.ownerOk(it.fullName.substringBefore('/')) })
    }

    suspend fun scanRepo(repo: String, api: String? = null): RepoScan = coroutineScope {
        val gh = github(repo.trim(), api)
        val info = gh.repoInfo()
        val problems = java.util.Collections.synchronizedList(mutableListOf<String>())
        val workflows = gh.workflows().filter { it.state == "active" }
        val perWorkflow = workflows.map { w ->
            async {
                try {
                    val run = gh.runs(w.file, null, status = "success", perPage = 1).firstOrNull() ?: return@async emptyList()
                    val prefixes = runCatching { ProjectSetup.autoPrefixes(gh.readFile(w.path, info.defaultBranch)) }.getOrDefault(emptyList())
                    val dispatch = runCatching { ProjectSetup.dispatchInputs(gh.readFile(w.path, info.defaultBranch)) }.getOrDefault(emptyMap())
                    gh.runArtifacts(run.id).filter { !it.expired && looksLikeApk(it.name) }.map { a ->
                        BuildCandidate(
                            w.name, w.file, run.event, a,
                            Profile.BuildDef(
                                id = idFor(w.file, a.name), label = w.name.ifBlank { w.file.substringBeforeLast('.') }, workflowFile = w.file,
                                artifact = artifactPattern(a.name), autoPrefixes = prefixes, dispatchInputs = dispatch,
                            ),
                        )
                    }
                } catch (e: GitHubError) { problems += "${w.file}: ${e.message}"; emptyList() }
            }
        }.awaitAll().flatten()
        // Ids must be unique; the PR-triggered workflow is the default.
        val seen = HashMap<String, Int>()
        val unique = perWorkflow.map { c -> val n = (seen[c.proposal.id] ?: 0) + 1; seen[c.proposal.id] = n; if (n == 1) c else c.copy(proposal = c.proposal.copy(id = "${c.proposal.id}-$n")) }
        val defaultIdx = unique.indexOfFirst { it.event == "pull_request" }.takeIf { it >= 0 } ?: 0
        val withDefault = unique.mapIndexed { i, c -> c.copy(proposal = c.proposal.copy(default = i == defaultIdx)) }
        val release = runCatching { gh.releases(5).flatMap { it.assets }.firstOrNull { it.name.endsWith(".apk") }?.name }.getOrNull()
        RepoScan(info.fullName, info.defaultBranch, withDefault, release, problems.toList())
    }

    /** Downloads the candidate's APK (cached like any build) and reads its manifest: package, version, deeplink hosts. */
    suspend fun readManifest(repo: String, c: BuildCandidate, api: String? = null): BuildCandidate {
        val apk = rt.builds.files.artifact(github(repo, api), c.artifact)
        val m = BuildFiles.manifest(apk)
        return c.copy(manifest = m, proposal = c.proposal.copy(packageName = m.packageName))
    }

    suspend fun scanJira(site: String): JiraScan {
        val s = site.trim().removePrefix("https://").trimEnd('/')
        rt.rules.checkSite(s)
        val jira = Jira(rt.jiraHttp ?: Jira.httpClient(), { rt.settings.current.connections.jiraBase(s) }, { s },
            { SecretStore.scoped(rt.secrets, SecretStore.JIRA_EMAIL, s) }, { SecretStore.scoped(rt.secrets, SecretStore.JIRA_TOKEN, s) })
        val projects = jira.projects()
        val labels = runCatching { jira.labels() }.getOrDefault(emptyList())
        val (queue, approved) = suggestLabels(labels)
        return JiraScan(s, projects, labels, queue, approved)
    }

    /** A profile from the chosen pieces; [ProfileStore.save] checks it. */
    fun draft(
        id: String, name: String, repo: RepoScan?, chosen: List<BuildCandidate>, jira: JiraScan?, projects: List<String>,
        queueLabels: List<String>, approvedLabel: String?, defaultPackage: String?, productionPackage: String?,
    ): Profile {
        val builds = chosen.map { it.proposal }.let { list -> if (list.none { it.default } && list.isNotEmpty()) listOf(list[0].copy(default = true)) + list.drop(1) else list }
        val links = chosen.flatMap { it.manifest?.links.orEmpty() }.distinct()
        val host = links.firstOrNull { it.startsWith("https://") } ?: links.firstOrNull { it.startsWith("http://") }
        val pkg = defaultPackage?.takeIf { it.isNotBlank() } ?: builds.firstOrNull { it.default }?.packageName.orEmpty()
        val approved = approvedLabel?.takeIf { it.isNotBlank() } ?: "devtest-approved"
        return Profile(
            id = id, name = name,
            app = Profile.App(defaultPackage = pkg, productionPackage = productionPackage?.takeIf { it.isNotBlank() }, family = commonPrefix(builds.map { it.packageName }.filter { it.isNotBlank() } + listOfNotNull(productionPackage?.takeIf { it.isNotBlank() }))),
            github = Profile.GitHub(repo = repo?.repo.orEmpty()),
            builds = builds,
            tracker = Profile.Tracker(
                site = jira?.site.orEmpty(), projects = projects, queueLabels = queueLabels.ifEmpty { listOf("devtest") }, approvedLabel = approved,
                onApproved = Profile.Outcome(addLabels = listOf(approved)), onChanges = Profile.Outcome(handBack = true),
            ),
            navigation = Profile.Navigation(host = host.orEmpty()),
        )
    }

    companion object {
        private val SHA = Regex("-[0-9a-f]{7,40}(?=\\.apk$|$)")
        private val VERSION = Regex("\\d+(\\.\\d+)+")

        fun looksLikeApk(name: String): Boolean = Regex("(?i)apk").containsMatchIn(name) && !Regex("(?i)\\b(aab|bundle|mapping|test-results?|reports?|lint)\\b").containsMatchIn(name)

        /** A regex for the artifact name that survives the next build: commit hashes and versions vary. */
        fun artifactPattern(name: String): String {
            val out = StringBuilder()
            var i = 0
            val markers = (SHA.findAll(name).map { it.range to "(-[0-9a-f]{6,40})?" } + VERSION.findAll(name).map { it.range to "[0-9.]+" }).sortedBy { it.first.first }.toList()
            for ((range, rep) in markers) {
                if (range.first < i) continue
                out.append(quoteLiteral(name.substring(i, range.first)))
                out.append(rep)
                i = range.last + 1
            }
            out.append(quoteLiteral(name.substring(i)))
            return out.toString()
        }

        private fun quoteLiteral(s: String) = s.replace(Regex("[\\\\^$.|?*+()\\[\\]{}]")) { "\\" + it.value }

        fun idFor(workflowFile: String, artifact: String): String {
            val base = workflowFile.substringBeforeLast('.').lowercase().replace(Regex("^(build|ci|android)[-_]?"), "").replace(Regex("[-_]?(build|ci|apk)$"), "")
            return base.replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { artifact.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(20).ifEmpty { "build" } }
        }

        /**
         * Branch prefixes a workflow builds by itself: `branches` globs such as feature/ + two stars under
         * pull_request, and `startsWith(github.head_ref, 'feature/')` conditions.
         */
        fun autoPrefixes(yaml: String): List<String> {
            val fromIf = Regex("startsWith\\(\\s*github\\.(?:head_ref|event\\.pull_request\\.head\\.ref)\\s*,\\s*['\"]([^'\"]+)['\"]").findAll(yaml).map { it.groupValues[1] }
            val fromBranches = Regex("(?m)^\\s*-\\s*['\"]?([A-Za-z0-9._-]+/)\\*\\*?['\"]?\\s*$").findAll(yaml).map { it.groupValues[1] }
            return (fromIf + fromBranches).distinct().toList()
        }

        /** workflow_dispatch inputs with their defaults and choice options. */
        fun dispatchInputs(yaml: String): Map<String, Profile.DispatchInput> {
            val block = Regex("(?ms)workflow_dispatch:\\s*\\n(\\s+)inputs:\\s*\\n(.*?)(?=^\\S|^\\s{0,2}\\w+:\\s*$)").find(yaml)?.groupValues?.get(2) ?: return emptyMap()
            val out = LinkedHashMap<String, Profile.DispatchInput>()
            val entries = Regex("(?m)^(\\s+)([A-Za-z0-9_-]+):\\s*$").findAll(block).toList()
            val indent = entries.minOfOrNull { it.groupValues[1].length } ?: return emptyMap()
            val top = entries.filter { it.groupValues[1].length == indent }
            for ((k, m) in top.withIndex()) {
                val body = block.substring(m.range.last + 1, top.getOrNull(k + 1)?.range?.first ?: block.length)
                val default = Regex("(?m)^\\s+default:\\s*['\"]?([^'\"\\n]*)['\"]?\\s*$").find(body)?.groupValues?.get(1)?.trim().orEmpty()
                val desc = Regex("(?m)^\\s+description:\\s*['\"]?([^'\"\\n]*)['\"]?\\s*$").find(body)?.groupValues?.get(1)?.trim()
                val opts = Regex("(?ms)^\\s+options:\\s*\\n((?:\\s+-\\s*.+\\n?)+)").find(body)?.groupValues?.get(1)?.lines()?.mapNotNull { Regex("^\\s+-\\s*['\"]?([^'\"]+?)['\"]?\\s*$").find(it)?.groupValues?.get(1) }.orEmpty()
                out[m.groupValues[2]] = Profile.DispatchInput(default, opts, desc?.ifEmpty { null })
            }
            return out
        }

        /** Labels that look like «ready for a devtest», and the one that looks like «passed». */
        fun suggestLabels(labels: List<String>): Pair<List<String>, String?> {
            val testy = labels.filter { Regex("(?i)(dev|side|qa|peer|manual).?test|^test(ing)?$|^qa$").containsMatchIn(it) }
            val done = Regex("(?i)approved|godkjent|passed|ok$|done$|verified")
            return testy.filterNot { done.containsMatchIn(it) } to testy.firstOrNull { done.containsMatchIn(it) }
        }

        /** The package family: the longest dotted prefix all packages share (`com.example.app` of `.debug` and `.beta`). */
        fun commonPrefix(packages: List<String>): String? {
            if (packages.isEmpty()) return null
            val parts = packages.map { it.split('.') }
            val n = (0 until parts.minOf { it.size }).takeWhile { i -> parts.all { it[i] == parts[0][i] } }.count()
            return if (n >= 2) parts[0].take(n).joinToString(".") else null
        }
    }
}
