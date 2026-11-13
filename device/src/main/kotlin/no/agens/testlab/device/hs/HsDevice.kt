package no.agens.testlab.device.hs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import no.agens.testlab.core.dump.UiDumpRender
import no.agens.testlab.core.autopilot.HsRunner
import no.agens.testlab.core.sel.NodeQuery
import no.agens.testlab.core.sel.TapHit
import java.io.File

/**
 * One phone, driven over a persistent connection to its daemon. Calls are serialized. A broken
 * connection is re-established once (re-using or re-starting the daemon); read-only calls are
 * retried after that, actions are not, because a tap that went through must not be sent twice.
 */
class HsDevice(
    val serial: String,
    private val connect: () -> HsEndpoint,
) : AutoCloseable {
    private val lock = Mutex()
    private var endpoint: HsEndpoint? = null
    private var conn: HsConnection? = null

    /** When the last input (tap, key, swipe, typing, launch) went to the phone: reads right after it may predate its effect. */
    @Volatile var lastInputMs = 0L
        private set

    /** For inputs sent around the daemon (adb `input`, `am start`): the next read waits for the screen to settle too. */
    fun markInput() { lastInputMs = System.currentTimeMillis() }

    private suspend fun <T> call(idempotent: Boolean, block: (HsConnection) -> T): T = withContext(Dispatchers.IO) {
        // Non-idempotent calls are the inputs; reads are idempotent.
        if (!idempotent) lastInputMs = System.currentTimeMillis()
        lock.withLock {
            var reconnected = false
            while (true) {
                val c = conn?.takeIf { it.isOpen } ?: openConnection()
                try {
                    return@withLock block(c)
                } catch (e: HsDisconnected) {
                    conn?.close()
                    conn = null
                    endpoint = null
                    if (reconnected || !idempotent) throw e
                    reconnected = true
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    private fun openConnection(): HsConnection {
        val ep = endpoint ?: connect().also { endpoint = it }
        return HsConnection("127.0.0.1", ep.hostPort).also { conn = it }
    }

    // --- reads ---

    suspend fun dumpJson(): JsonElement = Json.parseToJsonElement(call(true) { it.callText("dump_active", timeoutMs = 15_000) })
    suspend fun tree(settle: Boolean = false): String = UiDumpRender.renderTree(if (settle) settledDump() else dumpJson())
    suspend fun table(settle: Boolean = false): String = UiDumpRender.renderTable(if (settle) settledDump() else dumpJson())

    /**
     * The tree once the screen stopped changing, when an input went out less than [windowMs] ago:
     * a dump right after a tap or back can still be the previous screen (the accessibility tree
     * lags the display). Two reads [pollMs] apart that agree end the wait; at most [maxMs].
     */
    suspend fun settledDump(windowMs: Long = 1_500, pollMs: Long = 250, maxMs: Long = 2_000): JsonElement {
        var d = dumpJson()
        if (System.currentTimeMillis() - lastInputMs > windowMs) return d
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            delay(pollMs)
            val n = dumpJson()
            if (withoutTs(n) == withoutTs(d)) return n
            d = n
        }
        return d
    }

    private fun withoutTs(e: JsonElement): JsonElement = (e as? kotlinx.serialization.json.JsonObject)?.let { kotlinx.serialization.json.JsonObject(it - "ts") } ?: e
    suspend fun topActivity(): String = call(true) { it.callText("state top") }.trim()

    /** The daemon's device snapshot (`state device`): model, release, sdk, battery, network, top activity… */
    suspend fun deviceState(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(call(true) { it.callText("state device") }) as kotlinx.serialization.json.JsonObject

    /** Installed packages (`pm_list`), one per line as the daemon prints them. */
    suspend fun listApps(thirdPartyOnly: Boolean = false): String = call(true) { it.callText(if (thirdPartyOnly) "pm_list 3" else "pm_list") }.trim()

    /** Physical screen size in pixels. */
    suspend fun screenSize(): Pair<Int, Int> {
        val parts = call(true) { it.callText("info") }.trim().split(' ')
        return parts[0].toInt() to parts[1].toInt()
    }

    /** JPEG/WebP/PNG bytes; [size] is the long edge (null = native). FLAG_SECURE screens raise HsError("secure-window"). */
    suspend fun screenshot(size: Int? = 768, quality: Int = 80, format: String = "jpeg"): ByteArray {
        val cmd = buildString {
            append("screenshot")
            if (size == null) append(" max=1") else append(" size=").append(size)
            append(" q=").append(quality).append(" fmt=").append(format)
        }
        return call(true) { it.call(cmd, timeoutMs = 20_000) }
    }

    /**
     * The live mirror: JPEG frames from the daemon's `stream` verb on a connection of its own, so
     * it never holds up tool calls. Frames come when the screen changes, at most [fps] a second.
     * Runs until the coroutine is cancelled or the stream ends.
     */
    suspend fun mirror(longEdge: Int = 720, fps: Int = 12, quality: Int = 60, onFrame: (ByteArray) -> Unit) =
        streamFrames("stream size=$longEdge q=$quality fps=$fps", "tl-mirror-$serial", onFrame)

    /**
     * The screen as H.264 (the daemon's `stream_h264`) on a connection of its own: the first frame
     * carries SPS/PPS, then one access unit per frame, a key frame every [gopSec] seconds. Frames
     * only come when the screen changes. For recordings.
     */
    suspend fun h264(longEdge: Int = 1280, kbps: Int = 2_500, fps: Int = 30, gopSec: Int = 2, onFrame: (ByteArray) -> Unit) =
        streamFrames("stream_h264 size=$longEdge kbps=$kbps fps=$fps gop=$gopSec", "tl-h264-$serial", onFrame)

    /** Asks every running H.264 stream on the phone for a key frame now (a clean start for a clip). */
    suspend fun keyframe() { call(true) { it.callText("keyframe") } }

    private suspend fun streamFrames(cmd: String, threadName: String, onFrame: (ByteArray) -> Unit) {
        val ep = withContext(Dispatchers.IO) { lock.withLock { endpoint ?: connect().also { endpoint = it } } }
        val c = withContext(Dispatchers.IO) { HsConnection("127.0.0.1", ep.hostPort, readTimeoutMs = 0) }
        try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { c.close() }
                kotlin.concurrent.thread(isDaemon = true, name = threadName) {
                    try {
                        c.stream(cmd) { f -> onFrame(f); cont.isActive }
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    }
                }
            }
        } finally {
            c.close()
        }
    }

    /** `dumpsys <service> [args]` through the daemon (no adb round trip). */
    suspend fun dumpsys(service: String): String = call(true) { it.collect("dumpsys $service").decodeToString() }

    /** Whether the soft keyboard is up (`mInputShown` / `mIsInputViewShown` in `dumpsys input_method`). */
    suspend fun imeShown(): Boolean {
        val d = dumpsys("input_method")
        return "mInputShown=true" in d || "mIsInputViewShown=true" in d
    }

    // --- actions ---

    /**
     * Hides the soft keyboard: BACK, but only while the keyboard is up (BACK on a screen without a
     * keyboard navigates away). The lab tapped a fixed chevron position instead; see PARITY.md.
     */
    suspend fun hideKeyboard(): Boolean {
        if (!imeShown()) return false
        key("BACK")
        return true
    }

    suspend fun tap(x: Int, y: Int) { call(false) { it.callText("tap x=$x y=$y") } }
    suspend fun swipeDir(dir: String) { call(false) { it.callText("swipe_dir ${dir.lowercase()}") } }
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int = 300) {
        call(false) { it.callText("swipe x1=$x1 y1=$y1 x2=$x2 y2=$y2 dur=$durMs") }
    }
    /** The profile's noise dialogs (Profile.dismiss), set by the runtime. */
    @Volatile var noise: List<no.agens.testlab.core.profile.Profile.Dismiss> = emptyList()

    /** Dismisses a noise dialog on screen ([dump], else a fresh one) with its named button; its name, or null. */
    suspend fun dismissNoise(dump: JsonElement? = null): String? {
        if (noise.isEmpty()) return null
        val hit = no.agens.testlab.core.sel.NoiseDialogs.find(dump ?: dumpJson(), noise) ?: return null
        val b = hit.button.bounds ?: return null
        tap(b.centerX, b.centerY)
        return "${hit.rule.name} («${hit.button.text.ifEmpty { hit.button.desc }}»)"
    }

    /**
     * After launching [pkg]: watches up to [ms] for a noise dialog of a rule naming [pkg] and dismisses
     * it; what was dismissed, or null. Nothing to watch when no rule names the package.
     */
    suspend fun dismissNoiseAfterLaunch(pkg: String, ms: Long = 5_000): String? {
        if (noise.none { pkg in it.packages }) return null
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            runCatching { dismissNoise() }.getOrNull()?.let { return it }
            delay(500)
        }
        return null
    }

    /** iOS bridge verb: the home screen, closing the app in front and [bundles] first when [close]; the bundles closed. */
    suspend fun home(close: Boolean, bundles: List<String>): List<String> =
        call(false) { it.callText("home close=${if (close) 1 else 0} pkgs=${bundles.joinToString(",")}", timeoutMs = 60_000) }.trim().split(',').filter { it.isNotBlank() }

    /** iOS bridge verb: WebDriverAgent's MJPEG stream rate (frames a second) and scale (percent). */
    suspend fun mjpegSettings(fps: Int, scalePercent: Int) { call(false) { it.callText("mjpeg_settings fps=$fps scale=$scalePercent") } }

    /** iOS bridge verbs; Android's orientation goes through adb (AndroidGestures). */
    suspend fun orientation(): String = call(true) { it.callText("orientation") }.trim()
    suspend fun setOrientation(mode: String): String = call(false) { it.callText("orientation_set $mode", timeoutMs = 30_000) }.trim()

    /** A drag along [points] (iOS bridge verb; Android drags go through the touch injector, see AndroidGestures). */
    suspend fun drag(points: List<Pair<Int, Int>>, durMs: Int, holdMs: Int, repeat: Int) {
        val pts = points.joinToString(";") { "${it.first},${it.second}" }
        call(false) { it.callText("drag pts=$pts dur=$durMs hold=$holdMs repeat=$repeat", timeoutMs = 30_000 + holdMs + durMs * repeat) }
    }
    suspend fun key(name: String) { call(false) { it.callText("key ${name.uppercase()}") } }
    suspend fun keyCode(code: Int) { call(false) { it.callText("key code=$code") } }
    /**
     * Starts [component] (a package, `pkg/.Activity`, or an iOS bundle id). [args] are launch
     * arguments, which only an iOS Simulator takes: they follow ` -- `, separated by U+001F.
     */
    /** Starts an app with launch values on Android (adb `am start` extras); set by the runtime. */
    @Volatile var launchWith: (suspend (component: String, args: List<String>) -> Unit)? = null

    suspend fun amStart(component: String, args: List<String> = emptyList()) {
        val via = launchWith
        if (args.isNotEmpty() && via != null) { via(component, args); markInput(); return }
        val cmd = "am_start n=$component" + if (args.isEmpty()) "" else " -- " + args.joinToString(ARG_SEP)
        call(false) { it.callText(cmd) }
    }
    suspend fun forceStop(pkg: String) { call(false) { it.callText("am_force_stop $pkg") } }

    /** iOS Simulators: taps Open on the system's «Open in “App”?» prompt; true when there was one. */
    suspend fun acceptOpenPrompt(): Boolean = call(false) { it.callText("open_prompt_accept") }.trim() == "accepted"

    /**
     * Types into the focused field. The daemon's `text` verb only knows the virtual keymap
     * (no æøå), trims the command (trailing spaces vanish) and caps commands at 256 bytes, so
     * text is sent in chunks: printable ASCII as key events, anything else through the
     * clipboard and a paste, trailing spaces as SPACE key presses.
     */
    suspend fun typeText(text: String): String {
        var via = "typed"
        for (chunk in TypeChunks.split(text)) when (chunk) {
            is TypeChunks.Chunk.Keys -> typeKeysChecked(chunk.text)
            is TypeChunks.Chunk.Paste -> {
                // Some fields refuse a paste (Android 16 on some apps): set the focused field's text instead.
                val rejected = try {
                    val r = call(false) { c -> c.callText("clip_set ${chunk.text}"); c.callText("paste") }
                    isPasteRejected(r)
                } catch (e: HsError) {
                    if (!isPasteRejected(e.raw)) throw e
                    true
                }
                if (rejected) { appendToFocused(chunk.text); via = "set as the field's text (the field refused a paste)" }
            }
            is TypeChunks.Chunk.Spaces -> repeat(chunk.count) { keyCode(KEYCODE_SPACE) }
        }
        return via
    }

    private fun isPasteRejected(s: String) = s.lowercase().let { "paste" in it && "reject" in it }

    private suspend fun appendToFocused(text: String) {
        val n = focusedField() ?: throw HsError("NOT_FOUND", "the field refused a paste and no focused input field was found to set the text on", "")
        val cmd = NodeQuery.quoted(fieldText(n) + text)?.let { "node_set_text ${NodeQuery.daemonSelector(n)} value=$it" } ?: throw HsError("TOO_LONG", "text too long to set", "")
        call(false) { it.callText(cmd) }
    }

    /** Replaces the focused field's text with [text] (clears it first), instead of typing after what is there. */
    suspend fun replaceFocused(text: String): String {
        val n = focusedField() ?: throw HsError("NOT_FOUND", "no focused input field: tap the field first, or use handsets_fill(selector, value)", "")
        return fill(NodeQuery.daemonSelector(n), text)
    }

    /**
     * `hs tap QUERY`: `#id`, a label (exact, then case-insensitive, then substring; text before
     * content description) or a CSS-like selector, resolved on a fresh dump (see [NodeQuery]).
     * Clicks by id when the node has one (no gesture), else taps the centre. Matches that are
     * off screen are refused rather than tapped at a clipped position. Retried on a new dump while
     * nothing matches, which covers a screen that is still loading.
     */
    suspend fun tapText(query: String, nth: Int = 0, attempts: Int = 3, retryDelayMs: Long = 250): String {
        var last: Exception? = null
        repeat(attempts) { attempt ->
            if (attempt > 0) delay(retryDelayMs)
            val hits = NodeQuery.tapHits(dumpJson(), query)
            val onScreen = hits.filter { 'v' in it.flags && it.bounds.hasArea }
            val hit = onScreen.getOrNull(nth)
            if (hit == null) {
                last = when {
                    hits.isEmpty() -> HsError("NOT_FOUND", "no a11y node matched ${NodeQuery.debugQuote(query)}", "")
                    onScreen.isEmpty() -> HsError("OFF_SCREEN", "${NodeQuery.debugQuote(hits.first().label)} is off screen: swipe to bring it into view", "")
                    else -> HsError("NOT_FOUND", "nth=$nth out of range (${onScreen.size} matches on screen)", "")
                }
                return@repeat
            }
            try {
                return NodeQuery.tapReport(hit, clickOrTap(hit))
            } catch (e: HsError) {
                last = e
            }
        }
        throw last ?: HsError("NOT_FOUND", query, "")
    }

    /**
     * The on-screen node [query] names, from a fresh dump (retried while nothing matches): where a
     * gesture that is not a click (a TalkBack touch) should land.
     */
    suspend fun locate(query: String, nth: Int = 0, attempts: Int = 3, retryDelayMs: Long = 250): TapHit {
        var last: HsError? = null
        repeat(attempts) { attempt ->
            if (attempt > 0) delay(retryDelayMs)
            val hits = NodeQuery.tapHits(dumpJson(), query)
            val onScreen = hits.filter { 'v' in it.flags && it.bounds.hasArea }
            onScreen.getOrNull(nth)?.let { return it }
            last = when {
                hits.isEmpty() -> HsError("NOT_FOUND", "no a11y node matched ${NodeQuery.debugQuote(query)}", "")
                onScreen.isEmpty() -> HsError("OFF_SCREEN", "${NodeQuery.debugQuote(hits.first().label)} is off screen: swipe to bring it into view", "")
                else -> HsError("NOT_FOUND", "nth=$nth out of range (${onScreen.size} matches on screen)", "")
            }
        }
        throw last ?: HsError("NOT_FOUND", query, "")
    }

    private suspend fun clickOrTap(hit: TapHit): String {
        NodeQuery.clickSelector(hit)?.let { sel ->
            try {
                call(false) { it.callText("node_click $sel") }
                return "click"
            } catch (e: HsError) {
                // A custom view that only listens for touches rejects the click; a label that changed since the dump is not found.
                if ("rejected" !in e.raw && "not-found" !in e.raw) throw e
            }
        }
        tap(hit.bounds.centerX, hit.bounds.centerY)
        return "tap"
    }

    /**
     * `hs fill SELECTOR VALUE`: sets an input's text in one accessibility action (no keyboard).
     * SELECTOR is daemon grammar (`id=…`, `text~=…`, `class=EditText`) or free text naming the
     * field by its text, hint or a label next to it. A value the daemon can't quote, or one too long
     * for one command, is typed into the cleared field instead.
     */
    suspend fun fill(selector: String, value: String): String {
        val resolved = NodeQuery.fillSelector(dumpJson(), selector)
            ?: throw HsError("NOT_FOUND", "fill: no input widget matched ${NodeQuery.debugQuote(selector)}: pass an explicit selector (id=… or class=EditText text~=…)", "")
        val cmd = NodeQuery.quoted(value)?.let { "node_set_text $resolved value=$it" }
        if (cmd != null && cmd.encodeToByteArray().size <= HsWire.MAX_CMD) {
            call(false) { it.callText(cmd) }
            return "ok set_text $resolved"
        }
        call(false) { it.callText("node_set_text $resolved value=\"\"") }
        try { call(false) { it.callText("node_focus $resolved") } } catch (e: HsError) { if ("rejected" !in e.raw) throw e }
        typeText(value)
        return "ok typed into $resolved"
    }

    /** IME action (Done/Search/Next) on the focused field, or on the field matched by [selector]. */
    suspend fun submit(selector: String? = null): String =
        call(false) { it.callText(if (selector.isNullOrBlank()) "submit" else "submit $selector") }.trim()

    /**
     * `hs wait SPEC`: a duration (`500ms`, `2s`), `idle [duration]`, a package or `pkg/.Activity`
     * in front, or else something on screen: `#id`, text or content description (case-insensitive
     * substring) or a CSS-like selector, polled on the dump.
     */
    suspend fun waitFor(spec: String, timeoutMs: Long = 15_000, pollMs: Long = 250): String {
        val s = spec.trim()
        parseDurationMs(s)?.let { delay(it); return "ok slept ${it}ms" }
        if (s == "idle" || s.startsWith("idle ")) {
            val idle = parseDurationMs(s.removePrefix("idle").trim()) ?: 200
            return call(true) { it.callText("wait_for_idle idle_ms=$idle timeout_ms=$timeoutMs", timeoutMs = (timeoutMs + 5_000).toInt()) }.trim()
        }
        if (isComponent(s) || isPkg(s)) {
            return call(true) { it.callText("wait_for_activity n=$s timeout_ms=$timeoutMs", timeoutMs = (timeoutMs + 5_000).toInt()) }.trim()
        }
        val t0 = System.currentTimeMillis()
        while (true) {
            val found = try { NodeQuery.present(dumpJson(), s) } catch (e: HsError) { if (e.tag == "secure-window") null else throw e }
            if (found != null) return "ok ${NodeQuery.debugQuote(found)} after ${System.currentTimeMillis() - t0}ms"
            if (System.currentTimeMillis() - t0 >= timeoutMs) throw HsError("TIMEOUT", "wait_for ${NodeQuery.debugQuote(s)}: not on screen after ${timeoutMs / 1000.0}s", "")
            delay(pollMs)
        }
    }

    /**
     * Key events, checked against the focused field. A keyboard that is still starting (a field
     * that just got focus, a keyboard app that was not running) swallows the first keys: the
     * emulator turned "7Xq…" into "Xq…". When the field then reads exactly the old text plus the
     * typed text minus its first one to three characters, the intended text is set in one action.
     * Anything else (an app that formats its input, a password field, no focused field in the
     * accessibility tree) is left alone.
     */
    private suspend fun typeKeysChecked(keys: String) {
        val field = focusedField()
        val before = field?.takeIf { 'p' !in it.flags }?.let(::fieldText)
        call(false) { it.callText("text $keys") }
        if (before == null || keys.length < 2) return
        val expected = before + keys
        var after: String? = null
        for (i in 0 until 5) {
            after = focusedField()?.let(::fieldText)
            if (after == expected) return
            if (i < 4) delay(60)
        }
        val now = after ?: return
        if ((1..minOf(3, keys.length - 1)).none { k -> now == before + keys.substring(k) }) return
        val q = NodeQuery.quoted(expected) ?: return
        val cmd = "node_set_text value=$q" // no selector: the field with input focus
        if (cmd.encodeToByteArray().size <= HsWire.MAX_CMD) call(false) { it.callText(cmd) }
    }

    private suspend fun focusedField(): no.agens.testlab.core.sel.UiNode? = try {
        no.agens.testlab.core.sel.UiNode.primaryRoot(dumpJson())?.walk()?.firstOrNull { 'F' in it.flags && NodeQuery.isInputWidget(it) }
    } catch (e: HsError) {
        null
    }

    /** An empty field reports its hint as its text. */
    private fun fieldText(n: no.agens.testlab.core.sel.UiNode): String = if (n.text.isNotEmpty() && n.text == n.str("hint")) "" else n.text

    /**
     * The engine's `hs`-style commands on this device (see [HsRunner]). Only the verbs the
     * engine and plan runner use are implemented; anything else is a programming error.
     */
    fun asHsRunner(): HsRunner = HsRunner { args ->
        when (args.firstOrNull()) {
            "ui" -> if (noise.isEmpty()) { if ("--tree" in args) tree().trim() else table().trim() } else {
                // A noise dialog (the profile's dismiss list) goes before the autopilot reads the screen: not a step.
                var d = dumpJson()
                repeat(2) { if (dismissNoise(d) != null) { delay(800); d = dumpJson() } }
                (if ("--tree" in args) UiDumpRender.renderTree(d) else UiDumpRender.renderTable(d)).trim()
            }
            "show" -> if (args.getOrNull(1) == "top") topActivity() else error("unsupported: show ${args.drop(1)}")
            "tap" -> {
                val a = args.drop(1)
                val x = a.getOrNull(0)?.toIntOrNull()
                val y = a.getOrNull(1)?.toIntOrNull()
                if (x != null && y != null) { tap(x, y); "ok" } else tapText(a.joinToString(" "))
            }
            "swipe" -> { swipeDir(args[1]); "ok" }
            "go" -> { key(args[1]); "ok" }
            "type" -> { typeText(args.drop(1).joinToString(" ")); "ok" }
            "open" -> { amStart(args[1], args.drop(2)); "ok" }
            "see" -> {
                val path = File(args[1])
                val size = args.indexOf("--size").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 768
                val fmt = when (path.extension.lowercase()) { "png" -> "png"; "webp" -> "webp"; else -> "jpeg" }
                val bytes = screenshot(if ("--native" in args) null else size, format = fmt)
                path.parentFile?.mkdirs()
                path.writeBytes(bytes)
                "saved ${bytes.size} bytes to $path"
            }
            else -> error("unsupported hs command: ${args.joinToString(" ")}")
        }
    }

    override fun close() {
        conn?.close()
        conn = null
    }

    companion object {
        /** Between launch arguments on the wire (they may hold spaces). */
        const val ARG_SEP = "\u001f"
        const val KEYCODE_SPACE = 62

        private val DURATION = Regex("^(\\d+)(ms|s)$")

        /** `500ms` / `2s`, as `hs wait` reads them. */
        fun parseDurationMs(s: String): Long? = DURATION.matchEntire(s.trim())?.let { m ->
            val n = m.groupValues[1].toLong()
            if (m.groupValues[2] == "s") n * 1000 else n
        }

        /** `pkg/.Activity` or `pkg/full.Class`. */
        fun isComponent(s: String): Boolean = '/' in s && ' ' !in s && isPkg(s.substringBefore('/'))

        /** Dot-separated identifier with no slash or space (upstream `is_pkg`). */
        fun isPkg(s: String): Boolean = '/' !in s && '.' in s && s.all { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '_' }
    }
}

