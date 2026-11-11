package no.agens.testlab.services.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * "Isolate the app during a run": every build installed besides the one under test claims the
 * return link of a login, so the login can land in a chooser or the wrong build. The other builds are
 * disabled (`pm disable-user`, data kept) and enabled again afterwards. Each package is written to
 * a record file *before* it is disabled, so after a crash the next start (or [restoreAll]) enables
 * them again; a phone that is not attached keeps its record until it is.
 */
class Isolation(private val rt: LabRuntime, private val file: File = File(rt.dirs.data, "isolation.json")) {
    @Serializable
    private data class Entry(val serial: String, val packages: List<String>)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Synchronized
    private fun load(): MutableList<Entry> = try {
        if (file.isFile) json.decodeFromString<List<Entry>>(file.readText()).toMutableList() else mutableListOf()
    } catch (e: Exception) {
        mutableListOf()
    }

    @Synchronized
    private fun save(entries: List<Entry>) {
        if (entries.isEmpty()) { file.delete(); return }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(entries))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    @Synchronized
    private fun remember(serial: String, pkg: String) {
        val all = load()
        val e = all.firstOrNull { it.serial == serial }
        if (e == null) all += Entry(serial, listOf(pkg)) else { all.remove(e); all += e.copy(packages = (e.packages + pkg).distinct()) }
        save(all)
    }

    @Synchronized
    private fun forget(serial: String, pkg: String) {
        save(load().mapNotNull { e -> if (e.serial != serial) e else e.copy(packages = e.packages - pkg).takeIf { it.packages.isNotEmpty() } })
    }

    /** Packages disabled on [serial] by an isolation that has not been undone. */
    fun pending(serial: String): List<String> = load().firstOrNull { it.serial == serial }?.packages.orEmpty()

    /** Disables every other installed app package on [serial]; returns them. */
    suspend fun isolate(serial: String, keep: String, family: String = rt.profile.app.family ?: ""): List<String> = withContext(Dispatchers.IO) {
        // Disabling other builds of the family is Android's pm; an iOS Simulator has nothing like it.
        if (family.isBlank() || rt.platformOf(serial) != no.agens.testlab.core.Platform.ANDROID) return@withContext emptyList()
        val others = rt.adb().shell(serial, "pm list packages ${shQuote(family)}").stdout.lines()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() && it != keep && (it == family || it.startsWith("$family.")) }
        for (p in others) {
            remember(serial, p)
            val r = rt.adb().shell(serial, "pm disable-user --user 0 ${shQuote(p)}")
            if ("disabled" !in r.stdout) { forget(serial, p); throw IllegalStateException("could not disable $p: ${(r.stdout + r.stderr).trim().take(200)}") }
        }
        others
    }

    /** Enables what [isolate] disabled on [serial]. */
    suspend fun restore(serial: String): List<String> = withContext(Dispatchers.IO) {
        val pkgs = pending(serial)
        for (p in pkgs) {
            val r = rt.adb().shell(serial, "pm enable ${shQuote(p)}")
            if ("enabled" in r.stdout || "Unknown package" in r.stdout + r.stderr) forget(serial, p)
        }
        pkgs
    }

    /** At start-up: undo isolations a crash left behind on the devices that are attached. */
    suspend fun restoreAll(): Map<String, List<String>> {
        val attached = runCatching { rt.listDevices().filter { it.ready }.map { it.serial }.toSet() }.getOrDefault(emptySet())
        return load().map { it.serial }.filter { it in attached }.associateWith { restore(it) }
    }
}
