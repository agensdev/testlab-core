package no.agens.testlab.services.testrepos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.services.github.GitHub
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.SecretStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The profile's helper repos ([Profile.testRepos]): a read-only copy of each in the cache, fetched
 * as GitHub's tarball of the branch's newest commit with the user's own token, and fetched again
 * when the branch has moved (checked at most every [maxAgeMs]). Their skills join the playbooks;
 * their files can be listed, read and searched; a `maestro` repo's flows are indexed with their
 * test-case ids. Nothing is ever written to those repos.
 */
class TestRepos(private val rt: LabRuntime, private val maxAgeMs: Long = 3_600_000) {
    @Serializable
    data class State(val repo: String, val ref: String? = null, val sha: String, val syncedAt: Long, val files: Int)

    data class Synced(val repo: Profile.TestRepo, val state: State?, val changed: Boolean, val problem: String? = null)

    data class Flow(val path: String, val name: String?, val testCase: String?, val tags: List<String>)

    data class RepoSkill(val name: String, val description: String, val text: String)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** The active profile's test repos. */
    fun configured(): List<Profile.TestRepo> = rt.profile.testRepos

    fun find(id: String): Profile.TestRepo = configured().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("no test repo '$id' in the profile «${rt.profile.name}»" + configured().map { it.id }.let { if (it.isEmpty()) " (it has none: add testRepos to profile.json)" else ": it has ${it.joinToString()}" })

    private fun base(t: Profile.TestRepo) = File(rt.dirs.cache, "test-repos/${rt.profile.id}/${t.id}")
    /** Where [t]'s files are (exists once it was fetched). */
    fun root(t: Profile.TestRepo) = File(base(t), "files")
    private fun stateFile(t: Profile.TestRepo) = File(base(t), "state.json")
    fun state(t: Profile.TestRepo): State? = stateFile(t).takeIf { it.isFile }?.let { runCatching { json.decodeFromString<State>(it.readText()) }.getOrNull() }
        ?.takeIf { it.repo == t.repo && it.ref == t.ref && root(t).isDirectory }

    /**
     * Brings [t] up to date: nothing when it was checked within [maxAgeMs] (unless [force]); else the
     * branch's newest commit, downloaded only when it differs from the copy. A failure keeps the copy
     * there is, and says why.
     */
    suspend fun sync(t: Profile.TestRepo, force: Boolean = false): Synced = lock.withLock {
        val now = System.currentTimeMillis()
        val have = state(t)
        if (!force && have != null && now - have.syncedAt < maxAgeMs) return@withLock Synced(t, have, false)
        try {
            rt.rules.checkRepo(t.repo)
            val gh = GitHub(rt.builds.httpClient, { SecretStore.scoped(rt.secrets, SecretStore.GITHUB_TOKEN, t.repo.substringBefore('/')) }, t.repo, rt.githubApi())
            val sha = gh.commitSha(t.ref)
            if (have != null && have.sha == sha) return@withLock Synced(t, have.copy(syncedAt = now).also { save(t, it) }, false)
            val files = withContext(Dispatchers.IO) { fetch(gh, t, sha) }
            Synced(t, State(t.repo, t.ref, sha, now, files).also { save(t, it) }, true)
        } catch (e: Exception) {
            Synced(t, have, false, (e.message ?: e::class.simpleName.orEmpty()) + if (have != null) " (using the copy from ${java.time.Instant.ofEpochMilli(have.syncedAt)})" else "")
        }
    }

    suspend fun syncAll(force: Boolean = false): List<Synced> = configured().map { sync(it, force) }

    private fun save(t: Profile.TestRepo, s: State) {
        stateFile(t).parentFile.mkdirs()
        stateFile(t).writeText(json.encodeToString(s))
    }

    /** Downloads [sha]'s tarball, unpacks it next to the copy and swaps it in; returns the number of files. */
    private suspend fun fetch(gh: GitHub, t: Profile.TestRepo, sha: String): Int {
        val b = base(t).apply { mkdirs() }
        val tar = File(b, "download.tar.gz")
        val staging = File(b, "staging").apply { deleteRecursively(); mkdirs() }
        try {
            gh.downloadTarball(sha, tar)
            val p = ProcessBuilder("tar", "xzf", tar.path, "-C", staging.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(300, TimeUnit.SECONDS) || p.exitValue() != 0) throw IllegalStateException("unpacking ${t.repo} failed: ${out.trim().take(200)}")
            val top = staging.listFiles()?.singleOrNull { it.isDirectory } ?: throw IllegalStateException("GitHub's tarball of ${t.repo} has no single folder")
            val dest = root(t)
            val old = File(b, "old")
            old.deleteRecursively()
            if (dest.exists() && !dest.renameTo(old)) throw IllegalStateException("could not move the old copy of ${t.repo} aside")
            if (!top.renameTo(dest)) { old.renameTo(dest); throw IllegalStateException("could not put the new copy of ${t.repo} in place") }
            old.deleteRecursively()
            return dest.walkTopDown().count { it.isFile }
        } finally {
            tar.delete()
            staging.deleteRecursively()
        }
    }

    // --- reading ---

    /** A path inside [t]'s copy, refused when it leaves it. */
    fun resolve(t: Profile.TestRepo, path: String): File {
        val r = root(t)
        if (!r.isDirectory) throw IllegalStateException("${t.id} (${t.repo}) isn't fetched yet: lab_test_repos(sync=true), or GitHub said why it can't be")
        val f = File(r, path.trim().removePrefix("/")).canonicalFile
        if (f != r.canonicalFile && !f.path.startsWith(r.canonicalPath + File.separator)) throw IllegalArgumentException("$path is outside ${t.id}")
        return f
    }

