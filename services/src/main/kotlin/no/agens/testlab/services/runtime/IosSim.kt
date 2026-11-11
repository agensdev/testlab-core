package no.agens.testlab.services.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import no.agens.testlab.core.Platform
import no.agens.testlab.core.nav.Destinations
import no.agens.testlab.device.adb.LabDevice
import no.agens.testlab.device.hs.HsEndpoint
import no.agens.testlab.device.ios.Appium
import no.agens.testlab.device.ios.AxeCli
import no.agens.testlab.device.ios.IosBridge
import no.agens.testlab.device.ios.WebDriver
import no.agens.testlab.device.ios.XcrunCli
import no.agens.testlab.services.builds.InstallResult
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * iOS Simulators: the booted ones are the devices; connecting one serves the daemon's protocol for it
 * through an [IosBridge]. With AXe installed ([axe]) that is all: the screen and input go through AXe,
 * and the Appium session (the first on a machine builds WebDriverAgent, which takes minutes) is opened
 * only when something needs it. Without AXe it is opened at once.
 */
class IosSimBackend(
    private val xcrun: XcrunCli,
    private val appium: Appium,
    private val tmp: File,
    private val axe: () -> AxeCli? = { AxeCli.locate()?.let(::AxeCli) },
) : DeviceBackend {
    private class Conn(val bridge: IosBridge, val axe: Boolean) {
        /** A session that was opened and has died takes the bridge with it; AXe needs none. */
        fun alive(): Boolean = bridge.driverIfStarted?.alive() ?: axe
    }
    private val conns = ConcurrentHashMap<String, Conn>()
    private val available by lazy { xcrun.available() }

    override fun devices(): List<LabDevice> {
        if (!available) throw NotOnPlatform("iOS Simulators need Xcode: install it from the App Store, then run `xcode-select -s /Applications/Xcode.app`")
        return xcrun.simulators().filter { it.booted }.map { it.toDevice() }
    }

    private fun session(serial: String): Pair<WebDriver, Int?> {
        val driver = WebDriver(appium.ensureRunning().let { appium.url })
        val mjpeg = Appium.stablePort("$serial-mjpeg", 9100..9999)
        val wda = Appium.stablePort(serial, 8100..8999)
        driver.newSession(Appium.capabilities(serial, wda, mjpeg))
        Appium.goDirect(driver, wda)
        return driver to mjpeg
    }

    @Synchronized
    override fun connect(serial: String, takeOver: Boolean): HsEndpoint {
        conns[serial]?.let { c -> if (c.alive()) return c.bridge.endpoint; release(serial) }
        val ax = axe()
        val bridge = if (ax != null) IosBridge(serial, xcrun, ax, { session(serial) }, File(tmp, serial.take(8)))
        else session(serial).let { (d, mjpeg) -> IosBridge(serial, xcrun, d, mjpeg, File(tmp, serial.take(8))) }
        conns[serial] = Conn(bridge, ax != null)
        return bridge.endpoint
    }

    /** Opens [serial]'s WebDriver session (connected first); the first on a machine builds WebDriverAgent. */
    fun startWda(serial: String) { connect(serial, false); conns.getValue(serial).bridge.startWda() }

    /** Whether AXe drives the screens. */
    fun usesAxe(): Boolean = axe() != null

    override fun release(serial: String) {
        conns.remove(serial)?.let { c -> c.bridge.close(); c.bridge.driverIfStarted?.deleteSession() }
    }

    fun close() {
        for (s in conns.keys.toList()) release(s)
        appium.stop()
    }
}

/**
 * iOS Simulator actions through `xcrun simctl`. A simulator has no lock screen, no TalkBack and no
 * Android keycodes; the font size is Dynamic Type (`ui content_size`).
 */
class IosSimOps(private val rt: LabRuntime, private val xcrun: XcrunCli, private val stateFile: File = File(rt.dirs.data, "content-size.json")) : PlatformOps {
    override val platform = Platform.IOS

    override suspend fun openUrl(serial: String, url: String, pkg: String): String = withContext(Dispatchers.IO) {
        val full = Destinations(rt.profileFor(serial).navigation).url(url)
        xcrun.openUrl(serial, full)
        "opened $full" + if (acceptOpenPrompt(serial, pkg)) " (accepted the «Open in» prompt)" else ""
    }

