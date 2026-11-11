package no.agens.testlab.services.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.services.proxyman.ProxyRules
import no.agens.testlab.services.proxyman.StdioMcp

/**
 * Is the rig working from the phone's side? Everything measured on this computer can be fine while
 * the phone has no network: a proxy pinned to an old address, Wi-Fi dropped, a hung Proxyman, a
 * missing CA. Each check says pass, warn or fail, what it saw, and the fix.
 */
class RigHealth(private val rt: LabRuntime) {
    enum class Level { PASS, WARN, FAIL, SKIP }

    data class Check(val name: String, val level: Level, val value: String, val fix: String? = null)

    private suspend fun shell(serial: String, cmd: String, timeoutMs: Long = 20_000) = withContext(Dispatchers.IO) { rt.adb().shell(serial, cmd, timeoutMs) }

    suspend fun run(serial: String, full: Boolean = true): List<Check> {
        val out = mutableListOf<Check>()
        val android = rt.platformOf(serial) == no.agens.testlab.core.Platform.ANDROID
        if (!android) {
            out += Check("platform", Level.SKIP, "iOS: the phone-side checks are Android's; the simulator shares this Mac's network")
        } else {
            val state = runCatching { rt.listDevices().firstOrNull { it.serial == serial } }.getOrNull()
            if (state == null || !state.ready) {
                out += Check("adb", Level.FAIL, state?.state ?: "not connected", state?.hint ?: "connect the phone (handsets_list_adb_devices says how)")
                return out
            }
            out += Check("adb", Level.PASS, "${state.state} over ${state.transport}")
            out += simAndLock(serial)
            out += network(serial)
            out += proxy(serial)
        }
        if (rt.settings.current.proxyman.enabled) out += proxyman()
        if (android && full) {
            out += packages(serial)
            out += deviceState(serial)
        }
        out += recordings(serial)
        return out
    }

    private suspend fun simAndLock(serial: String): List<Check> = buildList {
        val sim = runCatching { rt.adbOps.simLock(serial) }.getOrNull()
        add(if (sim != null) Check("sim", Level.FAIL, sim, "a person enters the SIM PIN on the phone; no tool may type into it (three wrong tries lock the SIM)") else Check("sim", Level.PASS, "no SIM PIN asked"))
        val locked = runCatching { rt.adbOps.keyguardShowing(serial) }.getOrDefault(false)
        add(if (locked) Check("lock_screen", Level.WARN, "the lock screen is up", "handsets_unlock_device") else Check("lock_screen", Level.PASS, "unlocked"))
    }

    private suspend fun network(serial: String): Check {
        val active = shell(serial, "dumpsys connectivity | grep -m1 'Active default network'").stdout.trim()
        val wifi = shell(serial, "dumpsys wifi | grep -m1 'mWifiInfo'").stdout.trim()
        val ssid = Regex("SSID: \"?([^\",]+)").find(wifi)?.groupValues?.get(1)?.takeIf { it != "<unknown ssid>" }
        return when {
            active.isEmpty() || "none" in active.lowercase() ->
                Check("network", Level.FAIL, "no active network", "the phone dropped its Wi-Fi (check Settings → Network: a saved network set to «Won't automatically connect» stays off): tap it to reconnect")
            else -> Check("network", Level.PASS, active.substringAfter(':').trim().ifEmpty { active } + (ssid?.let { ", Wi-Fi $it" } ?: ""))
        }
    }

    /** The phone's proxy setting, then a real request through it made by the phone itself. */
    private suspend fun proxy(serial: String): List<Check> = buildList {
        val px = if (rt.settings.current.proxyman.enabled) runCatching { rt.proxyman.status(serial) }.getOrNull() else null
        val cur = px?.proxy ?: shell(serial, "settings get global http_proxy").stdout.trim().takeUnless { it.isEmpty() || it == "null" || it == ":0" }
        if (cur == null) {
            add(Check("proxy", Level.PASS, "direct (no proxy)"))
            val direct = probe(serial, null)
            add(if (direct.startsWith("HTTP/")) Check("internet", Level.PASS, "the phone reaches the internet ($direct)") else Check("internet", Level.FAIL, "no answer from the internet ($direct)", "check the phone's Wi-Fi and DNS"))
            return@buildList
        }
        if (px?.wanted != null && px.wanted != cur) {
            add(Check("proxy", Level.FAIL, "the phone uses $cur, Testlab set ${px.wanted}", "something else changed it (another tool, a pinning service with an old address): handsets_proxy(on) sets it again"))
        } else add(Check("proxy", Level.PASS, cur + if (px?.ours == true) " (set by Testlab)" else " (not set by Testlab)"))
        val host = cur.substringBeforeLast(':')
        val port = cur.substringAfterLast(':').toIntOrNull() ?: 0
        val open = shell(serial, "toybox nc -w 3 ${shQuote(host)} $port </dev/null >/dev/null 2>&1; echo rc=$?").stdout.trim()
        if (!open.endsWith("rc=0")) {
            add(Check("proxy_reachable", Level.FAIL, "the phone can't connect to $cur ($open)",
                if (host == "127.0.0.1") "the adb reverse port is gone (replugged?): handsets_proxy(on) sets it again; handsets_proxy(off) gives the phone direct network meanwhile"
                else "the proxy's address has changed (DHCP) or it is down: point the phone at the right address, or handsets_proxy(off)"))
            return@buildList
        }
        add(Check("proxy_reachable", Level.PASS, "the phone connects to $cur"))
        val through = probe(serial, host to port)
        add(if (through.startsWith("HTTP/")) Check("via_proxy", Level.PASS, "a request from the phone through the proxy got $through")
        else Check("via_proxy", Level.FAIL, "the proxy accepts the phone's connection but doesn't answer ($through)", "the proxy is hung: restart Proxyman; handsets_proxy(off) gives the phone its network back meanwhile"))
    }

