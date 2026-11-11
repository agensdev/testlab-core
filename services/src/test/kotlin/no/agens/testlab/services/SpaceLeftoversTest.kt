package no.agens.testlab.services

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpaceLeftoversTest {
    @Test
    fun `the default space's leftovers are named, and none in the default space itself`() {
        val tmp = Files.createTempDirectory("tl-left").toFile()
        val root = AppDirs(File(tmp, "data"), File(tmp, "cache"), File(tmp, "work"))
        val acme = AppDirs(File(tmp, "data/spaces/acme"), File(tmp, "cache/spaces/acme"), File(tmp, "work/acme"))
        assertEquals(emptyList(), SpaceLeftovers.find(acme, root))
        File(root.data, "proxied.json").apply { parentFile.mkdirs(); writeText("[]") }
        File(root.workspace, "reports/20261011-x").mkdirs()
        val left = SpaceLeftovers.find(acme, root)
        assertTrue(left.any { "proxy routings" in it } && left.any { "1 report(s)" in it }, left.toString())
        assertEquals(emptyList(), SpaceLeftovers.find(root, root))
        tmp.deleteRecursively()
    }
}
