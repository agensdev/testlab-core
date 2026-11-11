package no.agens.testlab.device.hs

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HsDeviceTest {
    private val dump = """{"ts":1,"root":{"cls":"android.widget.FrameLayout","bounds":[0,0,1080,2400],"children":[
        {"cls":"android.view.View","rid":"no.x:id/fragment_games","flags":"cfev","bounds":[300,2850,550,2940],
         "children":[{"cls":"android.widget.TextView","text":"Spill","bounds":[380,2900,470,2930]}]}]}}"""

    private fun daemon(extra: (String) -> ByteArray? = { null }) = FakeHsDaemon { cmd ->
        extra(cmd) ?: when {
            cmd == "dump_active" -> dump.encodeToByteArray()
            cmd == "state top" -> "no.x/.MainActivity".encodeToByteArray()
            cmd == "info" -> "1080 2400".encodeToByteArray()
            cmd.startsWith("screenshot") -> byteArrayOf(-1, -40, -1, -32) // JPEG magic
            cmd.startsWith("tap x=5000") -> "ERR:OFF_SCREEN:x=5000 y=1 display=1080x2400".encodeToByteArray()
            else -> "ok".encodeToByteArray()
        }
    }

    @Test
    fun framingRoundTripAndCommandCap() {
        val out = ByteArrayOutputStream()
        HsWire.writeCommand(out, "tap x=1 y=2")
        val bytes = out.toByteArray()
        assertContentEquals(byteArrayOf(0, 0, 0, 11), bytes.copyOfRange(0, 4))
        assertEquals("tap x=1 y=2", HsWire.readFrame(ByteArrayInputStream(bytes)).decodeToString())
        assertFailsWith<IllegalArgumentException> { HsWire.writeCommand(ByteArrayOutputStream(), "text " + "x".repeat(300)) }
        val err = assertFailsWith<HsError> { HsWire.checkError("ERR:secure-window:com.bank/.Login".encodeToByteArray()) }
        assertEquals("secure-window", err.tag)
        assertEquals("com.bank/.Login", err.detail)
    }

    @Test
    fun runnerMapsHsCommandsToWireVerbs() = runTest {
        daemon().use { d ->
            val dev = HsDevice("emu") { HsEndpoint("emu", d.port) }
            val hs = dev.asHsRunner()
            val tree = hs(listOf("ui", "--tree"))
            assertTrue(tree.startsWith("FrameLayout  @(540,1200)"), tree)
            assertTrue(tree.contains("View #fragment_games  [click,focusable]  @(425,2895)"), tree)
            assertEquals("no.x/.MainActivity", hs(listOf("show", "top")))
            hs(listOf("tap", "425", "2895"))
            hs(listOf("swipe", "up"))
            hs(listOf("go", "back"))
            hs(listOf("open", "no.x"))
            hs(listOf("tap", "#fragment_games")) // by id: clicked by id, as upstream `hs tap #id`
            val shot = File.createTempFile("shot", ".jpg").apply { deleteOnExit() }
            hs(listOf("see", shot.path, "--size", "1080"))
            assertEquals(4, shot.length())
            dev.close()
            val cmds = d.commands.filter { it != "dump_active" && it != "state top" }
            assertEquals(
                listOf("tap x=425 y=2895", "swipe_dir up", "key BACK", "am_start n=no.x", "node_click id=no.x:id/fragment_games", "screenshot size=1080 q=80 fmt=jpeg"),
                cmds,
            )
        }
    }

    @Test
    fun daemonErrorsSurfaceTyped() = runTest {
        daemon().use { d ->
            val dev = HsDevice("emu") { HsEndpoint("emu", d.port) }
            val e = assertFailsWith<HsError> { dev.tap(5000, 1) }
            assertEquals("OFF_SCREEN", e.tag)
            dev.close()
        }
    }

    /** A daemon whose first command dies with the connection, like a daemon restarted mid-call. */
    private fun flakyDaemon(): FakeHsDaemon {
        var dropped = false
        return FakeHsDaemon { cmd ->
            if (!dropped) { dropped = true; null }
            else if (cmd == "dump_active") dump.encodeToByteArray() else "ok".encodeToByteArray()
        }
    }

    @Test
    fun readsReconnectOnce() = runTest {
        flakyDaemon().use { d ->
            var connects = 0
            val dev = HsDevice("emu") { connects++; HsEndpoint("emu", d.port) }
            assertTrue(dev.tree().startsWith("FrameLayout")) // dropped once, retried on a fresh connection
            assertEquals(2, connects)
            dev.close()
        }
    }

    @Test
    fun actionsAreNeverResent() = runTest {
        flakyDaemon().use { d ->
            val dev = HsDevice("emu") { HsEndpoint("emu", d.port) }
            assertFailsWith<HsDisconnected> { dev.tap(1, 1) } // it may have been delivered: do not tap twice
            assertEquals(listOf("tap x=1 y=1"), d.commands)
            dev.close()
        }
    }

    @Test
    fun typeChunks() {
        // Any non-keymap character: the whole text is pasted, edge spaces typed as SPACE keys.
        assertEquals(
            listOf(TypeChunks.Chunk.Spaces(1), TypeChunks.Chunk.Paste("Blåbær 1"), TypeChunks.Chunk.Spaces(1)),
            TypeChunks.split(" Blåbær 1 "),
        )
        assertEquals(listOf(TypeChunks.Chunk.Keys("plain ascii"), TypeChunks.Chunk.Spaces(2)), TypeChunks.split("plain ascii  "))
        // long ASCII is cut under the command cap; no chunk may end with a space
        val long = TypeChunks.split("a b ".repeat(200))
        assertTrue(long.all { it !is TypeChunks.Chunk.Keys || (it.text.length <= TypeChunks.MAX_BYTES && !it.text.endsWith(' ')) })
        val typed = long.joinToString("") { when (it) { is TypeChunks.Chunk.Keys -> it.text; is TypeChunks.Chunk.Paste -> it.text; is TypeChunks.Chunk.Spaces -> " ".repeat(it.count) } }
        assertEquals("a b ".repeat(200), typed)
        // emoji (surrogate pairs) are never split
        assertEquals(listOf(TypeChunks.Chunk.Paste("😀😀")), TypeChunks.split("😀😀"))
    }
}
