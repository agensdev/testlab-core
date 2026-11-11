package no.agens.testlab.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.agens.testlab.core.Platform
import no.agens.testlab.mcp.Param.Companion.bool
import no.agens.testlab.mcp.Param.Companion.int
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.crash.Crashlytics
import no.agens.testlab.services.runtime.LabRuntime

/** The iOS counterparts of the Android CLI tools: simulators that set themselves up, and the App Store's crash and performance reports through Xcode. */
internal fun ToolBox.iosTools(rt: LabRuntime) {
    val slow = SlowCalls()

    tool(
        "handsets_simulator_create",
        """
        Make an iPhone simulator (macOS with Xcode): model like "iPhone 17", empty = the newest plain
        iPhone this Xcode has, on the newest iOS runtime. With no iOS runtime installed it downloads
        one first (several GB, many minutes). start=true boots it; then drive it with
        handsets_use_device(serial=<UDID>). A simulator suits flows and layouts; push notifications,
        the camera, Face ID, bank-ID logins and performance need a real iPhone.
        """,
        str("model", "iPhone model; empty = the newest plain iPhone", ""),
        bool("start", "boot it once made", true),
    ) { a ->
        val sims = rt.simulators
        val model = a.strOrNull("model")
        val noRuntime = withContext(Dispatchers.IO) { runCatching { rt.xcrun?.runtimes()?.isEmpty() }.getOrNull() } == true
        if (noRuntime) rt.confirmWrite("Download the iOS simulator runtime", "No iOS simulator runtime is installed: download one (several GB) to make the simulator?")
        text(slow.run("sim|$model|${a.bool("start")}", "Still making the simulator" + (if (noRuntime) " (downloading the iOS runtime takes many minutes)" else "") + ": call handsets_simulator_create again with the same arguments for the result.") {
            withContext(Dispatchers.IO) {
                val sim = sims.create(model)
                if (!a.bool("start")) "made ${sim.name} (${sim.runtime}): serial ${sim.udid}. handsets_simulator_start(\"${sim.udid}\") boots it."
                else sims.start(sim.udid).let { "made and booted ${it.name} (${it.runtime}): serial ${it.udid}. handsets_use_device(serial=\"${it.udid}\") to drive it." }
            }
        })
    }

    /** The App Store bundle id: the iOS profile's production app, else its default one. */
    suspend fun bundle(given: String?): String = given?.takeIf { it.isNotBlank() } ?: rt.onPlatform(Platform.IOS) {
        rt.profile.app.productionPackage?.takeIf { it.isNotBlank() } ?: rt.profile.app.defaultPackage.takeIf { it.isNotBlank() }
    } ?: throw BadArgument("which app? pass bundle_id (the iOS profile has no app.productionPackage)")

    fun channel(a: Args): Boolean? = when (a.str("channel")) { "testflight" -> true; "app_store" -> false; else -> null }

    fun base(bundleId: String, a: Args) = buildMap<String, JsonElement> {
        put("bundle_id", JsonPrimitive(bundleId)); put("platform", JsonPrimitive("iOS"))
        a.strOrNull("app_version")?.let { put("app_version", JsonPrimitive(it)) }
        channel(a)?.let { put("is_beta", JsonPrimitive(it)) }
    }

    val CHANNEL = str("channel", "app_store | testflight; empty = both", "", listOf("", "app_store", "testflight"))

    tool(
        "lab_ios_crashes",
        """
        The iOS app's crashes in the field, from Apple (App Store and TestFlight, last 14 days, by
        devices affected), read through Xcode 27's MCP server with the Apple account signed in to
        Xcode. Answers «does this crash hit real users, how many, which versions?». match = a crash
        from the test run (its exception, the app's own frames from the simulator's log or a crash
        report): only the signatures sharing it, best first. A match is a lead: read the signature's
        logs with lab_ios_crash before calling it the same crash. Default app: the iOS profile's
        production app.
        """,
        str("match", "a crash's exception line or stack trace", ""),
        str("app_version", "only this version, e.g. 8.7.0", ""),
        CHANNEL,
        int("count", "how many signatures", 10),
        str("bundle_id", "the app; empty = the iOS profile's production app", ""),
        readOnly = true,
    ) { a ->
        val id = bundle(a.strOrNull("bundle_id"))
        val match = a.strOrNull("match")
        val r = rt.xcode.call("GetTopCrashIssues", JsonObject(base(id, a) + ("count" to JsonPrimitive(if (match != null) 50 else a.int("count").coerceIn(1, 50)))))
        val data = r["data"]?.let(::parseData)
        val sigs = data?.let { Crashlytics.objects(it) }.orEmpty()
        text(buildJsonObject {
            put("bundle_id", id)
            (r["message"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { put("message", it) }
            if (match == null) {
                if (sigs.isNotEmpty()) putJsonArray("signatures") { sigs.take(a.int("count")).forEach { add(it) } } else put("data", data ?: r)
            } else {
                val ranked = Crashlytics.rank(sigs.map { Crashlytics.Issue(signature(it), 0, it) }, Crashlytics.terms(match))
                put("matches", ranked.size)
                if (ranked.isEmpty()) put("note", "no signature in the last 14 days shares this crash's exception or the app's frames (${sigs.size} signature(s)): not seen in the field, or reported under another name")
                putJsonArray("signatures") { ranked.take(a.int("count")).forEach { i -> addJsonObject { put("score", i.score); put("signature", i.json) } } }
            }
        }.encode())
    }

    tool(
        "lab_ios_crash",
        "One iOS crash signature (its name from lab_ios_crashes): the crash logs, and Xcode's triage notes and recommendations.",
        str("signature", "the signature name from lab_ios_crashes"),
        str("app_version", "only this version", ""),
        CHANNEL,
        str("bundle_id", "the app; empty = the iOS profile's production app", ""),
        readOnly = true,
    ) { a ->
        val r = rt.xcode.call("GetCrashIssueLogs", JsonObject(base(bundle(a.strOrNull("bundle_id")), a) + ("signature_name" to JsonPrimitive(a.str("signature")))))
        text(((r["data"] as? JsonPrimitive)?.content ?: r.encode()).let { if (it.length > 60_000) it.take(60_000) + "\n… cut at 60 000 characters" else it })
    }

    tool(
        "lab_ios_performance",
        """
        The iOS app's performance in the field, from Apple: kind = launches (slow launches), hangs,
        diskwrites or energy. Without app_version it lists the versions to choose from; with signature
        it shows that issue's logs (stack traces, timeline) and triage notes.
        """,
        str("kind", "launches | hangs | diskwrites | energy", "hangs", listOf("launches", "hangs", "diskwrites", "energy")),
        str("app_version", "the version, e.g. 8.7.0; empty = list the versions", ""),
        str("signature", "an issue's signature name, for its logs", ""),
        CHANNEL,
        str("bundle_id", "the app; empty = the iOS profile's production app", ""),
        readOnly = true,
    ) { a ->
        val args = base(bundle(a.strOrNull("bundle_id")), a) + ("diagnostic_type" to JsonPrimitive(a.str("kind")))
        val sig = a.strOrNull("signature")
        if (sig != null && a.strOrNull("app_version") == null) throw BadArgument("an issue's logs need app_version")
        val r = rt.xcode.call(if (sig != null) "GetFieldPerformanceIssueLogs" else "GetTopFieldPerformanceIssues", JsonObject(args + listOfNotNull(sig?.let { "signature_name" to JsonPrimitive(it) })))
        val out = buildJsonObject {
            r.forEach { (k, v) -> if (k == "data") put("data", parseData(v)) else put(k, v) }
        }.encode()
        text(if (out.length > 60_000) out.take(60_000) + "\n… cut at 60 000 characters" else out)
    }
}

/** Xcode returns its report data as a JSON string; parsed when it is JSON, else kept as text. */
private fun parseData(e: JsonElement): JsonElement = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { s ->
    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(s) }.getOrNull()
} ?: e

/** A crash signature's name, as lab_ios_crash takes it. */
private fun signature(o: JsonObject): String? = listOf("signatureName", "signature_name", "signature", "name", "title")
    .firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
