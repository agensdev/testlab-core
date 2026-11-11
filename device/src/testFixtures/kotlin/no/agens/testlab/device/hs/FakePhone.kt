package no.agens.testlab.device.hs

import java.util.Collections

/**
 * A scripted phone behind a [FakeHsDaemon]: shows [screen] (a `dump_active` JSON), records taps,
 * keys and typed text, and answers the read verbs the app and the MCP tools use. [onTap] can
 * switch the screen, so flows (tap → next screen) can be scripted.
 */
class FakePhone(
    @Volatile var screen: String,
    val width: Int = 1080,
    val height: Int = 2400,
    @Volatile var top: String = "com.example.app/.MainActivity",
) : AutoCloseable {
    val taps: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())
    val keys: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val nodeCommands: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val typedText = StringBuffer()
    val typed: String get() = typedText.toString()
    @Volatile var keyboardShown = false
    @Volatile var onTap: (Int, Int) -> Unit = { _, _ -> }
    @Volatile private var clip = ""

    /** A text field: `%FIELD%` in [screen] shows its text (or [fieldHint] while empty, as Android reports it). */
    @Volatile var field = ""
    @Volatile var fieldHint = ""
    /** The field refuses a paste (`ERR:paste-rejected`), as some apps' fields do. */
    @Volatile var rejectPaste = false
    /** The next `text` command loses this many leading keys, like a keyboard that is still starting. */
    @Volatile var dropKeys = 0
    /** How the app reformats the field after each change (identity = no formatting). */
    @Volatile var format: (String) -> String = { it }
    @Volatile var h264Streams = 0

    private fun updateField(v: String) { field = format(v) }

    private fun jsonEscape(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun render(): String = screen.replace("%FIELD%", jsonEscape(field.ifEmpty { fieldHint }))

    val daemon = FakeHsDaemon.multiFrame { cmd -> answer(cmd) }
    val port: Int get() = daemon.port
    val commands: List<String> get() = daemon.commands

    private fun ok(s: String = "ok") = listOf(s.encodeToByteArray())

    private fun arg(cmd: String, key: String): Int = Regex("\\b$key=(-?\\d+)").find(cmd)!!.groupValues[1].toInt()

    private fun answer(cmd: String): List<ByteArray>? = when {
        cmd == "dump_active" -> ok(render())
        cmd == "info" -> ok("$width $height")
        cmd == "state top" -> ok(top)
        cmd == "state device" -> ok("""{"manufacturer":"Agens","model":"Fake Phone","release":"16","sdk":36,"width":$width,"height":$height,"rotation":0,"battery_level":100,"interactive":true,"top_activity":"$top"}""")
        cmd.startsWith("screenshot") -> listOf(JPEG_HEADER)
        // Like the daemon: the parameter sets first, then one access unit per frame (no end marker: a stream runs until closed).
        cmd.startsWith("stream_h264") -> { h264Streams++; H264_FRAMES }
        cmd.startsWith("tap ") -> { val x = arg(cmd, "x"); val y = arg(cmd, "y"); taps += x to y; onTap(x, y); ok() }
        cmd.startsWith("key ") -> { keys += cmd.removePrefix("key ").trim(); if (cmd.endsWith("BACK")) keyboardShown = false; ok() }
        cmd.startsWith("swipe_dir ") -> ok()
        cmd.startsWith("text ") -> {
            val keys = cmd.removePrefix("text ").drop(dropKeys)
            dropKeys = 0
            typedText.append(keys)
            updateField(field + keys)
            ok()
        }
        cmd.startsWith("clip_set ") -> { clip = cmd.removePrefix("clip_set "); ok() }
        cmd == "paste" || cmd.startsWith("paste ") -> if (rejectPaste) listOf("ERR:paste-rejected".encodeToByteArray()) else { typedText.append(clip); updateField(field + clip); ok() }
        cmd.startsWith("node_set_text") -> {
            nodeCommands += cmd
            Regex("value=\"([^\"]*)\"").find(cmd)?.let { updateField(it.groupValues[1]) }
            ok("ok set_text")
        }
        cmd.startsWith("node_") || cmd.startsWith("submit") -> { nodeCommands += cmd; if (cmd.startsWith("node_click")) listOf("ERR:not-found:fake".encodeToByteArray()) else ok("ok") }
        cmd.startsWith("am_start") || cmd.startsWith("am_force_stop") -> ok()
        cmd.startsWith("pm_list") -> ok("package:com.example.app\npackage:com.example.shop.feature")
        cmd.startsWith("dumpsys input_method") -> FakeHsDaemon.streamed("mInputShown=$keyboardShown".encodeToByteArray())
        cmd.startsWith("dumpsys") -> FakeHsDaemon.streamed(ByteArray(0))
        else -> ok("ERR:UNKNOWN_CMD:${cmd.substringBefore(' ')}")
    }

    override fun close() = daemon.close()

    companion object {
        /** testsrc-160x320-10fps.h264 (3 s, a key frame per second) as the daemon sends it: SPS+PPS, then one access unit per frame. */
        val H264_FRAMES: List<ByteArray> by lazy {
            val b = FakePhone::class.java.getResourceAsStream("/rec/testsrc-160x320-10fps.h264")!!.readBytes()
            val nals = no.agens.testlab.device.rec.H264.split(b)
            val sc = byteArrayOf(0, 0, 0, 1)
            val config = nals.filter { no.agens.testlab.device.rec.H264.type(it) in setOf(7, 8) }.fold(ByteArray(0)) { acc, n -> acc + sc + n }
            val aus = mutableListOf<ByteArray>()
            var cur = ByteArray(0)
            for (n in nals) {
                when (no.agens.testlab.device.rec.H264.type(n)) {
                    9 -> { if (cur.isNotEmpty()) aus += cur; cur = ByteArray(0) }
                    7, 8, 6 -> {}
                    else -> cur += sc + n
                }
            }
            if (cur.isNotEmpty()) aus += cur
            listOf(config) + aus
        }

        /** A real (tiny) JPEG of a 1080×2400-shaped screen, red over blue, so pictures can be decoded and scaled. */
        private val JPEG_HEADER: ByteArray by lazy {
            val img = java.awt.image.BufferedImage(54, 120, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 120) for (x in 0 until 54) img.setRGB(x, y, if (y < 60) 0xFF0000 else 0x0000FF)
            java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpeg", it) }.toByteArray()
        }
    }
}
