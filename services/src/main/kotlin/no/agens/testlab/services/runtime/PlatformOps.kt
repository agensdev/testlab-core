package no.agens.testlab.services.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.agens.testlab.core.Platform
import no.agens.testlab.services.builds.InstallResult
import java.io.File

/** A tool or step that the device's platform doesn't have (TalkBack on an iOS Simulator…). */
class NotOnPlatform(message: String) : IllegalStateException(message)

/**
 * What the lab does to a device outside the daemon's UI verbs, per platform: deeplinks, installs,
 * app data, the font size, the lock screen. [LabRuntime.ops] picks the one for a device.
 */
interface PlatformOps {
    val platform: Platform

    suspend fun openUrl(serial: String, url: String, pkg: String): String
    suspend fun install(serial: String, file: File): InstallResult
    suspend fun isInstalled(serial: String, pkg: String): Boolean
    suspend fun uninstall(serial: String, pkg: String): String
    /** Deletes the app's data (logs it out); the app stays installed. */
    suspend fun clearData(serial: String, pkg: String)
    /** Installed packages (bundle ids on iOS) starting with [prefix]. */
    suspend fun packages(serial: String, prefix: String = ""): List<String>

    fun fontScale(serial: String): Double
    fun setFontScale(serial: String, scale: Double): Double
    fun resetFontScale(serial: String): Double
    /** The value [setFontScale] replaced, until [resetFontScale] puts it back. */
    fun originalFontScale(serial: String): Double?

    suspend fun unlock(serial: String, pin: String?): String

    /** Starts [pkg] with launch arguments on a device whose screen isn't driven (a real iPhone, this Mac). */
    suspend fun launch(serial: String, pkg: String, args: List<String>): String = throw NotOnPlatform("starting apps outside the device session")
    /** Stops [pkg] on such a device. */
    suspend fun stop(serial: String, pkg: String): String = throw NotOnPlatform("stopping apps outside the device session")

    /** Throws [NotOnPlatform] for a feature only Android has, with [what] in the message. */
    fun requireAndroid(what: String) {
        if (platform != Platform.ANDROID) throw NotOnPlatform("$what is not available on ${platform.label} devices")
    }
}

/** Android: adb and the classes that wrap it. */
class AndroidOps(private val rt: LabRuntime) : PlatformOps {
    override val platform = Platform.ANDROID

    override suspend fun openUrl(serial: String, url: String, pkg: String) = rt.adbOps.openUrl(serial, url, pkg)
    override suspend fun install(serial: String, file: File) = rt.builds.installer.install(serial, file)
    override suspend fun isInstalled(serial: String, pkg: String) = rt.builds.installer.isInstalled(serial, pkg)
    override suspend fun uninstall(serial: String, pkg: String) = rt.adbOps.uninstall(serial, pkg)
    override suspend fun clearData(serial: String, pkg: String) { withContext(Dispatchers.IO) { rt.adb().shell(serial, "pm clear ${shQuote(pkg)}") } }
    override suspend fun packages(serial: String, prefix: String): List<String> = withContext(Dispatchers.IO) {
        rt.adb().shell(serial, "pm list packages" + if (prefix.isNotEmpty()) " ${shQuote(prefix)}" else "").stdout.lines()
            .map { it.trim().removePrefix("package:") }.filter { it.isNotEmpty() && it.startsWith(prefix) }
    }

    override fun fontScale(serial: String) = rt.fontScale.current(serial)
    override fun setFontScale(serial: String, scale: Double) = rt.fontScale.set(serial, scale)
    override fun resetFontScale(serial: String) = rt.fontScale.reset(serial)
    override fun originalFontScale(serial: String) = rt.fontScale.original(serial)

    override suspend fun unlock(serial: String, pin: String?) = rt.adbOps.unlock(serial, pin)
}
