package no.agens.testlab.services.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * The phone settings a round tends to leave changed (font size, TalkBack, «Don't keep activities»,
 * animation scales, analytics debug properties, demo mode, disabled packages…): a snapshot at the
 * start of a session, the difference later, and one call to put them back. Android only.
 */
class DeviceState(private val rt: LabRuntime) {
    /** One setting: how to read it and how to write a value back (null value = delete / reset). */
    data class Key(val id: String, val label: String, val read: String, val write: (String?) -> String?)

    @Serializable
    data class Snapshot(val serial: String, val takenAt: String, val values: Map<String, String?>, val disabledPackages: List<String> = emptyList())

    data class Change(val id: String, val label: String, val was: String?, val now: String?)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private fun file(serial: String) = File(rt.dirs.data, "device-state/${serial.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    private suspend fun shell(serial: String, cmd: String, timeoutMs: Long = 20_000) = withContext(Dispatchers.IO) { rt.adb().shell(serial, cmd, timeoutMs) }

    /** Reads every key in one adb call (a line per key, `<id>=<value>`). */
    suspend fun read(serial: String): Snapshot {
        val script = KEYS.joinToString("; ") { k -> "echo \"${k.id}=$(${k.read} 2>/dev/null | head -1)\"" }
        val out = shell(serial, script).stdout
        val values = KEYS.associate { k ->
            val line = out.lineSequence().firstOrNull { it.startsWith(k.id + "=") }?.substringAfter('=')?.trim()
            k.id to normalise(k.id, line)
        }
        val disabled = shell(serial, "pm list packages -d").stdout.lines().mapNotNull { it.trim().removePrefix("package:").takeIf { p -> p.isNotEmpty() } }.sorted()
        return Snapshot(serial, Instant.now().toString(), values, disabled)
    }

    suspend fun snapshot(serial: String): Snapshot = read(serial).also { s ->
        val f = file(serial)
        f.parentFile?.mkdirs()
        f.writeText(json.encodeToString(s))
    }

    fun saved(serial: String): Snapshot? = file(serial).takeIf { it.isFile }?.let { runCatching { json.decodeFromString<Snapshot>(it.readText()) }.getOrNull() }

    /** What differs now from the saved snapshot ([base]); disabled packages count as one entry each. */
    suspend fun diff(serial: String, base: Snapshot? = saved(serial)): Pair<Snapshot?, List<Change>> {
        base ?: return null to emptyList()
        val now = read(serial)
        val changes = KEYS.mapNotNull { k ->
            val a = base.values[k.id]
            val b = now.values[k.id]
            if (a != b) Change(k.id, k.label, a, b) else null
        } + (now.disabledPackages - base.disabledPackages.toSet()).map { Change("disabled:$it", "package $it", "enabled", "disabled") } +
            (base.disabledPackages - now.disabledPackages.toSet()).map { Change("disabled:$it", "package $it", "disabled", "enabled") }
        return base to changes
    }

    /** Puts every changed setting back to the snapshot's value; returns what was done and what could not be. */
    suspend fun restore(serial: String): List<String> {
        val (base, changes) = diff(serial)
        if (base == null) return listOf("no snapshot of $serial: lab_device_state(action=snapshot) takes one (lab_session_start does too)")
        if (changes.isEmpty()) return listOf("nothing to restore: $serial is as it was at ${base.takenAt}")
        val done = mutableListOf<String>()
        for (c in changes) {
            if (c.id.startsWith("disabled:")) {
                val pkg = c.id.removePrefix("disabled:")
                val cmd = if (c.was == "disabled") "pm disable-user --user 0 ${shQuote(pkg)}" else "pm enable ${shQuote(pkg)}"
                val r = shell(serial, cmd)
                done += "${c.label}: ${c.was}" + if (!r.ok) " (failed: ${r.stderr.trim().take(80)})" else ""
                continue
            }
            if (c.id == "global.http_proxy" && serial in rt.proxyman.routed.value) { done += "${c.label}: left as ${c.now}: Testlab routes this phone through Proxyman (handsets_proxy off ends that)"; continue }
            val key = KEYS.first { it.id == c.id }
            val cmd = key.write(c.was)
            if (cmd == null) { done += "${c.label}: ${c.now} → ${c.was} must be set by hand (${MANUAL[c.id] ?: "no adb command for it"})"; continue }
            val r = shell(serial, cmd)
            done += "${c.label}: ${c.now ?: "unset"} → ${c.was ?: "unset"}" + if (!r.ok) " (failed: ${r.stderr.trim().take(80)})" else ""
        }
        // «Don't keep activities» written with `settings` does not reach ActivityManager until the toggle is flipped.
        if (changes.any { it.id == "dont_keep_activities" }) done += "NOTE: «Don't keep activities» is read by ActivityManager from Developer options: check the toggle there too"
        return done
    }

    companion object {
        private fun settings(ns: String, key: String) = Key("$ns.$key", key, "settings get $ns $key") { v ->
            if (v == null) "settings delete $ns $key" else "settings put $ns $key ${shQuote(v)}"
        }
        private fun prop(name: String) = Key("prop.$name", name, "getprop $name") { v -> "setprop $name ${shQuote(v ?: "")}" }

        /** Default values so a never-set key and its default compare equal (`null` ≈ "1.0" for font_scale). */
        private val DEFAULTS = mapOf(
            "system.font_scale" to "1.0", "global.window_animation_scale" to "1.0", "global.transition_animation_scale" to "1.0",
            "global.animator_duration_scale" to "1.0", "dont_keep_activities" to "0", "secure.accessibility_enabled" to "0",
            "global.stay_on_while_plugged_in" to "0", "system.show_touches" to "0", "global.sysui_demo_allowed" to "0",
            "prop.debug.firebase.analytics.app" to ".none.", "global.http_proxy" to ":0",
        )

        fun normalise(id: String, raw: String?): String? {
            val v = raw?.trim()?.takeUnless { it.isEmpty() || it == "null" }
            return v ?: DEFAULTS[id]
        }

        val KEYS: List<Key> = listOf(
            settings("system", "font_scale"),
            Key("night_mode", "dark mode", "cmd uimode night") { v -> v?.let { "cmd uimode night ${if ("yes" in it) "yes" else if ("auto" in it) "auto" else "no"}" } },
            settings("secure", "enabled_accessibility_services"),
            settings("secure", "accessibility_enabled"),
            Key("dont_keep_activities", "Don't keep activities", "settings get global always_finish_activities") { v -> "settings put global always_finish_activities ${v ?: "0"}" },
            Key("navigation_mode", "navigation (0 = 3-button, 2 = gestures)", "settings get secure navigation_mode") { v ->
                when (v) {
                    "0" -> "cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton"
                    "2" -> "cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural"
                    else -> null
                }
            },
            settings("system", "accelerometer_rotation"),
            settings("system", "user_rotation"),
            settings("global", "stay_on_while_plugged_in"),
            settings("global", "window_animation_scale"),
            settings("global", "transition_animation_scale"),
            settings("global", "animator_duration_scale"),
            settings("system", "show_touches"),
            settings("global", "sysui_demo_allowed"),
            Key("demo_mode", "SystemUI demo mode", "settings get global sysui_tuner_demo_on") { v ->
                if (v == "1") null else "am broadcast -a com.android.systemui.demo -e command exit; settings put global sysui_tuner_demo_on 0"
            },
            settings("global", "http_proxy"),
            prop("debug.firebase.analytics.app"),
            prop("log.tag.FA"),
            prop("log.tag.FA-SVC"),
        )

        private val MANUAL = mapOf(
            "navigation_mode" to "Settings → System → Navigation mode",
            "demo_mode" to "turn demo mode on in Developer options",
        )
    }
}
