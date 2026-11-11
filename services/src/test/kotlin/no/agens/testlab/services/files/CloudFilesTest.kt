package no.agens.testlab.services.files

import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudFilesTest {
    private val f = File.createTempFile("cloud", ".json").apply { writeText("{}") }
    private val realFetch = CloudFiles.fetch
    @AfterTest fun tearDown() { CloudFiles.fetch = realFetch; f.delete() }

    @Test
    fun aPlaceholderIsFetchedAndReadAgain() {
        val fetched = mutableListOf<File>()
        CloudFiles.fetch = { fetched += it }
        var tries = 0
        val text = CloudFiles.read(f) { tries++; if (tries < 3) throw IOException("Resource deadlock avoided") else it.readText() }
        assertEquals("{}", text)
        assertEquals(listOf(f), fetched, "fetched once, then waited for it")
    }

    @Test
    fun otherErrorsAreNotRetried() {
        var tries = 0
        assertFailsWith<IOException> { CloudFiles.read(f) { tries++; throw IOException("Permission denied") } }
        assertEquals(1, tries)
    }

    @Test
    fun givingUpSaysWhatToDo() {
        CloudFiles.fetch = {}
        val e = assertFailsWith<IOException> { CloudFiles.read(f, seconds = 1) { throw IOException("Resource deadlock avoided") } }
        assertTrue("iCloud" in e.message!! && "workspace" in e.message!!, e.message)
    }
}
