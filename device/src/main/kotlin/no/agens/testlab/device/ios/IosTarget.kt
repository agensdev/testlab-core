package no.agens.testlab.device.ios

import java.io.File
import java.util.Base64

/**
 * What [IosBridge] needs from the device besides WebDriverAgent and AXe: screenshots, the app list,
 * app starts and stops, and its name. A simulator has them through `simctl` ([SimTarget]); a real
 * iPhone through `devicectl` and WebDriverAgent ([PhoneTarget]).
 */
interface IosTarget {
    val udid: String
    /** The device's name and iOS version, for `state`. */
    fun info(): Pair<String, String?>
    fun screenshot(dest: File, type: String)
    fun bundleIds(): List<String>
    fun launch(bundleId: String, args: List<String>)
    fun terminate(bundleId: String)
    /** The bundle id of each running app by process id (AXe's tree gives pids only). */
    fun processBundles(): Map<Int, String> = emptyMap()
    /** Puts [text] on the device's pasteboard (AXe types the US keyboard only). */
    fun pasteboard(text: String): Unit = throw XcrunException("this device has no pasteboard access")
}

/** An iOS Simulator through `xcrun simctl`. */
class SimTarget(private val xcrun: XcrunCli, override val udid: String) : IosTarget {
    override fun info(): Pair<String, String?> {
        val sim = runCatching { xcrun.simulators().firstOrNull { it.udid == udid } }.getOrNull()
        return (sim?.name ?: "iOS Simulator") to sim?.os
    }

    override fun screenshot(dest: File, type: String) = xcrun.screenshot(udid, dest, type)
    override fun bundleIds(): List<String> = xcrun.bundleIds(udid)
    override fun launch(bundleId: String, args: List<String>) = xcrun.launch(udid, bundleId, args)
    override fun terminate(bundleId: String) = xcrun.terminate(udid, bundleId)

    /** From launchd's list of the simulator's apps (`UIKitApplication:<bundle>[…]`). */
    override fun processBundles(): Map<Int, String> {
        val list = runCatching { xcrun.sim(udid, "spawn", udid, "launchctl", "list", timeoutMs = 10_000).stdout }.getOrDefault("")
        return buildMap {
            for (line in list.lineSequence()) {
                val cols = line.split('\t')
                val p = cols.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
                val label = cols.getOrNull(2)?.trim() ?: continue
                if (label.startsWith("UIKitApplication:")) put(p, label.removePrefix("UIKitApplication:").substringBefore('['))
            }
        }
    }

    override fun pasteboard(text: String) {
        val r = xcrun.sim(udid, "pbcopy", udid, stdin = text.toByteArray())
        if (!r.ok) throw XcrunException("simctl pbcopy failed: ${r.stderr.trim().take(200)}")
    }
}

/**
 * A real iPhone or iPad: apps through `devicectl`, screenshots and app stops through its
 * WebDriverAgent session ([driver], opened by the bridge).
 */
class PhoneTarget(
    private val devicectl: DevicectlCli,
    override val udid: String,
    private val name: String,
    private val os: String?,
    private val driver: () -> WebDriver,
) : IosTarget {
    override fun info(): Pair<String, String?> = name to os

    /** WebDriverAgent gives a PNG; a JPEG asked for is written as PNG too (the bridge decodes either). */
    override fun screenshot(dest: File, type: String) {
        dest.parentFile?.mkdirs()
        dest.writeBytes(driver().screenshot())
    }

    override fun bundleIds(): List<String> = devicectl.apps(udid)
    override fun launch(bundleId: String, args: List<String>) = devicectl.launch(udid, bundleId, args)

    override fun terminate(bundleId: String) {
        driver().mobile("terminateApp", kotlinx.serialization.json.buildJsonObject { put("bundleId", kotlinx.serialization.json.JsonPrimitive(bundleId)) })
    }

    companion object {
        fun decode(base64: String): ByteArray = Base64.getMimeDecoder().decode(base64)
    }
}
