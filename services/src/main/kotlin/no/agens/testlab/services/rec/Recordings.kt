package no.agens.testlab.services.rec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.agens.testlab.device.rec.Recording
import no.agens.testlab.device.rec.RecordingWriter
import no.agens.testlab.services.runtime.LabRuntime
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Session recordings, one per device, from the daemon's H.264 stream (no scrcpy or ffmpeg
 * needed). Files live in `<data>/recordings/<serial>/<started>/` until a report takes them; a
 * recording whose app was killed is turned into `recovered.mp4` at the next start.
 */
class Recordings(private val rt: LabRuntime) {
    private val root = File(rt.dirs.data, "recordings")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Live(val serial: String, val dir: File, val writer: RecordingWriter, var job: Job?, val startedAt: Long) {
        @Volatile var lastError: String? = null
    }

    private val live = ConcurrentHashMap<String, Live>()
    private val activeFlow = MutableStateFlow<Set<String>>(emptySet())
    /** Serials being recorded now (the UI shows a red dot). */
    val active: StateFlow<Set<String>> = activeFlow.asStateFlow()

    data class Status(val serial: String, val recording: Boolean, val seconds: Double, val frames: Int, val bytes: Long, val error: String?, val dir: File)

    private fun publish() { activeFlow.value = live.keys.toSet() + sims.keys + phones.keys }

    /** Real iPhones/iPads: WebDriverAgent's MJPEG stream through ffmpeg. */
    private class PhoneRec(val rec: MjpegRecorder, val job: Job)
    private val phones = ConcurrentHashMap<String, PhoneRec>()

    /** iOS Simulators record themselves (`simctl io recordVideo` writes the MP4). */
    private val sims = ConcurrentHashMap<String, no.agens.testlab.device.ios.XcrunCli.RecordVideo>()

    /** Starts recording [serial]; a no-op returning false when it already records. */
    suspend fun start(serial: String): Boolean {
        if (live.containsKey(serial) || sims.containsKey(serial) || phones.containsKey(serial)) return false
        if (rt.platformOf(serial) == no.agens.testlab.core.Platform.IOS) {
            if (rt.realIos(serial)) return startPhone(serial)
            val x = rt.xcrun ?: throw IllegalStateException("recording a simulator needs macOS with Xcode")
            rt.device(serial)
            val dir = File(root, "${safe(serial)}/${LocalDateTime.now().format(STAMP)}")
            val rv = withContext(Dispatchers.IO) { x.recordVideo(serial, File(dir, "sim.mp4")) }
            if (sims.putIfAbsent(serial, rv) != null) { rv.stop(); return false }
            publish()
            delay(1_000) // simctl needs a moment before the first frame
            return true
        }
        val dev = rt.device(serial) // connects, so a missing device fails here rather than silently later
        val dir = File(root, "${safe(serial)}/${LocalDateTime.now().format(STAMP)}")
        val w = RecordingWriter(dir)
        val l = Live(serial, dir, w, null, System.currentTimeMillis())
        if (live.putIfAbsent(serial, l) != null) { w.close(); dir.deleteRecursively(); return false }
        l.job = scope.launch {
            // A dropped connection (cable, daemon restart) reconnects and goes on in the same file:
            // the new stream starts with its own SPS/PPS and a key frame.
            while (isActive) {
                try {
                    dev.h264 { au -> w.frame(au) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    l.lastError = e.message
                }
                delay(2_000)
            }
        }
        publish()
        // Answer once frames flow (the encoder takes a second or two to start), so what the
        // caller does next is on the recording.
        val t0 = System.currentTimeMillis()
        while (w.frames == 0 && System.currentTimeMillis() - t0 < 3_000) delay(50)
        return true
    }

    /**
     * A real iPhone/iPad: WebDriverAgent's MJPEG stream (10 frames a second, half size) read through
     * the bridge, its newest frame fed to ffmpeg on a steady timer. Needs ffmpeg.
     */
    private suspend fun startPhone(serial: String): Boolean {
        val ffmpeg = no.agens.testlab.device.ToolLocator(appToolsDir = rt.dirs.cache).findOptional("ffmpeg")?.path
            ?: throw IllegalStateException("recording a real iPhone/iPad needs ffmpeg (brew install ffmpeg): take screenshots instead")
        val dev = rt.device(serial)
        runCatching { dev.mjpegSettings(PHONE_FPS, 50) }
        val dir = File(root, "${safe(serial)}/${LocalDateTime.now().format(STAMP)}")
        val rec = withContext(Dispatchers.IO) { MjpegRecorder(ffmpeg, File(dir, "phone.mp4"), PHONE_FPS) }
        val job = scope.launch {
            while (isActive) {
                try { dev.mirror(longEdge = 1280, fps = PHONE_FPS) { rec.frame(it) } } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                delay(1_000)
            }
        }
        if (phones.putIfAbsent(serial, PhoneRec(rec, job)) != null) { job.cancel(); rec.discard(); return false }
        publish()
        val t0 = System.currentTimeMillis()
        while (rec.frames == 0 && System.currentTimeMillis() - t0 < 5_000) delay(100)
        return true
    }

    fun status(serial: String? = null): List<Status> = phones.filterKeys { serial == null || it == serial }.map { (s, p) ->
        Status(s, true, (System.currentTimeMillis() - p.rec.startedAt) / 1000.0, p.rec.frames, p.rec.file.length(), null, p.rec.file.parentFile)
    } + live.values.filter { serial == null || it.serial == serial }.map { l ->
        Status(l.serial, true, (System.currentTimeMillis() - l.startedAt) / 1000.0, l.writer.frames, l.writer.bytes, l.lastError, l.dir)
    } + sims.filterKeys { serial == null || it == serial }.map { (s, rv) ->
        Status(s, true, (System.currentTimeMillis() - rv.startedAt) / 1000.0, 0, rv.file.length(), null, rv.file.parentFile)
    }

    fun isRecording(serial: String) = live.containsKey(serial) || sims.containsKey(serial) || phones.containsKey(serial)

    /** Stops [serial]'s recording and writes it to [dest] as MP4; null when nothing was recording. */
    suspend fun stop(serial: String, dest: File): Recording.Result? {
        phones.remove(serial)?.let { p ->
            publish()
            p.job.cancelAndJoin()
            return withContext(Dispatchers.IO) {
                val f = p.rec.stop() ?: return@withContext null.also { p.rec.file.parentFile?.deleteRecursively() }
                dest.parentFile?.mkdirs()
                f.copyTo(dest, overwrite = true); f.parentFile.deleteRecursively()
                val (w, h) = runCatching { rt.geometry(serial).let { it.width to it.height } }.getOrDefault(0 to 0)
                Recording.Result(dest, p.rec.frames, System.currentTimeMillis() - p.rec.startedAt, p.rec.startedAt, w, h, recovered = false)
            }
        }
        sims.remove(serial)?.let { rv ->
            publish()
            return withContext(Dispatchers.IO) {
                val f = rv.stop() ?: return@withContext null
                dest.parentFile?.mkdirs()
                f.copyTo(dest, overwrite = true); f.parentFile.deleteRecursively()
                val (w, h) = runCatching { rt.geometry(serial).let { it.width to it.height } }.getOrDefault(0 to 0)
                Recording.Result(dest, 0, System.currentTimeMillis() - rv.startedAt, rv.startedAt, w, h, recovered = false)
            }
        }
        val l = live.remove(serial) ?: return null
        publish()
        l.job?.cancelAndJoin()
        l.writer.stop()
        return withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            try {
                Recording.toMp4(l.dir, dest).also { l.dir.deleteRecursively() }
            } catch (e: Recording.NoVideo) {
                l.dir.deleteRecursively()
                null
            }
        }
    }

