package no.agens.testlab.device.adb

import java.io.File
import java.util.concurrent.TimeUnit

class AdbException(message: String) : RuntimeException(message)

/** One line of `adb devices -l`. */
/** A device of either platform; the name stays from when there was only adb. */
typealias LabDevice = AdbDevice

data class AdbDevice(
    val serial: String,
    /** device | unauthorized | offline | no permissions | recovery | sideload | … */
    val state: String,
    val model: String? = null,
    val product: String? = null,
    val device: String? = null,
    val transportId: String? = null,
    val detail: String? = null,
    /** Android phones and emulators come from adb; iOS Simulators from `xcrun simctl` (serial = UDID). */
    val platform: no.agens.testlab.core.Platform = no.agens.testlab.core.Platform.ANDROID,
    /** The OS version where known ("26.5"). */
    val os: String? = null,
    /** A real iOS device: `iphone`, `ipad` or `mac` (this Mac, running iOS apps). Null: a simulator, or Android. */
    val kind: String? = null,
) {
    val ready: Boolean get() = state == "device"
    val isEmulator: Boolean get() = platform == no.agens.testlab.core.Platform.IOS && kind == null || serial.startsWith("emulator-")

    /** Takes installs and app starts, but its screen can't be read or tapped (a real iPhone, this Mac). */
    val installOnly: Boolean get() = state == INSTALL_ONLY

    /** How it is reached: usb, wifi (Android 11+ wireless debugging, an mDNS name), ip (`adb connect host:port`), emulator, or simulator (iOS). */
    val transport: String get() = when {
        kind != null -> kind
        platform == no.agens.testlab.core.Platform.IOS -> "simulator"
        isEmulator -> "emulator"
        "._adb-tls-connect._tcp" in serial || serial.startsWith("adb-") && "._tcp" in serial -> "wifi"
        IP_SERIAL.matches(serial) -> "ip"
        else -> "usb"
    }

    companion object {
        /** The state of a real iOS device that is there and paired: see [installOnly]. */
        const val INSTALL_ONLY = "install-only"

        /** host:port or [v6]:port, which is how adb names a device it reached over the network. */
        val IP_SERIAL = Regex("^(\\[[0-9a-fA-F:]+]|[A-Za-z0-9.-]+):\\d{1,5}$")
    }

    /** Plain-language next step for a device that is not usable yet. */
    val hint: String?
        get() = when {
            state == "device" -> null
            state == INSTALL_ONLY && kind == "mac" -> "${model ?: serial} takes builds and app starts (handsets_install_branch_build, handsets_open_app, handsets_login) but its screen can't be read or tapped: use a simulator for that."
            state == INSTALL_ONLY -> "${model ?: serial} takes builds and app starts (handsets_install_branch_build, handsets_open_app, handsets_login). To read and tap its screen too, set the Apple team that signs WebDriverAgent (the setup page, `${no.agens.testlab.AppIdentity.SLUG} open setup`, or `${no.agens.testlab.AppIdentity.SLUG} setup --team <TEAM ID>`)."
            platform == no.agens.testlab.core.Platform.IOS && kind != null -> "${model ?: serial}: $detail"
            platform == no.agens.testlab.core.Platform.IOS -> "The simulator is ${state}: start it with handsets_simulator_start or in Devices."
            else -> adbHint
        }

    private val adbHint: String?
        get() = when (state) {
            "device" -> null
            "unauthorized" -> "Unlock the phone and accept the \"Allow USB debugging\" prompt."
            "offline" -> if (transport == "usb") "The phone is offline to adb: unplug and reconnect the cable." else "The phone is offline to adb: check it is on the same network with wireless debugging on, then connect again."
            "no permissions" -> "adb may not open the USB device: install the Android udev rules (Linux) and reconnect."
            else -> "adb reports \"$state\"."
        }
}

