package no.agens.testlab.device.hs

import no.agens.testlab.device.adb.AdbCli
import no.agens.testlab.device.adb.AdbException
import java.io.File
import java.security.MessageDigest

/** The pinned handsets daemon jar (v0.1.38, MIT) shipped inside the app. */
class BundledJar(val bytes: ByteArray) {
    val sha256: String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val sha8: String get() = sha256.take(8)

    /** adb push needs a file: materialize the jar once in [dir] under its checksum name. */
    fun materialize(dir: File): File {
        dir.mkdirs()
        val f = File(dir, "ntlab-$sha8.jar")
        if (!f.isFile || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
        return f
    }

    companion object {
        const val VERSION = "v0.1.38"
        const val SHA256 = "2e7eb11e6af2e5a3afac612113f30a7653bee48b2dc2d73551f52cd566f3c12e"

        fun load(): BundledJar {
            val bytes = BundledJar::class.java.getResourceAsStream("/handsets/hs.jar")?.use { it.readBytes() }
                ?: error("handsets/hs.jar is missing from the app resources")
            return BundledJar(bytes).also { check(it.sha256 == SHA256) { "bundled hs.jar checksum mismatch: ${it.sha256}" } }
        }
    }
}

/** A running daemon for one device, reachable on localhost:[hostPort]. */
data class HsEndpoint(val serial: String, val hostPort: Int)

class DaemonStartError(message: String) : RuntimeException(message)

/**
 * Android grants the accessibility (UiAutomation) connection to one process at a time. Another
 * automation tool on the phone (upstream `hs`, `uiautomator dump`, Appium, Maestro) blocks every
 * UI dump until it is stopped; `connect(takeOver = true)` stops the known ones.
 */
class AutomationBusy(val holders: List<String>) : RuntimeException(
    "Another automation tool holds the phone's accessibility connection" +
        (if (holders.isNotEmpty()) " (${holders.joinToString()})" else "") +
        ". Stop it, or connect with take-over to stop it for you.",
)

/**
 * Deploys and starts the handsets daemon. Differences from upstream `hs use`, on purpose:
 * - the process is named `ntlabd`, so upstream's `pkill -f hsd` and ours never kill each other;
 * - the jar on the phone is named after its checksum, which doubles as the version check
 *   (the protocol has no version handshake);
 * - the host port is whatever adb hands out (`forward tcp:0`), so several phones never clash.
 */
class HsDaemon(
    private val adb: AdbCli,
    private val jar: BundledJar,
    private val cacheDir: File,
    val devicePort: Int = DEVICE_PORT,
    private val log: (String) -> Unit = {},
) {
    private val remoteJar get() = "/data/local/tmp/ntlab-${jar.sha8}.jar"

    fun connect(serial: String, takeOver: Boolean = false): HsEndpoint {
        val dev = adb.devices().firstOrNull { it.serial == serial }
            ?: throw DaemonStartError("$serial is not connected (adb devices does not list it)")
        if (!dev.ready) throw DaemonStartError("$serial is ${dev.state}. ${dev.hint}")

        // Fast path: our daemon, this jar version, already forwarded and able to dump.
        val existing = adb.forwards().firstOrNull { it.serial == serial && it.remote == "tcp:$devicePort" }
        if (existing != null) {
            val port = existing.local.removePrefix("tcp:").toIntOrNull()
            val marker = adb.shell(serial, "cat $MARKER 2>/dev/null").stdout.trim()
            if (port != null && marker == jar.sha8 && ping(port, 1_000) && canDump(port)) {
                log("$serial: daemon already running on tcp:$port")
                return HsEndpoint(serial, port)
            }
            adb.removeForward(serial, existing.local)
        }

        // Slow path: stop any old daemon of ours, make sure this jar is on the phone, start it.
        adb.shell(serial, killCmd(PROCESS) + "; rm -f $MARKER")
        if (takeOver) stopOtherAutomation(serial)
        waitGone(serial)
        val present = adb.shell(serial, "ls $remoteJar 2>/dev/null").stdout.trim() == remoteJar
        if (!present) {
            adb.shell(serial, "rm -f /data/local/tmp/ntlab-*.jar")
            adb.push(serial, jar.materialize(cacheDir), remoteJar)
            log("$serial: pushed ${jar.sha8} daemon jar")
        }
        val start = "CLASSPATH=$remoteJar nohup app_process /system/bin --nice-name=$PROCESS " +
            "dev.handsets.daemon.Main --port=$devicePort > $DEVICE_LOG 2>&1 &"
        adb.shell(serial, start)
        adb.shell(serial, "echo ${jar.sha8} > $MARKER")
        val port = adb.forwardAnyPort(serial, devicePort)
        val deadline = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < deadline) {
            if (ping(port, 300)) {
                // The daemon answers ping even without the accessibility connection; a dump proves it works.
                if (!canDump(port)) {
                    val holders = automationHolders(serial)
                    release(serial)
                    throw AutomationBusy(holders)
                }
                log("$serial: daemon started, forwarded to tcp:$port")
                return HsEndpoint(serial, port)
            }
            Thread.sleep(40)
        }
        val tail = adb.shell(serial, "tail -n 20 $DEVICE_LOG").stdout.trim()
        throw DaemonStartError("the daemon did not answer within 6 s on $serial. Daemon log:\n$tail")
    }

