package no.agens.testlab.services.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.agens.testlab.AppIdentity
import no.agens.testlab.core.Platform
import no.agens.testlab.core.nav.Destinations
import no.agens.testlab.device.adb.LabDevice
import no.agens.testlab.device.hs.HsEndpoint
import no.agens.testlab.device.ios.Appium
import no.agens.testlab.device.ios.DevicectlCli
import no.agens.testlab.device.ios.IosBridge
import no.agens.testlab.device.ios.MacIosApps
import no.agens.testlab.device.ios.PhoneTarget
import no.agens.testlab.device.ios.PhysicalIos
import no.agens.testlab.device.ios.WebDriver
import no.agens.testlab.services.builds.IosApp
import no.agens.testlab.services.builds.InstallResult
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** What driving a real iPhone's screen takes: Appium, and the Apple team that signs WebDriverAgent. */
data class PhoneDriving(val appium: Appium, val teamId: String, val wdaBundleId: String, val tmp: File)

/**
 * Real iOS devices: paired iPhones and iPads (`devicectl`, iOS 17 and newer) and, on Apple Silicon,
 * this Mac, which runs iOS apps. They take device builds (`.ipa`) and app starts with launch
 * arguments. With [driving] (an Apple team set) an iPhone's screen is read and tapped too, through
 * WebDriverAgent built for the phone, and it is listed ready like a simulator; without, it is
 * [LabDevice.INSTALL_ONLY]. This Mac is always install-only.
 */
class IosDeviceBackend(
    private val devicectl: DevicectlCli?,
    private val mac: Boolean = MacIosApps.supported(),
    private val driving: () -> PhoneDriving? = { null },
) : DeviceBackend {
    private class Conn(val bridge: IosBridge, val driver: WebDriver)
    private val conns = ConcurrentHashMap<String, Conn>()
    private val known = ConcurrentHashMap<String, PhysicalIos>()

    override fun devices(): List<LabDevice> = buildList {
        val drivable = driving() != null
        devicectl?.let { d -> runCatching { d.devices() }.getOrDefault(emptyList()).forEach { known[it.udid] = it; add(toDevice(it, drivable)) } }
        if (mac) add(LabDevice(MacIosApps.SERIAL, LabDevice.INSTALL_ONLY, model = "This Mac", platform = Platform.IOS, kind = "mac", detail = "iOS apps on Apple Silicon"))
    }

    @Synchronized
    override fun connect(serial: String, takeOver: Boolean): HsEndpoint {
        if (serial == MacIosApps.SERIAL) throw NotOnPlatform("This Mac takes builds and app starts, but its screen can't be read or tapped: use an iOS Simulator for that")
        val d = driving() ?: throw NotOnPlatform(
            "A real iPhone's screen is read and tapped through WebDriverAgent, which Xcode signs with your Apple developer team: set it on the setup page (`${AppIdentity.SLUG} open setup`) or with `${AppIdentity.SLUG} setup ios --team <TEAM ID>` (installs and app starts work without)",
        )
        conns[serial]?.let { c -> if (c.driver.alive()) return c.bridge.endpoint; release(serial) }
        val ctl = devicectl ?: throw NotOnPlatform("real iPhones need Xcode 15 or newer (devicectl)")
        val phone = known[serial] ?: runCatching { ctl.devices() }.getOrDefault(emptyList()).onEach { known[it.udid] = it }.firstOrNull { it.udid == serial }
        phone?.problem?.let { throw NotOnPlatform("${phone.name}: $it") }
        val name = phone?.name ?: "iPhone"
        val driver = WebDriver(d.appium.ensureRunning().let { d.appium.url })
        val mjpeg = Appium.stablePort("$serial-mjpeg", 9100..9999)
        val wda = Appium.stablePort(serial, 8100..8999)
        try {
            driver.newSession(Appium.realDeviceCapabilities(serial, wda, mjpeg, d.teamId, d.wdaBundleId), timeoutMs = 900_000)
        } catch (e: Exception) {
            val msg = (e.message ?: e.toString()).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(300)
            throw IllegalStateException("WebDriverAgent did not start on $name: ${Appium.realDeviceHint(e.message ?: "")}. (Appium: $msg)", e)
        }
        // Appium forwards the phone's WebDriverAgent port to this computer, so the direct calls reach it too.
        Appium.goDirect(driver, wda)
        val bridge = IosBridge(PhoneTarget(ctl, serial, name, phone?.os) { driver }, null, { driver to mjpeg }, File(d.tmp, serial.take(8)))
        conns[serial] = Conn(bridge, driver)
        return bridge.endpoint
    }

    override fun release(serial: String) {
        conns.remove(serial)?.let { c -> c.bridge.close(); c.driver.deleteSession() }
    }

    fun close() { for (s in conns.keys.toList()) release(s) }

    companion object {
        fun toDevice(p: PhysicalIos, drivable: Boolean = false) = LabDevice(
            p.udid, if (p.problem != null) "unavailable" else if (drivable) "device" else LabDevice.INSTALL_ONLY,
            model = p.name, platform = Platform.IOS, os = p.os,
            kind = if (p.tablet) "ipad" else "iphone", detail = p.problem ?: p.model,
        )
    }
}

