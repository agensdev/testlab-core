package no.agens.testlab.device.ios

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class ExifTest {
    /** A TIFF block (big-endian) with one IFD entry: Orientation = [o]. */
    private fun tiff(o: Int) = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, o.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)

    /** A portrait-native iPad picture: a red mark in its top left corner. */
    private fun picture(fmt: String): ByteArray {
        val img = BufferedImage(160, 240, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = Color.RED; fillRect(0, 0, 20, 20); dispose() } }
        return ByteArrayOutputStream().also { ImageIO.write(img, fmt, it) }.toByteArray()
    }

    private fun jpegWith(o: Int): ByteArray {
        val j = picture("jpg")
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff(o)
        val len = payload.size + 2
        return j.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload + j.copyOfRange(2, j.size)
    }

    private fun pngWith(o: Int): ByteArray {
        val p = picture("png")
        val data = tiff(o)
        val type = "eXIf".toByteArray()
        val crc = CRC32().apply { update(type); update(data) }.value
        fun be(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        val chunk = be(data.size.toLong()) + type + data + be(crc)
        return p.copyOfRange(0, 33) + chunk + p.copyOfRange(33, p.size) // after the signature and IHDR
    }

    @Test
    fun theTurnIsReadAndApplied() {
        assertEquals(8, Exif.orientation(jpegWith(8)))
        assertEquals(6, Exif.orientation(pngWith(6)))
        assertEquals(1, Exif.orientation(picture("png")), "no tag: as it is")
        val img = ImageIO.read(jpegWith(8).inputStream())
        val up = Exif.upright(img, 8)
        assertEquals(240 to 160, up.width to up.height, "landscape, as taps see the screen")
        // 90° anticlockwise: the picture's top left corner ends up bottom left.
        assertEquals(true, Color(up.getRGB(5, 155)).red > 200 && Color(up.getRGB(235, 5)).red < 100)
    }
}
