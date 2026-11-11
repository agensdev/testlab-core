package no.agens.testlab.services.logs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.agens.testlab.core.Platform
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.runtime.shQuote
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Device logs as evidence: `adb logcat` (or an iOS Simulator's `log stream`) written to a file from
 * the moment a capture starts, with presets for the app's own lines, crashes and analytics, and the
 * analytics events and crashes read back out of it. Start the capture BEFORE reproducing.
 */
class LogCapture(private val rt: LabRuntime) {
    data class Capture(val serial: String, val file: File, val startedMs: Long, val preset: String, val pkg: String?, val firebase: Boolean, val process: Process)

    data class Crash(val time: String, val process: String, val first: String, val recorder: Boolean)

    data class Event(val time: String, val name: String, val params: String)

    data class Stopped(val file: File, val lines: Int, val crashes: List<Crash>, val events: Int, val seconds: Double)

    private val running = java.util.concurrent.ConcurrentHashMap<String, Capture>()
    private val last = java.util.concurrent.ConcurrentHashMap<String, File>()
    private val dir: File get() = File(rt.dirs.data, "logs/device").apply { mkdirs() }

    fun status(serial: String? = null): List<Capture> = running.values.filter { serial == null || it.serial == serial }.filter { it.process.isAlive }

    /**
     * [preset]: all | app (the app's own process: [pkg]) | crash | analytics; [tags] adds `TAG:LEVEL`
     * filters (with them, all = only those tags). [firebase] turns on Firebase Analytics debug logging
     * for [pkg] until the capture stops.
     */
    suspend fun start(serial: String, preset: String, pkg: String?, tags: List<String>, firebase: Boolean, iosProcess: String?): Capture = withContext(Dispatchers.IO) {
        running[serial]?.takeIf { it.process.isAlive }?.let { return@withContext it }
        // `simctl spawn … log stream` knows simulators only ("Invalid device" for a real one), and Apple
        // has no streaming device log through devicectl.
        if (rt.platformOf(serial) == Platform.IOS && rt.realIos(serial)) throw no.agens.testlab.services.runtime.NotOnPlatform(
            "device logs from a real iPhone/iPad aren't supported: read them in Console.app on the Mac (the device in the sidebar, start streaming, filter by the app), " +
                "or `idevicesyslog -u $serial` (libimobiledevice); crashes come with lab_ios_crashes. A simulator's logs work.")
        rt.leases.ensure(serial)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(dir, "${serial.replace(Regex("[^A-Za-z0-9._-]"), "_")}-$stamp-$preset.log")
        val cmd = if (rt.platformOf(serial) == Platform.IOS) iosCommand(serial, preset, iosProcess) else {
            if (firebase) {
                val p = pkg ?: throw IllegalArgumentException("firebase debug needs the app's package")
                rt.adb().shell(serial, "setprop debug.firebase.analytics.app ${shQuote(p)}; setprop log.tag.FA VERBOSE; setprop log.tag.FA-SVC VERBOSE")
            }
            androidCommand(serial, preset, pkg, tags, firebase)
        }
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(file).start()
        Thread.sleep(300)
        if (!proc.isAlive && proc.exitValue() != 0) throw IllegalStateException("the log capture stopped at once: ${file.readText().take(200)}")
        Capture(serial, file, System.currentTimeMillis(), preset, pkg, firebase, proc).also { running[serial] = it }
    }

    private suspend fun androidCommand(serial: String, preset: String, pkg: String?, tags: List<String>, firebase: Boolean): List<String> {
        val adb = rt.adb().adb.path
        val base = listOf(adb, "-s", serial, "logcat", "-v", "epoch", "-T", "1")
        val extra = tags.map { if (':' in it) it else "$it:V" }
        return base + when (preset) {
            "app" -> {
                val p = pkg ?: throw IllegalArgumentException("the app preset needs the app's package")
                val uid = rt.adb().shell(serial, "pm list packages -U ${shQuote(p)}").stdout.lines()
                    .firstNotNullOfOrNull { l -> Regex("package:${Regex.escape(p)} uid:(\\d+)").find(l)?.groupValues?.get(1) }
                    ?: throw IllegalArgumentException("$p is not installed on $serial")
                listOf("--uid=$uid")
            }
            "crash" -> listOf("AndroidRuntime:E", "DEBUG:F", "libc:F") + extra + "*:S"
            "analytics" -> listOf("FA:V", "FA-SVC:V") + extra + "*:S"
            else -> if (extra.isEmpty()) emptyList() else extra + "*:S"
        }.let { if (firebase && preset == "all" && extra.isNotEmpty()) listOf("FA:V", "FA-SVC:V") + it else it }
    }

    private fun iosCommand(udid: String, preset: String, process: String?): List<String> {
        val predicate = when {
            process != null -> "process == \"$process\""
            preset == "crash" -> "eventMessage CONTAINS[c] \"crash\" OR messageType == fault"
            else -> null
        }
        return listOf("xcrun", "simctl", "spawn", udid, "log", "stream", "--style", "compact", "--level", "debug") + (predicate?.let { listOf("--predicate", it) } ?: emptyList())
    }

    /** Stops the capture of [serial]: the file, its size, the crashes and analytics events in it. */
    suspend fun stop(serial: String): Stopped? = withContext(Dispatchers.IO) {
        val c = running.remove(serial) ?: return@withContext null
        c.process.destroy()
        if (!c.process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) c.process.destroyForcibly()
        if (c.firebase) runCatching { rt.adb().shell(serial, "setprop debug.firebase.analytics.app .none.; setprop log.tag.FA ''; setprop log.tag.FA-SVC ''") }
        // Tokens printed by debug builds (push tokens, bearer tokens) never leave this computer in clear.
        runCatching { c.file.writeText(rt.redactor.apply(no.agens.testlab.services.proxyman.Proxyman.redactTokens(c.file.readText()))) }
        last[serial] = c.file
        val text = c.file.readText()
        Stopped(c.file, text.lines().count { it.isNotBlank() }, crashes(text), events(text).size, (System.currentTimeMillis() - c.startedMs) / 1000.0)
    }

    fun stopAll() = running.keys.toList().forEach { s -> runCatching { kotlinx.coroutines.runBlocking { stop(s) } } }

    /** The running capture's file, else the last one stopped. */
    fun file(serial: String): File? = running[serial]?.file ?: last[serial] ?: dir.listFiles { f -> f.name.startsWith(serial.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-") }?.maxByOrNull { it.lastModified() }

    fun read(serial: String, grep: String?, limit: Int): List<String> {
        val f = file(serial) ?: return emptyList()
        val re = grep?.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) }
        return f.readLines().filter { re == null || re.containsMatchIn(it) }.takeLast(limit).map { rt.redactor.apply(no.agens.testlab.services.proxyman.Proxyman.redactTokens(it)) }
    }

    companion object {
        /** Recorders (scrcpy, Genymobile's server) crash in logcat too; their crashes are the rig's, not the app's. */
        private val RECORDER = Regex("(?i)scrcpy|genymobile|com\\.genymobile")
        private val FA_EVENT = Regex("Logging event:?\\s*(?:\\(FE\\))?\\s*origin=([^,]+),name=([^,]+),params=(.*)$")

        fun crashes(text: String): List<Crash> {
            val lines = text.lines()
            return lines.mapIndexedNotNull { i, l ->
                if ("FATAL EXCEPTION" !in l && "*** *** *** ***" !in l) return@mapIndexedNotNull null
                val next = (i + 1 until minOf(lines.size, i + 6)).firstOrNull { j -> "FATAL EXCEPTION" in lines[j] || "*** *** *** ***" in lines[j] } ?: minOf(lines.size, i + 6)
                val window = lines.subList(i, next)
                val proc = window.firstNotNullOfOrNull { Regex("Process: ([^,\\s]+)").find(it)?.groupValues?.get(1) ?: Regex(">>> (\\S+) <<<").find(it)?.groupValues?.get(1) } ?: "?"
                val first = window.drop(1).firstOrNull { "Exception" in it || "Error" in it || "signal" in it }?.substringAfter(": ")?.trim()?.take(160) ?: l.trim().take(160)
                Crash(l.trim().substringBefore(' '), proc, first, RECORDER.containsMatchIn(window.joinToString("\n")))
            }
        }

        /** Each app crash's own lines (its log tag's, up to [max]): the stack trace to look the crash up by. The recorders' crashes are left out. */
        fun crashTraces(text: String, max: Int = 60): List<String> {
            val lines = text.lines()
            val starts = lines.indices.filter { "FATAL EXCEPTION" in lines[it] || "*** *** *** ***" in lines[it] }
            return starts.mapIndexedNotNull { n, i ->
                val tag = LOG_TAG.find(lines[i])?.groupValues?.get(1)
                val end = minOf(lines.size, i + max * 3, starts.getOrElse(n + 1) { lines.size })
                val block = lines.subList(i, end).filter { tag == null || LOG_TAG.find(it)?.groupValues?.get(1) == tag }.take(max)
                block.joinToString("\n").takeUnless { RECORDER.containsMatchIn(it) }
            }
        }

        private val LOG_TAG = Regex("\\s[VDIWEF][\\s/]+([^:]+?)\\s*(?:\\(\\s*\\d+\\))?:\\s")

        /** Firebase Analytics' own debug lines (`FA-SVC: Logging event: origin=app,name=…,params=Bundle[{…}]`). */
        fun events(text: String, pattern: Regex? = null): List<Event> = text.lines().mapNotNull { l ->
            if (pattern != null) {
                val m = pattern.find(l) ?: return@mapNotNull null
                fun group(n: String) = runCatching { m.groups[n]?.value }.getOrNull()
                return@mapNotNull Event(l.trim().substringBefore(' '), group("name") ?: m.groupValues.getOrElse(1) { "" }, group("params") ?: "")
            }
            FA_EVENT.find(l)?.let { m -> Event(l.trim().substringBefore(' '), m.groupValues[2], m.groupValues[3].trim()) }
        }
    }
}
