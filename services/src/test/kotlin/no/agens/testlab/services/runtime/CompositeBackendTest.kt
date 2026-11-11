package no.agens.testlab.services.runtime

import kotlinx.coroutines.runBlocking
import no.agens.testlab.core.Platform
import no.agens.testlab.device.adb.FakeAdb
import no.agens.testlab.device.adb.LabDevice
import no.agens.testlab.device.hs.HsEndpoint
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompositeBackendTest {
    private val tmp = Files.createTempDirectory("tl-composite").toFile()

    @AfterTest
    fun tearDown() { tmp.deleteRecursively() }

    private class Fixed(val list: List<LabDevice>, val log: MutableList<String>, val fail: Throwable? = null) : DeviceBackend {
        override fun devices(): List<LabDevice> { fail?.let { throw it }; return list }
        override fun connect(serial: String, takeOver: Boolean): HsEndpoint { log += "connect $serial"; return HsEndpoint(serial, 1) }
        override fun release(serial: String) { log += "release $serial" }
    }

    private val sim = LabDevice("8F1C-UDID", "device", model = "iPhone 17", platform = Platform.IOS, os = "26.5")

    @Test
    fun bothPlatformsListAndEachSerialGoesToItsOwnBackend() {
        val androidLog = mutableListOf<String>(); val iosLog = mutableListOf<String>()
        val c = CompositeBackend(mapOf(
            Platform.ANDROID to Fixed(listOf(LabDevice("R5CT1", "device")), androidLog),
            Platform.IOS to Fixed(listOf(sim), iosLog),
        ))
        assertEquals(listOf("R5CT1", "8F1C-UDID"), c.devices().map { it.serial })
        c.connect("8F1C-UDID", false); c.connect("R5CT1", false); c.release("8F1C-UDID")
        assertEquals(listOf("connect R5CT1"), androidLog)
        assertEquals(listOf("connect 8F1C-UDID", "release 8F1C-UDID"), iosLog)
        assertEquals("simulator", sim.transport)
        assertTrue(sim.isEmulator)
    }

    @Test
    fun aBackendThatCannotListLeavesTheOthersAndAloneItsErrorStands() {
        val c = CompositeBackend(mapOf(Platform.ANDROID to Fixed(emptyList(), mutableListOf(), NoAdb()), Platform.IOS to Fixed(listOf(sim), mutableListOf())))
        assertEquals(listOf("8F1C-UDID"), c.devices().map { it.serial })
        val only = CompositeBackend(mapOf(Platform.ANDROID to Fixed(emptyList(), mutableListOf(), NoAdb())))
        assertFailsWith<NoAdb> { only.devices() }
    }

    @Test
    fun anIosDeviceIsToldAndroidOnlyFeaturesAreMissing() = runBlocking {
        val dirs = AppDirs(File(tmp, "d"), File(tmp, "c"), File(tmp, "w"))
        val rt = LabRuntime(dirs, SettingsStore(dirs.settings), MemorySecretStore(), adbOverride = FakeAdb { "" }.cli,
            backendOverride = Fixed(listOf(LabDevice("R5CT1", "device"), sim), mutableListOf()))
        try {
            assertEquals(Platform.IOS, rt.platformOf("8F1C-UDID"))
            assertEquals(Platform.ANDROID, rt.platformOf("R5CT1"))
            assertEquals("iOS devices need macOS with Xcode", assertFailsWith<NotOnPlatform> { rt.ops("8F1C-UDID") }.message)
            assertEquals("generic-ios", rt.profileFor("8F1C-UDID").id)
            assertEquals("generic", rt.profileFor("R5CT1").id)
        } finally { rt.close() }
    }
}
