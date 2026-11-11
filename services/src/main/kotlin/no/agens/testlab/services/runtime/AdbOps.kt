package no.agens.testlab.services.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import no.agens.testlab.core.nav.Destinations
import no.agens.testlab.device.adb.AdbException

/** Quotes one argument for the device's `sh` (adb joins `shell` arguments into one command line). */
fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * Device actions that go through adb instead of the daemon: an implicit VIEW intent (the daemon's
 * `am_start` needs a component, and the app's deeplink handler must pick the URL up itself),
 * uninstall and the lock screen.
 */
class AdbOps(private val rt: LabRuntime) {
    private suspend fun shell(serial: String, command: String, timeoutMs: Long = 30_000) =
        withContext(Dispatchers.IO) { rt.adb().shell(serial, command, timeoutMs) }

    /** `am start -W -a VIEW -d URL PKG`, as the lab's hs-go.sh; returns the Status/Error lines. */
    suspend fun openUrl(serial: String, url: String, pkg: String): String {
        val full = rt.destinations().url(url)
        val r = shell(serial, "am start -W -a android.intent.action.VIEW -d ${shQuote(full)} ${shQuote(pkg)}", 40_000)
        val lines = (r.stdout + "\n" + r.stderr).lines().filter { Regex("(?i)status|error|warning").containsMatchIn(it) }.map { it.trim() }.take(3)
        if (!r.ok || lines.any { it.startsWith("Error", ignoreCase = true) }) {
            throw AdbException("could not open $full in $pkg: ${lines.joinToString("; ").ifEmpty { r.stderr.trim().take(200) }}")
        }
        return (listOf("opened $full in $pkg") + lines).joinToString("\n")
    }

    suspend fun uninstall(serial: String, pkg: String): String {
        val r = withContext(Dispatchers.IO) { rt.adb().run(listOf("uninstall", pkg), serial, 120_000) }
        val out = (r.stdout + r.stderr).trim()
        if (!r.ok || "Failure" in out) throw AdbException("uninstall $pkg failed: ${out.take(300)}")
        return out.ifEmpty { "Success" }
    }

    /** Whether the keyguard (lock screen) is up; the marker names differ between Android versions. */
    suspend fun keyguardShowing(serial: String): Boolean {
        val window = shell(serial, "dumpsys window").stdout
        if (LOCK_MARKERS.any { it in window }) return true
        val activities = shell(serial, "dumpsys activity activities | grep -i keyguard").stdout
        return "mKeyguardShowing=true" in activities || "keyguardShowing=true" in activities
    }

    /**
     * Wakes the screen and unlocks it: a swipe up reveals the PIN pad, then the PIN and ENTER. Does
     * nothing when the screen is not locked, so it is safe to call (the lab's script typed the PIN
     * regardless, into whatever had focus). [pin] null means a swipe-only lock screen.
     */
    suspend fun unlock(serial: String, pin: String?): String {
        shell(serial, "input keyevent KEYCODE_WAKEUP")
        delay(400)
        if (!keyguardShowing(serial)) return "not locked"
        simLock(serial)?.let { throw SimLocked(it) }
        val dev = rt.device(serial)
        val (w, h) = dev.screenSize()
        shell(serial, "input swipe ${w / 2} ${h * 7 / 10} ${w / 2} ${h / 5} 250")
        delay(500)
        // After a reboot the SIM PIN screen comes before the lock screen: the device PIN typed there
        // uses up SIM attempts (three wrong ones lock the SIM with its PUK). Never type into it.
        simLock(serial)?.let { throw SimLocked(it) }
        val dump = runCatching { dev.dumpJson() }.getOrNull()
        if (dump != null && SIM_ENTRY.containsMatchIn(dump.toString())) {
            // The field may already hold digits (a pocket press, an earlier attempt): the user should clear it first.
            val held = no.agens.testlab.core.sel.UiNode.primaryRoot(dump)?.walk()
                ?.firstOrNull { n -> SIM_FIELD.containsMatchIn(n.str("rid")) && n.text.isNotEmpty() && n.text != n.str("hint") }?.text?.length
            throw SimLocked("the SIM PIN screen is showing" + (held?.let { ", and its field already holds $it character(s) nobody here typed: clear them on the phone first" } ?: ""))
        }
        if (!pin.isNullOrEmpty()) {
            if (!pin.all { it.isLetterOrDigit() }) throw IllegalArgumentException("the device PIN may only contain letters and digits")
            shell(serial, "input text ${shQuote(pin)}")
            shell(serial, "input keyevent KEYCODE_ENTER")
        }
        for (i in 0 until 6) {
            delay(500)
            if (!keyguardShowing(serial)) return "unlocked"
        }
        throw IllegalStateException(if (pin.isNullOrEmpty()) "still locked: the lock screen needs a PIN (`testlab secrets set device_pin`)" else "still locked: is the device PIN right?")
    }

    /** Why the SIM wants a PIN or PUK (`gsm.sim.state`, per slot), or null. */
    suspend fun simLock(serial: String): String? {
        val state = shell(serial, "getprop gsm.sim.state").stdout.trim()
        val locked = state.split(',').map { it.trim() }.filter { it in SIM_LOCKED_STATES }
        return if (locked.isEmpty()) null else "the SIM is ${locked.joinToString().lowercase().replace('_', ' ')} (gsm.sim.state=$state)"
    }

    companion object {
        private val SIM_LOCKED_STATES = setOf("PIN_REQUIRED", "PUK_REQUIRED", "NETWORK_LOCKED", "PERM_DISABLED")
        private val SIM_FIELD = Regex("(?i)(simPinEntry|simPukEntry|sim_pin|sim_puk|pinEntry)")
        private val SIM_ENTRY = Regex("(?i)\"rid\":\"[^\"]*(simPinEntry|simPukEntry|sim_pin|sim_puk)")
        private val LOCK_MARKERS = listOf("mDreamingLockscreen=true", "isKeyguardShowing=true", "mShowingLockscreen=true", "mKeyguardShowing=true")
    }
}

/** The SIM wants its PIN or PUK: unlock refuses, since the device PIN typed there uses up SIM attempts. */
class SimLocked(why: String) : IllegalStateException("$why: unlock the SIM on the phone yourself; the device PIN must not go into the SIM PIN field (three wrong tries lock the SIM)")
