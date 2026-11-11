package no.agens.testlab.services.runtime

import no.agens.testlab.device.HostOs
import no.agens.testlab.device.ToolLocator
import no.agens.testlab.services.tools.AndroidCli
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Android emulators: which virtual devices (AVDs) exist, making one, starting and stopping
 * them. With Google's Android CLI a computer without Android Studio gets the emulator, a system
 * image and the device in one step (`android emulator create`); without it, the AVDs Android
 * Studio made are listed and started with the SDK's own emulator.
 */
class Emulators(private val rt: LabRuntime, private val os: HostOs = HostOs.current(), private val cli: () -> AndroidCli = { rt.androidCli }) {
    data class Avd(val name: String, val api: String? = null, val running: Boolean = false, val serial: String? = null)

    /** `<sdk>/emulator/emulator`, next to the adb in use, else wherever the tool locator finds it. */
    fun binary(): File? {
        val exe = if (os == HostOs.WINDOWS) "emulator.exe" else "emulator"
        rt.locateAdb()?.parentFile?.parentFile?.let { sdk -> File(sdk, "emulator/$exe").takeIf { it.isFile }?.let { return it } }
        return runCatching { ToolLocator(appToolsDir = rt.dirs.cache).findOptional("emulator")?.path }.getOrNull()
    }

    /** Whether emulators can be made here (the Android CLI is installed). */
    val canCreate: Boolean get() = cli().installed

