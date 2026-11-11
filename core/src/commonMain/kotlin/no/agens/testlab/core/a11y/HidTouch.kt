package no.agens.testlab.core.a11y

import kotlin.math.roundToInt

/**
 * A virtual touchscreen for `adb shell hid -` (uhid; shell is in group `uhid`): touches from it
 * go the kernel's way, so TalkBack takes them for a finger, which injected input (`input tap`)
 * never is. One stream registers the device, waits for it to come up, plays a gesture and ends;
 * the device goes when the stream does.
 *
 * The descriptor is a one-contact multitouch digitizer (tip switch, contact id, X/Y 0..4095,
 * contact count). hid-multitouch makes it a direct touchscreen mapped to the whole display; a
 * single-touch descriptor comes up as a touchpad (a pointer), which TalkBack ignores. It has no
 * Contact Count Maximum feature report: the driver would wait 5 s for an answer `hid` never gives.
 */
object HidTouch {
    const val MAX = 4095

    private val DESCRIPTOR = intArrayOf(
        0x05, 0x0D, 0x09, 0x04, 0xA1, 0x01, 0x85, 0x01, // Digitizer: Touch Screen, report 1
        0x09, 0x22, 0xA1, 0x02, //   Finger
        0x09, 0x42, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02, // tip switch
        0x95, 0x07, 0x81, 0x03, //     padding
        0x09, 0x51, 0x25, 0x0F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02, // contact id
        0x05, 0x01, 0x09, 0x30, 0x26, 0xFF, 0x0F, 0x75, 0x10, 0x95, 0x01, 0x81, 0x02, // X
        0x09, 0x31, 0x81, 0x02, // Y
        0xC0,
        0x05, 0x0D, 0x09, 0x54, 0x25, 0x0F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02, // contact count
        0xC0,
    )

    /** What one gesture is: touch-downs at points over time. */
    sealed interface Gesture
    /** [count] touches at one point, each held [holdMs], [gapMs] apart (explore = 1×600 ms, double-tap = 2×50 ms). */
    data class Taps(val x: Int, val y: Int, val count: Int = 1, val holdMs: Int = 50, val gapMs: Int = 90) : Gesture
    /** One finger from (x1,y1) to (x2,y2) over [ms]. */
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val ms: Int = 200) : Gesture

    /** The `hid` command stream (one JSON object a line) for [g] on a [width]×[height] screen. */
    fun stream(g: Gesture, width: Int, height: Int, registerWaitMs: Int = 1000): String {
        fun sx(x: Number) = (x.toDouble() * MAX / (width - 1)).roundToInt().coerceIn(0, MAX)
        fun sy(y: Number) = (y.toDouble() * MAX / (height - 1)).roundToInt().coerceIn(0, MAX)
        fun report(tip: Boolean, x: Int, y: Int) = """{"id":1,"command":"report","report":[1,${if (tip) 1 else 0},0,${x and 0xFF},${x shr 8},${y and 0xFF},${y shr 8},${if (tip) 1 else 0}]}"""
        fun delay(ms: Int) = """{"id":1,"command":"delay","duration":$ms}"""
        val out = mutableListOf(
            """{"id":1,"command":"register","name":"testlab-touch","vid":6353,"pid":31249,"bus":"usb","descriptor":[${DESCRIPTOR.joinToString(",")}]}""",
            delay(registerWaitMs),
        )
        when (g) {
            is Taps -> repeat(g.count) { i ->
                out += report(true, sx(g.x), sy(g.y)); out += delay(g.holdMs); out += report(false, sx(g.x), sy(g.y))
                if (i < g.count - 1) out += delay(g.gapMs)
            }
            is Swipe -> {
                val steps = maxOf(4, g.ms / 16)
                for (i in 0..steps) {
                    out += report(true, sx(g.x1 + (g.x2 - g.x1) * i / steps.toDouble()), sy(g.y1 + (g.y2 - g.y1) * i / steps.toDouble()))
                    out += delay(g.ms / steps)
                }
                out += report(false, sx(g.x2), sy(g.y2))
            }
        }
        out += delay(300) // let the last event through before the device goes
        return out.joinToString("\n", postfix = "\n")
    }
}
