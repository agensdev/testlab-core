package no.agens.testlab.services.github

import no.agens.testlab.services.http.osTrust
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A GitHub API failure, already worded for the user, with the link that fixes it when there is one. */
class GitHubError(val status: Int, message: String, val fixUrl: String? = null) : RuntimeException(message)

data class Artifact(
    val id: Long,
    val name: String,
    val bytes: Long,
    val expired: Boolean,
    val createdAt: String?,
    val expiresAt: String?,
    val runId: Long?,
    val headBranch: String?,
    val headSha: String?,
)

data class WorkflowRun(
    val id: Long,
    val name: String,
    val path: String,
    val headBranch: String,
    val headSha: String,
    val status: String,
    val conclusion: String?,
    val event: String,
    val runNumber: Long,
    val createdAt: String,
    val htmlUrl: String,
)

data class PullRequest(val number: Int, val title: String, val headRef: String, val headSha: String, val draft: Boolean, val state: String, val htmlUrl: String)

data class PullDetail(
    val number: Int,
    val title: String,
    val body: String,
    val author: String,
    val state: String,
    val draft: Boolean,
    val merged: Boolean,
    val headRef: String,
    val headSha: String,
    val baseRef: String,
    val createdAt: String,
    val updatedAt: String,
    val additions: Long,
    val deletions: Long,
    val changedFiles: Long,
    val labels: List<String>,
    val reviewers: List<String>,
    val htmlUrl: String,
    /** The base branch's commit the PR was last compared with (fixed once it is merged). */
    val baseSha: String = "",
)

data class PrFile(val path: String, val status: String, val additions: Long, val deletions: Long, val previousPath: String?, val patch: String?)

data class PrHit(val number: Int, val title: String, val state: String, val draft: Boolean, val author: String, val updatedAt: String, val htmlUrl: String)

data class Workflow(val id: Long, val name: String, val path: String, val state: String) {
    val file: String get() = path.substringAfterLast('/')
}

/** `GET /compare/base...head`: how [head] relates to [base], and their merge base. */
data class Comparison(val aheadBy: Int, val behindBy: Int, val mergeBase: String, val files: List<String>)

data class RepoInfo(val fullName: String, val defaultBranch: String, val private: Boolean, val htmlUrl: String)

/** A repo the token can read, for picking one ([GitHub.userRepos]). */
data class UserRepo(val fullName: String, val private: Boolean, val description: String?, val language: String?, val pushedAt: String?, val archived: Boolean, val fork: Boolean)

/**
 * The repos a token can read, newest activity first. [ssoHidden] organisations (ids) keep theirs out of
 * the list until the token is authorized for their SSO; [truncated] when there were more than were fetched.
 */
data class RepoList(val repos: List<UserRepo>, val ssoHidden: List<String>, val truncated: Boolean)

data class ReleaseAsset(val id: Long, val name: String, val bytes: Long, val apiUrl: String)
data class Release(val tag: String, val name: String, val publishedAt: String?, val prerelease: Boolean, val assets: List<ReleaseAsset>)

/**
 * The few GitHub REST calls the builds need: one specific message per failure (bad
 * token, SSO not authorized, rate limit, not visible), quota from `x-ratelimit-*`, and artifact
 * downloads that follow the redirect to the signed storage URL *without* the token: the storage
 * rejects extra Authorization, and the token must not leave api.github.com anyway.
 */
