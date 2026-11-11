package no.agens.testlab.device.ios

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.core.Platform
import no.agens.testlab.device.adb.LabDevice
import java.io.File
import java.util.concurrent.TimeUnit

class XcrunException(message: String) : RuntimeException(message)

/** An installed simulator runtime («iOS 26.5»). */
data class SimRuntime(val identifier: String, val name: String, val version: String, val available: Boolean)

/** A kind of simulated device: [identifier] for `simctl create`, [family] iPhone, iPad… */
data class DeviceType(val identifier: String, val name: String, val family: String)

/** A simulator `simctl list` knows: [runtime] is the OS («iOS 26.5»), [state] Booted, Shutdown… */
data class Simulator(val udid: String, val name: String, val state: String, val runtime: String, val available: Boolean) {
    val booted: Boolean get() = state == "Booted"
    val os: String? get() = runtime.substringAfter(' ', "").ifEmpty { null }
    val isPhone: Boolean get() = name.startsWith("iPhone")

    fun toDevice() = LabDevice(udid, if (booted) "device" else state.lowercase(), model = name, platform = Platform.IOS, os = os)
}

/**
 * `xcrun simctl` (Xcode's simulator control). Output goes through files, as with adb, so a child
 * that outlives the call (the Simulator's helpers) can't hold a pipe open. [exec] replaces the
 * process in tests; it gets the arguments after `xcrun` and the stdin, if any.
 */
