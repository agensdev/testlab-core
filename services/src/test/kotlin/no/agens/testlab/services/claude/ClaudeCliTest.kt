package no.agens.testlab.services.claude

import no.agens.testlab.device.HostOs
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudeCliTest {
    private val tmp = Files.createTempDirectory("tl-claude").toFile()

    @Test
    fun claudeIsLookedForWhereItsInstallersPutIt() {
        val home = File(tmp, "home")
        assertTrue(File(home, ".local/bin/claude") in ClaudeCli.candidates(HostOs.MAC, emptyMap(), home))
        val win = ClaudeCli.candidates(HostOs.WINDOWS, mapOf("APPDATA" to "C:/Users/k/AppData/Roaming"), home)
        assertTrue(File("C:/Users/k/AppData/Roaming", "npm/claude.cmd") in win, win.toString())
        val bin = File(home, ".local/bin/claude").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true) }
        assertEquals(bin.path, ClaudeCli.locate(bin.path)?.path, "a path in the settings wins")
    }
}
