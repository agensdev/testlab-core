package no.agens.testlab.device.ios

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class DevicectlTest {
    @Test
    fun realIphonesAndIpadsFromBothShapesOfTheList() {
        val json = """{"result":{"devices":[
          {"hardwareProperties":{"platform":"iOS","deviceType":"iPhone","udid":"00008110-AAA","marketingName":"iPhone 13","reality":"physical"},
           "connectionProperties":{"pairingState":"paired","tunnelState":"disconnected"},
           "deviceProperties":{"name":"Test iPhone","osVersionNumber":"18.6"}},
          {"properties":{"hardware":{"platform":"iOS","deviceType":"iPad","udid":"00008120-BBB","productType":"iPad14,1"},
             "connection":{"pairingState":"unpaired","tunnelState":"unavailable"},"state":{"name":"Lab iPad"},
             "software":{"osVersionNumber":{"stringValue":"17.5"}}}},
          {"hardwareProperties":{"platform":"iOS","deviceType":"iPhone","udid":"SIM","reality":"simulated"}},
          {"hardwareProperties":{"platform":"watchOS","deviceType":"appleWatch","udid":"W"}}
        ]}}"""
        val list = DevicectlCli.parseDevices(Json.parseToJsonElement(json) as JsonObject)
        assertEquals(listOf("00008110-AAA", "00008120-BBB"), list.map { it.udid }, "simulators and watches left out")
        assertEquals(PhysicalIos("00008110-AAA", "Test iPhone", "iPhone 13", "18.6", false, null), list[0], "disconnected = no tunnel yet, still usable")
        assertEquals(true, list[1].tablet)
        assertEquals("17.5", list[1].os)
        assertEquals(true, list[1].problem!!.startsWith("not paired"))
        assertEquals(listOf("com.example.a", "com.example.b"), DevicectlCli.parseApps(Json.parseToJsonElement("""{"result":{"apps":[{"bundleIdentifier":"com.example.a"},{"bundleIdentifier":"com.example.b"}]}}""") as JsonObject))
    }
}
