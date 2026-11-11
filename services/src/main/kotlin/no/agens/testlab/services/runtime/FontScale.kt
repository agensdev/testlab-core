package no.agens.testlab.services.runtime

import kotlinx.serialization.json.Json
import java.io.File

/**
 * The phone's font size (`settings system font_scale`), for large-text tests: [set] remembers the
 * value it replaced, [reset] puts exactly that back (1.0 when nothing was remembered).
 */
class FontScale(private val rt: LabRuntime, private val stateFile: File = File(rt.dirs.data, "font-scale.json")) {
    private val json = Json { prettyPrint = true }

    private fun sh(serial: String, cmd: String): String = rt.adb().shell(serial, cmd).stdout.trim()
    private fun saved(): MutableMap<String, String> = runCatching { json.decodeFromString<Map<String, String>>(stateFile.readText()).toMutableMap() }.getOrDefault(mutableMapOf())
    private fun save(m: Map<String, String>) { stateFile.parentFile?.mkdirs(); stateFile.writeText(json.encodeToString(m)) }

    fun current(serial: String): Double = sh(serial, "settings get system font_scale").toDoubleOrNull() ?: 1.0

    /** The original, when ${no.agens.testlab.AppIdentity.NAME} changed it and hasn't put it back. */
    fun original(serial: String): Double? = saved()[serial]?.toDoubleOrNull()

    fun set(serial: String, scale: Double): Double {
        require(scale in 0.5..3.0) { "font scale must be between 0.5 and 3.0" }
        if (original(serial) == null) save(saved().also { it[serial] = current(serial).toString() })
        sh(serial, "settings put system font_scale $scale")
        return current(serial)
    }

    fun reset(serial: String): Double {
        val back = original(serial) ?: 1.0
        sh(serial, "settings put system font_scale $back")
        save(saved().also { it.remove(serial) })
        return current(serial)
    }
}