    /**
     * A simulator asks «Open in “App”?» when an app's own scheme is opened from outside: Open is
     * tapped, as a person would. Watched until [pkg] is in front without one, three seconds at most.
     */
    private suspend fun acceptOpenPrompt(serial: String, pkg: String): Boolean {
        val dev = runCatching { rt.device(serial) }.getOrNull() ?: return false
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 3_000) {
            if (runCatching { dev.acceptOpenPrompt() }.getOrDefault(false)) return true
            // The prompt comes within a moment of the link, so an app in front a little later means none.
            if (System.currentTimeMillis() - t0 > 600 && runCatching { dev.topActivity() }.getOrDefault("").startsWith(pkg)) return false
            delay(200)
        }
        return false
    }

    override suspend fun install(serial: String, file: File): InstallResult = withContext(Dispatchers.IO) {
        if (file.name.endsWith(".ipa")) return@withContext InstallResult(false, "${file.name} is a device build (.ipa): it can't run on a simulator. Pick the build type made for simulators (a .app)", null)
        runCatching { xcrun.install(serial, file) }.fold({ InstallResult(true, "Success", null) }, { InstallResult(false, it.message ?: "simctl install failed", null) })
    }

    override suspend fun isInstalled(serial: String, pkg: String): Boolean = withContext(Dispatchers.IO) { xcrun.isInstalled(serial, pkg) }

    override suspend fun uninstall(serial: String, pkg: String): String = withContext(Dispatchers.IO) { xcrun.uninstall(serial, pkg); "Success" }

    /** Empties the app's data container (what `pm clear` does on Android); the keychain is the simulator's and stays. */
    override suspend fun clearData(serial: String, pkg: String) = withContext(Dispatchers.IO) {
        xcrun.terminate(serial, pkg)
        val dir = xcrun.dataContainer(serial, pkg) ?: return@withContext
        dir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        for (sub in listOf("Documents", "Library/Caches", "Library/Preferences", "tmp")) File(dir, sub).mkdirs()
    }

    override suspend fun packages(serial: String, prefix: String): List<String> = withContext(Dispatchers.IO) { xcrun.bundleIds(serial).filter { it.startsWith(prefix) } }

    private val json = Json { prettyPrint = true }
    private fun saved(): MutableMap<String, String> = runCatching { json.decodeFromString<Map<String, String>>(stateFile.readText()).toMutableMap() }.getOrDefault(mutableMapOf())
    private fun save(m: Map<String, String>) { stateFile.parentFile?.mkdirs(); stateFile.writeText(json.encodeToString(m)) }

    override fun fontScale(serial: String): Double = scaleOf(xcrun.contentSize(serial))

    override fun setFontScale(serial: String, scale: Double): Double {
        require(scale in 0.5..3.5) { "font scale must be between 0.5 and 3.5" }
        if (!saved().containsKey(serial)) save(saved().also { it[serial] = xcrun.contentSize(serial) })
        xcrun.contentSize(serial, categoryFor(scale))
        return fontScale(serial)
    }

    override fun resetFontScale(serial: String): Double {
        xcrun.contentSize(serial, saved()[serial] ?: "large")
        save(saved().also { it.remove(serial) })
        return fontScale(serial)
    }

    override fun originalFontScale(serial: String): Double? = saved()[serial]?.let(::scaleOf)

    override suspend fun unlock(serial: String, pin: String?) = "not locked (a simulator has no lock screen)"

    companion object {
        /** Dynamic Type sizes as the text scale they give the body style (large is the default). */
        val SIZES = listOf(
            "extra-small" to 0.82, "small" to 0.88, "medium" to 0.94, "large" to 1.0, "extra-large" to 1.12,
            "extra-extra-large" to 1.24, "extra-extra-extra-large" to 1.35, "accessibility-medium" to 1.65,
            "accessibility-large" to 1.94, "accessibility-extra-large" to 2.35, "accessibility-extra-extra-large" to 2.76,
            "accessibility-extra-extra-extra-large" to 3.12,
        )

        fun scaleOf(category: String): Double = SIZES.firstOrNull { it.first == category.trim() }?.second ?: 1.0
        fun categoryFor(scale: Double): String = SIZES.minBy { kotlin.math.abs(it.second - scale) }.first
    }
}
