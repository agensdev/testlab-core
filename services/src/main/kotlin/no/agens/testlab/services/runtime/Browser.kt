package no.agens.testlab.services.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.device.HostOs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Opens links in the person's default web browser. On macOS `open URL` does that for http(s), but a
 * `file://` page goes to whichever app owns .html files, which is often Safari even when another
 * browser is the default: every link is therefore opened with the browser registered for https.
 */
object Browser {
    /** Whether this session can show a page: a desktop (macOS, Windows, or Linux with a display). */
    fun available(env: Map<String, String> = System.getenv()): Boolean = when (HostOs.current()) {
        HostOs.LINUX -> !env["DISPLAY"].isNullOrBlank() || !env["WAYLAND_DISPLAY"].isNullOrBlank()
        else -> true
    }

    /** Opens [url]; false when nothing could open it (then show the link instead). */
    fun open(url: String): Boolean {
        val cmd = when (HostOs.current()) {
            HostOs.MAC -> defaultMacBrowser()?.let { listOf("open", "-b", it, url) } ?: listOf("open", url)
            HostOs.WINDOWS -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            HostOs.LINUX -> if (!available()) return false else listOf("xdg-open", url)
        }
        return runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            !p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() == 0
        }.getOrDefault(false)
    }

    /** The bundle id of the app that opens https links (the default browser), from Launch Services; null = not set (Safari). */
    fun defaultMacBrowser(home: File = File(System.getProperty("user.home"))): String? {
        val plist = File(home, "Library/Preferences/com.apple.LaunchServices/com.apple.launchservices.secure.plist")
        if (!plist.isFile) return null
        val json = runCatching {
            val p = ProcessBuilder("plutil", "-convert", "json", "-o", "-", plist.path).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor(5, TimeUnit.SECONDS) }
        }.getOrNull() ?: return null
        return handlerFor(json)
    }

    /** The https (else http) handler in Launch Services' LSHandlers, as `plutil -convert json` prints them. */
    fun handlerFor(launchServicesJson: String): String? {
        val handlers = runCatching { (Json.parseToJsonElement(launchServicesJson) as JsonObject)["LSHandlers"] as? JsonArray }.getOrNull() ?: return null
        fun forScheme(s: String) = handlers.mapNotNull { it as? JsonObject }.firstOrNull { (it["LSHandlerURLScheme"] as? JsonPrimitive)?.content == s }
            ?.let { h -> listOf("LSHandlerRoleAll", "LSHandlerRoleViewer").firstNotNullOfOrNull { (h[it] as? JsonPrimitive)?.content } }
            ?.takeIf { it.isNotBlank() && it != "-" }
        return forScheme("https") ?: forScheme("http")
    }
}
