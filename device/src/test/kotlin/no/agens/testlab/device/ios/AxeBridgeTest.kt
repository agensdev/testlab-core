package no.agens.testlab.device.ios

import kotlinx.coroutines.runBlocking
import no.agens.testlab.device.hs.HsDevice
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real HsDevice on top of the bridge driven by a fake AXe and a fake simctl: no WebDriver at all. */
class AxeBridgeTest {
    private val tmp = Files.createTempDirectory("tl-axe").toFile()
    private val json = javaClass.getResource("/ios/axe-login.json")!!.readText()
    private val axeCalls = mutableListOf<String>()
    private val simctl = mutableListOf<String>()
    private var wdaOpened = false

    private val axe = AxeCli("axe", exec = { args, stdin ->
        synchronized(axeCalls) { axeCalls += (args.filter { it != "--udid" && it != "SIM-1" } + listOfNotNull(stdin?.decodeToString()?.let { "<$it>" })).joinToString(" ") }
        XcrunCli.Result(0, if (args[0] == "describe-ui") json else "", "")
    })
    private val xcrun = XcrunCli(exec = { args, stdin ->
        synchronized(simctl) { simctl += args.joinToString(" ") + (stdin?.let { " <${it.decodeToString()}>" } ?: "") }
        if (args.getOrNull(1) == "io" && args.getOrNull(3) == "screenshot") ImageIO.write(BufferedImage(1206, 2622, BufferedImage.TYPE_INT_RGB), "png", File(args.last()))
        val out = if (args.getOrNull(1) == "spawn") "PID\tStatus\tLabel\n4242\t0\tUIKitApplication:com.example.shop[ab12][rb-legacy]\n-\t0\tcom.apple.other\n" else ""
        XcrunCli.Result(0, out, "")
    })
    private val bridge = IosBridge("SIM-1", xcrun, axe, { wdaOpened = true; error("no WebDriverAgent in this test") }, File(tmp, "b"))
    private val dev = HsDevice("SIM-1") { bridge.endpoint }

    @AfterTest
    fun tearDown() { dev.close(); bridge.close(); tmp.deleteRecursively() }

    private fun lastAxe(prefix: String) = synchronized(axeCalls) { axeCalls.last { it.startsWith(prefix) } }

    @Test
    fun axeDrivesTheSimulatorWithoutWebDriverAgent() = runBlocking {
        assertEquals(1206 to 2622, dev.screenSize(), "pixels: 402 pt × 3, the size from the tree's app element")
        val table = dev.table()
        assertTrue("#email" in table && "Remember me" in table && "Cart" in table, table)
        assertTrue("Scrolled away" !in table, "off-screen elements are not on the screen: $table")
        assertEquals("com.example.shop/Log in", dev.topActivity(), "the bundle from launchd by pid, the title from the heading")

        dev.tapText("Cart")
        assertEquals("tap -x 201 -y 816", lastAxe("tap"), "the tab's centre, back in points")

        assertTrue("set_text" in dev.fill("E-mail", "kari@example.com"))
        assertEquals(listOf("tap -x 201 -y 222", "key-combo --modifiers 227 --key 4", "key 42", "type --stdin <kari@example.com>"), synchronized(axeCalls) { axeCalls.filter { !it.startsWith("describe-ui") }.takeLast(4) })
        val email = Regex("\"rid\":\"email\"[^}]*\"flags\":\"([a-zA-Z]+)\"").find(dev.dumpJson().toString())!!.groupValues[1]
        assertTrue('F' in email, "the field tapped last has the focus: $email")

        dev.typeText("Blåbær")
        assertTrue(simctl.any { it.startsWith("simctl pbcopy SIM-1") && "å" in it }, "æøå go in through the pasteboard: $simctl")
        assertEquals("key-combo --modifiers 227 --key 25", lastAxe("key-combo"))

        dev.key("ENTER")
        assertEquals("key 40", lastAxe("key "))
        dev.key("BACK")
        assertEquals("tap -x 43 -y 84", lastAxe("tap"), "BACK taps the top-left back button")
        dev.key("HOME")
        assertEquals("button home", lastAxe("button"))
        dev.swipeDir("up")

        dev.amStart("com.example.shop/.Main")
        assertTrue(simctl.any { it == "simctl launch --terminate-running-process SIM-1 com.example.shop" }, simctl.toString())
        assertTrue(dev.waitFor("com.example.shop", 2_000).startsWith("ok"))
        assertTrue(!wdaOpened, "nothing above needed WebDriverAgent")
    }

    @Test
    fun theTreeReadsLikeAndroids() {
        val d = AxTree.parse(json, 3.0, { pid, _ -> if (pid == 4242) "com.example.shop" else "?" }).dump.toString()
        assertTrue("\"cls\":\"EditText\"" in d && "\"desc\":\"E-mail\"" in d, d)
        assertTrue(Regex("\"text\":\"Remember me\",\"flags\":\"ckKve\"").containsMatchIn(d), "a switch that is on: $d")
        assertTrue(Regex("\"rid\":\"login\",\"text\":\"Log in\",\"flags\":\"cv\"").containsMatchIn(d), "a disabled button has no e: $d")
        assertTrue("\"bounds\":[402,2370,804,2526]" in d, "points × 3: $d")
        assertTrue(AxeCli.typeable("kari@example.com") && !AxeCli.typeable("Blåbær"))
        assertEquals(null, AxeCli.locate(mapOf("TESTLAB_IOS_DRIVER" to "wda", "PATH" to "/usr/bin")))
    }
}
