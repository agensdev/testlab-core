package no.agens.testlab.services.proxyman

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Mac's system proxy for simulators, against a fake networksetup holding two services' settings. */
class MacProxyTest {
    private val tmp = Files.createTempDirectory("tl-macproxy").toFile()
    private val file = File(tmp, "mac-proxy.json")

    /** service → (web, secure) as networksetup would print them. */
    private val state = linkedMapOf(
        "Wi-Fi" to arrayOf(MacProxy.Setting(true, "corp-proxy.example", 3128), MacProxy.Setting(false)),
        "Thunderbolt Bridge" to arrayOf(MacProxy.Setting(false), MacProxy.Setting(false)),
    )
    private val calls = mutableListOf<String>()

    private val exec: (List<String>) -> Pair<Int, String> = { a ->
        calls += a.joinToString(" ")
        fun show(s: MacProxy.Setting) = "Enabled: ${if (s.enabled) "Yes" else "No"}\nServer: ${s.server}\nPort: ${s.port}\nAuthenticated Proxy Enabled: 0\n"
        val i = { secure: Boolean -> if (secure) 1 else 0 }
        when (a[0]) {
            "-listallnetworkservices" -> 0 to "An asterisk (*) denotes that a network service is disabled.\nWi-Fi\nThunderbolt Bridge\n*iPhone USB\n"
            "-getwebproxy", "-getsecurewebproxy" -> 0 to show(state.getValue(a[1])[i(a[0] == "-getsecurewebproxy")])
            "-setwebproxy", "-setsecurewebproxy" -> { state.getValue(a[1])[i(a[0] == "-setsecurewebproxy")] = MacProxy.Setting(true, a[2], a[3].toInt()); 0 to "" }
            "-setwebproxystate", "-setsecurewebproxystate" -> {
                val k = i(a[0] == "-setsecurewebproxystate"); val cur = state.getValue(a[1])[k]
                state.getValue(a[1])[k] = cur.copy(enabled = a[2] == "on"); 0 to ""
            }
            else -> 1 to "unknown"
        }
    }
    private val mac = MacProxy(file, exec)

    @AfterTest
    fun tearDown() { tmp.deleteRecursively() }

    @Test
    fun routingSavesTheMacsOwnSettingsAndTheLastSimulatorPutsThemBack() {
        assertEquals("corp-proxy.example:3128", mac.current())
        mac.route("SIM-1", "192.0.2.10:9090")
        mac.route("SIM-2", "192.0.2.10:9090")
        assertEquals("192.0.2.10:9090", mac.current())
        assertTrue(state.values.all { it[0] == MacProxy.Setting(true, "192.0.2.10", 9090) && it[1] == MacProxy.Setting(true, "192.0.2.10", 9090) })
        assertFalse(calls.any { "iPhone USB" in it }, "a disabled service is left alone")
        val before = mac.record()!!.before
        assertEquals(MacProxy.Setting(true, "corp-proxy.example", 3128), before.getValue("Wi-Fi").web, "the second simulator keeps the first's saved settings")

        assertFalse(mac.unroute("SIM-1"), "SIM-2 is still routed")
        assertEquals("192.0.2.10:9090", mac.current())
        assertTrue(mac.unroute("SIM-2"))
        assertEquals("corp-proxy.example:3128", mac.current(), "the Mac's own proxy is back")
        assertFalse(state.getValue("Wi-Fi")[1].enabled, "the secure proxy that was off is off again")
        assertFalse(state.getValue("Thunderbolt Bridge")[0].enabled)
        assertFalse(file.exists())
    }

    @Test
    fun aCrashedRunIsUndoneAtTheNextStart() {
        mac.route("SIM-1", "127.0.0.1:9090")
        assertTrue(MacProxy(file, exec).restoreAll(), "a new process finds the record")
        assertEquals("corp-proxy.example:3128", mac.current())
        assertNull(mac.record())
    }
}
