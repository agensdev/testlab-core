package no.agens.testlab.services.update

import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import no.agens.testlab.services.github.GitHub
import no.agens.testlab.services.runtime.LabRuntime
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Installs a newer build over this one: the CLI download (`testlab-cli-<v>-<os>-<arch>.tar.gz`). A
 * build comes from the newest GitHub release (no token needed when the repo is public), the newest `dist-<os>` artifact of a package/release
 * run (`ci`), or files the person downloaded. It is unpacked next to the old one and run once
 * (`--version`) before it is swapped in; the old one is kept in `<data>/previous/` for [rollback]. Paths stay the same, so the
 * background service, Claude's MCP config and the PATH symlink keep working.
 */
class Upgrader(private val rt: LabRuntime, private val os: HostOs = HostOs.current()) {
    class Failed(message: String) : RuntimeException(message)

    /** What a source provides, unpacked to local files. */
    data class Build(val label: String, val version: String?, val cli: File)

    /** The `os-arch` suffix of this machine's files, as build.yml names them. */
    val platform: String = when (os) {
        HostOs.MAC -> "macos-" + if (System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }) "arm64" else "x64"
        HostOs.LINUX -> "linux-x64"
        HostOs.WINDOWS -> "windows-x64"
    }
    /** build.yml's artifact for this OS: dist-macos, dist-linux, dist-windows. */
    private val distName = "dist-" + platform.substringBefore('-')

    private val work = File(rt.dirs.cache, "upgrade")
    val previousDir = File(rt.dirs.data, "previous")

    /**
     * ${AppIdentity.NAME}'s own repo, with the token for *its* owner (`github.token@<owner>`, else the plain one):
     * the active profile's token may belong to a work account that can't see it.
     */
    private fun github() = GitHub(rt.builds.httpClient, { updateToken(rt) }, AppIdentity.REPO, UPDATE_API)

    /**
     * Reads releases: with the token when there is one, and without when there is none or GitHub
     * refuses it (expired, or an account that can't see the repo). A public repo's releases need no
     * token; a private one's answer without it is the same "not found" the token got.
     */
    private suspend fun <T> releases(block: suspend (GitHub) -> T): T {
        val open = GitHub(rt.builds.httpClient, { null }, AppIdentity.REPO, UPDATE_API, anonymous = true)
        if (runCatching { updateToken(rt) }.getOrNull().isNullOrBlank()) return block(open)
        return try {
            block(github())
        } catch (e: no.agens.testlab.services.github.GitHubError) {
            if (e.status !in setOf(401, 403, 404, 429)) throw e
            try { block(open) } catch (_: no.agens.testlab.services.github.GitHubError) { throw e }
        }
    }

    companion object {
        /** Releases and package builds live on github.com, whatever GitHub the profile's repo is on. */
        const val UPDATE_API = "https://api.github.com"

        fun updateToken(rt: LabRuntime): String? =
            no.agens.testlab.services.secrets.SecretStore.scoped(rt.secrets, no.agens.testlab.services.secrets.SecretStore.GITHUB_TOKEN, AppIdentity.REPO.substringBefore('/'))
    }

    // --- where a build comes from ---

    /** The newest release with this platform's files, when it is newer than [current]; null when there is none. */
    suspend fun latestRelease(current: String = AppIdentity.VERSION, progress: (String) -> Unit = {}): Build? = releases { gh ->
        val r = gh.releases().filter { it.tag.removePrefix("v").matches(Regex("\\d+\\.\\d+\\.\\d+")) }
            .reduceOrNull { a, b -> if (UpdateCheck.newer(b.tag, a.tag)) b else a } ?: return@releases null
        val v = r.tag.removePrefix("v")
        if (!UpdateCheck.newer(v, current)) return@releases null
        val dir = fresh("release-$v")
        suspend fun pick(match: (String) -> Boolean) = r.assets.firstOrNull { match(it.name) }?.let { a ->
            File(dir, a.name).also { f -> gh.downloadAsset(a.apiUrl, f) { done, total -> progress(pct("${a.name}", done, total)) } }
        }
        val cli = pick { it.contains("-cli-") && it.contains(platform) } ?: throw Failed("release ${r.tag} has no CLI for $platform")
        Build("release ${r.tag}", v, cli)
    }

    /** The newest unexpired `dist-<os>` artifact of a package or release run. */
    suspend fun latestCi(progress: (String) -> Unit = {}): Build {
        val gh = github()
        val a = gh.artifactsPage(1, 20, distName).first.firstOrNull { !it.expired && it.name == distName }
            ?: throw Failed("no $distName artifact in ${AppIdentity.REPO}: run Actions → package first (artifacts expire after 14 days)")
        val zip = File(fresh("ci-${a.id}"), "$distName.zip")
        gh.downloadArtifact(a.id, zip) { done, total -> progress(pct("$distName (${a.createdAt ?: "artifact ${a.id}"})", done, total)) }
        return fromPath(zip).copy(label = "CI artifact $distName from ${a.createdAt ?: a.id}")
    }

    /** A downloaded folder (the unzipped artifact), the artifact's zip, or a CLI tar.gz. */
    fun fromPath(p: File): Build {
        if (!p.exists()) throw Failed("not found: ${p.path}")
        val dir = when {
            p.isDirectory -> p
            p.name.endsWith(".zip") -> fresh("zip").also { unzip(p, it) }
            else -> p.parentFile
        }
        val files = if (p.isFile && !p.name.endsWith(".zip")) listOf(p) else dir.walkTopDown().maxDepth(3).filter { it.isFile }.toList()
        val cli = files.firstOrNull { it.name.contains("-cli-") && it.name.contains(platform) && it.name.endsWith(".tar.gz") }
            ?: throw Failed("no $platform CLI (.tar.gz) in ${p.path}")
        val v = cli.name.let { Regex("-(\\d+\\.\\d+\\.\\d+)-").find(it)?.groupValues?.get(1) }
        return Build(p.path, v, cli)
    }

    // --- installing ---

    /** Where the running CLI lives (its `bin/` launcher sets `testlab.home`), else the usual place if it is there. */
    fun cliHome(): File? = System.getProperty("testlab.home")?.let { File(it).canonicalFile }
        ?: File(System.getProperty("user.home"), ".local/opt/${AppIdentity.SLUG}").takeIf { File(it, "bin/${AppIdentity.SLUG}").isFile }

    /** Unpacks [tar] beside [home], checks the new CLI runs, swaps it in; the old one goes to [previousDir]. Returns its version line. */
    fun installCli(tar: File, home: File): String {
        if (os == HostOs.WINDOWS) throw Failed("upgrading on Windows is not supported yet: unzip the new CLI over the old one")
        val staging = File(home.parentFile, ".${home.name}-new-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            run(listOf("tar", "xzf", tar.path, "-C", staging.path), "unpack ${tar.name}")
            val unpacked = staging.listFiles()?.singleOrNull { it.isDirectory } ?: throw Failed("${tar.name} doesn't hold one folder")
            clearQuarantine(unpacked)
            val version = run(listOf(File(unpacked, "bin/${AppIdentity.SLUG}").path, "--version"), "run the new CLI").trim()
            swap(home, unpacked, File(previousDir, "cli"))
            refreshClaudePlugin(home)
            return version
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Puts back what the last upgrade replaced. */
    fun rollback(): List<String> {
        val done = mutableListOf<String>()
        File(previousDir, "cli").takeIf { it.isDirectory }?.let { prev ->
            val home = cliHome() ?: throw Failed("no CLI install found to roll back")
            swap(home, prev, File(previousDir, "cli-rolled-back")); done += "CLI → ${home.path}"
            refreshClaudePlugin(home)?.let { done += it }
        }
        if (done.isEmpty()) throw Failed("nothing to roll back in ${previousDir.path}")
        return done
    }

    /**
     * The Claude Code plugin from the CLI in [home] (the one just installed): `claude-plugin
     * --if-installed`, so it follows every upgrade and rollback, but only where it was installed.
     * Run by that CLI, not this JVM, whose classes are the old version's. Never fails the upgrade.
     */
    fun refreshClaudePlugin(home: File): String? = runCatching {
        val launcher = File(home, "bin/${AppIdentity.SLUG}")
        if (!launcher.canExecute()) return null
        // Every space's copy (each in its own Claude profile, see `space claude`).
        no.agens.testlab.services.Spaces.list().mapNotNull { space ->
            val p = ProcessBuilder(listOf(launcher.path) + no.agens.testlab.services.Spaces.args(space) + listOf("claude-plugin", "--if-installed")).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return@mapNotNull "Claude Code plugin ($space): timed out updating; run ${AppIdentity.SLUG} claude-plugin" }
            out.lineSequence().firstOrNull { it.startsWith("Claude Code plugin") }?.let { if (space == no.agens.testlab.services.Spaces.DEFAULT) it else it.replaceFirst("Claude Code plugin", "Claude Code plugin ($space)") }
        }.joinToString("; ").ifEmpty { null }
    }.getOrNull()

    // --- plumbing ---

    /** [current] → [previous] (replacing an older one), [replacement] → [current]; put back on failure. */
    private fun swap(current: File, replacement: File, previous: File) {
        previous.parentFile.mkdirs()
        previous.deleteRecursively()
        val hadCurrent = current.exists()
        if (hadCurrent && !moveDir(current, previous)) throw Failed("could not move ${current.path} aside")
        if (!moveDir(replacement, current)) {
            if (hadCurrent) moveDir(previous, current)
            throw Failed("could not put the new files at ${current.path}; the old ones are back")
        }
    }

    /** A rename, else a copy (another volume). */
    private fun moveDir(from: File, to: File): Boolean = from.renameTo(to) ||
        runCatching { run(listOf("cp", "-R", from.path, to.path), "copy"); from.deleteRecursively(); true }.getOrDefault(false)

    private fun clearQuarantine(f: File) {
        if (os == HostOs.MAC) runCatching { run(listOf("xattr", "-dr", "com.apple.quarantine", f.path), "clear the quarantine") }
    }

    private fun fresh(name: String) = File(work, name).apply { deleteRecursively(); mkdirs() }

    private fun unzip(zip: File, to: File) {
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            generateSequence { z.nextEntry }.forEach { e ->
                val f = File(to, e.name).canonicalFile
                require(f.path.startsWith(to.canonicalPath + File.separator)) { "bad zip entry ${e.name}" }
                if (e.isDirectory) f.mkdirs() else { f.parentFile.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
    }

    private fun run(cmd: List<String>, what: String, timeoutS: Long = 300): String {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroyForcibly(); throw Failed("$what timed out") }
        if (p.exitValue() != 0) throw Failed("$what failed: ${out.trim().lines().takeLast(5).joinToString(" / ")}")
        return out
    }


    private fun pct(what: String, done: Long, total: Long?) = "$what: " + (total?.takeIf { it > 0 }?.let { "${done * 100 / it} %" } ?: "${done / 1_000_000} MB")
}
