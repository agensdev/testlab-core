package no.agens.testlab.device.ios

import no.agens.testlab.core.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XcrunTest {
    @Test
    fun simulatorsComeFromTheListWithTheirOs() {
        val json = """{"devices":{
          "com.apple.CoreSimulator.SimRuntime.iOS-26-5":[
            {"udid":"AAA-1","name":"iPhone 17","state":"Booted","isAvailable":true},
            {"udid":"AAA-2","name":"iPad Air","state":"Shutdown","isAvailable":true},
            {"udid":"AAA-3","name":"iPhone 16","state":"Shutdown","isAvailable":false}],
          "com.apple.CoreSimulator.SimRuntime.watchOS-11-0":[{"udid":"W","name":"Watch","state":"Booted","isAvailable":true}]}}"""
        val sims = XcrunCli.parseList(json)
        assertEquals(listOf("AAA-1", "AAA-2"), sims.map { it.udid })
        val d = sims.first().toDevice()
        assertEquals(Platform.IOS, d.platform)
        assertEquals("26.5", d.os)
        assertEquals("device", d.state)
        assertEquals("shutdown", sims[1].toDevice().state)
        assertEquals("iOS 18.2.1", XcrunCli.runtimeName("com.apple.CoreSimulator.SimRuntime.iOS-18-2-1"))
        assertNull(XcrunCli.runtimeName("com.apple.CoreSimulator.SimRuntime.tvOS-18-0"))
    }

    @Test
    fun appsPutTheUsersFirst() {
        val json = """{"com.apple.mobilesafari":{"ApplicationType":"System"},"com.example.shop":{"ApplicationType":"User"}}"""
        assertEquals(listOf("com.example.shop", "com.apple.mobilesafari"), XcrunCli.parseApps(json))
    }

    @Test
    fun theGuardRunsBeforeACommandForASimulator() {
        val seen = mutableListOf<String>()
        val x = XcrunCli(exec = { args, _ -> seen += args.joinToString(" "); XcrunCli.Result(0, "", "") })
        x.guard = { udid -> seen += "lease $udid" }
        x.launch("AAA-1", "com.example.shop", listOf("-environment", "int"))
        assertEquals(listOf("lease AAA-1", "simctl launch --terminate-running-process AAA-1 com.example.shop -environment int"), seen)
    }

    @Test
    fun anMjpegStreamSplitsIntoJpegs() {
        val a = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        val b = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 3, 0xFF.toByte(), 0xD9.toByte())
        val stream = "--b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + a + "\r\n--b\r\n\r\n".toByteArray() + b
        val got = mutableListOf<ByteArray>()
        Mjpeg.frames(stream.inputStream()) { got += it; true }
        assertEquals(listOf(a.toList(), b.toList()), got.map { it.toList() })
    }
}
