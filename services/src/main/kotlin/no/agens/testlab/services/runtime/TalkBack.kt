package no.agens.testlab.services.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * TalkBack on a phone: status, on, off. Injected taps, swipes and key events pass TalkBack's touch
 * exploration (checked on Android 16), so driving works with it on; this is for testing an app
 * with TalkBack, and putting the phone back as it was: [off] remembers exactly which service it
 * removed, so [on] restores it.
 */
class TalkBack(private val rt: LabRuntime, private val stateFile: File = File(rt.dirs.data, "talkback.json")) {
    @Serializable
    data class Status(
        /** A TalkBack service is in the enabled list. */
        val enabled: Boolean,
        /** Android is in touch-exploration mode right now (TalkBack running). */
        val touchExploration: Boolean,
        /** The TalkBack component on this phone, when installed. */
        val component: String?,
        /** ${no.agens.testlab.AppIdentity.NAME} turned it off and will put back these on `on`. */
        val turnedOffByUs: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun sh(serial: String, cmd: String): String = rt.adb().shell(serial, cmd).stdout.trim()

    private fun saved(): MutableMap<String, List<String>> =
        runCatching { json.decodeFromString<Map<String, List<String>>>(stateFile.readText()).toMutableMap() }.getOrDefault(mutableMapOf())

    private fun save(m: Map<String, List<String>>) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(json.encodeToString(m))
    }

    fun status(serial: String): Status {
        val list = enabledList(sh(serial, "settings get secure enabled_accessibility_services"))
        val touch = "touchExplorationEnabled=true" in sh(serial, "dumpsys accessibility | grep -o 'touchExplorationEnabled=[a-z]*' | head -1")
        return Status(list.any(::isTalkBack), touch, installedComponent(serial), saved()[serial].orEmpty())
    }

    fun off(serial: String): Status {
        val list = enabledList(sh(serial, "settings get secure enabled_accessibility_services"))
        val (removed, rest) = list.partition(::isTalkBack)
        if (removed.isNotEmpty()) {
            write(serial, rest)
            save(saved().also { it[serial] = (it[serial].orEmpty() + removed).distinct() })
            waitFor(serial, false)
        }
        return status(serial)
    }

    fun on(serial: String): Status {
        val list = enabledList(sh(serial, "settings get secure enabled_accessibility_services"))
        val wanted = saved()[serial].orEmpty().ifEmpty { listOfNotNull(installedComponent(serial)) }
        if (wanted.isEmpty()) throw IllegalStateException("TalkBack is not installed on $serial (no ${GOOGLE_PACKAGE} or other TalkBack service)")
        write(serial, (list + wanted).distinct())
        save(saved().also { it.remove(serial) })
        waitFor(serial, true)
        return status(serial)
    }

    private fun write(serial: String, list: List<String>) {
        val v = list.joinToString(":")
        // An empty value is written as "" (settings put needs an argument); accessibility_enabled follows the list.
        sh(serial, "settings put secure enabled_accessibility_services ${if (v.isEmpty()) "\"\"" else "'$v'"}; settings put secure accessibility_enabled ${if (list.isEmpty()) 0 else 1}")
    }

    private fun waitFor(serial: String, exploring: Boolean) {
        repeat(20) {
            if (runCatching { status(serial).touchExploration }.getOrDefault(!exploring) == exploring) return
            Thread.sleep(150)
        }
    }

    private fun installedComponent(serial: String): String? {
        val pkgs = sh(serial, "pm list packages | grep -i talkback").lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }
        return when {
            GOOGLE_PACKAGE in pkgs -> GOOGLE_COMPONENT
            SAMSUNG_PACKAGE in pkgs -> SAMSUNG_COMPONENT
            else -> null
        }
    }

    companion object {
        const val GOOGLE_PACKAGE = "com.google.android.marvin.talkback"
        const val GOOGLE_COMPONENT = "$GOOGLE_PACKAGE/com.google.android.marvin.talkback.TalkBackService"
        const val SAMSUNG_PACKAGE = "com.samsung.android.accessibility.talkback"
        const val SAMSUNG_COMPONENT = "$SAMSUNG_PACKAGE/com.samsung.android.marvin.talkback.TalkBackService"

        /** `settings get` prints "null" for an unset list. */
        fun enabledList(raw: String): List<String> =
            raw.trim().takeUnless { it == "null" }.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }

        fun isTalkBack(component: String): Boolean = component.substringBefore('/').contains("talkback", ignoreCase = true)

    }
}
