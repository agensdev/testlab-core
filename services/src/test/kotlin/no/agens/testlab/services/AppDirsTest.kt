package no.agens.testlab.services

import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppDirsTest {
    private val home = File("/home/u")
    private fun File.norm() = path.replace('\\', '/')

    @Test
    fun linuxHonoursXdg() {
        val d = AppDirs.forHost(HostOs.LINUX, mapOf("XDG_DATA_HOME" to "/x/data"), home)
        assertEquals("/x/data/${AppIdentity.SLUG}", d.data.norm())
        assertEquals("/home/u/.cache/${AppIdentity.SLUG}", d.cache.norm())
        assertEquals("/home/u/${AppIdentity.SLUG}/reports", d.reports.norm())
    }

    @Test
    fun macUsesLibraryFolders() {
        val d = AppDirs.forHost(HostOs.MAC, emptyMap(), home)
        assertEquals("/home/u/Library/Application Support/${AppIdentity.NAME}/secrets.vault", d.vault.norm())
        assertEquals("/home/u/Library/Caches/${AppIdentity.NAME}/builds", d.builds.norm())
    }

    @Test
    fun windowsUsesAppData() {
        val d = AppDirs.forHost(HostOs.WINDOWS, mapOf("APPDATA" to "C:/R", "LOCALAPPDATA" to "C:/L"), home)
        assertEquals("C:/R/${AppIdentity.NAME}", d.data.norm())
        assertEquals("C:/L/${AppIdentity.NAME}/Cache", d.cache.norm())
    }
}
