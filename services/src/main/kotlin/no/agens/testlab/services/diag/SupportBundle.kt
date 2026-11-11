package no.agens.testlab.services.diag

import kotlinx.serialization.json.Json
import no.agens.testlab.AppIdentity
import no.agens.testlab.device.ToolLocator
import no.agens.testlab.services.SelfTest
import no.agens.testlab.services.http.TrustRoots
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.SecretStore
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The zip a colleague sends when something goes wrong: versions, OS, tools, devices, settings,
 * which secrets are set (names only), the app log, recent traces and state files. Every text
 * goes through the redactor first, and e-mail addresses are masked too, so it can be shared.
 * Screenshots, recordings, reports and the vault are never included.
 */
class SupportBundle(private val rt: LabRuntime) {
    data class Result(val zip: File, val files: List<String>)

    /** [activity] is the app's recent tool calls, one line each (the app passes them in). */
    fun write(dest: File, activity: List<String> = emptyList()): Result {
        val names = mutableListOf<String>()
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream()).use { z ->
            fun add(name: String, text: String) {
                z.putNextEntry(ZipEntry(name)); z.write(scrub(text).toByteArray()); z.closeEntry(); names += name
            }
            fun addFile(name: String, f: File, tailBytes: Long = 2_000_000) {
                if (!f.isFile) return
                val text = if (f.length() <= tailBytes) f.readText() else f.inputStream().use { it.skip(f.length() - tailBytes); "…(cut)\n" + it.readBytes().decodeToString() }
                add(name, text)
            }
            add("about.txt", about())
            add("selftest.json", runCatching { Json { prettyPrint = true }.encodeToString(SelfTest.Result.serializer(), SelfTest.run()) }.getOrElse { "selftest failed: $it" })
            add("secrets.txt", secrets())
            add("tools.txt", tools())
            add("devices.txt", devices())
            addFile("settings.json", rt.dirs.settings)
            for (n in listOf("installed-builds.json", "isolation.json", "devtests.json", "adb-server.log")) addFile(n, File(rt.dirs.data, n))
            addFile("logs/app.log", File(rt.dirs.data, "logs/app.log"))
            addFile("logs/app.log.1", File(rt.dirs.data, "logs/app.log.1"))
            if (activity.isNotEmpty()) add("activity.txt", activity.joinToString("\n"))
            rt.dirs.traces.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }.take(20).forEach { addFile("traces/${it.name}", it, 500_000) }
        }
        return Result(dest, names)
    }

    fun defaultName(): String = "${AppIdentity.SLUG}-diagnostics-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.zip"

    private fun scrub(text: String): String = EMAIL.replace(rt.redactor.apply(text), "‹e-mail›")

    private fun about(): String = buildString {
        appendLine("${AppIdentity.NAME} ${AppIdentity.VERSION} (${AppIdentity.BUILD})")
        appendLine("Java ${System.getProperty("java.runtime.version")} (${System.getProperty("java.vendor")})")
        appendLine("OS ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        appendLine("Locale ${java.util.Locale.getDefault()} · time zone ${java.util.TimeZone.getDefault().id}")
        appendLine("Space ${rt.dirs.space}${rt.rules.current.client?.let { " ($it)" } ?: ""}")
        appendLine("Data ${rt.dirs.data}")
        appendLine("Cache ${rt.dirs.cache}")
        appendLine("Workspace ${rt.settings.current.workspace ?: rt.dirs.workspace}")
        runCatching { TrustRoots.manager }
        appendLine("Trusted roots: ${TrustRoots.sources.joinToString()}")
        appendLine("Active device: ${rt.active.value ?: "none"}")
        appendLine("Recording: ${rt.recordings.status().joinToString { it.serial }.ifEmpty { "no" }}")
        appendLine("Created ${LocalDateTime.now().withNano(0)}")
    }

    private fun secrets(): String = runCatching {
        if ((rt.secrets as? no.agens.testlab.services.secrets.VaultSecretStore)?.isLocked() == true) return@runCatching "The vault is locked (no passphrase given): nothing could be read."
        val keys = rt.secrets.keys()
        val wanted = listOf(SecretStore.GITHUB_TOKEN, SecretStore.JIRA_EMAIL, SecretStore.JIRA_TOKEN, SecretStore.JEV_KEY, SecretStore.OPENAI_KEY, SecretStore.MCP_TOKEN, SecretStore.DEVICE_PIN)
        (wanted.map { "$it: ${if (it in keys) "set" else "not set"}" } + (keys - wanted.toSet()).sorted().map { "$it: set" }).joinToString("\n")
    }.getOrElse { "could not read the vault: ${it.message}" }

    private fun tools(): String = buildString {
        val loc = ToolLocator(appToolsDir = rt.dirs.cache)
        appendLine("adb candidates (first existing wins):")
        loc.adbCandidates().forEach { (f, src) -> appendLine("  ${if (f.isFile) "✓" else "·"} $f  [$src]") }
        appendLine("adb in use: ${rt.locateAdb() ?: "none"}")
        for (t in listOf("scrcpy", "ffmpeg", "ffprobe")) appendLine("$t: ${loc.findOptional(t)?.let { "${it.path} [${it.source}]" } ?: "not found"}")
    }

    private fun devices(): String = runCatching {
        val adb = rt.adb()
        buildString {
            appendLine(adb.run(listOf("version")).stdout.trim())
            appendLine()
            appendLine(adb.run(listOf("devices", "-l")).let { it.stdout + it.stderr }.trim())
            appendLine()
            appendLine("Nearby (mDNS):")
            appendLine(runCatching { adb.mdnsServices().joinToString("\n") { (n, t, a) -> "  $n $t $a" }.ifEmpty { "  none" } }.getOrElse { "  ${it.message}" })
        }
    }.getOrElse { "adb: ${it.message}" }

    companion object {
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    }
}