    /** Stops our daemon and removes its forward (the daemon listens unauthenticated on the phone). */
    fun release(serial: String) {
        runCatching {
            adb.forwards().filter { it.serial == serial && it.remote == "tcp:$devicePort" }.forEach { adb.removeForward(serial, it.local) }
        }
        runCatching { adb.shell(serial, killCmd(PROCESS) + "; rm -f $MARKER") }
    }

    /** Whether upstream `hs` holds the device's single accessibility (UiAutomation) slot. */
    fun upstreamDaemonRunning(serial: String): Boolean =
        adb.shell(serial, "pidof hsd").stdout.trim().isNotEmpty()

    /** Known processes that take the UiAutomation connection, as "name (pid)". */
    fun automationHolders(serial: String): List<String> = OTHER_AUTOMATION.mapNotNull { (name, process) ->
        adb.shell(serial, "pidof $process").stdout.trim().takeIf { it.isNotEmpty() }?.let { "$name (pid $it)" }
    }

    private fun stopOtherAutomation(serial: String) {
        for ((name, process) in OTHER_AUTOMATION) {
            if (adb.shell(serial, "pidof $process").stdout.trim().isNotEmpty()) {
                adb.shell(serial, killCmd(process))
                log("$serial: stopped $name to take over the accessibility connection")
            }
        }
        Thread.sleep(300)
    }

    /**
     * Kill by PID from `pidof` (argv[0]): toybox `pkill NAME` compares another field and misses the
     * renamed daemon, and `pkill -f` would also match the `sh -c` running this very command.
     */
    private fun killCmd(process: String) = "p=\$(pidof $process); [ -n \"\$p\" ] && kill -9 \$p"

    private fun canDump(port: Int): Boolean = try {
        HsConnection("127.0.0.1", port, connectTimeoutMs = 1_000, readTimeoutMs = 10_000).use { it.call("dump_active"); true }
    } catch (e: HsError) {
        !e.detail.contains("UiAutomation not connected")
    } catch (e: Exception) {
        false
    }

    private fun waitGone(serial: String) {
        repeat(30) {
            if (adb.shell(serial, "pidof $PROCESS").stdout.trim().isEmpty()) return
            Thread.sleep(100)
        }
        throw AdbException("old $PROCESS process on $serial did not exit")
    }

    private fun ping(port: Int, timeoutMs: Int): Boolean = try {
        HsConnection("127.0.0.1", port, connectTimeoutMs = timeoutMs, readTimeoutMs = timeoutMs).use { it.callText("ping") == "pong" }
    } catch (e: Exception) {
        false
    }

    companion object {
        const val PROCESS = "ntlabd"
        const val DEVICE_PORT = 27183
        const val MARKER = "/data/local/tmp/ntlab.sha"
        const val DEVICE_LOG = "/data/local/tmp/ntlab.log"

        /** Display name to process name, for tools that hold UiAutomation while they run. */
        val OTHER_AUTOMATION = listOf(
            "upstream hs daemon" to "hsd",
            "uiautomator" to "uiautomator",
            "Appium UiAutomator2" to "io.appium.uiautomator2.server",
            "Maestro" to "dev.mobile.maestro",
        )
    }
}
