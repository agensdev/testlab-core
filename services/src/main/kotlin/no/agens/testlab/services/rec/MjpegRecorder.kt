package no.agens.testlab.services.rec

import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * A video from a stream of JPEG frames (a real iPhone/iPad's WebDriverAgent MJPEG stream): ffmpeg reads
 * JPEGs from a pipe (image2pipe) and writes H.264. The stream sends frames only when the screen
 * changes, so a timer feeds the newest frame [fps] times a second: the video keeps real time. ffmpeg
 * applies an iPad frame's EXIF turn itself.
 */
class MjpegRecorder(ffmpeg: File, val file: File, private val fps: Int = 10) {
    val startedAt = System.currentTimeMillis()
    @Volatile private var latest: ByteArray? = null
    @Volatile private var running = true
    @Volatile var frames = 0
        private set
    private val log = File(file.parentFile, "ffmpeg.log")
    private val proc: Process
    private val stdin: OutputStream
    private val feeder: Thread

    init {
        file.parentFile?.mkdirs()
        proc = ProcessBuilder(command(ffmpeg.path, fps, file)).redirectErrorStream(true).redirectOutput(log).start()
        stdin = proc.outputStream.buffered(1 shl 20)
        feeder = Thread({ feed() }, "mjpeg-rec-${file.parentFile?.name}").apply { isDaemon = true; start() }
    }

    /** The newest frame from the stream; the timer sends it. */
    fun frame(jpeg: ByteArray) { latest = jpeg }

    private fun feed() {
        val step = 1_000_000_000L / fps
        var next = System.nanoTime()
        while (running) {
            latest?.let { f -> runCatching { stdin.write(f); frames++ }.onFailure { running = false } }
            next += step
            val wait = next - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else next = System.nanoTime()
        }
    }

    /** Ends the video; the MP4, or null when no frame came (or ffmpeg failed: its log says why). */
    fun stop(): File? {
        running = false
        feeder.join(2_000)
        runCatching { stdin.flush(); stdin.close() }
        if (!proc.waitFor(60, TimeUnit.SECONDS)) proc.destroyForcibly()
        return file.takeIf { frames > 0 && it.isFile && it.length() > 0 }
    }

    fun discard() { running = false; runCatching { stdin.close() }; proc.destroyForcibly(); file.parentFile?.deleteRecursively() }

    companion object {
        /** JPEGs on stdin at [fps] → H.264 MP4 (even sizes for yuv420p). */
        fun command(ffmpeg: String, fps: Int, out: File) = listOf(
            ffmpeg, "-hide_banner", "-loglevel", "warning", "-y",
            "-f", "image2pipe", "-framerate", fps.toString(), "-c:v", "mjpeg", "-i", "-",
            "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
            out.path,
        )
    }
}
