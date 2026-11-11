package no.agens.testlab.services

import no.agens.testlab.device.HostOs
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpacesTest {
    private val home = Files.createTempDirectory("spaces").toFile()
    @AfterTest fun tearDown() { Spaces.override = null; home.deleteRecursively() }

    @Test
    fun aSpaceHasItsOwnFoldersOutsideDocuments() {
        val d = AppDirs.forHost(HostOs.MAC, emptyMap(), home, space = "acme")
        assertEquals(File(home, "Library/Application Support/Testlab/spaces/acme"), d.data)
        assertEquals(File(home, "Library/Caches/Testlab/spaces/acme"), d.cache)
        assertEquals(File(home, "Testlab/acme"), d.workspace, "not ~/Documents, which iCloud may sync")
        assertEquals(File(home, "Documents/Testlab"), AppDirs.forHost(HostOs.MAC, emptyMap(), home, space = "default").workspace, "default stays where it was")
        assertEquals(File(home, "testlab/globex"), AppDirs.forHost(HostOs.LINUX, emptyMap(), home, space = "globex").workspace)
    }

    @Test
    fun theSpaceComesFromTheFlagThenTheEnvironmentThenTheLastUsed() {
        val root = AppDirs.root(HostOs.LINUX, mapOf("XDG_DATA_HOME" to File(home, "d").path), home)
        assertEquals(Spaces.DEFAULT, Spaces.current(emptyMap(), root))
        Spaces.use("globex", root)
        assertEquals("globex", Spaces.current(emptyMap(), root))
        assertEquals("acme", Spaces.current(mapOf("TESTLAB_SPACE" to "acme"), root))
        Spaces.override = "initech"
        assertEquals("initech", Spaces.current(mapOf("TESTLAB_SPACE" to "acme"), root))
    }

    @Test
    fun namesDifferPerSpaceAndDefaultKeepsTheOldOnes() {
        assertEquals("testlab", Spaces.mcpName(Spaces.DEFAULT))
        assertEquals("testlab-acme", Spaces.mcpName("acme"))
        assertEquals(listOf("--space", "acme"), Spaces.args("acme"))
        assertEquals(emptyList(), Spaces.args(Spaces.DEFAULT))
    }

    @Test
    fun badNamesAreRefused() {
        for (bad in listOf("Bad Name", "../x", "reports", "spaces", "-x", "a".repeat(40))) assertFailsWith<IllegalArgumentException>(bad) { Spaces.check(bad) }
        assertTrue(Spaces.valid("initech-2"))
    }
}