    /** One plain-HTTP request from the phone (through [proxy] when given): its status line, or why not. */
    private suspend fun probe(serial: String, proxy: Pair<String, Int>?): String {
        val (host, port, path) = if (proxy != null) Triple(proxy.first, proxy.second, "http://$PROBE_HOST/generate_204") else Triple(PROBE_HOST, 80, "/generate_204")
        val req = "GET $path HTTP/1.1\\r\\nHost: $PROBE_HOST\\r\\nConnection: close\\r\\n\\r\\n"
        val r = shell(serial, "printf '$req' | toybox nc -w 6 ${shQuote(host)} $port 2>&1 | head -1", 15_000)
        return r.stdout.trim().ifEmpty { "no answer within 6 s" }
    }

    private suspend fun proxyman(): List<Check> = buildList {
        val px = rt.proxyman
        val h = runCatching { px.health() }.getOrNull()
        if (h == null || !h.proxyReachable) { add(Check("proxyman", Level.FAIL, "Proxyman's port doesn't answer here", "start Proxyman (and check its port in the settings)")); return@buildList }
        add(if (h.viaProxyStatus?.startsWith("2") == true) Check("proxyman", Level.PASS, "forwarding (${h.viaProxyStatus})") else Check("proxyman", Level.FAIL, "accepts connections but doesn't forward (${h.viaProxyStatus})", "restart Proxyman"))
        if (!h.mcp.startsWith("connected")) { add(Check("proxyman_mcp", Level.WARN, h.mcp, "rule checks need Proxyman's MCP bridge")); return@buildList }
        val rules = runCatching { px.rules.list() }.getOrNull()
        if (rules != null) {
            val ours = rules.count { it.ours }
            add(if (rules.isEmpty()) Check("proxy_rules", Level.PASS, "no rules: traffic is real")
            else Check("proxy_rules", Level.WARN, "${rules.size} rule(s) active (${ours} made by Testlab): ${rules.take(5).joinToString { "${it.type} «${it.name.ifEmpty { it.url }}»" }}",
                "mocked traffic is not real evidence: lab_proxy_cleanup removes Testlab's own rules; others belong to whoever made them"))
        }
        val ssl = runCatching { StdioMcp.parseLoose(StdioMcp.text(px.call("get_ssl_proxying_list", JsonObject(emptyMap())))) as? JsonObject }.getOrNull()
        if (ssl != null) {
            val excluded = (ssl["excludeList"] as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonObject)?.takeIf { (it["isEnabled"] as? JsonPrimitive)?.content != "false" }?.get("name")?.let { (it as JsonPrimitive).content } }
            val missing = ProxyRules.SSL_EXCLUDE_BASELINE.filter { it !in excluded }
            add(if (missing.isEmpty()) Check("ssl_exclude", Level.PASS, "Google hosts excluded from decryption (${excluded.size} host(s) excluded)")
            else Check("ssl_exclude", Level.WARN, "not excluded from SSL proxying: ${missing.joinToString()}",
                "decrypting Google hosts breaks Play services, push and analytics upload: add them to Proxyman's SSL exclude list, then force-stop Play services and the app"))
        }
        runCatching { px.caTrust() }.getOrNull()?.let { t ->
            add(Check("phone_trusts_ca", if (t.startsWith("trusted")) Level.PASS else if (t.startsWith("unknown")) Level.SKIP else Level.FAIL, t, if (t.startsWith("probably NOT")) no.agens.testlab.services.proxyman.Proxyman.certHint(rt.profile) else null))
        }
    }

    /** The profile's app family on the phone: which builds, which disabled (a disabled sibling makes logins land in the wrong app). */
    private suspend fun packages(serial: String): List<Check> = buildList {
        val app = rt.profileFor(serial).app
        val names = listOfNotNull(app.family, app.defaultPackage.takeIf { it.isNotBlank() }, app.productionPackage).distinct()
        if (names.isEmpty()) return@buildList
        val listed = shell(serial, "pm list packages -U; echo ---; pm list packages -d").stdout
        val (all, disabledPart) = listed.split("---").let { it[0] to it.getOrElse(1) { "" } }
        val disabled = disabledPart.lines().map { it.trim().removePrefix("package:") }.toSet()
        val installed = all.lines().mapNotNull { Regex("package:(\\S+)").find(it)?.groupValues?.get(1) }
            .filter { p -> names.any { n -> p == n || p.startsWith("$n.") } }.sorted()
        if (installed.isEmpty()) { add(Check("app", Level.WARN, "no ${names.first()} build installed", "handsets_install_branch_build or handsets_install_build")); return@buildList }
        val records = runCatching { rt.builds.installedOn(serial) }.getOrDefault(emptyList()).associateBy { it.packageName }
        for (p in installed) {
            val v = shell(serial, "dumpsys package ${shQuote(p)} | grep -m2 -E 'versionCode|versionName'").stdout.lines().joinToString(" ") { it.trim() }
            val rec = records[p]
            val what = listOfNotNull(Regex("versionName=(\\S+)").find(v)?.groupValues?.get(1), Regex("versionCode=(\\d+)").find(v)?.groupValues?.get(1)?.let { "($it)" },
                rec?.let { r -> listOfNotNull(r.branch, r.headSha?.take(7), r.tag).joinToString(" ").ifEmpty { null } }).joinToString(" ")
            add(if (p in disabled) Check("app $p", Level.WARN, "$what, DISABLED", "pm-disabled (a login disables sibling builds while it runs, and enables them again after): lab_device_state(restore) enables it if the snapshot had it enabled, or `pm enable` it")
            else Check("app $p", Level.PASS, what.ifEmpty { "installed" }))
        }
    }

    private suspend fun deviceState(serial: String): List<Check> {
        val now = runCatching { rt.deviceState.read(serial) }.getOrNull() ?: return emptyList()
        val odd = buildList {
            fun v(k: String) = now.values[k]
            if (v("system.font_scale") != "1.0") add("font scale ${v("system.font_scale")}")
            if (v("dont_keep_activities") == "1") add("«Don't keep activities» on")
            if (!v("secure.enabled_accessibility_services").isNullOrBlank() && "talkback" in v("secure.enabled_accessibility_services")!!.lowercase()) add("TalkBack on")
            listOf("global.window_animation_scale", "global.transition_animation_scale", "global.animator_duration_scale").filter { v(it) != "1.0" }.takeIf { it.isNotEmpty() }?.let { add("animation scales changed") }
            if (v("prop.debug.firebase.analytics.app") != ".none." && !v("prop.debug.firebase.analytics.app").isNullOrBlank()) add("Firebase analytics debug for ${v("prop.debug.firebase.analytics.app")}")
            if (v("demo_mode") == "1") add("SystemUI demo mode on")
        }
        val (base, changes) = runCatching { rt.deviceState.diff(serial) }.getOrDefault(null to emptyList())
        return listOf(
            if (odd.isEmpty()) Check("device_settings", Level.PASS, "defaults (font 1.0, no TalkBack, activities kept, normal animations)")
            else Check("device_settings", Level.WARN, odd.joinToString(", "), "left over from a test? lab_device_state(diff) compares with the session's snapshot, restore puts it back"),
        ) + if (base != null && changes.isNotEmpty()) listOf(Check("since_snapshot", Level.WARN, "${changes.size} setting(s) changed since ${base.takenAt}: ${changes.take(4).joinToString { it.label }}", "lab_device_state(restore) before the report")) else emptyList()
    }

    private fun recordings(serial: String): List<Check> {
        val r = rt.recordings.status(serial).firstOrNull() ?: return listOf(Check("recording", Level.PASS, "not recording"))
        return listOf(if (r.seconds > 2 * 3600) Check("recording", Level.WARN, "recording for %.1f h".format(r.seconds / 3600), "a recording left running from an earlier session: lab_session_discard, then lab_session_start")
        else Check("recording", Level.PASS, "recording for %.0f s".format(r.seconds)))
    }

    companion object {
        const val PROBE_HOST = "connectivitycheck.gstatic.com"
    }
}
