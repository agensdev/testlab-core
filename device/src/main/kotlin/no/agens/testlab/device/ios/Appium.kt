package no.agens.testlab.device.ios

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

class AppiumMissing(message: String) : RuntimeException(message)

/**
 * Appium with its XCUITest driver: what drives a simulator's UI (through WebDriverAgent, which it
 * builds with Xcode on first use). Installed with npm into [home], pinned to the versions the lab
 * was verified with, and run as a child on [port] (one per space).
 */
class Appium(private val home: File, val port: Int, private val log: File) {
    @Volatile private var process: Process? = null
    val url: String get() = "http://127.0.0.1:$port"

    private val driverHome get() = File(home, "home")
    private val bin get() = File(home, "node_modules/.bin/appium")

    /** Node's folder: on PATH, else Homebrew's or the installer's (a launchd service has a bare PATH). */
    fun nodeDir(): File? = (System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin"))
        .map(::File).firstOrNull { File(it, "node").canExecute() && File(it, "npm").canExecute() }

    fun installed(): Boolean = bin.canExecute() && File(driverHome, "node_modules/appium-xcuitest-driver").isDirectory

    private fun env(pb: ProcessBuilder): ProcessBuilder = pb.apply {
        val node = nodeDir() ?: throw AppiumMissing("Node.js was not found: install it (brew install node, or nodejs.org) for iOS Simulators")
        environment()["PATH"] = node.path + File.pathSeparator + environment()["PATH"].orEmpty()
        environment()["APPIUM_HOME"] = driverHome.path
    }

    /** `npm install appium@…` and `appium driver install xcuitest@…` into [home]; [onLine] gets their output. */
    fun install(onLine: (String) -> Unit = {}) {
        home.mkdirs(); driverHome.mkdirs()
        val npm = File(nodeDir() ?: throw AppiumMissing("Node.js was not found: install it (brew install node, or nodejs.org) for iOS Simulators"), "npm")
        fun run(vararg cmd: String) {
            val p = env(ProcessBuilder(*cmd)).directory(home).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().forEachLine(onLine)
            if (p.waitFor() != 0) throw AppiumMissing("${cmd.take(3).joinToString(" ")} failed (exit ${p.exitValue()})")
        }
        if (!bin.canExecute()) run(npm.path, "install", "--prefix", home.path, "--no-fund", "--no-audit", "appium@$APPIUM_VERSION")
        if (!File(driverHome, "node_modules/appium-xcuitest-driver").isDirectory) run(bin.path, "driver", "install", "xcuitest@$XCUITEST_VERSION")
    }

    /** Starts Appium unless one already answers on [port]. */
    @Synchronized
    fun ensureRunning(): WebDriver {
        val probe = WebDriver(url)
        if (probe.status()) return probe
        if (!installed()) throw AppiumMissing("Appium for iOS Simulators is not installed: run `testlab setup ios`")
        log.parentFile?.mkdirs()
        process = env(ProcessBuilder(bin.path, "--address", "127.0.0.1", "--port", port.toString(), "--log-level", "warn", "--log-no-colors"))
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 60_000) {
            if (probe.status()) return probe
            if (process?.isAlive == false) throw AppiumMissing("Appium stopped at start (exit ${process?.exitValue()}): see ${log.path}")
            Thread.sleep(300)
        }
        throw AppiumMissing("Appium did not answer on port $port within a minute: see ${log.path}")
    }

    fun stop() {
        process?.let { p -> p.destroy(); if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly() }
        process = null
    }

