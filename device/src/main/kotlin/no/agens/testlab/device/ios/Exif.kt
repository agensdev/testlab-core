package no.agens.testlab.device.ios

import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage

/**
 * The EXIF orientation tag of a JPEG (APP1 Exif) or PNG (eXIf chunk). A real iPad gives its
 * screenshots portrait-native with the tag saying how to turn them (8 = landscape), while taps are in
 * the screen as it is turned: [upright] applies it, so picture positions map to tap pixels.
 */
object Exif {
    fun orientation(bytes: ByteArray): Int = runCatching { tiffIn(bytes)?.let { (off, len) -> fromTiff(bytes, off, len) } }.getOrNull() ?: 1

    /** Where the TIFF block (EXIF data) starts and how long it is, in a JPEG or PNG. */
    private fun tiffIn(b: ByteArray): Pair<Int, Int>? {
        if (b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) {
            var i = 8
            while (i + 8 <= b.size) {
                val len = be32(b, i)
                val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
                if (type == "eXIf") return (i + 8) to len
                if (type == "IDAT" || type == "IEND") return null
                i += 12 + len
            }
            return null
        }
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 4 <= b.size && b[i] == 0xFF.toByte()) {
            val marker = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker == 0xE1 && i + 10 <= b.size && String(b, i + 4, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") return (i + 10) to (len - 8)
            if (marker == 0xDA) return null
            i += 2 + len
        }
        return null
    }

    private fun fromTiff(b: ByteArray, off: Int, len: Int): Int? {
        val little = b[off] == 'I'.code.toByte()
        fun u16(p: Int) = if (little) (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) else ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        fun u32(p: Int) = if (little) u16(p) or (u16(p + 2) shl 16) else (u16(p) shl 16) or u16(p + 2)
        val ifd = off + u32(off + 4)
        val n = u16(ifd)
        for (k in 0 until n) {
            val e = ifd + 2 + k * 12
            if (e + 12 > off + len) return null
            if (u16(e) == 0x0112) return u16(e + 8)
        }
        return null
    }

    private fun be32(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)

    /** [img] turned as EXIF [orientation] says (1 = as it is, 3 = 180°, 6 = 90° clockwise, 8 = 90° anticlockwise). */
    fun upright(img: BufferedImage, orientation: Int): BufferedImage {
        val w = img.width.toDouble(); val h = img.height.toDouble()
        val t = when (orientation) {
            3 -> AffineTransform().apply { translate(w, h); rotate(Math.PI) }
            6 -> AffineTransform().apply { translate(h, 0.0); rotate(Math.PI / 2) }
            8 -> AffineTransform().apply { translate(0.0, w); rotate(-Math.PI / 2) }
            else -> return img
        }
        val out = if (orientation == 3) BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB) else BufferedImage(img.height, img.width, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(img, AffineTransformOp(t, AffineTransformOp.TYPE_NEAREST_NEIGHBOR), 0, 0)
        g.dispose()
        return out
    }
}
