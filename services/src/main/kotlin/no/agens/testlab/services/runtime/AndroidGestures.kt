package no.agens.testlab.services.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android gestures outside the daemon's verbs, through adb: a smooth drag along a path (Testlab's own
 * touch injector, tl-touch.dex, run with app_process like scrcpy's server).
 */
class AndroidGestures(private val rt: LabRuntime) {
    private val pushed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    data class Drag(val via: String, val moves: Int?, val ms: Long?)

    /**
     * Press at the first point, hold [holdMs], move along [points] (pixels of the current orientation,
     * as handsets_tap_xy) in [durationMs] per pass, [repeat] passes (back and forth), release. A MOVE
     * every ~16 ms, timed on the phone. Falls back to `input motionevent` (one process per event:
     * the finger jumps, and games may ignore it) when the injector can't run.
     */
    suspend fun drag(serial: String, points: List<Pair<Int, Int>>, durationMs: Long, holdMs: Long, repeat: Int): Drag = withContext(Dispatchers.IO) {
        require(points.size >= 2) { "a drag needs at least two points" }
        val adb = rt.adb()
        val spec = points.joinToString(";") { "${it.first},${it.second}" }
        val timeout = holdMs + durationMs * repeat + 20_000
        val problem = runCatching {
            val remote = ensureDex(serial)
            val r = adb.shell(serial, "CLASSPATH=$remote app_process / $MAIN '$spec' $durationMs $holdMs $repeat", timeout)
            val m = Regex("ok moves=(\\d+) ms=(\\d+)").find(r.stdout)
            if (m != null) return@withContext Drag("injector", m.groupValues[1].toInt(), m.groupValues[2].toLong())
            (r.stdout + r.stderr).trim().lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "exit ${r.exit}"
        }.getOrElse { it.message ?: it.toString() }
        // Fallback: one shell, but each `input` is its own process.
        val cmds = buildList {
            add("input motionevent DOWN ${points[0].first} ${points[0].second}")
            if (holdMs > 0) add("sleep ${"%.2f".format(java.util.Locale.ROOT, holdMs / 1000.0)}")
            for (pass in 0 until repeat) {
                val path = if (pass % 2 == 1) points.reversed() else points
                path.drop(1).forEach { (x, y) -> add("input motionevent MOVE $x $y") }
            }
            val last = if (repeat % 2 == 0) points.first() else points.last()
            add("input motionevent UP ${last.first} ${last.second}")
        }
        adb.shell(serial, cmds.joinToString("; "), timeout)
        Drag("input motionevent (the injector failed: $problem)", null, null)
    }

    /** Pushes the injector once per version (its CRC in the file name) and gives its path on the phone. */
    private fun ensureDex(serial: String): String {
        val bytes = AndroidGestures::class.java.getResourceAsStream("/touch/tl-touch.dex")?.use { it.readBytes() }
            ?: throw IllegalStateException("tl-touch.dex is missing from this build")
        val crc = java.util.zip.CRC32().apply { update(bytes) }.value.toString(16)
        val remote = "/data/local/tmp/tl-touch-$crc.dex"
        if ("$serial|$remote" in pushed) return remote
        val adb = rt.adb()
        if (adb.shell(serial, "ls $remote 2>/dev/null").stdout.trim() != remote) {
            val local = File(rt.dirs.cache, "tl-touch-$crc.dex").apply { parentFile?.mkdirs(); writeBytes(bytes) }
            adb.push(serial, local, remote)
        }
        pushed += "$serial|$remote"
        return remote
    }

    /**
     * The home screen, with a clean state when [close]: the app in front (not the launcher) and
     * [packages] force-stopped, then HOME. Returns what was stopped.
     */
    suspend fun home(serial: String, packages: List<String>, close: Boolean): List<String> = withContext(Dispatchers.IO) {
        val adb = rt.adb()
        val stopped = mutableListOf<String>()
        if (close) {
            val launcher = adb.shell(serial, "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").stdout
                .lines().lastOrNull { '/' in it }?.substringBefore('/')?.trim()
            val front = Regex("topResumedActivity=ActivityRecord\\{\\S+ \\S+ ([A-Za-z0-9._]+)/").find(adb.shell(serial, "dumpsys activity activities | grep -m1 topResumedActivity").stdout)?.groupValues?.get(1)
            val installed = runCatching { rt.ops(serial).packages(serial) }.getOrDefault(emptyList()).toSet()
            val targets = (listOfNotNull(front) + packages.filter { it in installed }).distinct()
                .filter { it != launcher && it != "com.android.systemui" && it.isNotBlank() }
            for (p in targets) { adb.shell(serial, "am force-stop ${shQuote(p)}"); stopped += p }
        }
        adb.shell(serial, "input keyevent KEYCODE_HOME")
        stopped
    }

