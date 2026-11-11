package no.agens.testlab.device.hs

import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A TCP stand-in for the on-device daemon: speaks the real framing, records every command and
 * answers through [reply] (return null to drop the connection, simulating a dead daemon). A
 * reply of several frames (streamed verbs such as `dumpsys`) ends with the empty frame itself.
 */
class FakeHsDaemon private constructor(private val frames: (String) -> List<ByteArray>?, @Suppress("UNUSED_PARAMETER") multi: Boolean) : AutoCloseable {
    /** Single-frame replies, the common case. */
    constructor(reply: (String) -> ByteArray?) : this({ cmd: String -> reply(cmd)?.let { listOf(it) } }, true)

    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())

    init {
        thread(isDaemon = true, name = "fake-hsd") {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) {
        s.use {
            val input = s.getInputStream().buffered()
            val output = s.getOutputStream()
            while (true) {
                val cmd = try { HsWire.readFrame(input).decodeToString() } catch (e: Exception) { return }
                commands += cmd
                val r = frames(cmd) ?: return
                for (f in r) HsWire.writeFrame(output, f)
            }
        }
    }

    override fun close() = server.close()

    companion object {
        /** A daemon whose replies may be several frames (streamed verbs). */
        fun multiFrame(frames: (String) -> List<ByteArray>?) = FakeHsDaemon(frames, true)

        /** A streamed reply: the payload and the end-of-stream marker. */
        fun streamed(payload: ByteArray): List<ByteArray> = listOf(payload, ByteArray(0))
    }
}
