package no.agens.testlab.mcp

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Pictures for a terminal: `png` for terminals that draw pixels (kitty, Ghostty), `rgb` for the
 * rest, raw pixels a mod packs into half-block cells (two pixels a cell). Decoded and scaled here
 * because a mod has no image decoder.
 */
object Frames {
    data class Frame(val format: String, val w: Int, val h: Int, val data: String)

    /** [w]×[h] is the box to fit (aspect kept); 0 = the picture's own size for that side. */
    /** `file` writes the PNG with [writer] (a ring of files the terminal reads itself) and returns its path in `data`. */
    fun convert(encoded: ByteArray, format: String, w: Int, h: Int, writer: ((ByteArray) -> java.io.File)? = null): Frame {
        val src = ImageIO.read(encoded.inputStream()) ?: throw IllegalStateException("not a picture")
        val (tw, th) = fit(src.width, src.height, w, h)
        val img = if (tw == src.width && th == src.height) src else BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB).also { out ->
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(src, 0, 0, tw, th, null); dispose()
            }
        }
        return when (format) {
            "rgb" -> {
                val px = ByteArray(tw * th * 3)
                var i = 0
                for (y in 0 until th) for (x in 0 until tw) {
                    val c = img.getRGB(x, y); px[i++] = (c shr 16).toByte(); px[i++] = (c shr 8).toByte(); px[i++] = c.toByte()
                }
                Frame("rgb", tw, th, Base64.getEncoder().encodeToString(px))
            }
            "file" -> {
                val write = writer ?: throw BadArgument("format file needs a place to write")
                Frame("file", tw, th, write(ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()).path)
            }
            "png" -> Frame("png", tw, th, Base64.getEncoder().encodeToString(ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()))
            else -> throw BadArgument("format is png, rgb or file")
        }
    }

    /** A ring of [size] PNG files in [dir] named by [prefix]: rewinding shows an older one, still there. */
    class Ring(private val dir: java.io.File, private val prefix: String, private val size: Int = 240) {
        private var n = 0
        @Synchronized fun write(png: ByteArray): java.io.File {
            dir.mkdirs()
            val f = java.io.File(dir, "$prefix-${n++ % size}.png")
            val tmp = java.io.File(dir, f.name + ".tmp")
            tmp.writeBytes(png)
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return f
        }
    }

    internal fun fit(sw: Int, sh: Int, w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 && h <= 0) return sw to sh
        val s = minOf(if (w > 0) w.toDouble() / sw else Double.MAX_VALUE, if (h > 0) h.toDouble() / sh else Double.MAX_VALUE)
        return maxOf(1, (sw * s).toInt()) to maxOf(1, (sh * s).toInt())
    }
}
