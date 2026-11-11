package no.agens.testlab.services.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.agens.testlab.services.github.PrFile
import no.agens.testlab.services.runtime.LabRuntime
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * A PR's screens before and after, without building it: the reference images of the app's
 * screenshot tests (Paparazzi, Roborazzi, swift-snapshot-testing) that the PR adds, changes or
 * removes, fetched from GitHub at the merge base and at the PR's head, and set side by side.
 */
class PrScreens(private val rt: LabRuntime) {
    /** [diff]: how before and after differ (null when one side is missing or the sizes really differ, then [resized]). */
    data class Screen(val path: String, val name: String, val status: String, val before: File?, val after: File?, val pair: File?, val diff: Diff? = null, val resized: String? = null)

    /** The share of pixels that differ, and the band of the screen they are in (fractions of its height). */
    data class Diff(val share: Double, val top: Double, val bottom: Double) {
        override fun toString(): String = if (share == 0.0) "identical (allowing a one-pixel shift)"
            else "%.2f%% of the pixels, between %d%% and %d%% of the height (a one-pixel shift allowed)".format(share * 100, (top * 100).toInt(), (bottom * 100).toInt())
    }
    data class Result(val number: Int, val title: String, val base: String, val head: String, val screens: List<Screen>, val more: Int, val patterns: List<String>)

    fun patterns(): List<String> = rt.profile.snapshotImages.ifEmpty { DEFAULTS }

    /** The PR's changed reference images ([filter]: text in the path), at most [limit], downloaded and paired. */
    suspend fun forPr(number: Int, filter: String? = null, limit: Int = 30): Result {
        val gh = rt.builds.github()
        val pr = gh.pullDetail(number)
        val pats = patterns()
        val files = gh.pullFiles(number, 3000).filter { isSnapshot(it.path, pats) || (it.previousPath != null && isSnapshot(it.previousPath, pats)) }
            .filter { f -> filter.isNullOrBlank() || filter.lowercase() in f.path.lowercase() }
        // Where the PR branched off: from the PR's own base commit, so a merged PR compares right too.
        val base = gh.compare(pr.baseSha.ifEmpty { pr.baseRef }, pr.headSha).mergeBase
        val dir = File(rt.dirs.cache, "pr-screens/${rt.profile.github.repo.replace('/', '_')}/$number-${pr.headSha.take(10)}").apply { mkdirs() }
        val screens = files.take(limit).mapIndexed { i, f -> fetch(gh, f, i, base, pr.headSha, dir) }
        return Result(number, pr.title, base, pr.headSha, screens, (files.size - limit).coerceAtLeast(0), pats)
    }

    private suspend fun fetch(gh: no.agens.testlab.services.github.GitHub, f: PrFile, i: Int, base: String, head: String, dir: File): Screen {
        val stem = "%02d".format(i + 1)
        val ext = f.path.substringAfterLast('.', "png")
        suspend fun get(path: String, ref: String, side: String): File? {
            val out = File(dir, "$stem-$side.$ext")
            if (out.isFile && out.length() > 0) return out
            return runCatching { gh.downloadFile(path, ref, out); out }.getOrNull()
        }
        val before = if (f.status == "added") null else get(f.previousPath ?: f.path, base, "before")
        val after = if (f.status == "removed") null else get(f.path, head, "after")
        val pair = withContext(Dispatchers.IO) { runCatching { pair(before, after, File(dir, "$stem-pair.png")) }.getOrNull() }
        val diff = if (before != null && after != null) withContext(Dispatchers.IO) { runCatching { diff(before, after) }.getOrNull() } else null
        val resized = if (before != null && after != null && diff == null) runCatching { "${size(before)} → ${size(after)}" }.getOrNull() else null
        return Screen(f.path, nameOf(f.path), f.status, before, after, pair, diff, resized)
    }

