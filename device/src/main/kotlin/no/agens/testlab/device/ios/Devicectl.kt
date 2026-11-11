package no.agens.testlab.device.ios

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/** An iPhone or iPad `devicectl` knows; [problem] says why it can't take an install now (null: it can). */
data class PhysicalIos(val udid: String, val name: String, val model: String, val os: String?, val tablet: Boolean, val problem: String?)

/**
 * `xcrun devicectl` (Xcode 15+): installs and starts apps on paired iPhones and iPads (iOS 17 and
 * newer), by cable or over the network. It can't read or tap the screen; that takes XCUITest on the
 * phone. Output is read from `--json-output` files, as devicectl's text output is for people.
 */
class DevicectlCli(private val xcrun: XcrunCli) {
    private fun json(vararg args: String, timeoutMs: Long = 120_000): JsonObject {
        val out = File.createTempFile("devicectl", ".json")
        try {
            val r = xcrun.run(listOf("devicectl") + args.toList() + listOf("--json-output", out.path), timeoutMs)
            val o = runCatching { Json.parseToJsonElement(out.readText()) as? JsonObject }.getOrNull()
            if (!r.ok) throw XcrunException("devicectl ${args.take(3).joinToString(" ")} failed: " + (errorOf(o) ?: r.stderr.ifBlank { r.stdout }.trim().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()).take(400))
            return o ?: JsonObject(emptyMap())
        } finally { out.delete() }
    }

    fun devices(): List<PhysicalIos> = parseDevices(json("list", "devices", timeoutMs = 30_000))

    fun install(udid: String, ipa: File) { json("device", "install", "app", "--device", udid, ipa.path, timeoutMs = 600_000) }

    fun uninstall(udid: String, bundleId: String) { json("device", "uninstall", "app", "--device", udid, bundleId) }

    /** Starts [bundleId], stopping a running copy first (launch arguments are read at start only). */
    fun launch(udid: String, bundleId: String, args: List<String> = emptyList()) {
        try {
            json(*(listOf("device", "process", "launch", "--device", udid, "--terminate-existing", bundleId) + (if (args.isEmpty()) emptyList() else listOf("--") + args)).toTypedArray())
        } catch (e: XcrunException) {
            // SpringBoard refuses without saying why: nearly always a locked phone, else a developer not yet trusted.
            if ("FBSOpenApplicationServiceErrorDomain" in (e.message ?: "")) throw XcrunException(
                "the phone refused to start $bundleId: it is usually locked (unlock it and try again). After a new certificate, also trust the developer under Settings → General → VPN & Device Management",
            )
            throw e
        }
    }

    /** Starts [bundleId] with [url] to open (a universal link or the app's own scheme). */
    fun openUrl(udid: String, url: String, bundleId: String) { json("device", "process", "launch", "--device", udid, "--payload-url", url, bundleId) }

    fun apps(udid: String): List<String> = parseApps(json("device", "info", "apps", "--device", udid))

