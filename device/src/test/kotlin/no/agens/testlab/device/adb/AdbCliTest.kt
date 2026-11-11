package no.agens.testlab.device.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbCliTest {
    @Test
    fun parsesDevicesWithStatesAndProperties() {
        val text = """
            List of devices attached
            * daemon not running; starting now at tcp:5037
            emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1
            0349da42               unauthorized usb:1-1 transport_id:3
            1A041FDEE001H8         no permissions (missing udev rules? user is in the plugdev group); see [http://developer.android.com/tools/device.html] usb:3-2 transport_id:2
            37281FDJG00DYA         offline transport_id:4

        """.trimIndent()
        val d = AdbCli.parseDevices(text)
        assertEquals(listOf("emulator-5554", "0349da42", "1A041FDEE001H8", "37281FDJG00DYA"), d.map { it.serial })
        assertEquals("sdk_gphone64_x86_64", d[0].model)
        assertTrue(d[0].ready && d[0].isEmulator)
        assertNull(d[0].hint)
        assertEquals("unauthorized", d[1].state)
        assertTrue(d[1].hint!!.contains("Allow USB debugging"))
        assertEquals("no permissions", d[2].state)
        assertEquals("2", d[2].transportId)
        assertTrue(d[2].hint!!.contains("udev"))
        assertEquals("offline", d[3].state)
    }

    @Test
    fun transportsAreTold() {
        val text = """
            List of devices attached
            0349da42               device usb:1-1 product:x model:Nord transport_id:1
            adb-R5CT900XYZ-AbCdEf._adb-tls-connect._tcp device product:y model:Pixel_8 transport_id:2
            10.0.0.50:5555      device product:z model:Pixel_6 transport_id:3
            [fe80::1]:5555         offline transport_id:4
            emulator-5554          device product:sdk model:sdk transport_id:5
        """.trimIndent()
        assertEquals(listOf("usb", "wifi", "ip", "ip", "emulator"), AdbCli.parseDevices(text).map { it.transport })
        assertTrue(AdbCli.parseDevices(text)[3].hint!!.contains("same network"), "an offline network device gets network advice, not cable advice")
    }

    @Test
    fun connectAndPairReadAdbsWords() {
        val adb = FakeAdb { a -> when (a[0]) {
            "connect" -> if (a[1] == "10.0.0.9:5555") "failed to connect to '${a[1]}': Connection refused" else "connected to ${a[1]}"
            "pair" -> if (a[2] == "000000") "Failed: Wrong password or connection was dropped." else "Successfully paired to ${a[1]} [guid=adb-X]"
            "mdns" -> "List of discovered mdns services\nadb-X\t_adb-tls-connect._tcp\t10.0.0.50:37123\n"
            else -> ""
        } }
        assertEquals("connected to 10.0.0.50:5555", adb.cli.connect("10.0.0.50:5555"))
        kotlin.test.assertFailsWith<AdbException> { adb.cli.connect("10.0.0.9:5555") }
        assertTrue(adb.cli.pair("10.0.0.50:40001", "123456").startsWith("Successfully"))
        kotlin.test.assertFailsWith<AdbException> { adb.cli.pair("10.0.0.50:40001", "000000") }
        assertEquals(listOf(Triple("adb-X", "connect", "10.0.0.50:37123")), adb.cli.mdnsServices())
    }
}
