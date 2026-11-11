package no.agens.testlab.services.tools

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import no.agens.testlab.device.HostOs
import no.agens.testlab.device.ToolLocator
import no.agens.testlab.services.builds.MockGitHub
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlatformToolsTest {
    private val tmp = Files.createTempDirectory("tl-pt").toFile()
    private val http = MockGitHub()

    @AfterTest
    fun tearDown() { tmp.deleteRecursively() }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use { z -> for ((n, t) in entries) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return b.toByteArray()
    }

    private val fakeAdb = "#!/bin/sh\necho 'Android Debug Bridge version 1.0.41'\necho 'Version 36.0.0-13206524'\n"

    @Test
    fun downloadsUnpacksAndIsFoundByTheLocator() = runBlocking {
        http.on("GET https://dl.google.com/android/repository/platform-tools-latest-linux.zip") { respond(zip("platform-tools/adb" to fakeAdb, "platform-tools/NOTICE.txt" to "terms"), HttpStatusCode.OK) }
        val pt = PlatformTools(http.client, tmp, HostOs.LINUX)
        assertTrue("Accept" in assertFailsWith<PlatformTools.Failed> { pt.install(acceptedTerms = false) }.message!!)
        assertEquals("Android Debug Bridge version 1.0.41", pt.install(acceptedTerms = true))
        assertTrue(pt.adb.canExecute())
        assertEquals(pt.adb, ToolLocator(HostOs.LINUX, env = emptyMap(), home = File(tmp, "nohome"), appToolsDir = tmp).findAdb()?.path)
        // A second install replaces the first.
        assertEquals("Android Debug Bridge version 1.0.41", pt.install(acceptedTerms = true))
        assertEquals(listOf("platform-tools"), tmp.list()!!.toList())
    }

    @Test
    fun aZipThatEscapesItsFolderIsRefused() = runBlocking {
        http.on("GET https://dl.google.com/") { respond(zip("../evil" to "x", "platform-tools/adb" to fakeAdb), HttpStatusCode.OK) }
        assertTrue("unsafe path" in assertFailsWith<PlatformTools.Failed> { PlatformTools(http.client, tmp, HostOs.LINUX).install(true) }.message!!)
        assertTrue(!File(tmp.parentFile, "evil").exists())
    }
}
