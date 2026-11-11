package no.agens.testlab.services.tools

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.agens.testlab.device.HostOs
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Downloads Google's Android SDK Platform-Tools (adb) into the app's tools folder, for people
 * without Android Studio. Only after the user has seen and accepted Google's terms: the app
 * does not bundle adb, because redistributing it is not clearly allowed.
 */
class PlatformTools(
    private val http: HttpClient,
    /** The folder that gets `platform-tools/` (ToolLocator's appToolsDir). */
    private val toolsDir: File,
    private val os: HostOs = HostOs.current(),
    private val baseUrl: String = "https://dl.google.com/android/repository",
) {
    val url: String get() = "$baseUrl/platform-tools-latest-${when (os) { HostOs.MAC -> "darwin"; HostOs.WINDOWS -> "windows"; HostOs.LINUX -> "linux" }}.zip"
    val adb: File get() = File(toolsDir, "platform-tools/" + if (os == HostOs.WINDOWS) "adb.exe" else "adb")

    class Failed(message: String) : RuntimeException(message)

    /** Downloads, unpacks atomically and checks that adb runs; returns adb's version line. */
    suspend fun install(acceptedTerms: Boolean, progress: (Long, Long?) -> Unit = { _, _ -> }): String = withContext(Dispatchers.IO) {
        if (!acceptedTerms) throw Failed("Accept the Android SDK terms first ($TERMS)")
        toolsDir.mkdirs()
        val zip = File(toolsDir, "platform-tools.zip.part")
        val staging = File(toolsDir, "platform-tools.new")
        try {
            http.prepareGet(url).execute { r ->
                if (r.status.value !in 200..299) throw Failed("dl.google.com answered ${r.status.value} for $url")
                val total = r.contentLength()
                val ch = r.bodyAsChannel()
                val buf = ByteArray(64 * 1024)
                var done = 0L
                zip.outputStream().use { o ->
                    while (true) {
                        val n = ch.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        if (n == 0) { if (ch.isClosedForRead) break else continue }
                        o.write(buf, 0, n); done += n; progress(done, total)
                    }
                }
            }
            staging.deleteRecursively()
            unzip(zip, staging)
            val unpacked = File(staging, "platform-tools")
            val exe = File(unpacked, adb.name)
            if (!exe.isFile) throw Failed("the download has no platform-tools/${adb.name}")
            if (os != HostOs.WINDOWS) unpacked.listFiles().orEmpty().filter { it.isFile && '.' !in it.name }.forEach { it.setExecutable(true, false) }
            val version = runVersion(exe)
            // Swap in: an adb server started from the old copy keeps running, which is fine.
            val target = File(toolsDir, "platform-tools")
            val old = File(toolsDir, "platform-tools.old")
            old.deleteRecursively()
            if (target.exists() && !target.renameTo(old)) target.deleteRecursively()
            if (!unpacked.renameTo(target)) { unpacked.copyRecursively(target, overwrite = true); if (os != HostOs.WINDOWS) target.listFiles().orEmpty().filter { '.' !in it.name }.forEach { it.setExecutable(true, false) } }
            old.deleteRecursively()
            version
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
    }

    private fun unzip(zip: File, into: File) {
        val root = into.canonicalFile
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val out = File(root, e.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw Failed("unsafe path in the download: ${e.name}")
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile.mkdirs()
                z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
            }
        }
    }

    private fun runVersion(exe: File): String {
        // Output to a file, not a pipe (see AdbCli): `adb version` starts no server, but stay consistent.
        val out = File.createTempFile("tl-adb", ".out")
        try {
            val p = ProcessBuilder(exe.path, "version").redirectErrorStream(true).redirectOutput(out).start()
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); throw Failed("adb from the download did not answer") }
            val text = out.readText().trim()
            if (p.exitValue() != 0) throw Failed("adb from the download failed (exit ${p.exitValue()}): ${text.take(200)}")
            return text.lineSequence().firstOrNull { it.isNotBlank() } ?: "adb"
        } finally {
            out.delete()
        }
    }

    companion object {
        const val TERMS = "https://developer.android.com/studio/terms"
        const val PAGE = "https://developer.android.com/tools/releases/platform-tools"
    }
}