/**
 * Runs the adb binary. Output goes to temp files, never pipes: the first adb call forks the
 * adb server, which inherits the descriptors and would keep a pipe reader waiting forever
 *. [startServer] starts the server once with its own log for the same reason.
 */
class AdbCli(
    val adb: File,
    private val serverLog: File? = null,
    /** Replaces the adb process (tests): gets the arguments after `adb`, `-s SERIAL` included. */
    private val exec: ((List<String>) -> Result)? = null,
) {
    data class Result(val exit: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exit == 0
    }

    /**
     * Called with the serial before every command for one phone: the runtime holds the phone for its
     * space there (or throws when another space holds it).
     */
    @Volatile var guard: ((String) -> Unit)? = null

    fun run(args: List<String>, serial: String? = null, timeoutMs: Long = 30_000): Result {
        if (serial != null) guard?.invoke(serial)
        val cmd = buildList {
            add(adb.path)
            if (serial != null) { add("-s"); add(serial) }
            addAll(args)
        }
        exec?.let { return it(cmd.drop(1)) }
        val out = File.createTempFile("tl-adb-", ".out")
        val err = File.createTempFile("tl-adb-", ".err")
        try {
            val p = ProcessBuilder(cmd).redirectOutput(out).redirectError(err).start()
            p.outputStream.close() // EOF on stdin: `adb shell` must not wait for input
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                throw AdbException("adb ${args.joinToString(" ")} timed out after ${timeoutMs / 1000}s")
            }
            return Result(p.exitValue(), out.readText(), err.readText())
        } finally {
            out.delete()
            err.delete()
        }
    }

    fun checked(args: List<String>, serial: String? = null, timeoutMs: Long = 30_000): String {
        val r = run(args, serial, timeoutMs)
        if (!r.ok) throw AdbException("adb ${args.joinToString(" ")} failed (${r.exit}): ${(r.stderr.ifBlank { r.stdout }).trim().take(300)}")
        return r.stdout
    }

    fun startServer() {
        if (exec != null) return
        val log = serverLog ?: File(System.getProperty("java.io.tmpdir"), "tl-adb-server.log")
        log.parentFile?.mkdirs()
        val p = ProcessBuilder(adb.path, "start-server")
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .redirectError(ProcessBuilder.Redirect.appendTo(log))
            .start()
        p.outputStream.close()
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw AdbException("adb start-server timed out (see $log)")
        }
    }

    fun devices(): List<AdbDevice> = parseDevices(checked(listOf("devices", "-l"), timeoutMs = 15_000))

    fun shell(serial: String, command: String, timeoutMs: Long = 30_000): Result = run(listOf("shell", command), serial, timeoutMs)

    /** `adb shell COMMAND` with [input] on its stdin (e.g. `hid -`, which reads a command stream). */
    fun shellWithInput(serial: String, command: String, input: String, timeoutMs: Long = 30_000): Result {
        guard?.invoke(serial)
        val cmd = listOf(adb.path, "-s", serial, "shell", command)
        exec?.let { return it(cmd.drop(1) + listOf("<<<", input)) }
        val out = File.createTempFile("tl-adb-", ".out")
        val err = File.createTempFile("tl-adb-", ".err")
        try {
            val p = ProcessBuilder(cmd).redirectOutput(out).redirectError(err).start()
            p.outputStream.use { it.write(input.toByteArray()) }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                throw AdbException("adb shell $command timed out after ${timeoutMs / 1000}s")
            }
            return Result(p.exitValue(), out.readText(), err.readText())
        } finally {
            out.delete()
            err.delete()
        }
    }

    fun push(serial: String, local: File, remote: String) {
        checked(listOf("push", local.path, remote), serial, timeoutMs = 120_000)
    }

    /** `adb forward tcp:0 tcp:P`: adb picks a free host port and prints it. */
    fun forwardAnyPort(serial: String, devicePort: Int): Int {
        val out = checked(listOf("forward", "tcp:0", "tcp:$devicePort"), serial).trim()
        return out.lines().last().trim().toIntOrNull() ?: throw AdbException("adb forward printed '$out', not a port")
    }

    data class Forward(val serial: String, val local: String, val remote: String)

    fun forwards(): List<Forward> = checked(listOf("forward", "--list")).lines().mapNotNull { line ->
        val p = line.trim().split(Regex("\\s+"))
        if (p.size >= 3) Forward(p[0], p[1], p[2]) else null
    }

    fun removeForward(serial: String, local: String) {
        run(listOf("forward", "--remove", local), serial)
    }

    /**
     * `adb connect host[:port]`: a phone on the network (wireless debugging, or `adb tcpip 5555`).
     * Returns adb's own words; throws when it could not connect.
     */
    fun connect(address: String): String {
        val a = address.trim()
        require(a.isNotEmpty() && ' ' !in a) { "give host:port, e.g. 10.0.0.23:5555" }
        val r = run(listOf("connect", a), timeoutMs = 20_000)
        val out = (r.stdout + r.stderr).trim()
        if (!r.ok || out.contains("failed", ignoreCase = true) || out.contains("cannot", ignoreCase = true) || out.contains("refused", ignoreCase = true)) {
            throw AdbException("adb connect $a: ${out.ifEmpty { "failed" }} (wireless debugging on, same network, and the port from the phone's Wireless debugging screen?)")
        }
        return out
    }

    /** `adb pair host:port code`: the one-time pairing of Android 11+ wireless debugging ("Pair device with pairing code"). */
    fun pair(address: String, code: String): String {
        require(code.all { it.isDigit() } && code.length in 4..10) { "the pairing code is the 6 digits the phone shows" }
        val r = run(listOf("pair", address.trim(), code), timeoutMs = 30_000)
        val out = (r.stdout + r.stderr).trim()
        if (!r.ok || "Successfully paired" !in out) throw AdbException("adb pair ${address.trim()}: ${out.ifEmpty { "failed" }}")
        return out
    }

    fun disconnect(address: String): String = run(listOf("disconnect", address.trim()), timeoutMs = 10_000).let { (it.stdout + it.stderr).trim() }

    /** Phones advertising wireless debugging on this network (`adb mdns services`): name, kind (pairing or connect) and address. */
    fun mdnsServices(): List<Triple<String, String, String>> = run(listOf("mdns", "services"), timeoutMs = 10_000).stdout.lines().mapNotNull { l ->
        val p = l.trim().split(Regex("\\s+"))
        if (p.size >= 3 && p[1].startsWith("_adb-tls-")) Triple(p[0], if ("pairing" in p[1]) "pairing" else "connect", p[2]) else null
    }

    fun install(serial: String, apk: File, timeoutMs: Long = 600_000): Result =
        run(listOf("install", "-r", "-d", apk.path), serial, timeoutMs)

    companion object {
        fun parseDevices(text: String): List<AdbDevice> = text.lines().drop(1).mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("*")) return@mapNotNull null
            val serial = line.substringBefore(' ').substringBefore('\t')
            var rest = line.substring(serial.length).trim()
            val state: String
            var detail: String? = null
            if (rest.startsWith("no permissions")) {
                state = "no permissions"
                val close = rest.indexOf(';')
                detail = rest.removePrefix("no permissions").trim()
                rest = if (close >= 0) rest.substring(close + 1) else ""
            } else {
                state = rest.substringBefore(' ')
                rest = rest.substring(state.length)
            }
            val kv = rest.trim().split(Regex("\\s+")).mapNotNull { t ->
                val i = t.indexOf(':')
                if (i > 0) t.substring(0, i) to t.substring(i + 1) else null
            }.toMap()
            AdbDevice(serial, state, kv["model"], kv["product"], kv["device"], kv["transport_id"], detail)
        }
    }
}