    companion object {
        /** The versions the iOS lab was verified with. */
        const val APPIUM_VERSION = "3.5.2"
        const val XCUITEST_VERSION = "11.14.0"

        /**
         * The session for [udid]: kept (noReset, a day without commands), WebDriverAgent and its MJPEG
         * server on ports of this simulator's own, so simulators of several spaces don't collide.
         */
        fun capabilities(udid: String, wdaPort: Int, mjpegPort: Int): JsonObject = buildJsonObject {
            put("platformName", "iOS")
            put("appium:automationName", "XCUITest")
            put("appium:udid", udid)
            put("appium:noReset", true)
            put("appium:newCommandTimeout", 86_400)
            put("appium:wdaLocalPort", wdaPort)
            put("appium:mjpegServerPort", mjpegPort)
            put("appium:wdaLaunchTimeout", 240_000)
            put("appium:wdaConnectionTimeout", 240_000)
            put("appium:shouldTerminateApp", false)
            put("appium:forceAppLaunch", false)
            if (fast()) {
                // WebDriverAgent waits for the app to go idle before each action and each tree, often for
                // seconds while something animates; the bridge waits itself where it matters (wait_for_idle).
                put("appium:waitForIdleTimeout", 0)
                put("appium:waitForQuiescence", false)
                // The live view's frames double as screenshots: good enough to read.
                put("appium:mjpegServerScreenshotQuality", 60)
            }
        }

        /**
         * The quick path for iOS (default): no waiting for idle apps, WebDriverAgent called directly,
         * the tree without `visible`, screenshots from the live view. `TESTLAB_IOS_FAST=0` turns it off
         * (the before/after measurement in scripts/dev/ios-e2e.sh).
         */
        fun fast(): Boolean = System.getenv("TESTLAB_IOS_FAST") != "0"

        /** Makes [driver]'s hot calls go straight to WebDriverAgent on [wdaPort], when [fast]. */
        fun goDirect(driver: WebDriver, wdaPort: Int) {
            if (!fast() || !driver.goDirect("http://127.0.0.1:$wdaPort")) return
            driver.sourceExcluded = "visible"
        }

        /**
         * The session for a real iPhone: as [capabilities], plus what building WebDriverAgent for the
         * phone takes. Xcode signs it with the Apple developer team [teamId] (an account signed in
         * under Xcode → Settings → Accounts; a free personal team works, its profile lasts 7 days),
         * under its own bundle id [wdaBundleId], and registers the phone with the team if needed.
         * [platformVersion] (see [buildableVersion]) is the iOS version WebDriverAgent is built for when
         * that can't be the phone's own; null leaves it to Appium, which asks the phone.
         */
        fun realDeviceCapabilities(udid: String, wdaPort: Int, mjpegPort: Int, teamId: String, wdaBundleId: String, platformVersion: String? = null): JsonObject = buildJsonObject {
            capabilities(udid, wdaPort, mjpegPort).forEach { (k, v) -> put(k, v) }
            platformVersion?.let { put("appium:platformVersion", it) }
            put("appium:xcodeOrgId", teamId)
            put("appium:xcodeSigningId", "Apple Development")
            put("appium:updatedWDABundleId", wdaBundleId)
            put("appium:allowProvisioningDeviceRegistration", true)
            // A phone takes longer than a simulator: the build, the install and the first start over the cable.
            put("appium:wdaLaunchTimeout", 480_000)
            put("appium:wdaConnectionTimeout", 480_000)
        }

        /**
         * The iOS version to build WebDriverAgent for when the phone's iOS ([deviceOs]) is newer than
         * this Xcode's iPhone SDK ([sdk]): the SDK's version, else null. Appium builds with the phone's
         * version as the deployment target, and Xcode refuses a target above its SDK ("the range of
         * supported deployment target versions is 15.0 to 27.1.x"), which stops every phone on a newer
         * iOS point release or beta than the installed Xcode. Built for the SDK's version it still runs
         * on the newer phone.
         */
        fun buildableVersion(deviceOs: String?, sdk: String?): String? {
            fun parts(v: String?) = Regex("^(\\d+)(?:\\.(\\d+))?").find(v.orEmpty().trim())?.let { (it.groupValues[1].toInt()) to (it.groupValues[2].toIntOrNull() ?: 0) }
            val phone = parts(deviceOs) ?: return null
            val xcode = parts(sdk) ?: return null
            val newer = phone.first > xcode.first || (phone.first == xcode.first && phone.second > xcode.second)
            return if (newer) "${xcode.first}.${xcode.second}" else null
        }

        /** A team id as Apple gives it (10 letters and digits, as on developer.apple.com → Membership). */
        val TEAM_ID = Regex("[A-Z0-9]{10}")

        /** The bundle id WebDriverAgent gets on a phone signed by [teamId]: unique per team, as Apple requires. */
        fun defaultWdaBundleId(prefix: String, teamId: String): String = "$prefix.wda.${teamId.lowercase()}.WebDriverAgentRunner"

        /**
         * What to do about a failed WebDriverAgent start on a real iPhone, from Appium's message: the
         * usual causes are signing, the phone's developer settings and a locked screen.
         */
        fun realDeviceHint(message: String): String {
            val m = message.lowercase()
            return when {
                "deployment target" in m -> "this Xcode is older than the phone's iOS: update Xcode"
                "signing" in m || "provisioning" in m || "no account" in m || "certificate" in m ->
                    "Xcode could not sign WebDriverAgent: sign in under Xcode → Settings → Accounts with an Apple ID in the team, and check the team id"
                // Appium passes on only xcodebuild's exit code, which is the same for both of these.
                "code 65" in m ->
                    "Xcode could not build WebDriverAgent. Usually it could not sign it (sign in under Xcode → Settings → Accounts with an Apple ID in the team, and check the team id), or this Xcode is older than the phone's iOS (update Xcode)"
                "developer mode" in m -> "turn on Settings → Privacy & Security → Developer Mode on the phone (it restarts)"
                "ui automation" in m || ("automation" in m && "enable" in m) -> "turn on Settings → Developer → Enable UI Automation on the phone"
                "untrusted" in m || "not trusted" in m || "trust" in m -> "trust the developer on the phone: Settings → General → VPN & Device Management"
                "locked" in m || "passcode" in m -> "unlock the phone and keep it unlocked (Settings → Display & Brightness → Auto-Lock → Never) while it is tested"
                else -> "check that the phone is unlocked, on the cable, with Developer Mode and Settings → Developer → Enable UI Automation on"
            }
        }

        /**
         * A port of [key]'s (a simulator's UDID) own within [range], the same every time: a WebDriverAgent
         * still running on the simulator from an earlier session is found and reused rather than
         * built and started again.
         */
        fun stablePort(key: String, range: IntRange): Int = range.first + Math.floorMod(key.hashCode(), range.last - range.first + 1)
    }
}
