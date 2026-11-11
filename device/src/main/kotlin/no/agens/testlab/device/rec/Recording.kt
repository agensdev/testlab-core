package no.agens.testlab.device.rec

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * A screen recording on disk while it runs: `video.h264` (the encoder's access units, Annex-B,
 * appended as they arrive), `frames.idx` (one line per unit: arrival time in ms, size, kind) and
 * `started` / `stopped` markers. Both files are flushed after every frame, so a killed app loses
 * at most the frame in flight; [Recording.toMp4] turns what is there into an MP4 afterwards, also
 * for a recording that never got its `stopped` marker.
 */
class RecordingWriter(val dir: File, startedAtMs: Long = System.currentTimeMillis()) : AutoCloseable {
    private val video: BufferedOutputStream
    private val index: BufferedOutputStream
    @Volatile var frames = 0
        private set
    @Volatile var bytes = 0L
        private set
    @Volatile var firstFrameMs: Long? = null
        private set

    init {
        dir.mkdirs()
        File(dir, "started").writeText(startedAtMs.toString())
        video = BufferedOutputStream(FileOutputStream(File(dir, "video.h264"), true), 1 shl 16)
        index = BufferedOutputStream(FileOutputStream(File(dir, "frames.idx"), true), 1 shl 12)
    }

    @Synchronized
    fun frame(au: ByteArray, atMs: Long = System.currentTimeMillis()) {
        if (au.isEmpty()) return
        val nals = H264.split(au).map(H264::type)
        val kind = when {
            nals.isNotEmpty() && nals.all { it == H264.NAL_SPS || it == H264.NAL_PPS || it == H264.NAL_SEI || it == H264.NAL_AUD } -> "C"
            H264.NAL_IDR in nals -> "K"
            else -> "P"
        }
        video.write(au); video.flush()
        index.write("$atMs ${au.size} $kind\n".toByteArray()); index.flush()
        frames++
        bytes += au.size
        if (firstFrameMs == null && kind != "C") firstFrameMs = atMs
    }

    /** Marks a clean stop at [atMs]: the last frame lasts until then. */
    @Synchronized
    fun stop(atMs: Long = System.currentTimeMillis()) {
        close()
        File(dir, "stopped").writeText(atMs.toString())
    }

    @Synchronized
    override fun close() {
        runCatching { video.close() }
        runCatching { index.close() }
    }
}

object Recording {
    data class Result(val file: File, val frames: Int, val durationMs: Long, val firstFrameMs: Long, val width: Int, val height: Int, val recovered: Boolean)

    class NoVideo(message: String) : RuntimeException(message)

    fun isStopped(dir: File) = File(dir, "stopped").isFile

    fun startedAt(dir: File): Long? = File(dir, "started").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()

    /**
     * Muxes [dir] into [out]. A recording without a `stopped` marker (the app was killed) ends at
     * the last frame that fully reached the disk, plus a moment. Frames before the first key frame
     * can't be decoded and are dropped.
     */
    fun toMp4(dir: File, out: File): Result {
        val h264 = File(dir, "video.h264")
        val idx = File(dir, "frames.idx")
        if (!h264.isFile || !idx.isFile) throw NoVideo("no recording in ${dir.path}")
        val length = h264.length()
        data class Entry(val at: Long, val offset: Long, val size: Int, val kind: String)
        val entries = mutableListOf<Entry>()
        var off = 0L
        for (line in idx.readLines()) {
            val p = line.trim().split(' ')
            if (p.size != 3) break // a line cut off by a crash ends the index
            val at = p[0].toLongOrNull() ?: break
            val size = p[1].toIntOrNull() ?: break
            if (off + size > length) break
            entries += Entry(at, off, size, p[2])
            off += size
        }
        RandomAccessFile(h264, "r").use { raf ->
            fun read(e: Entry): ByteArray = ByteArray(e.size).also { raf.seek(e.offset); raf.readFully(it) }
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            val samples = mutableListOf<Mp4Writer.Sample>()
            for (e in entries) {
                val nals = H264.split(read(e))
                nals.firstOrNull { H264.type(it) == H264.NAL_SPS }?.let { if (sps == null) sps = it }
                nals.firstOrNull { H264.type(it) == H264.NAL_PPS }?.let { if (pps == null) pps = it }
                if (e.kind == "C") continue
                if (samples.isEmpty() && e.kind != "K") continue // wait for the first key frame
                samples += Mp4Writer.Sample(e.offset, e.size, e.at, e.kind == "K")
            }
            val s = sps ?: throw NoVideo("the recording has no SPS (the stream never started)")
            val p = pps ?: throw NoVideo("the recording has no PPS")
            if (samples.isEmpty()) throw NoVideo("the recording has no key frame (the screen never changed?)")
            val stopped = File(dir, "stopped").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
            val end = stopped ?: (samples.last().timeMs + 100)
            Mp4Writer.write(out, s, p, samples, end) { sm -> H264.split(ByteArray(sm.size).also { raf.seek(sm.offset); raf.readFully(it) }) }
            val info = H264.parseSps(s)
            return Result(out, samples.size, end - samples.first().timeMs, samples.first().timeMs, info.width, info.height, stopped == null)
        }
    }
}