/** The simulators and the real devices as one iOS backend: a serial goes back to the one that listed it. */
class IosBackends(private val sims: DeviceBackend, private val real: DeviceBackend) : DeviceBackend {
    private val realSerials = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun devices(): List<LabDevice> {
        val r = runCatching { real.devices() }.getOrDefault(emptyList())
        realSerials.addAll(r.map { it.serial })
        // Without Xcode the simulators' error is the one to show; real devices alone still list.
        val s = runCatching { sims.devices() }.getOrElse { if (r.isEmpty()) throw it else emptyList() }
        return s + r
    }

    private fun of(serial: String) = if (serial in realSerials || serial == MacIosApps.SERIAL) real else sims
    override fun connect(serial: String, takeOver: Boolean): HsEndpoint = of(serial).connect(serial, takeOver)
    override fun release(serial: String) = of(serial).release(serial)
}

/** Installs, starts and lists apps on a real iPhone or iPad ([devicectl]) or this Mac ([mac]). */
class IosDeviceOps(private val rt: LabRuntime, private val devicectl: DevicectlCli?, private val mac: MacIosApps = MacIosApps()) : PlatformOps {
    override val platform = Platform.IOS

    private fun isMac(serial: String) = serial == MacIosApps.SERIAL
    private fun ctl(): DevicectlCli = devicectl ?: throw NotOnPlatform("real iPhones need Xcode 15 or newer (devicectl)")

    override suspend fun openUrl(serial: String, url: String, pkg: String): String = withContext(Dispatchers.IO) {
        val full = Destinations(rt.profileFor(serial).navigation).url(url)
        if (isMac(serial)) mac.launch(pkg, emptyList()).also { ProcessBuilder("/usr/bin/open", full).start().waitFor() }
        else ctl().openUrl(serial, full, pkg)
        "opened $full"
    }

    override suspend fun install(serial: String, file: File): InstallResult = withContext(Dispatchers.IO) {
        if (file.isDirectory) return@withContext InstallResult(false, "${file.name} is a simulator build (.app): a real device takes the device build (.ipa). Pick that build type", null)
        IosApp.profileExpiry(file)?.takeIf { it.isBefore(Instant.now()) }?.let {
            return@withContext InstallResult(false, "The signing profile in ${file.name} expired on ${it.toString().take(10)}: the device won't install it. Build it again (CI signs with the current profile)", null)
        }
        runCatching {
            if (isMac(serial)) mac.install(file, IosApp.ipaManifest(file).packageName) else ctl().install(serial, file)
        }.fold({ InstallResult(true, "Success", null) }, { InstallResult(false, it.message ?: "install failed", null) })
    }

    override suspend fun isInstalled(serial: String, pkg: String): Boolean = withContext(Dispatchers.IO) {
        if (isMac(serial)) mac.path(pkg) != null else pkg in ctl().apps(serial)
    }

    override suspend fun uninstall(serial: String, pkg: String): String = withContext(Dispatchers.IO) {
        if (isMac(serial)) mac.uninstall(pkg) else ctl().uninstall(serial, pkg)
        "Success"
    }

    override suspend fun launch(serial: String, pkg: String, args: List<String>): String = withContext(Dispatchers.IO) {
        if (isMac(serial)) mac.launch(pkg, args) else ctl().launch(serial, pkg, args)
        "started $pkg" + if (args.isEmpty()) "" else " with ${args.size} launch argument(s)"
    }

    override suspend fun stop(serial: String, pkg: String): String = withContext(Dispatchers.IO) {
        if (isMac(serial)) { mac.terminate(pkg); "stopped $pkg" } else throw NotOnPlatform("stopping an app on a real iPhone: start it again instead (a start stops the running copy)")
    }

    override suspend fun clearData(serial: String, pkg: String) = throw NotOnPlatform("clearing app data on a real device: uninstall and install it again")

    override suspend fun packages(serial: String, prefix: String): List<String> = withContext(Dispatchers.IO) {
        if (isMac(serial)) listOfNotNull(prefix.takeIf { it.isNotEmpty() && mac.path(it) != null }) else ctl().apps(serial).filter { it.startsWith(prefix) }.sorted()
    }

    override fun fontScale(serial: String): Double = throw NotOnPlatform("the text size of a real device is set on the device")
    override fun setFontScale(serial: String, scale: Double): Double = fontScale(serial)
    override fun resetFontScale(serial: String): Double = fontScale(serial)
    override fun originalFontScale(serial: String): Double? = null

    override suspend fun unlock(serial: String, pin: String?) = throw NotOnPlatform(
        "a real iPhone/iPad can't be unlocked from here (Apple allows no passcode entry through WebDriverAgent): unlock it by hand, " +
            "and set Settings → Display & Brightness → Auto-Lock to Never on a test device so it stays unlocked")
}
