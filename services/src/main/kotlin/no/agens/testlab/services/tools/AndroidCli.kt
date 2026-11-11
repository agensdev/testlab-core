package no.agens.testlab.services.tools

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.agens.testlab.device.HostOs
import no.agens.testlab.device.ToolLocator
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Google's Android CLI (`android`, developer.android.com/tools/agents): emulators that set
 * themselves up, Crashlytics, and Compose previews rendered without a device. Optional: what
 * needs it says how to get it. [install] does what Google's install.sh does, without editing the
 * shell profile: the binary into ~/.local/bin, where Testlab and the user's own agent both find it.
 */
class AndroidCli(
    private val http: HttpClient,
    /** The SDK to use (`--sdk`): the one Testlab's adb comes from, when it is a full SDK; null = the CLI's own choice. */
    private val sdk: () -> File? = { null },
    private val os: HostOs = HostOs.current(),
    private val home: File = File(System.getProperty("user.home")),
    private val locate: () -> File? = { ToolLocator(os = os, home = home).findAndroidCli()?.path },
    private val baseUrl: String = "https://dl.google.com/android/cli/latest",
) {
    data class Result(val exit: Int, val out: String, val err: String) {
        val ok: Boolean get() = exit == 0
        /** What went wrong, for a message: the CLI's own `Error:` line, else the end of its output. */
        val problem: String get() = (err.lines() + out.lines()).map { it.trim() }.lastOrNull { it.startsWith("Error:") }?.removePrefix("Error:")?.trim()
            ?: (err.ifBlank { out }).trim().lines().takeLast(3).joinToString(" ").take(300).ifBlank { "exit $exit" }
    }

    class Missing(message: String) : IllegalStateException(message)
    class Failed(message: String) : RuntimeException(message)

    fun binary(): File? = locate()

    /** The SDK passed to every command (null = the CLI picks its own). */
    fun sdkInUse(): File? = sdk()

    val installed: Boolean get() = binary() != null

    /** Runs `android <args>` (no usage metrics sent); stdout and stderr go through files, so a child it leaves running (an emulator) can't hold a pipe open. */
    fun run(args: List<String>, timeoutSec: Long = 120, cwd: File? = null, env: Map<String, String> = emptyMap()): Result {
        val bin = binary() ?: throw Missing(MISSING)
        val out = File.createTempFile("tl-android", ".out")
        val err = File.createTempFile("tl-android", ".err")
        try {
            val cmd = listOf(bin.path, "--no-metrics") + (sdk()?.let { listOf("--sdk=${it.path}") } ?: emptyList()) + args
            val pb = ProcessBuilder(cmd).redirectOutput(out).redirectError(err).redirectInput(ProcessBuilder.Redirect.from(nullInput()))
            cwd?.let { pb.directory(it) }
            pb.environment().putAll(env)
            val p = pb.start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.descendants().forEach { it.destroy() }; p.destroyForcibly()
                throw Failed("`android ${args.joinToString(" ")}` did not finish in $timeoutSec s")
            }
            return Result(p.exitValue(), out.readText(), err.readText())
        } finally {
            out.delete(); err.delete()
        }
    }

    /** Runs `android <args>` in this terminal (`auth login` opens a browser and may ask things); returns its exit code. */
    fun interactive(args: List<String>): Int {
        val bin = binary() ?: throw Missing(MISSING)
        return ProcessBuilder(listOf(bin.path, "--no-metrics") + args).inheritIO().start().waitFor()
    }

    private fun nullInput() = File(if (os == HostOs.WINDOWS) "NUL" else "/dev/null")

    /** `android info version`; null when it isn't installed or doesn't answer. */
    fun version(): String? = runCatching { run(listOf("info", "version"), 60).takeIf { it.ok }?.out?.trim()?.lines()?.lastOrNull()?.trim() }.getOrNull()

    /** The Google account it is signed in with (`android auth status`); null = not signed in. */
    fun account(): String? = runCatching {
        val r = run(listOf("auth", "status"), 30)
        val text = (r.out + "\n" + r.err).trim()
        if (!r.ok || NOT_LOGGED_IN.containsMatchIn(text)) null else text.lines().map { it.trim() }.firstOrNull { '@' in it }?.let { EMAIL.find(it)?.value ?: it } ?: text.lines().firstOrNull()?.trim()
    }.getOrNull()

    /** The download for this computer; null on Windows (winget installs it there). */
    val downloadUrl: String? get() = platform()?.let { "$baseUrl/$it/android" }

    private fun platform(): String? {
        val arm = System.getProperty("os.arch").orEmpty().let { it == "aarch64" || it == "arm64" }
        return when (os) {
            HostOs.MAC -> if (arm) "darwin_arm64" else "darwin_x86_64"
            HostOs.LINUX -> if (arm) "linux_arm64" else "linux_x86_64"
            HostOs.WINDOWS -> null
        }
    }

    /** Downloads the CLI into ~/.local/bin/android and lets it unpack itself; returns its version. */
    suspend fun install(progress: (Long, Long?) -> Unit = { _, _ -> }): String = withContext(Dispatchers.IO) {
        val url = downloadUrl ?: throw Failed("On Windows install it with: winget install -e --id Google.AndroidCLI")
        val dest = File(home, ".local/bin/android")
        dest.parentFile.mkdirs()
        val part = File(dest.parentFile, "android.part")
        try {
            http.prepareGet(url).execute { r ->
                if (r.status.value !in 200..299) throw Failed("dl.google.com answered ${r.status.value} for $url")
                val total = r.contentLength()
                val ch = r.bodyAsChannel()
                val buf = ByteArray(64 * 1024)
                var done = 0L
                part.outputStream().use { o ->
                    while (true) {
                        val n = ch.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        if (n == 0) { if (ch.isClosedForRead) break else continue }
                        o.write(buf, 0, n); done += n; progress(done, total)
                    }
                }
            }
            part.setExecutable(true, false)
            if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); dest.setExecutable(true, false) }
        } finally {
            part.delete()
        }
        // The first run unpacks the rest (as Google's installer does with ANDROID_CLI_FRESH_INSTALL).
        val first = ProcessBuilder(dest.path, "--no-metrics", "info", "version").redirectErrorStream(true).apply { environment()["ANDROID_CLI_FRESH_INSTALL"] = "1" }.start()
        val text = first.inputStream.bufferedReader().readText()
        if (!first.waitFor(600, TimeUnit.SECONDS) || first.exitValue() != 0) throw Failed("the Android CLI didn't start after the download: ${text.trim().takeLast(300)}")
        text.trim().lines().last().trim()
    }

    companion object {
        const val PAGE = "https://developer.android.com/tools/agents"
        const val MISSING = "Google's Android CLI isn't installed: `testlab android-cli install` gets it (or see $PAGE)"
        private val NOT_LOGGED_IN = Regex("(?i)not (logged|signed) in")
        private val EMAIL = Regex("[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}", RegexOption.IGNORE_CASE)
    }
}
