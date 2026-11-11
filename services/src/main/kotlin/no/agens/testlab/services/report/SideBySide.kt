package no.agens.testlab.services.report

import no.agens.testlab.device.ToolLocator
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * The side-by-side clip of a comparison (port of the lab's bin/sbs.sh): one lane per arm, each
 * from its first frame, scaled to one height, held on its last frame until the slowest is done,
 * a label on top and «done in N s» from the moment it finished. Labels are drawn here as PNGs, so
 * ffmpeg needs no font support; the running clock is added only when ffmpeg has drawtext and a
 * font is found. The fps resample comes before any text: the recordings only have frames when the
 * screen changes, and a clock drawn on them would freeze during waits (the bench6 lesson).
 */
object SideBySide {
    data class Lane(val video: File, val label: String, val doneSeconds: Double)

    private fun png(text: String, fg: Color, bg: Color, out: File, size: Int = 30) {
        val f = Font(Font.SANS_SERIF, Font.BOLD, size)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().apply { font = f }
        val w = probe.fontMetrics.stringWidth(text) + 28
        val h = probe.fontMetrics.height + 16
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            color = bg; fillRoundRect(0, 0, w, h, 14, 14)
            color = fg; font = f; drawString(text, 14, 8 + fontMetrics.ascent)
            dispose()
        }
        ImageIO.write(img, "png", out)
    }

    private val FONTS = listOf(
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
        "/Library/Fonts/Arial Bold.ttf", "C:/Windows/Fonts/arialbd.ttf",
    )

    /** Builds [out]; returns null when ffmpeg is missing or fails (the comparison report works without it). */
    fun build(lanes: List<Lane>, out: File, cacheDir: File, laneWidth: Int = 480, laneHeight: Int = 1040): File? {
        val ffmpeg = ToolLocator(appToolsDir = cacheDir).findOptional("ffmpeg")?.path ?: return null
        if (lanes.isEmpty()) return null
        val dir = out.parentFile
        val total = lanes.maxOf { it.doneSeconds } + 3
        val drawtext = runCatching {
            val p = ProcessBuilder(ffmpeg.path, "-hide_banner", "-filters").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor(10, TimeUnit.SECONDS) }
        }.getOrDefault("").contains(" drawtext ")
        val font = FONTS.firstOrNull { File(it).isFile }?.takeIf { drawtext && ':' !in it.drop(2) }
        val args = mutableListOf(ffmpeg.path, "-hide_banner", "-loglevel", "error", "-y")
        lanes.forEachIndexed { i, l ->
            png(l.label, Color.WHITE, Color(0, 0, 0, 150), File(dir, ".sbs-label-$i.png"))
            png("done in %.1f s".format(l.doneSeconds), Color(124, 252, 0), Color(0, 0, 0, 180), File(dir, ".sbs-done-$i.png"), 32)
            args += listOf("-i", l.video.path)
        }
        lanes.indices.forEach { i -> args += listOf("-i", File(dir, ".sbs-label-$i.png").path, "-i", File(dir, ".sbs-done-$i.png").path) }
        val n = lanes.size
        val f = StringBuilder()
        lanes.forEachIndexed { i, l ->
            val lab = n + 2 * i
            val done = n + 2 * i + 1
            val clock = font?.let { ",drawtext=fontfile='${it.replace("\\", "/").replace(":", "\\\\:")}':text='%{pts\\:hms}':fontsize=34:fontcolor=white:box=1:boxcolor=black@0.55:boxborderw=10:x=(w-text_w)/2:y=h-70:enable='lt(t\\,${"%.2f".format(java.util.Locale.ROOT, l.doneSeconds)})'" } ?: ""
            f.append("[$i:v]setpts=PTS-STARTPTS,fps=16,scale=$laneWidth:$laneHeight:force_original_aspect_ratio=decrease,pad=$laneWidth:$laneHeight:(ow-iw)/2:(oh-ih)/2,tpad=stop_mode=clone:stop_duration=${"%.1f".format(java.util.Locale.ROOT, total)}$clock[b$i];")
            f.append("[b$i][$lab:v]overlay=(W-w)/2:18[c$i];")
            f.append("[c$i][$done:v]overlay=(W-w)/2:H-h-16:enable='gte(t\\,${"%.2f".format(java.util.Locale.ROOT, l.doneSeconds)})'[v$i];")
        }
        lanes.indices.joinTo(f, "") { "[v$it]" }
        f.append(if (n > 1) "hstack=inputs=$n[out]" else "copy[out]")
        args += listOf("-filter_complex", f.toString(), "-map", "[out]", "-t", "%.1f".format(java.util.Locale.ROOT, total), "-r", "16",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out.path)
        val log = File(dir, "side-by-side.log")
        val p = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
        val ok = p.waitFor(600, TimeUnit.SECONDS) && p.exitValue() == 0 && out.length() > 0
        if (!ok) p.destroyForcibly()
        dir.listFiles { x -> x.name.startsWith(".sbs-") }?.forEach { it.delete() }
        return out.takeIf { ok }
    }
}
