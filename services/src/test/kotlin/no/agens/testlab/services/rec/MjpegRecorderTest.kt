package no.agens.testlab.services.rec

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MjpegRecorderTest {
    @Test
    fun framesGoInOnASteadyTimer() {
        val dir = Files.createTempDirectory("tl-mjpeg").toFile()
        try {
            assertEquals(listOf("-f", "image2pipe", "-framerate", "10", "-c:v", "mjpeg", "-i", "-"), MjpegRecorder.command("ffmpeg", 10, File("o.mp4")).let { it.subList(it.indexOf("-f"), it.indexOf("-") + 1) })
            // A stand-in ffmpeg: copies stdin to the output file.
            val fake = File(dir, "ffmpeg").apply { writeText("#!/bin/sh\nfor a; do out=\"\$a\"; done\ncat > \"\$out\"\n"); setExecutable(true) }
            val rec = MjpegRecorder(fake, File(dir, "r/phone.mp4"), fps = 20)
            Thread.sleep(150)
            assertEquals(0, rec.frames, "nothing is written before the first frame")
            rec.frame(byteArrayOf(1, 2, 3))
            Thread.sleep(600)
            val out = rec.stop()!!
            assertTrue(rec.frames in 8..14, "about 20 a second, the same frame again while the screen stands still: ${rec.frames}")
            assertEquals(rec.frames * 3L, out.length())
        } finally { dir.deleteRecursively() }
    }
}