class XcrunCli(
    private val xcrun: String = "/usr/bin/xcrun",
    private val exec: ((List<String>, ByteArray?) -> Result)? = null,
) {
    data class Result(val exit: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exit == 0
    }

    /** Runs before every command for a simulator: the space's lease on it (see the runtime's DeviceLeases). */
    @Volatile var guard: ((String) -> Unit)? = null

    fun run(args: List<String>, timeoutMs: Long = 60_000, stdin: ByteArray? = null): Result {
        exec?.let { return it(args, stdin) }
        val out = File.createTempFile("xcrun", ".out"); val err = File.createTempFile("xcrun", ".err")
        try {
            val pb = ProcessBuilder(listOf(xcrun) + args).redirectOutput(out).redirectError(err)
            if (stdin == null) pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            val p = pb.start()
            stdin?.let { p.outputStream.use { o -> o.write(it) } }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                throw XcrunException("xcrun ${args.take(3).joinToString(" ")} did not finish in ${timeoutMs / 1000}s")
            }
            return Result(p.exitValue(), out.readText(), err.readText())
        } finally { out.delete(); err.delete() }
    }

    /** `simctl` for one simulator: the lease is checked first. */
    fun sim(udid: String, vararg args: String, timeoutMs: Long = 60_000, stdin: ByteArray? = null): Result {
        guard?.invoke(udid)
        return run(listOf("simctl") + args.toList(), timeoutMs, stdin)
    }

    private fun checked(r: Result, what: String): Result {
        if (!r.ok) throw XcrunException("$what failed: ${(r.stderr.ifBlank { r.stdout }).trim().lineSequence().firstOrNull().orEmpty().take(300)}")
        return r
    }

    /** Whether Xcode's command-line tools answer at all (false off macOS, or without Xcode). */
    fun available(): Boolean = runCatching { run(listOf("simctl", "help"), 15_000).ok }.getOrDefault(false)

    fun simulators(): List<Simulator> = parseList(checked(run(listOf("simctl", "list", "devices", "available", "-j")), "simctl list").stdout)

    fun boot(udid: String) {
        val r = sim(udid, "boot", udid)
        // Booting a booted one is fine.
        if (!r.ok && "current state: Booted" !in r.stderr) checked(r, "simctl boot")
        checked(sim(udid, "bootstatus", udid, "-b", timeoutMs = 300_000), "simctl bootstatus")
    }

    fun shutdown(udid: String) { sim(udid, "shutdown", udid) }

    /** The installed iOS simulator runtimes, newest first. */
    fun runtimes(): List<SimRuntime> = parseRuntimes(checked(run(listOf("simctl", "list", "runtimes", "-j")), "simctl list runtimes").stdout)

    /** The kinds of simulated device («iPhone 17», «iPad Air 13-inch (M3)»). */
    fun deviceTypes(): List<DeviceType> = parseDeviceTypes(checked(run(listOf("simctl", "list", "devicetypes", "-j")), "simctl list devicetypes").stdout)

    /** Makes a simulator; returns its UDID. */
    fun create(name: String, deviceType: String, runtime: String): String =
        checked(run(listOf("simctl", "create", name, deviceType, runtime), 120_000), "simctl create").stdout.trim().lines().last().trim()

    /** Downloads and installs the newest iOS simulator runtime (several GB; `xcodebuild -downloadPlatform iOS`). */
    fun downloadIosRuntime() { checked(run(listOf("xcodebuild", "-downloadPlatform", "iOS"), 3_600_000), "xcodebuild -downloadPlatform iOS") }

    fun install(udid: String, app: File) { checked(sim(udid, "install", udid, app.path, timeoutMs = 300_000), "simctl install ${app.name}") }
    fun uninstall(udid: String, bundleId: String) { checked(sim(udid, "uninstall", udid, bundleId, timeoutMs = 120_000), "simctl uninstall $bundleId") }

    /** Starts [bundleId] (restarting it when it runs), with launch arguments for the app. */
    fun launch(udid: String, bundleId: String, args: List<String> = emptyList()) {
        checked(sim(udid, *(listOf("launch", "--terminate-running-process", udid, bundleId) + args).toTypedArray()), "simctl launch $bundleId")
    }

    fun terminate(udid: String, bundleId: String) { sim(udid, "terminate", udid, bundleId) }
    fun openUrl(udid: String, url: String) { checked(sim(udid, "openurl", udid, url), "simctl openurl") }

    /** The app's data container, or null when [bundleId] is not installed. */
    fun dataContainer(udid: String, bundleId: String): File? = sim(udid, "get_app_container", udid, bundleId, "data").takeIf { it.ok }?.stdout?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
    fun isInstalled(udid: String, bundleId: String): Boolean = sim(udid, "get_app_container", udid, bundleId, "app").ok

    /** Installed apps' bundle ids, user apps first (`simctl listapps` is an old-style plist: plutil turns it into JSON). */
    fun bundleIds(udid: String): List<String> {
        val plist = checked(sim(udid, "listapps", udid), "simctl listapps").stdout
        val json = checked(run(listOf("plutil", "-convert", "json", "-o", "-", "-"), stdin = plist.toByteArray()).let { if (exec == null && !it.ok) plutil(plist) else it }, "plutil").stdout
        return parseApps(json)
    }

    /** plutil is /usr/bin/plutil, not an xcrun tool: called directly when xcrun can't find it. */
    private fun plutil(plist: String): Result {
        val p = ProcessBuilder("/usr/bin/plutil", "-convert", "json", "-o", "-", "-").start()
        p.outputStream.use { it.write(plist.toByteArray()) }
        val out = p.inputStream.readBytes().decodeToString(); val err = p.errorStream.readBytes().decodeToString()
        return Result(p.waitFor(), out, err)
    }

    fun screenshot(udid: String, dest: File, type: String = "png") { checked(sim(udid, "io", udid, "screenshot", "--type=$type", dest.path, timeoutMs = 30_000), "simctl screenshot") }

    fun addRootCert(udid: String, pem: File) { checked(sim(udid, "keychain", udid, "add-root-cert", pem.path, timeoutMs = 60_000), "simctl keychain add-root-cert") }

    /** Dynamic Type: `ui content_size` with no value reads it. */
    fun contentSize(udid: String, value: String? = null): String =
        checked(sim(udid, *(listOf("ui", udid, "content_size") + listOfNotNull(value)).toTypedArray()), "simctl ui content_size").stdout.trim()

    /** `simctl io recordVideo`: runs until [RecordVideo.stop] (SIGINT), then the MP4 is complete. */
    fun recordVideo(udid: String, dest: File): RecordVideo {
        guard?.invoke(udid)
        dest.parentFile?.mkdirs()
        val log = File(dest.path + ".log")
        val p = ProcessBuilder(xcrun, "simctl", "io", udid, "recordVideo", "--codec=h264", "--force", dest.path)
            .redirectErrorStream(true).redirectOutput(log).start()
        return RecordVideo(p, dest, log)
    }

    class RecordVideo(private val p: Process, val file: File, private val log: File) {
        val startedAt = System.currentTimeMillis()

        /** SIGINT is how simctl finishes the file; a kill would leave it without its index. */
        fun stop(): File? {
            if (p.isAlive) runCatching { ProcessBuilder("kill", "-INT", p.pid().toString()).start().waitFor(5, TimeUnit.SECONDS) }
            if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly()
            log.delete()
            return file.takeIf { it.isFile && it.length() > 0 }
        }
    }

    companion object {
        /** `simctl list devices available -j`: runtimes («com.apple.CoreSimulator.SimRuntime.iOS-26-5») to devices. */
        fun parseList(json: String): List<Simulator> {
            val o = Json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
            val devices = o["devices"] as? JsonObject ?: return emptyList()
            return devices.entries.flatMap { (runtime, list) ->
                val os = runtimeName(runtime) ?: return@flatMap emptyList()
                (list as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { d ->
                    fun s(k: String) = (d[k] as? JsonPrimitive)?.content.orEmpty()
                    Simulator(s("udid"), s("name"), s("state"), os, s("isAvailable") != "false")
                }
            }.filter { it.available }
        }

        /** `simctl list runtimes -j`: the available iOS runtimes, newest first. */
        fun parseRuntimes(json: String): List<SimRuntime> {
            val list = (Json.parseToJsonElement(json) as? JsonObject)?.get("runtimes") as? JsonArray ?: return emptyList()
            return list.mapNotNull { it as? JsonObject }.map { r ->
                fun s(k: String) = (r[k] as? JsonPrimitive)?.content.orEmpty()
                SimRuntime(s("identifier"), s("name"), s("version"), s("isAvailable") != "false")
            }.filter { it.available && runtimeName(it.identifier) != null }
                .sortedByDescending { r -> r.version.split('.').map { it.toIntOrNull() ?: 0 }.let { v -> v.getOrElse(0) { 0 } * 1_000_000 + v.getOrElse(1) { 0 } * 1_000 + v.getOrElse(2) { 0 } } }
        }

        /** `simctl list devicetypes -j`. */
        fun parseDeviceTypes(json: String): List<DeviceType> {
            val list = (Json.parseToJsonElement(json) as? JsonObject)?.get("devicetypes") as? JsonArray ?: return emptyList()
            return list.mapNotNull { it as? JsonObject }.map { d ->
                fun s(k: String) = (d[k] as? JsonPrimitive)?.content.orEmpty()
                DeviceType(s("identifier"), s("name"), s("productFamily"))
            }
        }

        /**
         * The phones to make, best first: the plain «iPhone NN» models, newest first, then the rest of
         * the iPhones (a runtime supports only some models, so the caller tries them in turn).
         */
        fun phoneCandidates(types: List<DeviceType>): List<DeviceType> {
            val phones = types.filter { it.name.startsWith("iPhone") }
            fun number(t: DeviceType) = Regex("^iPhone (\\d+)").find(t.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val plain = phones.filter { Regex("^iPhone \\d+$").matches(it.name) }.sortedByDescending(::number)
            return plain + (phones - plain.toSet()).sortedByDescending(::number)
        }

        /** «iOS 26.5» from the runtime id; null for watchOS, tvOS and visionOS. */
        fun runtimeName(id: String): String? {
            val m = Regex("SimRuntime\\.iOS-(\\d+)-(\\d+)(?:-(\\d+))?$").find(id) ?: return null
            return "iOS " + m.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString(".")
        }

        /** Bundle ids from `listapps` as JSON, the user's apps (ApplicationType User) first. */
        fun parseApps(json: String): List<String> {
            val o = Json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
            fun type(v: Any?) = ((v as? JsonObject)?.get("ApplicationType") as? JsonPrimitive)?.content
            return o.entries.sortedBy { if (type(it.value) == "User") 0 else 1 }.map { it.key }
        }
    }
}
