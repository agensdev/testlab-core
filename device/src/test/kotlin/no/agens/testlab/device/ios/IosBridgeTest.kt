package no.agens.testlab.device.ios

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.device.hs.HsDevice
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real HsDevice on top of the bridge, with a fake WebDriver server and a fake simctl. */
class IosBridgeTest {
    private val tmp = Files.createTempDirectory("tl-ios").toFile()
    private val xml = javaClass.getResource("/ios/login.xml")!!.readText()
    private val posted = mutableListOf<Pair<String, String>>()
    private val simctl = mutableListOf<String>()
    private val gets = mutableListOf<String>()

    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val path = ex.requestURI.path.replace(Regex("^/session/[^/]+"), "/S")
            synchronized(posted) { if (ex.requestMethod == "POST") posted += path to body }
            synchronized(gets) { if (ex.requestMethod == "GET") gets += path }
            val value = when {
                path == "/session" -> """{"sessionId":"s1","capabilities":{}}"""
                path == "/S/window/rect" -> """{"x":0,"y":0,"width":402,"height":874}"""
                path == "/S/orientation" -> "\"LANDSCAPE\""
                path == "/S/source" -> Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(xml))
                path == "/S/execute/sync" && "activeAppInfo" in body -> """{"bundleId":"com.example.shop","name":"Shop"}"""
                path == "/S/element" && "\"email\"" in body -> """{"element-6066-11e4-a52e-4f735466cecf":"EL-1"}"""
                path == "/S/element" && "XCUIElementTypeNavigationBar" in body -> """{"element-6066-11e4-a52e-4f735466cecf":"NB-1"}"""
                path == "/S/element/NB-1/attribute/name" -> "\"Log in\""
                path == "/S/element" -> null
                else -> "null"
            }
            val (code, out) = if (value == null) 404 to """{"value":{"error":"no such element","message":"nope"}}""" else 200 to """{"value":$value}"""
            ex.sendResponseHeaders(code, out.length.toLong()); ex.responseBody.use { it.write(out.toByteArray()) }
        }
        start()
    }

    private val xcrun = XcrunCli(exec = { args, _ ->
        synchronized(simctl) { simctl += args.joinToString(" ") }
        if (args.getOrNull(1) == "io" && args.getOrNull(3) == "screenshot") {
            ImageIO.write(BufferedImage(1206, 2622, BufferedImage.TYPE_INT_RGB), "png", File(args.last()))
        }
        XcrunCli.Result(0, "", "")
    })
    private val driver = WebDriver("http://127.0.0.1:${http.address.port}").also { it.newSession(JsonObject(emptyMap())) }
    private val bridge = IosBridge("SIM-1", xcrun, driver, null, File(tmp, "b"))
    private val dev = HsDevice("SIM-1") { bridge.endpoint }

    @AfterTest
    fun tearDown() { dev.close(); bridge.close(); http.stop(0); tmp.deleteRecursively() }

    private fun last(path: String) = synchronized(posted) { posted.last { it.first == path }.second }
    private fun tapAt(): Pair<Int, Int> {
        val acts = Json.parseToJsonElement(last("/S/actions")).jsonObject["actions"]!!.jsonArray[0].jsonObject["actions"]!!.jsonArray
        val move = acts[0].jsonObject
        return move["x"]!!.jsonPrimitive.content.toInt() to move["y"]!!.jsonPrimitive.content.toInt()
    }

    @Test
    fun theDaemonVerbsDriveTheSimulator() = runBlocking {
        assertEquals(1206 to 2622, dev.screenSize(), "pixels: 402 pt × 3")
        assertTrue("#email" in dev.table())
        assertEquals("com.example.shop/Log in", dev.topActivity())
        val sources = { synchronized(gets) { gets.count { it == "/S/source" } } }
        Thread.sleep(1_100)
        val before = sources()
        assertEquals("com.example.shop/Log in", dev.topActivity(), "without a recent dump: the navigation bar alone")
        assertEquals(before, sources(), "and no full /source for it")

        dev.tapText("Cart")
        assertEquals(201 to 816, tapAt(), "the tab's centre, back in points")

        assertTrue("set_text" in dev.fill("E-mail", "kari@example.com"))
        assertEquals("""{"text":"kari@example.com"}""", last("/S/element/EL-1/value"))

        dev.key("BACK")
        assertEquals(43 to 84, tapAt(), "BACK taps the navigation bar's back button")

        assertEquals("landscape", dev.orientation())
        assertEquals("landscape", dev.setOrientation("landscape"))
        assertTrue("""{"orientation":"LANDSCAPE"}""" == last("/S/orientation"))
        assertEquals("landscape-right", orientationName("UIA_DEVICE_ORIENTATION_LANDSCAPERIGHT"))

        // Home: the active app and the profile's bundles terminated, then the home screen.
        assertEquals(listOf("com.example.shop", "com.example.shop.beta"), dev.home(true, listOf("com.example.shop.beta")))
        val sync = synchronized(posted) { posted.filter { it.first == "/S/execute/sync" }.map { it.second } }
        assertTrue(sync.any { "terminateApp" in it && "com.example.shop.beta" in it } && sync.last().let { "pressButton" in it && "home" in it }, sync.takeLast(3).toString())

        // A drag: one W3C touch sequence in points, legs timed by length, the second pass back.
        dev.drag(listOf(300 to 900, 900 to 900, 900 to 1500), 600, 100, 2)
        val drag = Json.parseToJsonElement(last("/S/actions")).jsonObject["actions"]!!.jsonArray[0].jsonObject
        assertEquals("touch", drag["parameters"]!!.jsonObject["pointerType"]!!.jsonPrimitive.content)
        val steps = drag["actions"]!!.jsonArray.map { s -> s.jsonObject.let { o -> listOf("type", "duration", "x", "y").joinToString(" ") { o[it]?.jsonPrimitive?.content ?: "-" } } }
        assertEquals(listOf("pointerMove 0 100 300", "pointerDown - - -", "pause 100 - -", "pointerMove 300 300 300", "pointerMove 300 300 500",
            "pointerMove 300 300 300", "pointerMove 300 100 300", "pointerUp - - -"), steps)

        dev.typeText("Blåbær")
        assertTrue("\"å\"" in last("/S/wda/keys"), "æøå go in through WebDriver's keys")

        dev.amStart("com.example.shop/.Main")
        assertTrue(simctl.any { it == "simctl launch --terminate-running-process SIM-1 com.example.shop" }, simctl.toString())
        assertTrue(dev.waitFor("com.example.shop", 2_000).startsWith("ok"))

        val jpeg = dev.screenshot(768)
        val img = ImageIO.read(jpeg.inputStream())
        assertEquals(768, img.height, "the long edge")
    }
}
