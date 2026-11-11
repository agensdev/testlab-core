package no.agens.testlab.services.files

import no.agens.testlab.device.HostOs
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reading files that macOS may have moved to iCloud. With «Desktop & Documents» in iCloud and
 * «Optimise Mac Storage» on, older files in ~/Documents (the default workspace, where reports
 * live) become placeholders, and reading one fails with EDEADLK, «Resource deadlock avoided».
 * Here such a read asks macOS to fetch the file (`brctl download`) and tries again.
 */
object CloudFiles {
    /** The error a placeholder read gives (EDEADLK). */
    fun isPlaceholder(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it is IOException && (it.message.orEmpty().contains("Resource deadlock avoided") || it.message.orEmpty().contains("EDEADLK")) }

    /** Asks macOS to bring [file] back from iCloud; elsewhere nothing to do. */
    var fetch: (File) -> Unit = { f ->
        if (HostOs.current() == HostOs.MAC) runCatching {
            ProcessBuilder("/usr/bin/brctl", "download", f.absolutePath).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(60, TimeUnit.SECONDS)
        }
    }

    /** Runs [read] on [file]; a placeholder is fetched and the read tried again (up to about [seconds]). */
    fun <T> read(file: File, seconds: Int = 30, read: (File) -> T): T {
        var fetched = false
        val until = System.currentTimeMillis() + seconds * 1000L
        while (true) {
            try {
                return read(file)
            } catch (e: Exception) {
                if (!isPlaceholder(e)) throw e
                if (!fetched) { fetch(file); fetched = true }
                if (System.currentTimeMillis() > until) throw IOException("${file.path} is in iCloud and didn't download in $seconds s (Finder: right-click → Download Now, or set the workspace to a folder outside iCloud: testlab config set workspace ~/Testlab)", e)
                Thread.sleep(500)
            }
        }
    }
}

fun File.readTextCloud(): String = CloudFiles.read(this) { it.readText() }
fun File.readBytesCloud(): ByteArray = CloudFiles.read(this) { it.readBytes() }
fun File.readLinesCloud(): List<String> = CloudFiles.read(this) { it.readLines() }
