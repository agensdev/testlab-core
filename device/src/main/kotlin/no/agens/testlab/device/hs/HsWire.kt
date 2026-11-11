package no.agens.testlab.device.hs

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A failure reported by the on-device daemon (`ERR:<tag>[:detail]`), e.g. `OFF_SCREEN`,
 * `secure-window`, `BAD_ARG`. [tag] is the first field after `ERR:`.
 */
class HsError(val tag: String, val detail: String, val raw: String) : RuntimeException("daemon error $raw")

/** Transport problems: the daemon is gone or the forward broke. */
class HsDisconnected(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The handsets wire format (docs/wire.md, v0.1.38): every frame is a big-endian uint32 length
 * plus payload, both directions. Commands are `verb [k=v …]` text of at most 256 bytes;
 * streamed replies are a run of frames ended by a zero-length frame.
 */
object HsWire {
    const val MAX_CMD = 256
    private const val MAX_REPLY = 64 * 1024 * 1024

    fun writeCommand(out: OutputStream, cmd: String) {
        val bytes = cmd.encodeToByteArray()
        require(bytes.isNotEmpty() && bytes.size <= MAX_CMD) {
            "daemon commands are limited to $MAX_CMD bytes (got ${bytes.size}): ${cmd.take(60)}"
        }
        val d = DataOutputStream(out)
        d.writeInt(bytes.size)
        d.write(bytes)
        d.flush()
    }

    fun writeFrame(out: OutputStream, payload: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(payload.size)
        d.write(payload)
        d.flush()
    }

    /** Reads one frame; an empty array is the end-of-stream marker of streamed replies. */
    fun readFrame(input: InputStream): ByteArray {
        val d = DataInputStream(input)
        val len = try { d.readInt() } catch (e: EOFException) { throw HsDisconnected("daemon closed the connection", e) }
        if (len < 0 || len > MAX_REPLY) throw HsDisconnected("bad frame length $len")
        val buf = ByteArray(len)
        d.readFully(buf)
        return buf
    }

    /** Throws [HsError] when the payload is an `ERR:` reply. */
    fun checkError(payload: ByteArray): ByteArray {
        if (payload.size >= 4 && payload[0] == 'E'.code.toByte() && payload[1] == 'R'.code.toByte() &&
            payload[2] == 'R'.code.toByte() && payload[3] == ':'.code.toByte()
        ) {
            val raw = payload.decodeToString()
            val body = raw.removePrefix("ERR:")
            throw HsError(body.substringBefore(':'), body.substringAfter(':', ""), raw)
        }
        return payload
    }
}

/** One TCP connection to the daemon (through an adb forward). Not thread-safe; callers serialize. */
class HsConnection(host: String, port: Int, connectTimeoutMs: Int = 2_000, readTimeoutMs: Int = 30_000) : Closeable {
    private val socket = Socket().apply {
        tcpNoDelay = true
        soTimeout = readTimeoutMs
        try {
            connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: Exception) {
            close()
            throw HsDisconnected("no daemon on $host:$port (${e.message})", e)
        }
    }
    private val input = socket.getInputStream().buffered()
    private val output = socket.getOutputStream()

    /** Sends one command and returns its single-frame reply (throws [HsError] on `ERR:`). */
    fun call(cmd: String, timeoutMs: Int? = null): ByteArray {
        val previous = socket.soTimeout
        if (timeoutMs != null) socket.soTimeout = timeoutMs
        try {
            HsWire.writeCommand(output, cmd)
            return HsWire.checkError(HsWire.readFrame(input))
        } catch (e: java.net.SocketTimeoutException) {
            close()
            throw HsDisconnected("daemon did not answer '${cmd.substringBefore(' ')}' in time", e)
        } catch (e: java.io.IOException) {
            close()
            throw HsDisconnected("connection to the daemon broke: ${e.message}", e)
        } finally {
            if (timeoutMs != null && !socket.isClosed) socket.soTimeout = previous
        }
    }

    fun callText(cmd: String, timeoutMs: Int? = null): String = call(cmd, timeoutMs).decodeToString()

    /**
     * For chunked replies that end with a zero-length frame and leave the connection usable
     * (`dumpsys`, `shell`, `pull`): returns the concatenated chunks.
     */
    fun collect(cmd: String, timeoutMs: Int = 15_000): ByteArray {
        val previous = socket.soTimeout
        socket.soTimeout = timeoutMs
        try {
            HsWire.writeCommand(output, cmd)
            val buf = java.io.ByteArrayOutputStream()
            while (true) {
                val f = HsWire.readFrame(input)
                if (f.isEmpty()) return buf.toByteArray()
                HsWire.checkError(f)
                buf.write(f)
            }
        } catch (e: java.io.IOException) {
            close()
            throw HsDisconnected("connection to the daemon broke during '${cmd.substringBefore(' ')}': ${e.message}", e)
        } finally {
            if (!socket.isClosed) socket.soTimeout = previous
        }
    }

    /** For a streaming verb: sends [cmd] and hands every frame to [onFrame] until the end marker or false. */
    fun stream(cmd: String, onFrame: (ByteArray) -> Boolean) {
        socket.soTimeout = 0
        HsWire.writeCommand(output, cmd)
        while (true) {
            val f = HsWire.readFrame(input)
            if (f.isEmpty()) return
            HsWire.checkError(f)
            if (!onFrame(f)) return
        }
    }

    val isOpen: Boolean get() = !socket.isClosed

    override fun close() {
        runCatching { socket.close() }
    }
}