    /** The virtual devices, running ones with their adb serial. */
    fun avds(): List<Avd> {
        if (cli().installed) {
            val r = cli().run(listOf("emulator", "list", "--long"), 60)
            if (r.ok) return parseList(r.out)
        }
        val bin = binary() ?: return emptyList()
        return runCatching {
            val p = ProcessBuilder(bin.path, "-list-avds").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(20, TimeUnit.SECONDS)
            out.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("INFO") && !it.contains(' ') }.map { Avd(it) }
        }.getOrDefault(emptyList())
    }

    /** The kinds of device `create` can make (phone, tablet sizes). */
    fun profiles(): List<String> {
        val r = cli().run(listOf("emulator", "create", "--list-profiles"), 120)
        if (!r.ok) throw IllegalStateException("android emulator create --list-profiles: ${r.problem}")
        return r.out.lines().map { it.trim() }.filter { PROFILE.matches(it) }
    }

    /**
     * Makes a virtual device of [profile] (`medium_phone`): the Android CLI downloads what is missing
     * (emulator, system image, a few GB the first time) and names the device after the profile.
     */
    fun create(profile: String): String {
        require(PROFILE.matches(profile)) { "profile '$profile': one of ${runCatching { profiles() }.getOrDefault(emptyList()).joinToString()}" }
        val before = avds().map { it.name }.toSet()
        val r = cli().run(listOf("emulator", "create", profile), 1800)
        if (!r.ok) throw IllegalStateException("couldn't make the emulator: ${r.problem}")
        return CREATED.find(r.out + r.err)?.groupValues?.get(1) ?: (avds().map { it.name } - before).firstOrNull() ?: profile
    }

    /**
     * Starts [avd] and waits until Android has booted (at most [waitSec]); returns its adb serial.
     * [headless] runs it without a window: Testlab's screen view shows it anyway.
     */
    fun start(avd: String, headless: Boolean = false, cold: Boolean = false, waitSec: Long = 300): String {
        val list = avds()
        val a = list.firstOrNull { it.name == avd } ?: throw IllegalArgumentException("No virtual device called $avd" + list.joinToString(prefix = ": there are ", transform = { it.name }).takeIf { list.isNotEmpty() }.orEmpty())
        if (a.running && a.serial != null) return a.serial
        val t0 = System.currentTimeMillis()
        val before = emulatorSerials()
        var log = File(System.getProperty("user.home"), ".android/$avd/emulator.log")
        val logStart = log.length()
        if (cli().installed) {
            // It waits for the boot itself, but its wait can end early ("Unexpected end of channel"), and it
            // reports success when the emulator dies at once: adb and the emulator's own log decide.
            val r = cli().run(listOf("emulator", "start") + listOfNotNull("--headless".takeIf { headless }, "--cold".takeIf { cold }) + avd, waitSec + 60)
            if (!r.ok) throw IllegalStateException("the emulator didn't start: ${r.problem}")
            LOG_AT.find(r.out + r.err)?.let { log = File(it.groupValues[1]) }
        } else {
            val bin = binary() ?: throw IllegalStateException("The Android emulator was not found: `testlab android-cli install` gets Google's Android CLI, which installs it")
            log = File(rt.dirs.data, "logs/emulator-$avd.log").apply { parentFile.mkdirs(); delete() }
            ProcessBuilder(listOf(bin.path, "-avd", avd) + listOfNotNull("-no-window".takeIf { headless }, "-no-snapshot-load".takeIf { cold })).redirectErrorStream(true).redirectOutput(log).start()
        }
        val from = if (log.length() >= logStart) logStart else 0
        val deadline = t0 + waitSec * 1000
        while (System.currentTimeMillis() < deadline) {
            val serial = avds().firstOrNull { it.name == avd && it.serial != null }?.serial
                ?: (emulatorSerials() - before).firstOrNull { avdName(it) == avd }
            if (serial != null && booted(serial)) return serial
            fatal(log, from)?.let { throw IllegalStateException("$avd stopped while starting: $it (its log: ${log.path})") }
            Thread.sleep(2000)
        }
        throw IllegalStateException("$avd didn't finish booting in $waitSec s (its log: ${log.path})")
    }

    /** The emulator's FATAL line written since [from], with what it means when it is a missing system image. */
    private fun fatal(log: File, from: Long): String? {
        if (!log.isFile || log.length() <= from) return null
        val text = runCatching { java.io.RandomAccessFile(log, "r").use { f -> f.seek(from); ByteArray((f.length() - from).toInt().coerceAtMost(1_000_000)).also { f.readFully(it) } }.decodeToString() }.getOrNull() ?: return null
        val line = text.lines().firstOrNull { "FATAL" in it }?.substringAfter("|")?.trim() ?: return null
        val missing = text.lines().firstOrNull { "is not a valid directory" in it && "system-images" in it }
        return line + if (missing != null) ". Its system image isn't in the SDK Testlab uses (${cli().sdkInUse()?.path ?: "the Android CLI's"}): the device was made with another SDK; make a new one (`testlab emulator create`, or ask Claude), or start it from Android Studio" else ""
    }

    /** Stops the emulator [nameOrSerial] (its apps and data stay). */
    fun stop(nameOrSerial: String) {
        val serial = if (nameOrSerial.startsWith("emulator-")) nameOrSerial else avds().firstOrNull { it.name == nameOrSerial }?.serial
            ?: throw IllegalArgumentException("$nameOrSerial isn't running")
        rt.drop(serial)
        if (cli().installed) {
            val r = cli().run(listOf("emulator", "stop", serial), 90)
            if (r.ok) return
        }
        rt.adb().run(listOf("emu", "kill"), serial, 15_000)
    }

    private fun emulatorSerials(): Set<String> = runCatching { rt.adb().devices().map { it.serial }.filter { it.startsWith("emulator-") }.toSet() }.getOrDefault(emptySet())

    private fun avdName(serial: String): String? = runCatching { rt.adb().run(listOf("emu", "avd", "name"), serial, 10_000).stdout.lines().firstOrNull()?.trim() }.getOrNull()

    private fun booted(serial: String): Boolean = runCatching { rt.adb().shell(serial, "getprop sys.boot_completed", 10_000).stdout.trim() == "1" }.getOrDefault(false)

    companion object {
        private val PROFILE = Regex("^[a-z][a-z0-9_]*$")
        private val CREATED = Regex("created device '([^']+)'")
        private val LOG_AT = Regex("log file location: '([^']+)'")

        /** `android emulator list --long`: a table with AVD ID, AVD Name, API Level, Status and Serial columns. */
        fun parseList(text: String): List<Avd> {
            val lines = text.lines().filter { it.isNotBlank() }
            val header = lines.firstOrNull { it.trimStart().startsWith("AVD ID") }
                ?: return lines.map { it.trim() }.filter { PROFILE.matches(it) || Regex("^[\\w.-]+$").matches(it) }.map { Avd(it) }
            val cols = listOf("AVD ID", "AVD Name", "API Level", "Status", "Serial").map { header.indexOf(it) }
            fun cell(l: String, i: Int): String? {
                val from = cols[i].takeIf { it >= 0 } ?: return null
                val to = cols.drop(i + 1).firstOrNull { it > from } ?: l.length
                return if (from >= l.length) null else l.substring(from, minOf(to, l.length)).trim().ifEmpty { null }
            }
            return lines.dropWhile { it != header }.drop(1).mapNotNull { l ->
                val id = cell(l, 0) ?: return@mapNotNull null
                val status = cell(l, 3)
                val serial = cell(l, 4)?.takeIf { it.startsWith("emulator-") || ':' in it }
                Avd(id, cell(l, 2), running = status != null && !status.equals("Offline", ignoreCase = true) && serial != null, serial = serial)
            }
        }
    }
}
