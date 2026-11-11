package no.agens.testlab.services.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.agens.testlab.core.a11y.HidTouch

/**
 * Real TalkBack gestures through a virtual touchscreen ([HidTouch]): explore by touch (moves
 * TalkBack's focus), double-tap (activates the focused node, wherever it is), and swipe to the
 * next or previous element. Refused while TalkBack isn't exploring: there a double-tap would
 * click whatever is under it.
 */
class TalkBackGestures(private val rt: LabRuntime) {
    enum class Direction { NEXT, PREVIOUS }

    private suspend fun play(serial: String, g: HidTouch.Gesture) {
        val (w, h) = rt.device(serial).screenSize()
        val r = withContext(Dispatchers.IO) { rt.adb().shellWithInput(serial, "hid -", HidTouch.stream(g, w, h), 30_000) }
        if (!r.ok) throw IllegalStateException("hid failed on $serial (${r.exit}): ${(r.stderr.ifBlank { r.stdout }).trim().take(300)}")
    }

    private fun requireExploring(serial: String) {
        rt.ops(serial).requireAndroid("TalkBack")
        if (!rt.talkback.status(serial).touchExploration) throw IllegalStateException("TalkBack isn't on for $serial: handsets_talkback(action=on) first, or use handsets_tap")
    }

    /** Explore by touch at x,y: TalkBack focuses the node there (and speaks it). */
    suspend fun touch(serial: String, x: Int, y: Int) { requireExploring(serial); play(serial, HidTouch.Taps(x, y, 1, holdMs = 600)) }

    /** Double-tap: activates the node TalkBack has in focus. Played in the middle of the screen; where doesn't matter. */
    suspend fun activate(serial: String) {
        requireExploring(serial)
        val (w, h) = rt.device(serial).screenSize()
        play(serial, HidTouch.Taps(w / 2, h / 2, 2, holdMs = 50, gapMs = 90))
    }

    /** One-finger swipe right (next) or left (previous) across the middle of the screen. */
    suspend fun swipe(serial: String, direction: Direction) {
        requireExploring(serial)
        val (w, h) = rt.device(serial).screenSize()
        val (a, b) = (w * 2 / 10) to (w * 8 / 10)
        play(serial, if (direction == Direction.NEXT) HidTouch.Swipe(a, h / 2, b, h / 2) else HidTouch.Swipe(b, h / 2, a, h / 2))
    }

    /**
     * After TalkBack comes on: its notification-permission dialog (denied with a TalkBack
     * double-tap, which is all that dialog takes once TalkBack runs) and its tutorial (Back). Up to
     * [seconds]; returns what it dismissed.
     */
    suspend fun dismissSetup(serial: String, seconds: Int = 6): List<String> {
        val done = mutableListOf<String>()
        val dev = rt.device(serial)
        repeat(seconds * 2) {
            val top = runCatching { dev.topActivity() }.getOrDefault("")
            when {
                "GrantPermissionsActivity" in top -> {
                    val hit = runCatching { dev.locate("#permission_deny_button", attempts = 1) }.getOrNull()
                    if (hit != null) {
                        touch(serial, hit.bounds.centerX, hit.bounds.centerY); activate(serial)
                        done += "notification permission (denied)"
                    }
                }
                TalkBack.isTalkBack(top) && Regex("(?i)tutorial|training|onboarding").containsMatchIn(top) -> {
                    dev.key("back"); done += "TalkBack tutorial"
                }
                done.isNotEmpty() -> return done
            }
            kotlinx.coroutines.delay(500)
        }
        return done
    }
}
