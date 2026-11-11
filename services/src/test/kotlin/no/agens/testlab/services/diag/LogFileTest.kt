package no.agens.testlab.services.diag

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogFileTest {
    @Test
    fun stderrLandsInTheFileAndRotates() {
        val dir = Files.createTempDirectory("tl-log").toFile()
        val saved = System.err
        try {
            LogFile.teeStderr(dir, maxBytes = 30_000)
            System.err.println("hello from stderr")
            RuntimeException("boom").printStackTrace()
            assertTrue("hello from stderr" in File(dir, "app.log").readText(), "files ${dir.list()?.toList()} sizes ${dir.listFiles()?.map { it.length() }}")
            assertTrue("RuntimeException: boom" in File(dir, "app.log").readText())
            repeat(800) { System.err.println("line $it with some padding to fill the file") }
            assertTrue(File(dir, "app.log.1").isFile, "rotated")
            assertTrue(File(dir, "app.log").length() < 32_000)
            assertEquals(setOf("app.log", "app.log.1"), dir.list()!!.toSet())
        } finally {
            System.setErr(saved)
            dir.deleteRecursively()
        }
    }
}