    /** Stops and deletes [serial]'s recording (or every recording when null). */
    suspend fun discard(serial: String? = null): List<String> {
        val phoneGone = phones.keys.filter { serial == null || it == serial }
        for (s in phoneGone) phones.remove(s)?.let { p -> p.job.cancelAndJoin(); withContext(Dispatchers.IO) { p.rec.discard() } }
        val simGone = sims.keys.filter { serial == null || it == serial } + phoneGone
        for (s in simGone) sims.remove(s)?.let { rv -> withContext(Dispatchers.IO) { rv.stop()?.parentFile?.deleteRecursively() } }
        val gone = live.keys.filter { serial == null || it == serial } + simGone
        for (s in gone) {
            val l = live.remove(s) ?: continue
            l.job?.cancelAndJoin()
            l.writer.close()
            l.dir.deleteRecursively()
        }
        publish()
        return gone
    }

    /**
     * At start-up: recordings that never got stopped (the app was killed) become `recovered.mp4`
     * in their folder. Returns what was recovered; [recovered] lists them for a report to take.
     */
    fun recoverAll(): List<File> = root.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }.filter { d ->
        d.isDirectory && File(d, "video.h264").isFile && !live.values.any { it.dir == d }
    }.mapNotNull { d ->
        runCatching { Recording.toMp4(d, File(d, "recovered.mp4")) }.getOrNull()?.let { r ->
            File(d, "video.h264").delete(); File(d, "frames.idx").delete()
            File(d, "recovered.json").writeText("""{"duration_ms":${r.durationMs},"first_frame_ms":${r.firstFrameMs},"frames":${r.frames},"width":${r.width},"height":${r.height}}""")
            r.file
        }
    }

    /** Recovered recordings of [serial] (newest first), each with the time its first frame was shown. */
    fun recovered(serial: String): List<File> = File(root, safe(serial)).listFiles().orEmpty()
        .mapNotNull { d -> File(d, "recovered.mp4").takeIf { it.isFile } }.sortedByDescending { it.parentFile.name }

    /** What [recoverAll] wrote next to a recovered.mp4. */
    fun recoveredResult(mp4: File): Recording.Result {
        val j = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(File(mp4.parentFile, "recovered.json").readText()) as kotlinx.serialization.json.JsonObject }.getOrNull()
        fun n(k: String) = (j?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        return Recording.Result(mp4, n("frames").toInt(), n("duration_ms"), n("first_frame_ms"), n("width").toInt(), n("height").toInt(), recovered = true)
    }

    suspend fun stopAll() { for (s in live.keys.toList() + sims.keys.toList()) runCatching { stop(s, File(File(root, safe(s)), "stopped-${System.currentTimeMillis()}.mp4")) } }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private const val PHONE_FPS = 10
        private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