    companion object {
        private fun errorOf(o: JsonObject?): String? = ((o?.get("error") as? JsonObject)?.get("userInfo") as? JsonObject)
            ?.let { (it["NSLocalizedDescription"] as? JsonObject)?.get("string") as? JsonPrimitive }?.content

        private fun JsonObject.obj(k: String) = this[k] as? JsonObject
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** `devicectl list devices` JSON: real iPhones and iPads (simulators, watches and TVs left out). */
        fun parseDevices(o: JsonObject): List<PhysicalIos> {
            val list = (o.obj("result")?.get("devices") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            return list.mapNotNull { d ->
                // `properties` is the new shape; the older fields are all older Xcodes have.
                val props = d.obj("properties")
                val hw = props?.obj("hardware") ?: d.obj("hardwareProperties") ?: JsonObject(emptyMap())
                val conn = props?.obj("connection") ?: d.obj("connectionProperties") ?: JsonObject(emptyMap())
                val state = props?.obj("state") ?: d.obj("deviceProperties") ?: JsonObject(emptyMap())
                val type = hw.str("deviceType")
                if (hw.str("reality") == "simulated" || hw.str("platform") != "iOS" || (type != "iPhone" && type != "iPad")) return@mapNotNull null
                val udid = hw.str("udid") ?: return@mapNotNull null
                val os = (props?.obj("software")?.obj("osVersionNumber"))?.str("stringValue") ?: d.obj("deviceProperties")?.str("osVersionNumber")
                val model = hw.str("marketingName") ?: hw.str("productType") ?: type
                val paired = conn.str("pairingState") == "paired"
                // «disconnected» only means no tunnel yet (devicectl makes one when needed); «unavailable» is gone.
                val reachable = (conn.str("tunnelState") ?: conn.str("state")) != "unavailable"
                PhysicalIos(udid, state.str("name") ?: model, model, os, type == "iPad",
                    if (!paired) "not paired: connect it by cable, unlock it and trust this Mac" else if (!reachable) "not connected" else null)
            }
        }

        fun parseApps(o: JsonObject): List<String> =
            (o.obj("result")?.get("apps") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("bundleIdentifier") }
    }
}

/**
 * iOS apps on this Mac (Apple Silicon runs iPhone and iPad apps): an `.ipa` opened with `open` is
 * installed by macOS itself, in the background; the app is then an ordinary process.
 */
class MacIosApps(private val run: (List<String>, Long) -> XcrunCli.Result = ::exec) {
    /** The installed app's folder, found by its bundle id (Spotlight), or null. */
    fun path(bundleId: String): File? {
        require(BUNDLE.matches(bundleId)) { "not a bundle id: $bundleId" }
        val r = run(listOf("/usr/bin/mdfind", "kMDItemCFBundleIdentifier == '$bundleId'"), 15_000)
        return r.stdout.lineSequence().map { it.trim() }.firstOrNull { it.endsWith(".app") }?.let(::File)
    }

    /** Opens [ipa] and waits until macOS has installed [bundleId] (a minute at most). */
    fun install(ipa: File, bundleId: String) {
        val before = path(bundleId)?.lastModified()
        val r = run(listOf("/usr/bin/open", ipa.path), 30_000)
        if (!r.ok) throw XcrunException("open ${ipa.name} failed: ${r.stderr.trim().take(300)}")
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 60_000) {
            Thread.sleep(1_000)
            val now = path(bundleId)?.lastModified()
            if (now != null && now != before) return
        }
        if (path(bundleId) == null) throw XcrunException("macOS did not install $bundleId within a minute: open ${ipa.path} by hand to see why (an iPhone-only app, or the developer not allowed on this Mac)")
    }

    /** Starts [bundleId] with [args], quitting a running copy first: `open` gives arguments to a new instance only. */
    fun launch(bundleId: String, args: List<String>) {
        val app = path(bundleId) ?: throw XcrunException("$bundleId is not installed on this Mac")
        if (args.isNotEmpty()) {
            run(listOf("/usr/bin/pkill", "-f", app.path + "/"), 10_000)
            Thread.sleep(800)
        }
        val r = run(listOf("/usr/bin/open", "-b", bundleId) + (if (args.isEmpty()) emptyList() else listOf("--args") + args), 30_000)
        if (!r.ok) throw XcrunException("open -b $bundleId failed: ${r.stderr.trim().take(300)}")
    }

    fun terminate(bundleId: String) { path(bundleId)?.let { run(listOf("/usr/bin/pkill", "-f", it.path + "/"), 10_000) } }

    /** Moves the app to the Trash (Finder), as removing it by hand does. */
    fun uninstall(bundleId: String) {
        val app = path(bundleId) ?: return
        terminate(bundleId)
        val r = run(listOf("/usr/bin/osascript", "-e", "tell application \"Finder\" to delete POSIX file \"${app.path.replace("\"", "")}\""), 30_000)
        if (!r.ok) throw XcrunException("could not move ${app.name} to the Trash: ${r.stderr.trim().take(300)}")
    }

    companion object {
        const val SERIAL = "mac"
        private val BUNDLE = Regex("[A-Za-z0-9._-]+")

        private fun exec(cmd: List<String>, timeoutMs: Long): XcrunCli.Result {
            val out = File.createTempFile("mac", ".out"); val err = File.createTempFile("mac", ".err")
            try {
                val p = ProcessBuilder(cmd).redirectOutput(out).redirectError(err).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); throw XcrunException("${cmd.first()} did not finish in ${timeoutMs / 1000}s") }
                return XcrunCli.Result(p.exitValue(), out.readText(), err.readText())
            } finally { out.delete(); err.delete() }
        }

        /** Apple Silicon runs iPhone and iPad apps; an Intel Mac does not. */
        fun supported(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac") && System.getProperty("os.arch") == "aarch64"
    }
}
