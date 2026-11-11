package no.agens.testlab.device.rec

import no.agens.testlab.device.hs.FakePhone
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecordingTest {
    private val tmp = Files.createTempDirectory("tl-rec").toFile()

    @AfterTest
    fun tearDown() { tmp.deleteRecursively() }

    /** Top-level and nested MP4 boxes by path ("moov/trak/tkhd"), for checking what we wrote. */
    private fun boxes(f: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        fun walk(b: ByteArray, start: Int, end: Int, prefix: String) {
            var p = start
            while (p + 8 <= end) {
                val size = DataInputStream(b.inputStream(p, 4)).readInt()
                val type = String(b, p + 4, 4, Charsets.US_ASCII)
                val path = prefix + type
                out.putIfAbsent(path, b.copyOfRange(p + 8, p + size))
                val skip = mapOf("stsd" to 8, "avc1" to 78, "dref" to 8)[type]
                if (type in setOf("moov", "trak", "mdia", "minf", "stbl", "dinf")) walk(b, p + 8, p + size, "$path/")
                else if (skip != null) walk(b, p + 8 + skip, p + size, "$path/")
                p += size
            }
        }
        val b = f.readBytes()
        walk(b, 0, b.size, "")
        return out
    }

    private fun i32(b: ByteArray, at: Int) = DataInputStream(b.inputStream(at, 4)).readInt()

    private fun record(dir: File, frames: List<ByteArray>, stopAt: Long? = 3_000, from: Long = 1_000_000) {
        val w = RecordingWriter(dir, startedAtMs = from - 400)
        frames.forEachIndexed { i, f -> w.frame(f, if (i == 0) from - 50 else from + (i - 1) * 100L) }
        if (stopAt != null) w.stop(from + stopAt) else w.close()
    }

    @Test
    fun spsGivesTheSize() {
        val sps = H264.split(FakePhone.H264_FRAMES.first()).first { H264.type(it) == H264.NAL_SPS }
        val info = H264.parseSps(sps)
        assertEquals(160 to 320, info.width to info.height)
        assertEquals(100, info.profile, "High profile")
    }

    @Test
    fun aFinishedRecordingLastsItsWallClockTime() {
        val dir = File(tmp, "a")
        record(dir, FakePhone.H264_FRAMES)
        val r = Recording.toMp4(dir, File(tmp, "a.mp4"))
        assertEquals(30, r.frames)
        assertEquals(3_000, r.durationMs, "first frame to the stop marker")
        assertEquals(false, r.recovered)
        val b = boxes(r.file)
        assertEquals(listOf("ftyp", "moov", "mdat"), b.keys.filter { '/' !in it }, "fast start: moov before mdat")
        val mvhd = b.getValue("moov/mvhd")
        assertEquals(1000, i32(mvhd, 12)); assertEquals(3000, i32(mvhd, 16))
        val tkhd = b.getValue("moov/trak/tkhd")
        assertEquals(160 shl 16, i32(tkhd, 76)); assertEquals(320 shl 16, i32(tkhd, 80))
        assertEquals(30, i32(b.getValue("moov/trak/mdia/minf/stbl/stsz"), 8))
        val stss = b.getValue("moov/trak/mdia/minf/stbl/stss")
        assertEquals(listOf(1, 11, 21), (0 until i32(stss, 4)).map { i32(stss, 8 + it * 4) }, "a key frame per second")
        assertTrue(b.containsKey("moov/trak/mdia/minf/stbl/stsd/avc1/avcC"))
        // The chunk offset points at the first sample, right after the mdat header.
        val stco = b.getValue("moov/trak/mdia/minf/stbl/stco")
        assertEquals(b.getValue("ftyp").size + 8 + b.getValue("moov").size + 8 + 8, i32(stco, 8))
    }

    @Test
    fun aKilledRecordingIsRecovered() {
        val dir = File(tmp, "k")
        record(dir, FakePhone.H264_FRAMES.take(16), stopAt = null)
        // A crash mid-write: half an index line and bytes the index never got to.
        File(dir, "frames.idx").appendText("1001500 9")
        File(dir, "video.h264").appendBytes(ByteArray(100))
        val r = Recording.toMp4(dir, File(tmp, "k.mp4"))
        assertTrue(r.recovered)
        assertEquals(15, r.frames)
        assertEquals(14 * 100L + 100, r.durationMs, "up to the last frame on disk, plus a moment")
    }

    @Test
    fun framesBeforeTheFirstKeyFrameAreDropped() {
        val frames = FakePhone.H264_FRAMES
        val dir = File(tmp, "p")
        record(dir, listOf(frames[0]) + frames.drop(3)) // starts on P frames, first key frame at index 10
        assertEquals(20, Recording.toMp4(dir, File(tmp, "p.mp4")).frames)
        val empty = File(tmp, "e").also { RecordingWriter(it).stop() }
        assertFailsWith<Recording.NoVideo> { Recording.toMp4(empty, File(tmp, "e.mp4")) }
    }
}
