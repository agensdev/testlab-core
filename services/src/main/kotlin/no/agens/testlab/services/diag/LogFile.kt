package no.agens.testlab.services.diag

import java.io.File
import java.io.OutputStream
import java.io.PrintStream

/**
 * Keeps a copy of stderr (where the app logs) in `<data>/logs/app.log`, rotated at [maxBytes]
 * to app.log.1, so the diagnostics zip has the last session's log even when the app was
 * started from the Dock or the Start menu and nobody saw stderr.
 */
class LogFile(private val dir: File, private val maxBytes: Long = 2_000_000) : OutputStream() {
    private val file = File(dir, "app.log")
    private var out = open()

    private fun open(): OutputStream? = runCatching { dir.mkdirs(); java.io.FileOutputStream(file, true).buffered() }.getOrNull()

    @Synchronized override fun write(b: Int) { out?.write(b); rotateIfNeeded() }
    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) { out?.write(b, off, len); if (b.indexOf('\n'.code.toByte()) >= 0) out?.flush(); rotateIfNeeded() }
    @Synchronized override fun flush() { out?.flush() }

    private fun rotateIfNeeded() {
        if (file.length() < maxBytes) return
        runCatching {
            out?.close()
            File(dir, "app.log.1").delete()
            file.renameTo(File(dir, "app.log.1"))
            out = open()
        }
    }

    companion object {
        /**
         * stderr goes to the console as before and to the log file; with [stdoutToo] stdout as well
         * (never in stdio MCP mode, where stdout is the protocol).
         */
        fun teeStderr(dir: File, maxBytes: Long = 2_000_000, stdoutToo: Boolean = false) {
            val log = LogFile(dir, maxBytes)
            System.setErr(tee(System.err, log))
            if (stdoutToo) System.setOut(tee(System.out, log))
        }

        private fun tee(console: PrintStream, log: LogFile) = PrintStream(object : OutputStream() {
            override fun write(b: Int) { console.write(b); log.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { console.write(b, off, len); log.write(b, off, len) }
            override fun flush() { console.flush(); log.flush() }
        }, true, Charsets.UTF_8)
    }
}
