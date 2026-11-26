package no.agens.testlab.device.ios

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.device.hs.HsDevice
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A real iPhone through the bridge: no simctl, screenshots from WebDriverAgent, apps through devicectl. */
class PhoneBridgeTest {
    private val tmp = Files.createTempDirectory("tl-phone").toFile()
    private val xml = javaClass.getResource("/ios/login.xml")!!.readText()
    private val posted = mutableListOf<Pair<String, String>>()
    private val devicectl = mutableListOf<String>()

    private val png: String = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1179, 2556, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        .let { Base64.getEncoder().encodeToString(it) }

    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val path = ex.requestURI.path.replace(Regex("^/session/[^/]+"), "/S")
            synchronized(posted) { if (ex.requestMethod == "POST") posted += path to body }
            val value = when {
                path == "/session" -> """{"sessionId":"s1","capabilities":{}}"""
                path == "/S/window/rect" -> """{"x":0,"y":0,"width":393,"height":852}"""
                path == "/S/screenshot" -> "\"$png\""
                path == "/S/source" -> Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(xml))
                path == "/S/execute/sync" && "activeAppInfo" in body -> """{"bundleId":"com.example.shop","name":"Shop"}"""
                else -> "null"
            }
            val out = """{"value":$value}"""
            ex.sendResponseHeaders(200, out.length.toLong()); ex.responseBody.use { it.write(out.toByteArray()) }
        }
        start()
    }

    /** Only devicectl may run: a simctl call would mean the bridge still thinks it is a simulator. */
    private val xcrun = XcrunCli(exec = { args, _ ->
        require(args.first() == "devicectl") { "not devicectl: $args" }
        synchronized(devicectl) { devicectl += args.joinToString(" ") }
        val out = File(args[args.indexOf("--json-output") + 1])
        if (args.take(4) == listOf("devicectl", "device", "info", "apps")) out.writeText("""{"result":{"apps":[{"bundleIdentifier":"com.example.shop"},{"bundleIdentifier":"com.example.other"}]}}""")
        else out.writeText("{}")
        XcrunCli.Result(0, "", "")
    })
    private val driver = WebDriver("http://127.0.0.1:${http.address.port}").also { it.newSession(JsonObject(emptyMap())) }
    private val bridge = IosBridge(PhoneTarget(DevicectlCli(xcrun), "00008110-AAA", "Test iPhone", "18.6") { driver }, null, { driver to null }, File(tmp, "b"))
    private val dev = HsDevice("00008110-AAA") { bridge.endpoint }

    @AfterTest
    fun tearDown() { dev.close(); bridge.close(); http.stop(0); tmp.deleteRecursively() }

    @Test
    fun screenAppsAndStateWithoutSimctl() = runBlocking {
        assertEquals(1179 to 2556, dev.screenSize(), "pixels from WebDriverAgent's screenshot over the window's points")
        assertTrue(dev.screenshot().isNotEmpty())
        assertEquals("com.example.shop\ncom.example.other", dev.listApps())
        dev.amStart("com.example.shop", listOf("-environment", "int"))
        assertTrue(devicectl.any { it.startsWith("devicectl device process launch --device 00008110-AAA --terminate-existing com.example.shop -- -environment int") }, devicectl.toString())
        dev.forceStop("com.example.shop")
        assertTrue(posted.any { it.first == "/S/execute/sync" && "terminateApp" in it.second && "com.example.shop" in it.second }, "an app is stopped through WebDriverAgent")
        val state = dev.deviceState()
        assertEquals("Test iPhone", state["model"]!!.jsonPrimitive.content)
        assertEquals("18.6", state["release"]!!.jsonPrimitive.content)
    }

    @Test
    fun realDeviceSessionIsSignedWithTheTeam() {
        val caps = Appium.realDeviceCapabilities("00008110-AAA", 8101, 9101, "ABCDE12345", "com.example.wda.WebDriverAgentRunner")
        assertEquals("ABCDE12345", caps["appium:xcodeOrgId"]!!.jsonPrimitive.content)
        assertEquals("com.example.wda.WebDriverAgentRunner", caps["appium:updatedWDABundleId"]!!.jsonPrimitive.content)
        assertEquals("00008110-AAA", caps["appium:udid"]!!.jsonPrimitive.content)
        assertEquals("480000", caps["appium:wdaLaunchTimeout"]!!.jsonPrimitive.content, "a phone gets longer than a simulator")
        assertEquals("x.wda.abcde12345.WebDriverAgentRunner", Appium.defaultWdaBundleId("x", "ABCDE12345"))
        assertTrue(Appium.TEAM_ID.matches("ABCDE12345") && !Appium.TEAM_ID.matches("abc"))
        assertTrue("Xcode → Settings → Accounts" in Appium.realDeviceHint("xcodebuild failed with code 65: Signing for WebDriverAgentRunner requires a development team"))
        assertTrue("Enable UI Automation" in Appium.realDeviceHint("Timed out waiting for WebDriverAgent"))
    }

    @Test
    fun aPhoneNewerThanXcodeIsBuiltForTheSdksVersion() {
        // iOS 27.2 on the phone, Xcode's SDK at 27.1: Xcode refuses a deployment target of 27.2.
        assertEquals("27.1", Appium.buildableVersion("27.2", "27.1"))
        assertEquals("18.2", Appium.buildableVersion("19.0", "18.2"))
        assertEquals("26.0", Appium.buildableVersion("26.1.1", "26"))
        // Same or older: Appium keeps the phone's own version.
        assertEquals(null, Appium.buildableVersion("27.1", "27.1"))
        assertEquals(null, Appium.buildableVersion("18.6", "27.1"))
        assertEquals(null, Appium.buildableVersion("27.1.2", "27.1"), "a patch release needs no cap")
        // Unknown on either side: nothing is forced.
        assertEquals(null, Appium.buildableVersion(null, "27.1"))
        assertEquals(null, Appium.buildableVersion("27.2", null))
        assertEquals(null, Appium.buildableVersion("beta", "27.1"))

        val capped = Appium.realDeviceCapabilities("00008110-AAA", 8101, 9101, "ABCDE12345", "com.example.wda.WebDriverAgentRunner", "27.1")
        assertEquals("27.1", capped["appium:platformVersion"]!!.jsonPrimitive.content)
        val plain = Appium.realDeviceCapabilities("00008110-AAA", 8101, 9101, "ABCDE12345", "com.example.wda.WebDriverAgentRunner")
        assertTrue("appium:platformVersion" !in plain, "left to Appium when the phone's own version builds")
    }

    @Test
    fun aFailedBuildNamesBothUsualCauses() {
        // Appium passes on only the exit code: the hint must not claim signing when it can't know.
        val bare = Appium.realDeviceHint("Unable to launch WebDriverAgent. Original error: xcodebuild failed with code 65.")
        assertTrue("Xcode → Settings → Accounts" in bare && "update Xcode" in bare)
        assertTrue(Appium.realDeviceHint("The iOS deployment target 'IPHONEOS_DEPLOYMENT_TARGET' is set to 27.2").startsWith("this Xcode is older"))
    }
}
