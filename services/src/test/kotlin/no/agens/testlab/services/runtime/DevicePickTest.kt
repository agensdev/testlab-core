package no.agens.testlab.services.runtime

import no.agens.testlab.device.adb.AdbDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevicePickTest {
    private val emu = AdbDevice("emulator-5554", "device")
    private val emu2 = AdbDevice("emulator-5556", "device")
    private val phone = AdbDevice("R5CT1234", "device", model = "Pixel_8")
    private val phone2 = AdbDevice("0123abcd", "device")
    private val locked = AdbDevice("ZY22", "unauthorized")

    @Test
    fun aPhoneWinsOverEmulators() {
        assertEquals("R5CT1234", DevicePick.default(listOf(emu, phone, emu2)))
        assertEquals("emulator-5554", DevicePick.default(listOf(emu, locked)))
    }

    @Test
    fun ambiguityOrNothingIsAnError() {
        assertTrue(assertFailsWith<NoDevice> { DevicePick.default(listOf(phone, phone2, emu)) }.message!!.contains("phones"))
        assertTrue(assertFailsWith<NoDevice> { DevicePick.default(listOf(emu, emu2)) }.message!!.contains("devices"))
        assertTrue(assertFailsWith<NoDevice> { DevicePick.default(listOf(locked)) }.message!!.contains("unauthorized"))
        assertFailsWith<NoDevice> { DevicePick.default(emptyList()) }
    }

    @Test
    fun windowTargets() {
        val ready = listOf(emu, phone)
        assertEquals("R5CT1234", DevicePick.target(ready, active = "emulator-5554"), "builds: a phone over the connected emulator")
        assertEquals("emulator-5554", DevicePick.target(ready, active = "emulator-5554", preferActive = true), "plans: the device on screen")
        assertEquals("emulator-5554", DevicePick.target(ready, active = null, chosen = "emulator-5554"), "the person's choice")
        assertEquals("R5CT1234", DevicePick.target(ready, active = "gone", chosen = "gone"))
        assertEquals("emulator-5556", DevicePick.target(listOf(emu, emu2), active = "emulator-5556"))
        assertNull(DevicePick.target(emptyList(), active = "emulator-5554"))
    }
}