    /**
     * Starts [component] (a package, or pkg/.Activity) with launch values: `am start` options such as
     * `--es key value`, `--ez key true`, `--ei key 1`. A package alone starts its launcher activity.
     * The values are passed as they are (quoted), never shown.
     */
    suspend fun launch(serial: String, component: String, args: List<String>): Unit = withContext(Dispatchers.IO) {
        val adb = rt.adb()
        val pkg = component.substringBefore('/')
        require(Regex("[A-Za-z0-9_.]+").matches(pkg)) { "not a package name: $pkg" }
        val c = if ('/' in component) component else adb.shell(serial, "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${shQuote(pkg)}").stdout
            .lines().lastOrNull { it.trim().startsWith("$pkg/") }?.trim() ?: throw IllegalStateException("$pkg has no launcher activity on this device (is it installed?)")
        val r = adb.shell(serial, "am start -n ${shQuote(c)} " + args.joinToString(" ") { shQuote(it) }, 20_000)
        val out = (r.stdout + r.stderr).lines().map { it.trim() }
        out.firstOrNull { it.startsWith("Error") || it.startsWith("Exception") || "does not exist" in it }?.let { throw IllegalStateException("could not start $c: $it") }
    }

    data class Orientation(val rotation: Int, val autoRotate: Boolean?, val userRotation: Int?, val physical: Pair<Int, Int>? = null) {
        val name: String get() = NAMES.getOrElse(rotation) { "rotation $rotation" }
        /** The screen in pixels as it is turned now. */
        val screen: Pair<Int, Int>? get() = physical?.let { (a, b) -> if (rotation % 2 == 1) maxOf(a, b) to minOf(a, b) else minOf(a, b) to maxOf(a, b) }
    }

    /** The rotation of the phone's own screen (display 0), and the rotation settings. */
    suspend fun orientation(serial: String): Orientation = withContext(Dispatchers.IO) {
        val adb = rt.adb()
        val rotation = displayRotation(adb.shell(serial, "dumpsys window displays", 20_000).stdout)
            ?: throw IllegalStateException("no rotation for display 0 in `dumpsys window displays`")
        val s = adb.shell(serial, "settings get system accelerometer_rotation; settings get system user_rotation; wm size").stdout.lines()
        val n = s.map { it.trim().toIntOrNull() }
        val size = s.firstNotNullOfOrNull { l -> Regex("(?:Override|Physical) size: (\\d+)x(\\d+)").find(l)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() } }
        Orientation(rotation, n.getOrNull(0)?.let { it == 1 }, n.getOrNull(1), size)
    }

    /**
     * portrait: user rotation 0 and auto-rotate on (as most people have it); landscape (1) and
     * landscape-right (3): auto-rotate off and that user rotation. Waits up to 3 s for the screen to
     * turn; an app that fixes its own orientation keeps it, which the returned orientation shows.
     */
    suspend fun setOrientation(serial: String, mode: String): Orientation = withContext(Dispatchers.IO) {
        val want = when (mode) { "portrait" -> 0; "landscape" -> 1; "landscape-right" -> 3; else -> throw IllegalArgumentException("orientation: get, portrait, landscape or landscape-right") }
        // The window manager's own command (Android 12+) on display 0; the settings as the fallback. Setting
        // user_rotation alone makes the window manager lock rotation again right after, so auto-rotate is set last.
        val cmd = if (want == 0) "cmd window user-rotation -d 0 lock 0 && cmd window user-rotation -d 0 free || { settings put system user_rotation 0; settings put system accelerometer_rotation 1; }"
            else "cmd window user-rotation -d 0 lock $want || { settings put system accelerometer_rotation 0; settings put system user_rotation $want; }"
        rt.adb().shell(serial, cmd)
        if (want == 0) {
            Thread.sleep(300)
            if (rt.adb().shell(serial, "settings get system accelerometer_rotation").stdout.trim() != "1") rt.adb().shell(serial, "settings put system accelerometer_rotation 1")
        }
        val until = System.currentTimeMillis() + 3_000
        var now = orientation(serial)
        while (now.rotation != want && System.currentTimeMillis() < until) { Thread.sleep(250); now = orientation(serial) }
        now
    }

    companion object {
        val NAMES = listOf("portrait", "landscape", "portrait-upside-down", "landscape-right")

        /**
         * Display 0's rotation from `dumpsys window displays`. Screen mirroring and casting add virtual
         * displays, listed first and never rotated, so the first `mRotation=` is the wrong one.
         */
        fun displayRotation(dump: String): Int? {
            val sections = dump.split(Regex("(?m)^\\s*Display: mDisplayId="))
            val main = sections.drop(1).firstOrNull { Regex("^0\\b").containsMatchIn(it) } ?: return null
            Regex("(?m)^\\s*mRotation=(\\d)").find(main)?.let { return it.groupValues[1].toInt() }
            return Regex("mDisplayRotation=ROTATION_(\\d+)").find(main)?.groupValues?.get(1)?.toIntOrNull()?.let { it / 90 }
        }

        const val MAIN = "no.agens.testlab.touch.Touch"
    }
}