    /** Files under [path] (relative to the repo), optionally only those matching [glob] (`*.md`, `tests/**/login*.yaml`). */
    fun files(t: Profile.TestRepo, path: String = "", glob: String? = null, limit: Int = 500): List<String> {
        val r = root(t)
        val from = resolve(t, path)
        val matcher = glob?.takeIf { it.isNotBlank() }?.let { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it") }
        return from.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile }
            .map { it.relativeTo(r).invariantSeparatorsPath }
            .filter { rel -> matcher == null || matcher.matches(java.nio.file.Paths.get(rel)) || matcher.matches(java.nio.file.Paths.get(rel.substringAfterLast('/'))) }
            .sorted().take(limit).toList()
    }

    /** [lines] lines of a text file from line [from] (1-based). */
    fun read(t: Profile.TestRepo, path: String, from: Int = 1, lines: Int = 400): String {
        val f = resolve(t, path)
        if (!f.isFile) throw IllegalArgumentException("no file $path in ${t.id}")
        if (f.length() > 4_000_000) throw IllegalArgumentException("$path is ${f.length() / 1_000_000} MB: too big to read here")
        val all = f.readLines()
        val start = (from - 1).coerceIn(0, all.size)
        return all.drop(start).take(lines).joinToString("\n") + if (start + lines < all.size) "\n… ${all.size - start - lines} more line(s): read on with from=${start + lines + 1}" else ""
    }

    data class Hit(val repo: String, val path: String, val line: Int, val text: String)

    /** Lines matching [regex] (case-insensitive) in the text files of [repos], optionally only files matching [glob]. */
    fun search(repos: List<Profile.TestRepo>, regex: String, glob: String? = null, limit: Int = 100): List<Hit> {
        val re = runCatching { Regex(regex, RegexOption.IGNORE_CASE) }.getOrElse { throw IllegalArgumentException("not a regex: ${it.message}") }
        val out = mutableListOf<Hit>()
        for (t in repos) {
            if (!root(t).isDirectory) continue
            for (rel in files(t, "", glob, limit = 20_000)) {
                val f = File(root(t), rel)
                if (f.length() > 2_000_000 || BINARY.containsMatchIn(rel)) continue
                f.useLines { ls -> ls.forEachIndexed { i, l -> if (out.size < limit && re.containsMatchIn(l)) out += Hit(t.id, rel, i + 1, l.trim().take(240)) } }
                if (out.size >= limit) return out
            }
        }
        return out
    }

    /** The skills in [t]: `<dir>/<name>/SKILL.md` under each skills folder, and SKILL.md files named directly. */
    fun skills(t: Profile.TestRepo): List<RepoSkill> {
        val r = root(t)
        if (!r.isDirectory) return emptyList()
        val found = linkedMapOf<String, File>()
        for (p in t.skills) {
            val f = runCatching { resolve(t, p) }.getOrNull() ?: continue
            when {
                f.isFile -> found[if (f.parentFile.canonicalFile == r.canonicalFile) t.id else "${t.id}:${f.parentFile.name}"] = f
                f.isDirectory -> f.listFiles()?.filter { File(it, "SKILL.md").isFile }?.sortedBy { it.name }?.forEach { d -> found["${t.id}:${d.name}"] = File(d, "SKILL.md") }
            }
        }
        return found.map { (name, f) ->
            val text = f.readText()
            val desc = frontmatter(text)["description"]?.takeIf { it.isNotBlank() }
                ?: text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                ?: "a skill in ${t.repo}"
            RepoSkill(name, "$desc (from ${t.repo})", text)
        }
    }

    /** A `maestro` repo's flows: every .yaml with `appId` in its header, with its name, test-case id and tags. */
    fun flows(t: Profile.TestRepo): List<Flow> {
        val r = root(t)
        if (!r.isDirectory) return emptyList()
        return files(t, "", "*.yaml", limit = 50_000).mapNotNull { rel ->
            val head = File(r, rel).useLines { ls -> ls.takeWhile { it.trim() != "---" }.take(80).toList() }
            if (head.none { it.startsWith("appId:") }) return@mapNotNull null
            fun value(key: String) = head.firstNotNullOfOrNull { l -> Regex("^\\s*$key:\\s*\"?([^\"#]*)\"?").find(l)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } }
            val tags = head.dropWhile { !it.startsWith("tags:") }.drop(1).takeWhile { it.trimStart().startsWith("- ") }.map { it.trim().removePrefix("- ").trim() } +
                (head.firstOrNull { it.startsWith("tags:") && "[" in it }?.substringAfter("[")?.substringBefore("]")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList())
            Flow(rel, value("name"), value("testinyId") ?: value("testCaseId"), tags)
        }
    }

    companion object {
        private val BINARY = Regex("(?i)\\.(png|jpe?g|gif|webp|mp4|mov|zip|gz|jar|apk|ipa|pdf|ttf|otf|ico)$")

        fun frontmatter(text: String): Map<String, String> {
            if (!text.startsWith("---")) return emptyMap()
            val end = text.indexOf("\n---", 3).takeIf { it >= 0 } ?: return emptyMap()
            return text.substring(3, end).lines().mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
        }
    }
}
