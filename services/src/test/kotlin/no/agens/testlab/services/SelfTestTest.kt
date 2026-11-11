package no.agens.testlab.services

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfTestTest {
    @Test
    fun allChecksPassOnTheBuildJdk() {
        val r = SelfTest.run()
        assertTrue(r.ok, r.checks.filterNot { it.ok }.toString())
        assertTrue(r.checks.map { it.name }.containsAll(listOf("tls13", "ec-keys", "aes-gcm")))
    }

    @Test
    fun argumentParsing() {
        assertTrue(SelfTest.isRequested(arrayOf("--selftest")))
        assertNull(SelfTest.outputFile(arrayOf("--selftest")))
        assertEquals(File("/tmp/x.json"), SelfTest.outputFile(arrayOf("--selftest=/tmp/x.json")))
        assertTrue(!SelfTest.isRequested(arrayOf("--mcp-stdio")))
    }

    @Test
    fun reportWritesJson() {
        val f = File.createTempFile("selftest", ".json").apply { deleteOnExit() }
        assertEquals(0, SelfTest.runAndReport(f))
        assertTrue(f.readText().contains("\"ok\": true"))
    }
}
