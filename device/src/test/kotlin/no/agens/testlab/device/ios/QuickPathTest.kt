package no.agens.testlab.device.ios

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The quick iOS path: WebDriverAgent called directly, the tree without `visible`, frames from MJPEG. */
class QuickPathTest {
    private val appiumCalls = mutableListOf<String>()
    private val wdaCalls = mutableListOf<String>()

    private fun server(calls: MutableList<String>, answer: (String, String) -> String): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.toString()
                synchronized(calls) { calls += "${ex.requestMethod} $path" }
                val out = answer(ex.requestMethod, path)
                ex.sendResponseHeaders(200, out.toByteArray().size.toLong()); ex.responseBody.use { it.write(out.toByteArray()) }
            }
            start()
        }

    private val appium = server(appiumCalls) { _, path ->
        when {
            path == "/session" -> """{"value":{"sessionId":"appium-1","capabilities":{}}}"""
            path.endsWith("/source") -> """{"value":"<AppiumAUT/>"}"""
            "/execute/sync" in path -> """{"value":{"bundleId":"via.appium"}}"""
            else -> """{"value":null}"""
        }
    }
    private val wda = server(wdaCalls) { _, path ->
        when {
            path == "/status" -> """{"value":{"ready":true},"sessionId":"wda-1"}"""
            path.startsWith("/session/wda-1/source") -> """{"value":"<XCUIElementTypeApplication/>"}"""
            path == "/wda/activeAppInfo" -> """{"value":{"bundleId":"com.example.shop"}}"""
            path == "/session/wda-1/window/size" -> """{"value":{"width":402,"height":874}}"""
            path == "/session/wda-1/element" -> """{"value":{"element-6066-11e4-a52e-4f735466cecf":"E1"}}"""
            else -> """{"value":null}"""
        }
    }

    @AfterTest
    fun tearDown() { appium.stop(0); wda.stop(0) }

    @Test
    fun hotCallsGoStraightToWebDriverAgentAndBackToAppiumWhenItGoes() {
        val d = WebDriver("http://127.0.0.1:${appium.address.port}")
        d.newSession(JsonObject(emptyMap()))
        assertTrue(d.goDirect("http://127.0.0.1:${wda.address.port}") && d.isDirect)
        d.sourceExcluded = "visible"
        assertEquals("<XCUIElementTypeApplication/>", d.source())
        assertTrue("GET /session/wda-1/source?excluded_attributes=visible" in wdaCalls, wdaCalls.toString())
        assertEquals("com.example.shop", ((d.mobile("activeAppInfo") as JsonObject)["bundleId"] as JsonPrimitive).content)
        assertEquals(402 to 874, d.windowRect(), "WebDriverAgent's /window/size")
        d.tap(10, 20)
        assertTrue(wdaCalls.any { it == "POST /session/wda-1/actions" } && appiumCalls.none { "/actions" in it }, "a tap never passes Appium: $appiumCalls")
        assertEquals("E1", d.find("-ios class chain", "**/XCUIElementTypeButton[1]"))
        assertTrue("POST /session/wda-1/element" in wdaCalls)

        wda.stop(0) // WebDriverAgent gone: the same calls go through Appium from now on
        assertEquals("<AppiumAUT/>", d.source())
        assertEquals("via.appium", ((d.mobile("activeAppInfo") as JsonObject)["bundleId"] as JsonPrimitive).content)
        assertTrue(!d.isDirect && appiumCalls.contains("GET /session/appium-1/source"), appiumCalls.toString())
    }

    @Test
    fun withoutVisibleTheTreeJudgesByBounds() {
        val xml = """<XCUIElementTypeApplication type="XCUIElementTypeApplication" name="Shop" x="0" y="0" width="402" height="874">
            <XCUIElementTypeButton type="XCUIElementTypeButton" label="On screen" x="10" y="100" width="100" height="44"/>
            <XCUIElementTypeButton type="XCUIElementTypeButton" label="Below" x="10" y="900" width="100" height="44"/>
            <XCUIElementTypeButton type="XCUIElementTypeButton" label="No size" x="10" y="200" width="0" height="0"/>
            <XCUIElementTypeButton type="XCUIElementTypeButton" label="Said hidden" visible="false" x="10" y="300" width="100" height="44"/>
        </XCUIElementTypeApplication>"""
        val texts = mutableListOf<String>()
        fun walk(o: JsonObject) { (o["text"] as? JsonPrimitive)?.let { texts += it.content }; (o["children"] as? JsonArray)?.forEach { walk(it.jsonObject) } }
        walk(XcuiTree.toDump(xml, 3.0, "com.example.shop")["root"]!!.jsonObject)
        assertEquals(listOf("On screen"), texts.filter { it != "Shop" })
    }

    @Test
    fun streamFramesAreHandedOutFreshThenTheLastOne() {
        val frame = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            ss.accept().use { s ->
                val o = s.getOutputStream()
                o.write("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=--b\r\n\r\n".toByteArray())
                repeat(3) { o.write("--b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray()); o.write(frame); o.write("\r\n".toByteArray()); o.flush(); Thread.sleep(50) }
                Thread.sleep(2_000) // then a still screen: nothing more is sent
            }
        }
        MjpegTap(ss.localPort).use { tap ->
            assertNotNull(tap.next(System.nanoTime(), 2_000), "a frame after the call")
            Thread.sleep(300)
            val t0 = System.currentTimeMillis()
            assertNotNull(tap.next(System.nanoTime(), 200), "no new frame: the last one is the screen")
            assertTrue(System.currentTimeMillis() - t0 < 1_000)
        }
        ss.close()
    }
}
