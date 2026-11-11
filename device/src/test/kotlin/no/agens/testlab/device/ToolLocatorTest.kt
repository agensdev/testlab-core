package no.agens.testlab.device

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolLocatorTest {
    private fun locator(os: HostOs, env: Map<String, String>, existing: Set<String>) =
        ToolLocator(os = os, env = env, home = File("/home/u"), isExecutable = { it.path.replace('\\', '/') in existing })

    @Test
    fun sdkEnvironmentWinsOverPath() {
        val l = locator(
            HostOs.LINUX,
            mapOf("ANDROID_HOME" to "/sdk", "PATH" to "/usr/bin:/bin"),
            setOf("/sdk/platform-tools/adb", "/usr/bin/adb"),
        )
        val found = l.findAdb()!!
        assertEquals("/sdk/platform-tools/adb", found.path.path.replace('\\', '/'))
        assertEquals("\$ANDROID_HOME", found.source)
    }

    @Test
    fun macFindsHomebrewWithoutPath() {
        // A Finder-launched app has no Homebrew on PATH; the fixed location still finds adb.
        val l = locator(HostOs.MAC, mapOf("PATH" to "/usr/bin:/bin"), setOf("/opt/homebrew/bin/adb"))
        assertEquals("Homebrew (Apple silicon)", l.findAdb()!!.source)
    }

    @Test
    fun windowsUsesExeAndLocalAppData() {
        val l = locator(
            HostOs.WINDOWS,
            mapOf("LOCALAPPDATA" to "C:/Users/u/AppData/Local", "PATH" to ""),
            setOf("C:/Users/u/AppData/Local/Android/Sdk/platform-tools/adb.exe"),
        )
        assertEquals("Android Studio SDK", l.findAdb()!!.source)
    }

    @Test
    fun nothingFound() {
        assertNull(locator(HostOs.LINUX, mapOf("PATH" to "/bin"), emptySet()).findAdb())
    }

    @Test
    fun hostOsDetection() {
        assertEquals(HostOs.MAC, HostOs.current("Mac OS X"))
        assertEquals(HostOs.WINDOWS, HostOs.current("Windows 11"))
        assertEquals(HostOs.LINUX, HostOs.current("Linux"))
    }
}
