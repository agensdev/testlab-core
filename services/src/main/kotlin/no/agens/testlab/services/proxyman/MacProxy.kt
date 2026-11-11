package no.agens.testlab.services.proxyman

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Proxyman for iOS Simulators: a simulator has no proxy setting of its own and uses the Mac's, so
 * routing one sets this Mac's web and secure web proxy (`networksetup`) on every network service
 * that is on. What was there before is saved in [file] first and put back when the last simulator
 * goes direct, at exit, and at the next start after a crash. While it is on, the whole Mac's HTTP(S)
 * goes through Proxyman, which is why routing asks first.
 */
class MacProxy(
    private val file: File,
    /** Runs `networksetup` (tests replace it); returns exit code and output. */
    private val exec: (List<String>) -> Pair<Int, String> = ::networksetup,
) {
    @Serializable
    data class Setting(val enabled: Boolean, val server: String = "", val port: Int = 0)

    @Serializable
    data class Saved(val web: Setting, val secure: Setting)

    @Serializable
    data class Record(val proxy: String, val before: Map<String, Saved>, val sims: List<String>)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun record(): Record? = runCatching { json.decodeFromString<Record>(file.readText()) }.getOrNull()

    private fun save(r: Record?) {
        if (r == null) { file.delete(); return }
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Record.serializer(), r))
    }

    private fun run(vararg args: String): String {
        val (code, out) = exec(args.toList())
        if (code != 0) throw ProxymanUnavailable(
            "networksetup ${args.firstOrNull()} failed: ${out.trim().take(200)}. Set the Mac's web and secure web proxy to ${record()?.proxy ?: "Proxyman"} " +
                "yourself in System Settings → Network → (your network) → Details → Proxies",
        )
        return out
    }

    /** Network services that are on (`networksetup -listallnetworkservices` marks the others with *). */
    fun services(): List<String> = run("-listallnetworkservices").lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("*") }

    fun get(service: String, secure: Boolean): Setting = parse(run(if (secure) "-getsecurewebproxy" else "-getwebproxy", service))

    /** The Mac's web proxy now, as `host:port` (the first service that has one on), or null. */
    fun current(): String? = runCatching { services().firstNotNullOfOrNull { s -> get(s, secure = false).takeIf { it.enabled && it.server.isNotEmpty() }?.let { "${it.server}:${it.port}" } } }.getOrNull()

    /** Points the Mac at [proxy] (`host:port`) for simulator [udid]; the first one saves what was set. */
    fun route(udid: String, proxy: String): Record {
        val host = proxy.substringBeforeLast(':'); val port = proxy.substringAfterLast(':')
        val old = record()
        val before = old?.before ?: services().associateWith { s -> Saved(get(s, false), get(s, true)) }
        // Saved before anything changes: a crash right after still gets undone.
        val r = Record(proxy, before, ((old?.sims ?: emptyList()) + udid).distinct())
        save(r)
        for (s in before.keys) {
            run("-setwebproxy", s, host, port)
            run("-setsecurewebproxy", s, host, port)
        }
        return r
    }

    /** Simulator [udid] goes direct; the Mac's own settings come back once no simulator is routed. Returns whether they did. */
    fun unroute(udid: String): Boolean {
        val r = record() ?: return false
        val rest = r.sims - udid
        if (rest.isNotEmpty()) { save(r.copy(sims = rest)); return false }
        restore(r)
        return true
    }

    /** Puts the saved settings back (exit, or the next start after a crash). */
    fun restoreAll(): Boolean = record()?.let { restore(it); true } ?: false

    private fun restore(r: Record) {
        for ((s, saved) in r.before) {
            runCatching { put(s, saved.web, secure = false) }
            runCatching { put(s, saved.secure, secure = true) }
        }
        save(null)
    }

    private fun put(service: String, v: Setting, secure: Boolean) {
        if (v.server.isNotEmpty()) run(if (secure) "-setsecurewebproxy" else "-setwebproxy", service, v.server, v.port.toString())
        run(if (secure) "-setsecurewebproxystate" else "-setwebproxystate", service, if (v.enabled) "on" else "off")
    }

    fun routed(udid: String): Boolean = record()?.sims?.contains(udid) == true

    companion object {
        /** `networksetup -getwebproxy` prints `Enabled: Yes`, `Server: …`, `Port: …`. */
        fun parse(out: String): Setting {
            fun v(k: String) = out.lines().firstOrNull { it.trim().startsWith("$k:") }?.substringAfter(':')?.trim().orEmpty()
            return Setting(v("Enabled").equals("Yes", ignoreCase = true), v("Server"), v("Port").toIntOrNull() ?: 0)
        }

        fun networksetup(args: List<String>): Pair<Int, String> {
            val p = ProcessBuilder(listOf("/usr/sbin/networksetup") + args).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString()
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); return 124 to "timed out" }
            return p.exitValue() to out
        }
    }
}