class GitHub(
    private val http: HttpClient,
    private val token: () -> String?,
    val repo: String,
    private val api: String = "https://api.github.com",
) {
    @Volatile var rateRemaining: Int? = null
        private set
    @Volatile var rateResetEpoch: Long? = null
        private set

    private val json = Json { ignoreUnknownKeys = true }

    private fun HttpRequestBuilder.auth(accept: String = "application/vnd.github+json") {
        val t = token()?.trim().orEmpty()
        if (t.isEmpty()) throw GitHubError(401, "No GitHub token: add one on the setup page (`testlab open setup`)", TOKEN_PAGE)
        header(HttpHeaders.Authorization, "Bearer $t")
        header(HttpHeaders.Accept, accept)
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun noteRate(r: HttpResponse) {
        r.headers["x-ratelimit-remaining"]?.toIntOrNull()?.let { rateRemaining = it }
        r.headers["x-ratelimit-reset"]?.toLongOrNull()?.let { rateResetEpoch = it }
    }

    /** Throws the right [GitHubError] for a failed response. */
    private suspend fun check(r: HttpResponse, what: String): HttpResponse {
        noteRate(r)
        val s = r.status.value
        if (s in 200..299 || s in 300..399) return r
        val body = runCatching { r.bodyAsText() }.getOrDefault("")
        val message = runCatching { (json.parseToJsonElement(body) as JsonObject)["message"]?.let { (it as JsonPrimitive).content } }.getOrNull().orEmpty()
        val sso = r.headers["X-GitHub-SSO"]?.substringAfter("url=", "")?.substringBefore(';')?.takeIf { it.startsWith("http") }
        throw when {
            s == 401 -> GitHubError(s, "GitHub rejected the token (invalid or expired): add a new one on the setup page (`testlab open setup`)", TOKEN_PAGE)
            s == 403 && sso != null -> GitHubError(s, "The token is not authorized for ${repo.substringBefore('/')}'s SSO: open the link and authorize it", sso)
            (s == 403 || s == 429) && (rateRemaining == 0 || "rate limit" in message.lowercase()) ->
                GitHubError(s, "GitHub's API limit for this token is used up until ${resetClock()}")
            s == 403 && "Resource not accessible" in message ->
                GitHubError(s, "The token may not $what: a fine-grained token needs Actions, Contents and Pull requests (read) on $repo", TOKEN_PAGE)
            s == 404 -> GitHubError(s, "Not found: $what (or the token can't see $repo)")
            s == 410 -> GitHubError(s, "Gone: $what has expired (GitHub keeps build artifacts for 30 days)")
            s == 422 -> GitHubError(s, "GitHub refused to $what: ${message.ifEmpty { "unprocessable" }}")
            else -> GitHubError(s, "GitHub answered $s for $what${if (message.isNotEmpty()) ": $message" else ""}")
        }
    }

    private fun resetClock(): String = rateResetEpoch?.let { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(it)) } ?: "the next hour"

    private suspend fun getJson(path: String, what: String): JsonElement {
        val r = check(http.get("$api$path") { auth() }, what)
        if (r.status.value in 300..399) throw GitHubError(r.status.value, "GitHub redirected $what (was the repo renamed? check the profile's github.repo)")
        return json.parseToJsonElement(r.bodyAsText())
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    /**
     * Every repo the token can read (`/user/repos`: owned, collaborator, organisation member), up to
     * [maxPages] × 100, newest push first. GitHub leaves out the repos of organisations whose SSO the
     * token isn't authorized for, and says so in `X-GitHub-SSO: partial-results; organizations=…`.
     */
    suspend fun userRepos(maxPages: Int = 5): RepoList {
        val out = mutableListOf<UserRepo>()
        val sso = linkedSetOf<String>()
        var truncated = false
        for (page in 1..maxPages) {
            val r = check(http.get("$api/user/repos?per_page=100&page=$page&sort=pushed&affiliation=owner,collaborator,organization_member") { auth() }, "list the repos the token can read")
            r.headers["X-GitHub-SSO"]?.takeIf { it.startsWith("partial-results") }?.substringAfter("organizations=", "")?.split(',')
                ?.map { it.trim() }?.filter { it.isNotEmpty() }?.let(sso::addAll)
            val a = json.parseToJsonElement(r.bodyAsText()) as JsonArray
            a.map { it as JsonObject }.forEach { o ->
                out += UserRepo(o.str("full_name"), o.bool("private"), o.str("description").ifEmpty { null }, o.str("language").ifEmpty { null },
                    o.str("pushed_at").ifEmpty { null }, o.bool("archived"), o.bool("fork"))
            }
            if (a.size < 100) break
            if (page == maxPages) truncated = true
        }
        return RepoList(out.sortedByDescending { it.pushedAt.orEmpty() }, sso.toList(), truncated)
    }

    suspend fun repoInfo(): RepoInfo = (getJson("/repos/$repo", "read $repo") as JsonObject).let {
        RepoInfo(it.str("full_name"), it.str("default_branch"), it.bool("private"), it.str("html_url"))
    }

    /** The repo's GitHub Actions workflows (active and disabled). */
    suspend fun workflows(): List<Workflow> =
        ((getJson("/repos/$repo/actions/workflows?per_page=100", "list the workflows of $repo") as JsonObject)["workflows"] as? JsonArray).orEmpty().map { it as JsonObject }
            .map { Workflow(it.long("id"), it.str("name"), it.str("path"), it.str("state")) }

    suspend fun compare(base: String, head: String): Comparison {
        val o = getJson("/repos/$repo/compare/${enc(base)}...${enc(head)}", "compare $base with $head") as JsonObject
        return Comparison(o.long("ahead_by").toInt(), o.long("behind_by").toInt(), o.obj("merge_base_commit").str("sha"),
            (o["files"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("filename") })
    }

    suspend fun pull(number: Int): PullRequest {
        val o = getJson("/repos/$repo/pulls/$number", "read PR #$number") as JsonObject
        val head = o.obj("head")
        return PullRequest(number, o.str("title"), head.str("ref"), head.str("sha"), o.bool("draft"), o.str("state"), o.str("html_url"))
    }

    suspend fun pullDetail(number: Int): PullDetail {
        val o = getJson("/repos/$repo/pulls/$number", "read PR #$number") as JsonObject
        val head = o.obj("head")
        return PullDetail(
            number, o.str("title"), o.str("body"), o.obj("user").str("login"), o.str("state"), o.bool("draft"), o.bool("merged"),
            head.str("ref"), head.str("sha"), o.obj("base").str("ref"), o.str("created_at"), o.str("updated_at"),
            o.long("additions"), o.long("deletions"), o.long("changed_files"),
            (o["labels"] as? JsonArray).orEmpty().map { (it as JsonObject).str("name") },
            (o["requested_reviewers"] as? JsonArray).orEmpty().map { (it as JsonObject).str("login") },
            o.str("html_url"),
            o.obj("base").str("sha"),
        )
    }

    /** Changed files of a PR (GitHub caps a page at 100 and the list at 3000). */
    suspend fun pullFiles(number: Int, maxFiles: Int = 300): List<PrFile> {
        val out = mutableListOf<PrFile>()
        var page = 1
        while (out.size < maxFiles) {
            val a = getJson("/repos/$repo/pulls/$number/files?per_page=100&page=$page", "list the files of PR #$number") as JsonArray
            out += a.map { (it as JsonObject).let { f -> PrFile(f.str("filename"), f.str("status"), f.long("additions"), f.long("deletions"), f.str("previous_filename").ifEmpty { null }, f.str("patch").ifEmpty { null }) } }
            if (a.size < 100) break
            page++
        }
        return out.take(maxFiles)
    }

    /** The unified diff of a PR. */
    suspend fun pullDiff(number: Int): String {
        val r = check(http.get("$api/repos/$repo/pulls/$number") { auth("application/vnd.github.diff") }, "read the diff of PR #$number")
        return r.bodyAsText()
    }

    /** A file's text at [ref] (branch, tag or sha; null = default branch). */
    suspend fun readFile(path: String, ref: String? = null): String {
        val p = path.trim().trimStart('/')
        require(p.isNotEmpty() && ".." !in p.split('/')) { "not a repository path: '$path'" }
        val r = check(http.get("$api/repos/$repo/contents/${p.split('/').joinToString("/") { enc(it) }}" + (ref?.let { "?ref=${enc(it)}" } ?: "")) { auth("application/vnd.github.raw+json") }, "read $p${ref?.let { " at $it" } ?: ""}")
        return r.bodyAsText()
    }

    /** Issue search restricted to this repo's PRs ([query] is GitHub search syntax). */
    suspend fun searchPulls(query: String, perPage: Int = 20): List<PrHit> {
        val q = "repo:$repo is:pr $query"
        val o = getJson("/search/issues?q=${enc(q)}&sort=updated&order=desc&per_page=$perPage", "search PRs") as JsonObject
        return (o["items"] as? JsonArray).orEmpty().map { it as JsonObject }.map { i ->
            val merged = i.obj("pull_request").str("merged_at").isNotEmpty()
            PrHit(i.long("number").toInt(), i.str("title"), if (merged) "merged" else i.str("state"), i.bool("draft"), i.obj("user").str("login"), i.str("updated_at"), i.str("html_url"))
        }
    }

    /** PRs whose head is [branch] (any state), newest first. */
    suspend fun pullsForBranch(branch: String): List<PrHit> {
        val owner = repo.substringBefore('/')
        val a = getJson("/repos/$repo/pulls?state=all&head=${enc("$owner:$branch")}&per_page=10", "list the PRs of $branch") as JsonArray
        return a.map { it as JsonObject }.map { p ->
            PrHit(p.long("number").toInt(), p.str("title"), if (p.str("merged_at").isNotEmpty()) "merged" else p.str("state"), p.bool("draft"), p.obj("user").str("login"), p.str("updated_at"), p.str("html_url"))
        }
    }

    /** Branch names starting with [prefix] (`git/matching-refs`). */
    suspend fun branchesWithPrefix(prefix: String): List<String> {
        val a = getJson("/repos/$repo/git/matching-refs/heads/${prefix.split('/').joinToString("/") { enc(it) }}", "list branches $prefix*") as JsonArray
        return a.map { (it as JsonObject).str("ref").removePrefix("refs/heads/") }
    }

    /** Recent runs of the repo, optionally of one workflow or branch. */
    suspend fun listRuns(workflowFile: String? = null, branch: String? = null, perPage: Int = 10): List<WorkflowRun> {
        val q = buildList { branch?.let { add("branch=${enc(it)}") }; add("per_page=$perPage") }.joinToString("&")
        val path = workflowFile?.let { "/repos/$repo/actions/workflows/${enc(it)}/runs?$q" } ?: "/repos/$repo/actions/runs?$q"
        val o = getJson(path, "list runs") as JsonObject
        return (o["workflow_runs"] as? JsonArray).orEmpty().map { (it as JsonObject).toRun() }
    }

    /** Runs for one commit, any workflow, newest first. */
    suspend fun runsForSha(sha: String, status: String? = "success", perPage: Int = 20): List<WorkflowRun> {
        val q = buildList { add("head_sha=${enc(sha)}"); status?.let { add("status=$it") }; add("per_page=$perPage") }.joinToString("&")
        return ((getJson("/repos/$repo/actions/runs?$q", "list runs of $sha") as JsonObject)["workflow_runs"] as? JsonArray).orEmpty().map { (it as JsonObject).toRun() }
    }

    /** Runs of one workflow file on [branch], newest first. */
    suspend fun runs(workflowFile: String, branch: String?, status: String? = null, perPage: Int = 10): List<WorkflowRun> {
        val q = buildList {
            branch?.let { add("branch=${enc(it)}") }
            status?.let { add("status=$it") }
            add("per_page=$perPage")
        }.joinToString("&")
        val o = getJson("/repos/$repo/actions/workflows/${enc(workflowFile)}/runs?$q", "list $workflowFile runs") as JsonObject
        return (o["workflow_runs"] as? JsonArray).orEmpty().map { (it as JsonObject).toRun() }
    }

    suspend fun run(runId: Long): WorkflowRun = (getJson("/repos/$repo/actions/runs/$runId", "read run $runId") as JsonObject).toRun()

    /** The step running now in [runId] ("Assemble build"), or null. */
    suspend fun currentStep(runId: Long): String? {
        val o = getJson("/repos/$repo/actions/runs/$runId/jobs?per_page=10", "read the jobs of run $runId") as JsonObject
        val jobs = (o["jobs"] as? JsonArray).orEmpty().map { it as JsonObject }
        val job = jobs.firstOrNull { it.str("status") == "in_progress" } ?: return null
        return (job["steps"] as? JsonArray).orEmpty().map { it as JsonObject }.firstOrNull { it.str("status") == "in_progress" }?.str("name") ?: job.str("name")
    }

    /** Runs of every workflow on [branch], newest first. */
    suspend fun allRuns(branch: String, status: String? = null, perPage: Int = 20): List<WorkflowRun> {
        val q = listOfNotNull("branch=${enc(branch)}", status?.let { "status=$it" }, "per_page=$perPage").joinToString("&")
        val o = getJson("/repos/$repo/actions/runs?$q", "list the runs on $branch") as JsonObject
        return (o["workflow_runs"] as? JsonArray).orEmpty().map { (it as JsonObject).toRun() }
    }

    suspend fun runArtifacts(runId: Long): List<Artifact> {
        val o = getJson("/repos/$repo/actions/runs/$runId/artifacts?per_page=100", "list the artifacts of run $runId") as JsonObject
        return (o["artifacts"] as? JsonArray).orEmpty().map { (it as JsonObject).toArtifact() }
    }

    /** The repo's artifacts, newest first (the catalog pages through these). */
    suspend fun artifactsPage(page: Int, perPage: Int = 100, name: String? = null): Pair<List<Artifact>, Long> {
        val o = getJson("/repos/$repo/actions/artifacts?per_page=$perPage&page=$page" + (name?.let { "&name=${enc(it)}" } ?: ""), "list artifacts") as JsonObject
        return (o["artifacts"] as? JsonArray).orEmpty().map { (it as JsonObject).toArtifact() } to ((o["total_count"] as? JsonPrimitive)?.longOrNull ?: 0)
    }

    suspend fun releases(perPage: Int = 20): List<Release> =
        (getJson("/repos/$repo/releases?per_page=$perPage", "list releases") as JsonArray).map { (it as JsonObject).toRelease() }

    suspend fun releaseByTag(tag: String): Release = (getJson("/repos/$repo/releases/tags/${enc(tag)}", "read release $tag") as JsonObject).toRelease()

    /** workflow_dispatch: starts [workflowFile] on [ref] with [inputs]. GitHub answers 204 without a run id. */
    suspend fun dispatch(workflowFile: String, ref: String, inputs: Map<String, String>) {
        val body = JsonObject(mapOf("ref" to JsonPrimitive(ref), "inputs" to JsonObject(inputs.mapValues { JsonPrimitive(it.value) }))).toString()
        check(http.post("$api/repos/$repo/actions/workflows/${enc(workflowFile)}/dispatches") { auth(); contentType(ContentType.Application.Json); setBody(body) }, "start $workflowFile on $ref")
    }

    /** Downloads an artifact's zip. The API answers with a redirect to signed storage, fetched without credentials. */
    suspend fun downloadArtifact(id: Long, dest: File, progress: (Long, Long?) -> Unit = { _, _ -> }) =
        download("$api/repos/$repo/actions/artifacts/$id/zip", "application/vnd.github+json", "download artifact $id", dest, progress)

    /** The commit [ref] (a branch, tag or sha; null = the default branch) points at now. */
    suspend fun commitSha(ref: String?): String {
        val r = ref ?: repoInfo().defaultBranch
        return ((getJson("/repos/$repo/commits/${enc(r)}", "read $r of $repo") as JsonObject)["sha"] as? JsonPrimitive)?.content
            ?: throw GitHubError(0, "GitHub gave no commit for $r of $repo")
    }

    /** One file's bytes at [ref] (a sha, branch or tag) into [dest]: images and other binary files. */
    suspend fun downloadFile(path: String, ref: String, dest: File) {
        val p = path.trim().trimStart('/')
        require(p.isNotEmpty() && ".." !in p.split('/')) { "not a repository path: '$path'" }
        download("$api/repos/$repo/contents/${p.split('/').joinToString("/") { enc(it) }}?ref=${enc(ref)}", "application/vnd.github.raw+json", "download $p at ${ref.take(7)}", dest) { _, _ -> }
    }

    /** The repo's files at [sha] as a .tar.gz (GitHub's tarball, one top folder). */
    suspend fun downloadTarball(sha: String, dest: File) =
        download("$api/repos/$repo/tarball/${enc(sha)}", "application/vnd.github+json", "download $repo at ${sha.take(7)}", dest) { _, _ -> }

    /** Downloads a release asset (the asset API URL with `Accept: application/octet-stream`). */
    suspend fun downloadAsset(apiUrl: String, dest: File, progress: (Long, Long?) -> Unit = { _, _ -> }) =
        download(apiUrl, "application/octet-stream", "download the release asset", dest, progress)

    private suspend fun download(url: String, accept: String, what: String, dest: File, progress: (Long, Long?) -> Unit) {
        require(url.startsWith(api)) { "only $api URLs carry the token" }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            var location: String? = null
            http.prepareGet(url) { auth(accept) }.execute { r ->
                check(r, what)
                if (r.status.value in 300..399) {
                    location = r.headers[HttpHeaders.Location] ?: throw GitHubError(r.status.value, "GitHub redirected $what without a location")
                } else {
                    copy(r, tmp, progress) // some endpoints (and test servers) answer directly
                }
            }
            location?.let { signed ->
                // The signed URL is the credential: no Authorization, no GitHub Accept header.
                http.prepareGet(signed).execute { r ->
                    if (r.status.value !in 200..299) throw GitHubError(r.status.value, "The download storage answered ${r.status.value} for $what (the link may have expired; try again)")
                    copy(r, tmp, progress)
                }
            }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        } finally {
            tmp.delete()
        }
    }

    private suspend fun copy(r: HttpResponse, out: File, progress: (Long, Long?) -> Unit) = withContext(Dispatchers.IO) {
        val total = r.contentLength()
        val ch = r.bodyAsChannel()
        val buf = ByteArray(64 * 1024)
        var done = 0L
        out.outputStream().use { o ->
            while (true) {
                val n = ch.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) { if (ch.isClosedForRead) break else continue }
                o.write(buf, 0, n)
                done += n
                progress(done, total)
            }
        }
    }

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.long(k: String): Long = (this[k] as? JsonPrimitive)?.longOrNull ?: 0
    private fun JsonObject.obj(k: String): JsonObject = this[k] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.toRun() = WorkflowRun(
        long("id"), str("name"), str("path"), str("head_branch"), str("head_sha"), str("status"),
        str("conclusion").ifEmpty { null }, str("event"), long("run_number"), str("created_at"), str("html_url"),
    )

    private fun JsonObject.toArtifact(): Artifact {
        val run = obj("workflow_run")
        return Artifact(
            long("id"), str("name"), long("size_in_bytes"), bool("expired"), str("created_at").ifEmpty { null }, str("expires_at").ifEmpty { null },
            (run["id"] as? JsonPrimitive)?.longOrNull, run.str("head_branch").ifEmpty { null }, run.str("head_sha").ifEmpty { null },
        )
    }

    private fun JsonObject.toRelease() = Release(
        str("tag_name"), str("name"), str("published_at").ifEmpty { null }, bool("prerelease"),
        (this["assets"] as? JsonArray).orEmpty().map { a -> (a as JsonObject).let { ReleaseAsset(it.long("id"), it.str("name"), it.long("size"), it.str("url")) } },
    )

    companion object {
        val TOKEN_PAGE: String get() = no.agens.testlab.services.diag.Diagnostics.TOKEN_PAGE

        /** The app's client: redirects handled by hand (see [download]), long reads for 100+ MB builds. */
        fun httpClient(): HttpClient = HttpClient(CIO) {
            osTrust()
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) { connectTimeoutMillis = 10_000; socketTimeoutMillis = 60_000 }
        }
    }
}