/** Splits text for typing (see [HsDevice.typeText]); pure so it is unit-tested. */
object TypeChunks {
    sealed interface Chunk {
        data class Keys(val text: String) : Chunk
        data class Paste(val text: String) : Chunk
        data class Spaces(val count: Int) : Chunk
    }

    /** Leaves room for the verb ("clip_set " is the longest) inside the 256-byte command cap. */
    const val MAX_BYTES = 240

    private fun isKeymap(c: Char) = c in ' '..'~'

    /**
     * Pure keymap text is typed as key events. Anything with other characters is pasted whole
     * (one clipboard round trip per ≤240-byte chunk instead of one per run: the emulator spent
     * 4.5 s on ten small pastes for "Blåbær syltetøy ÆØÅ"), with edge spaces typed as keys.
     */
    fun split(text: String): List<Chunk> {
        val out = mutableListOf<Chunk>()
        emitRun(text, text.all(::isKeymap), out)
        return out
    }

    private fun emitRun(run: String, keys: Boolean, out: MutableList<Chunk>) {
        var start = 0
        while (start < run.length) {
            var end = start
            var bytes = 0
            while (end < run.length) {
                val cpLen = if (run[end].isHighSurrogate() && end + 1 < run.length) 2 else 1
                val b = run.substring(end, end + cpLen).encodeToByteArray().size
                if (bytes + b > MAX_BYTES) break
                bytes += b
                end += cpLen
            }
            val piece = run.substring(start, end)
            // The daemon trims each command, so trailing (and, for clip_set, leading) spaces are typed as keys.
            val body = piece.trimEnd(' ')
            val trailing = piece.length - body.length
            if (body.isNotEmpty()) {
                if (keys) out += Chunk.Keys(body) else {
                    val lead = body.length - body.trimStart(' ').length
                    if (lead > 0) out += Chunk.Spaces(lead)
                    out += Chunk.Paste(body.trimStart(' '))
                }
            }
            if (trailing > 0) out += Chunk.Spaces(trailing)
            start = end
        }
    }
}
