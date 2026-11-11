package no.agens.testlab.services.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.SpaceRules
import no.agens.testlab.services.SpaceRulesStore
import no.agens.testlab.services.Spaces
import java.io.File

/**
 * Hands a shared phone from one client's space to the next: what the space that had it left behind
 * is undone, following that space's `space.json` `handover`:
 * - its apps (by `appFamilies`) cleared, or uninstalled; the builds an isolation disabled enabled again
 * - the proxy setting and reverse port it routed the phone through removed
 * - TalkBack and the font size put back as they were before it changed them
 *
 * The state files read are that space's own (isolation.json, proxied.json, talkback.json,
 * font-scale.json), so it works whether that space's server still runs or not.
 */
class Handover(private val rt: LabRuntime) {
    /** The steps it took, in words; a step that fails is listed with why and the rest go on. */
    fun run(serial: String, fromSpace: String): List<String> {
        val data = dataOf(fromSpace)
        val rules = SpaceRulesStore(AppDirs(data, data, data)).current
        val out = mutableListOf<String>()
        fun step(what: String, block: () -> String?) {
            runCatching(block).onSuccess { it?.let(out::add) }.onFailure { out += "$what failed: ${it.message}" }
        }
        val ops = rt.ops(serial)
        fun sh(cmd: String) = rt.adb().shell(serial, cmd).let { (it.stdout + it.stderr).trim() }
        if (ops.platform != no.agens.testlab.core.Platform.ANDROID) {
            step("apps") { apps(serial, rules, ops) }
            // A simulator's proxy is the Mac's: that space's saved settings come back when it was the last one routed.
            if (rules.handover.proxy) step("proxy") {
                val m = if (data.canonicalPath == rt.dirs.data.canonicalPath) rt.proxyman.mac else no.agens.testlab.services.proxyman.MacProxy(File(data, "mac-proxy.json"))
                if (!m.routed(serial)) null else if (m.unroute(serial)) "the Mac's own proxy settings are back" else "simulator no longer routed (others still are)"
            }
            if (rules.handover.accessibility) rt.xcrun?.let { x ->
                step("text size") {
                    val o = IosSimOps(rt, x, File(data, "content-size.json"))
                    if (o.originalFontScale(serial) != null) "text size back to ${o.resetFontScale(serial)}" else null
                }
            }
            return out.ifEmpty { listOf("nothing to undo") }
        }

        step("enable isolated builds") {
            runBlocking { Isolation(rt, File(data, "isolation.json")).restore(serial) }.takeIf { it.isNotEmpty() }?.let { "enabled again: ${it.joinToString()}" }
        }
        step("apps") { apps(serial, rules, ops) }
        if (rules.handover.proxy) step("proxy") { proxy(serial, data, ::sh) }
        if (rules.handover.accessibility) {
            step("TalkBack") {
                val tb = TalkBack(rt, File(data, "talkback.json"))
                val st = tb.status(serial)
                when {
                    st.turnedOffByUs.isNotEmpty() -> { tb.on(serial); "TalkBack on again (it was on before)" }
                    st.enabled -> { tb.off(serial); File(data, "talkback.json").let { f -> runCatching { dropKey(f, serial) } }; "TalkBack off" }
                    else -> null
                }
            }
            step("font size") {
                val fs = FontScale(rt, File(data, "font-scale.json"))
                if (fs.original(serial) != null || fs.current(serial) != 1.0) "font size back to ${fs.reset(serial)}" else null
            }
        }
        return out.ifEmpty { listOf("nothing to undo") }
    }

    private fun apps(serial: String, rules: SpaceRules, ops: PlatformOps): String? = runBlocking {
        if (rules.appFamilies.isEmpty()) return@runBlocking null
        val pkgs = ops.packages(serial).filter { p -> rules.appFamilies.any { f -> p == f || p.startsWith("$f.") } }
        if (pkgs.isEmpty()) return@runBlocking null
        for (p in pkgs) {
            if (rules.handover.uninstall) runCatching { ops.uninstall(serial, p) }
            else if (rules.handover.clearApps) ops.clearData(serial, p)
        }
        when {
            rules.handover.uninstall -> "uninstalled ${pkgs.joinToString()}"
            rules.handover.clearApps -> "cleared ${pkgs.joinToString()} (logins and data)"
            else -> null
        }
    }

    private fun proxy(serial: String, data: File, sh: (String) -> String): String? {
        if (data.canonicalPath == rt.dirs.data.canonicalPath) {
            // This space's own routing: Proxyman's own undo keeps its records right.
            val was = runCatching { rt.proxyman.status(serial).proxy }.getOrNull()
            runBlocking { rt.proxyman.unroute(serial) }
            return was?.let { "proxy $it removed" }
        }
        val f = File(data, "proxied.json")
        val rec = runCatching { (Json.parseToJsonElement(f.readText()) as JsonArray).map { it as JsonObject }.firstOrNull { it["serial"]?.jsonPrimitive?.content == serial } }.getOrNull()
            ?: return null
        val proxy = rec["proxy"]?.jsonPrimitive?.content
        val cur = sh("settings get global http_proxy")
        if (cur == proxy || cur.isEmpty() || cur == "null") sh("settings put global http_proxy :0")
        rec["reversePort"]?.let { p -> runCatching { rt.adb().run(listOf("reverse", "--remove", "tcp:${p.jsonPrimitive.int}"), serial) } }
        runCatching {
            val rest = (Json.parseToJsonElement(f.readText()) as JsonArray).filter { (it as JsonObject)["serial"]?.jsonPrimitive?.content != serial }
            if (rest.isEmpty()) f.delete() else f.writeText(JsonArray(rest).toString())
        }
        return "proxy $proxy removed"
    }

    private fun dropKey(f: File, serial: String) {
        if (!f.isFile) return
        val o = Json.parseToJsonElement(f.readText()) as JsonObject
        if (serial in o) f.writeText(JsonObject(o - serial).toString())
    }

    /** A space's data folder (the default one is the root). */
    private fun dataOf(space: String): File =
        if (space == Spaces.DEFAULT) rt.dirs.rootData else File(rt.dirs.rootData, "spaces/$space")
}