    companion object {
        /** Paparazzi, Roborazzi (when committed), swift-snapshot-testing and iOSSnapshotTestCase. */
        val DEFAULTS = listOf("**/snapshots/images/**", "**/src/test/screenshots/**", "**/screenshots/**", "**/__Snapshots__/**", "**/ReferenceImages*/**")
        private val IMAGE = Regex("(?i)\\.(png|jpe?g|webp)$")

        fun isSnapshot(path: String, patterns: List<String>): Boolean {
            if (!IMAGE.containsMatchIn(path)) return false
            val p = Paths.get("/" + path.trimStart('/'))
            return patterns.any { runCatching { FileSystems.getDefault().getPathMatcher("glob:" + if (it.startsWith("/") || it.startsWith("**")) it else "/$it").matches(p) }.getOrDefault(false) }
        }

        /** A readable name: Paparazzi's `pkg.name_TestClass_test.png` → `TestClass · test`; others: the file name. */
        fun nameOf(path: String): String {
            val file = path.substringAfterLast('/').substringBeforeLast('.')
            val noPackage = file.replace(Regex("^([a-z0-9_]+\\.)+[a-z0-9]+_(?=[A-Z])"), "")
            return noPackage.split('_').filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { file }
        }

        private fun size(f: File): String = javax.imageio.ImageIO.createImageInputStream(f).use { input ->
            val r = ImageIO.getImageReaders(input).next()
            try { r.input = input; "${r.getWidth(0)}×${r.getHeight(0)}" } finally { r.dispose() }
        }

        /**
         * Where before and after differ: a pixel counts when no pixel within one of it in the other
         * image is close (each channel within 40 of 255), so anti-aliasing and the one-pixel shifts of a
         * re-recording drop out. A size off by a pixel or two is the renderer's rounding: the after
         * image is scaled to the before's first. Null when the sizes really differ.
         */
        fun diff(before: File, after: File): Diff? {
            val a = ImageIO.read(before) ?: return null
            var b = ImageIO.read(after) ?: return null
            val w = a.width; val h = a.height
            if (kotlin.math.abs(w - b.width) > 2 || kotlin.math.abs(h - b.height) > 2) return null
            if (b.width != w || b.height != h) b = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { s ->
                s.createGraphics().apply { setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); drawImage(b, 0, 0, w, h, null); dispose() }
            }
            val pa = a.getRGB(0, 0, w, h, null, 0, w); val pb = b.getRGB(0, 0, w, h, null, 0, w)
            fun far(p: Int, q: Int) = (0..16 step 8).any { sh -> kotlin.math.abs(((p shr sh) and 0xFF) - ((q shr sh) and 0xFF)) > 40 }
            var n = 0L; var top = h; var bottom = -1
            for (y in 0 until h) for (x in 0 until w) {
                val p = pa[y * w + x]
                if (p == pb[y * w + x]) continue
                var near = false
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h && !far(p, pb[yy * w + xx])) { near = true; break@loop }
                }
                if (!near) { n++; if (y < top) top = y; bottom = y }
            }
            return if (n == 0L) Diff(0.0, 0.0, 0.0) else Diff(n.toDouble() / (w.toLong() * h), top.toDouble() / h, (bottom + 1).toDouble() / h)
        }

        /**
         * Before and after next to each other, scaled to one height, each with a label; a single image
         * when the other side is missing. Wide images (a card, a banner) are stacked instead, one width.
         */
        fun pair(before: File?, after: File?, out: File, height: Int = 900, width: Int = 1000): File? {
            val sides = listOfNotNull(before?.let { ImageIO.read(it)?.let { i -> "before" to i } }, after?.let { ImageIO.read(it)?.let { i -> (if (before == null) "new" else "after") to i } })
                .let { if (after == null && it.size == 1) listOf("removed" to it[0].second) else it }
            if (sides.isEmpty()) return null
            val label = 44; val gap = 24
            val wide = sides.map { it.second.width.toDouble() / it.second.height.coerceAtLeast(1) }.average() > 1.2
            // Each side's size on the canvas: one height side by side, one width stacked.
            val sized = sides.map { (l, img) ->
                if (wide) Triple(l, img, width to (img.height * width / img.width.coerceAtLeast(1)).coerceAtLeast(1))
                else Triple(l, img, (img.width * height / img.height.coerceAtLeast(1)).coerceAtLeast(1) to height)
            }
            val canvas = if (wide) BufferedImage(width + 2 * gap, sized.sumOf { it.third.second + label } + gap * (sized.size + 1), BufferedImage.TYPE_INT_RGB)
                else BufferedImage(sized.sumOf { it.third.first } + gap * (sized.size + 1), height + label + gap, BufferedImage.TYPE_INT_RGB)
            val g = canvas.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(0xF2, 0xF2, 0xF2); g.fillRect(0, 0, canvas.width, canvas.height)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 26)
            var x = gap; var y = 0
            for ((l, img, size) in sized) {
                val (w, h) = size
                g.color = Color(0x33, 0x33, 0x33); g.drawString(l, x, y + label - 12)
                g.drawImage(img, x, y + label, w, h, null)
                g.color = Color(0xBB, 0xBB, 0xBB); g.drawRect(x - 1, y + label - 1, w + 1, h + 1)
                if (wide) y += label + h + gap else x += w + gap
            }
            g.dispose()
            ImageIO.write(canvas, "png", out)
            return out
        }
    }
}
