package no.agens.testlab.device.rec

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/** H.264 Annex-B helpers: NAL splitting and the SPS fields an MP4 needs. */
object H264 {
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    fun type(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1f

    /** The NAL units of an Annex-B buffer (3- or 4-byte start codes), without start codes. */
    fun split(b: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>() // (start code position, payload start)
        var i = 0
        while (i + 2 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                if (b[i + 2].toInt() == 1) { starts += i to i + 3; i += 3; continue }
                if (i + 3 < b.size && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1) { starts += i to i + 4; i += 4; continue }
            }
            i++
        }
        if (starts.isEmpty()) return if (b.isNotEmpty()) listOf(b) else emptyList()
        return starts.mapIndexed { k, (_, p) ->
            var end = if (k + 1 < starts.size) starts[k + 1].first else b.size
            while (end > p && b[end - 1].toInt() == 0 && k + 1 < starts.size) end-- // trailing zero bytes belong to the next start code
            b.copyOfRange(p, end)
        }.filter { it.isNotEmpty() }
    }

    data class Sps(val width: Int, val height: Int, val profile: Int, val compat: Int, val level: Int, val chromaFormat: Int, val bitDepthLuma: Int, val bitDepthChroma: Int)

    /** Reads width and height (after cropping) from a sequence parameter set. */
    fun parseSps(nal: ByteArray): Sps {
        val rbsp = ByteArrayOutputStream()
        var zeros = 0
        for (k in 1 until nal.size) { // drop the NAL header and emulation-prevention bytes
            val v = nal[k].toInt() and 0xff
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            rbsp.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        val r = Bits(rbsp.toByteArray())
        val profile = r.u(8); val compat = r.u(8); val level = r.u(8)
        r.ue() // seq_parameter_set_id
        var chroma = 1; var depthL = 8; var depthC = 8
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue()
            if (chroma == 3) r.u(1)
            depthL = r.ue() + 8; depthC = r.ue() + 8
            r.u(1)
            if (r.u(1) == 1) { // scaling matrices: skip them
                for (k in 0 until if (chroma != 3) 8 else 12) if (r.u(1) == 1) {
                    var last = 8; var next = 8
                    for (j in 0 until if (k < 6) 16 else 64) { if (next != 0) next = (last + r.se() + 256) % 256; last = if (next == 0) last else next }
                }
            }
        }
        r.ue() // log2_max_frame_num
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> { r.u(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        }
        r.ue(); r.u(1) // max_num_ref_frames, gaps
        val wMbs = r.ue() + 1
        val hMap = r.ue() + 1
        val frameMbsOnly = r.u(1)
        if (frameMbsOnly == 0) r.u(1)
        r.u(1) // direct_8x8
        var w = wMbs * 16
        var h = hMap * 16 * (2 - frameMbsOnly)
        if (r.u(1) == 1) {
            val cl = r.ue(); val cr = r.ue(); val ct = r.ue(); val cb = r.ue()
            val cx = if (chroma == 0) 1 else 2
            val cy = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
            w -= (cl + cr) * cx; h -= (ct + cb) * cy
        }
        return Sps(w, h, profile, compat, level, chroma, depthL, depthC)
    }

    private class Bits(val b: ByteArray) {
        var pos = 0
        fun u(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun bit(): Int { if (pos >= b.size * 8) return 0; val v = (b[pos / 8].toInt() shr (7 - pos % 8)) and 1; pos++; return v }
        fun ue(): Int { var z = 0; while (bit() == 0 && z < 32) z++; return if (z == 0) 0 else ((1 shl z) - 1 + u(z)) }
        fun se(): Int { val k = ue(); return if (k % 2 == 1) (k + 1) / 2 else -(k / 2) }
    }
}

/**
 * A minimal, fast-start MP4 (moov before mdat) for one H.264 track with variable frame timing:
 * each sample lasts until the next one, so a screen that sat still for ten seconds plays for ten
 * seconds and the file's length is the session's wall-clock length.
 */
object Mp4Writer {
    class Sample(val offset: Long, val size: Int, val timeMs: Long, val sync: Boolean)

    private const val TIMESCALE = 90_000
    private const val MIN_GAP_MS = 8L

    /**
     * [nals] supplies a sample's NAL units (Annex-B payloads of one access unit, read from disk).
     * [endMs] is when the last frame stopped being current (the stop time).
     */
    fun write(out: File, sps: ByteArray, pps: ByteArray, samples: List<Sample>, endMs: Long, nals: (Sample) -> List<ByteArray>) {
        require(samples.isNotEmpty()) { "no frames" }
        val info = H264.parseSps(sps)
        // Frames that reach the computer in one burst share a millisecond: space them at least
        // MIN_GAP_MS apart (players and re-muxers dislike zero-length frames). The last frame
        // lasts until endMs, so the total stays the session's wall-clock length.
        val times = LongArray(samples.size)
        for (i in samples.indices) times[i] = if (i == 0) samples[0].timeMs else maxOf(samples[i].timeMs, times[i - 1] + MIN_GAP_MS)
        val end = maxOf(endMs, times.last() + MIN_GAP_MS)
        val deltas = samples.indices.map { i -> ((if (i + 1 < samples.size) times[i + 1] else end) - times[i]) * 90 }
        val totalTicks = deltas.sum()
        // Sample sizes in AVCC form (4-byte lengths), without parameter sets and delimiters.
        val sizes = samples.map { s -> nals(s).filter { H264.type(it) !in setOf(H264.NAL_SPS, H264.NAL_PPS, H264.NAL_AUD) }.sumOf { 4 + it.size } }
        val mdatSize = 8L + sizes.sumOf { it.toLong() }
        require(mdatSize < 0xFFFFFFFFL) { "recording too large for one MP4 (${mdatSize / 1_000_000} MB)" }
        val ftyp = box("ftyp") { s("isom"); i32(0x200); s("isom"); s("iso2"); s("avc1"); s("mp41") }
        var moov = moov(info, sps, pps, deltas, totalTicks, sizes, samples, chunkOffset = 0)
        moov = moov(info, sps, pps, deltas, totalTicks, sizes, samples, chunkOffset = ftyp.size + moov.size + 8L)
        val tmp = File(out.parentFile, out.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { f ->
            f.setLength(0)
            f.write(ftyp); f.write(moov)
            f.writeInt(mdatSize.toInt()); f.write("mdat".toByteArray())
            val len = ByteArray(4)
            for (s in samples) for (n in nals(s)) {
                if (H264.type(n) in setOf(H264.NAL_SPS, H264.NAL_PPS, H264.NAL_AUD)) continue
                len[0] = (n.size ushr 24).toByte(); len[1] = (n.size ushr 16).toByte(); len[2] = (n.size ushr 8).toByte(); len[3] = n.size.toByte()
                f.write(len); f.write(n)
            }
        }
        out.delete()
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    }

    private fun moov(info: H264.Sps, sps: ByteArray, pps: ByteArray, deltas: List<Long>, ticks: Long, sizes: List<Int>, samples: List<Sample>, chunkOffset: Long): ByteArray {
        val durMs = ticks / 90
        val matrix: B.() -> Unit = { i32(0x00010000); i32(0); i32(0); i32(0); i32(0x00010000); i32(0); i32(0); i32(0); i32(0x40000000) }
        return box("moov") {
            add(box("mvhd") { i32(0); i32(0); i32(0); i32(1000); i32(durMs.toInt()); i32(0x00010000); i16(0x0100); zeros(10); matrix(); zeros(24); i32(2) })
            add(box("trak") {
                add(box("tkhd") { i32(0x00000003); i32(0); i32(0); i32(1); i32(0); i32(durMs.toInt()); zeros(8); i16(0); i16(0); i16(0); i16(0); matrix(); i32(info.width shl 16); i32(info.height shl 16) })
                add(box("mdia") {
                    add(box("mdhd") { i32(0); i32(0); i32(0); i32(TIMESCALE); i32(ticks.toInt()); i16(0x55c4); i16(0) })
                    add(box("hdlr") { i32(0); i32(0); s("vide"); zeros(12); bytes("VideoHandler".toByteArray() + 0) })
                    add(box("minf") {
                        add(box("vmhd") { i32(1); zeros(8) })
                        add(box("dinf") { add(box("dref") { i32(0); i32(1); add(box("url ") { i32(1) }) }) })
                        add(box("stbl") {
                            add(box("stsd") {
                                i32(0); i32(1)
                                add(box("avc1") {
                                    zeros(6); i16(1); zeros(16); i16(info.width); i16(info.height); i32(0x00480000); i32(0x00480000); i32(0); i16(1)
                                    zeros(32); i16(0x18); i16(0xffff)
                                    add(box("avcC") {
                                        u8(1); u8(info.profile); u8(info.compat); u8(info.level); u8(0xff); u8(0xe1)
                                        i16(sps.size); bytes(sps); u8(1); i16(pps.size); bytes(pps)
                                        if (info.profile in setOf(100, 110, 122, 144)) {
                                            u8(0xfc or (info.chromaFormat and 3)); u8(0xf8 or ((info.bitDepthLuma - 8) and 7)); u8(0xf8 or ((info.bitDepthChroma - 8) and 7)); u8(0)
                                        }
                                    })
                                })
                            })
                            // Run-length coded durations.
                            val runs = mutableListOf<Pair<Int, Long>>()
                            for (d in deltas) if (runs.isNotEmpty() && runs.last().second == d) runs[runs.size - 1] = runs.last().first + 1 to d else runs += 1 to d
                            add(box("stts") { i32(0); i32(runs.size); for ((n, d) in runs) { i32(n); i32(d.toInt()) } })
                            val sync = samples.indices.filter { samples[it].sync }.map { it + 1 }
                            add(box("stss") { i32(0); i32(sync.size); sync.forEach { i32(it) } })
                            add(box("stsc") { i32(0); i32(1); i32(1); i32(samples.size); i32(1) })
                            add(box("stsz") { i32(0); i32(0); i32(sizes.size); sizes.forEach { i32(it) } })
                            add(box("stco") { i32(0); i32(1); i32(chunkOffset.toInt()) })
                        })
                    })
                })
            })
        }
    }

    private class B {
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        fun i32(v: Int) = d.writeInt(v)
        fun i16(v: Int) = d.writeShort(v)
        fun u8(v: Int) = d.writeByte(v)
        fun s(t: String) = d.write(t.toByteArray(Charsets.US_ASCII))
        fun zeros(n: Int) = d.write(ByteArray(n))
        fun bytes(b: ByteArray) = d.write(b)
        fun add(b: ByteArray) = d.write(b)
    }

    private fun box(type: String, body: B.() -> Unit): ByteArray {
        val b = B().apply(body)
        val payload = b.bos.toByteArray()
        val out = ByteArrayOutputStream(payload.size + 8)
        DataOutputStream(out).apply { writeInt(payload.size + 8); write(type.toByteArray(Charsets.US_ASCII)); write(payload) }
        return out.toByteArray()
    }
}
