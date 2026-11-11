package no.agens.testlab.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.agens.testlab.mcp.Param.Companion.SERIAL
import no.agens.testlab.mcp.Param.Companion.bool
import no.agens.testlab.mcp.Param.Companion.int
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.crash.Crashlytics
import no.agens.testlab.services.logs.LogCapture
import no.agens.testlab.services.runtime.Browser
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.tools.AndroidCli

/** What Google's Android CLI adds: emulators that set themselves up, and the app's crashes in the field (Crashlytics). */
internal fun ToolBox.androidCliTools(rt: LabRuntime) {
    val slow = SlowCalls()
    val emus = rt.emulators

    tool(
        "handsets_emulator_start",
        """
        Start an Android emulator by its name (an AVD), and wait until Android has booted; empty name =
        list them (running ones with their serial). Then drive it like a phone with
        handsets_use_device(serial). No emulator yet: handsets_emulator_create makes one. headless runs
        it without a window (Testlab's screen view still shows it); default: a window when this
        computer has a screen.
        """,
        str("name", "the emulator (AVD) name; empty = list them", ""),
        bool("headless", "no emulator window", false),
        bool("cold", "boot from scratch instead of the saved snapshot", false),
    ) { a ->
        val name = a.str("name").trim()
        if (name.isEmpty()) {
            val list = withContext(Dispatchers.IO) { emus.avds() }
            return@tool text(if (list.isEmpty()) "(no emulators: " + (if (emus.canCreate) "handsets_emulator_create makes one)" else "${AndroidCli.MISSING}; then handsets_emulator_create)")
            else list.joinToString("\n") { e -> listOfNotNull(e.name, e.api, if (e.running) "running ${e.serial}" else "stopped").joinToString("  ") })
        }
        val headless = a.bool("headless") || !Browser.available()
        text(slow.run("start|$name", "Still booting $name: call handsets_emulator_start(\"$name\") again for its serial; it doesn't start twice.") {
            val serial = withContext(Dispatchers.IO) { emus.start(name, headless = headless, cold = a.bool("cold")) }
            "$name is running: serial $serial. handsets_use_device(serial=\"$serial\") to drive it."
        })
    }

    tool(
        "handsets_emulator_create",
        """
        Make an Android emulator (needs Google's Android CLI): profile is the kind of device, e.g.
        medium_phone, small_phone, medium_tablet. The first one downloads the emulator and a system
        image (a few GB, some minutes); it is named after the profile. start=true boots it as well.
        An emulator is good for flows and layouts; use a real phone for camera, bank-ID logins, NFC,
        notifications and performance.
        """,
        str("profile", "kind of device; empty = list the profiles", "medium_phone"),
        bool("start", "start it once made", true),
        bool("headless", "no emulator window", false),
    ) { a ->
        if (!emus.canCreate) throw IllegalStateException(AndroidCli.MISSING)
        val profile = a.str("profile").trim()
        if (profile.isEmpty()) return@tool text(withContext(Dispatchers.IO) { emus.profiles() }.joinToString("\n"))
        val existing = withContext(Dispatchers.IO) { emus.avds() }.firstOrNull { it.name == profile }
        if (existing == null) rt.confirmWrite("Make an Android emulator", "Make a $profile emulator? The first one downloads the Android emulator and a system image (a few GB).")
        val headless = a.bool("headless") || !Browser.available()
        text(slow.run("create|$profile|${a.bool("start")}", "Still making the $profile emulator (downloads take minutes the first time): call handsets_emulator_create again with the same arguments for the result.") {
            withContext(Dispatchers.IO) {
                val name = existing?.name ?: emus.create(profile)
                val made = if (existing != null) "$name already exists" else "made the emulator $name"
                if (!a.bool("start")) "$made. handsets_emulator_start(\"$name\") boots it."
                else emus.start(name, headless = headless).let { serial -> "$made; it is running: serial $serial. handsets_use_device(serial=\"$serial\") to drive it." }
            }
        })
    }

    tool(
        "handsets_emulator_stop",
        "Stop an Android emulator, by name or serial (its apps and data stay). Releases the session first.",
        str("name", "the emulator's name or serial (emulator-5554)"),
    ) { a ->
        val n = a.str("name").trim()
        withContext(Dispatchers.IO) { emus.stop(n) }
        text("stopped $n")
    }

    tool(
        "lab_crashlytics_apps",
        """
        The Firebase projects and apps the Google account signed in to the Android CLI can read in
        Crashlytics, and which apps the profile names (crashlytics.apps: package → Firebase app id).
        Use it when lab_crashlytics_issues says the app is unknown or access is refused.
        """,
        str("project", "a Firebase project id, to list its apps", ""),
        readOnly = true,
    ) { a ->
        val apps = rt.crashlytics.apps(a.strOrNull("project"))
        text(buildJsonObject {
            put("profile_apps", buildJsonObject { rt.profile.crashlytics.apps.forEach { (k, v) -> put(k, v) } })
            put("account", rt.androidCli.account() ?: "not signed in")
            put("crashlytics", apps)
        }.encode())
    }

    tool(
        "lab_crashlytics_issues",
        """
        The app's crashes in the field, from Firebase Crashlytics: issue clusters (crash, ANR,
        non-fatal) with how many users and events, most users first. Answers «does this
        crash hit real users, how often, since which version?». match = a crash from the rig (its
        exception line or stack trace): only the issues sharing its exception and the app's own
        frames, best first. from_log=true does that for each app crash in the device's last log
        capture (lab_logs_stop). Default app: the production package. A match is a lead, not proof:
        read the issue's stack with lab_crashlytics_issue before saying it is the same crash, and say
        in the report which issue it is (id, users, versions).
        """,
        str("match", "a crash's exception line or stack trace", ""),
        bool("from_log", "match each app crash in the device's last log capture", false),
        str("type", "crash | anr | non-fatal; empty = all", "", listOf("", "crash", "anr", "non-fatal")),
        str("app_version", "only this app version, e.g. 5.12.0 or 5.12.0 (1042)", ""),
        str("device", "only this device, e.g. Pixel 8 or Samsung", ""),
        str("api_level", "only this Android version, e.g. 34 or Android 14", ""),
        str("timeframe", "24h | 7d | 30d | 60d | 90d; empty = the profile's", "", listOf("", "24h", "7d", "30d", "60d", "90d")),
        str("package", "which app (a package in crashlytics.apps); empty = production", ""),
        int("limit", "at most this many issues (per crash with from_log)", 20),
        SERIAL,
        readOnly = true,
    ) { a ->
        val limit = a.int("limit").coerceIn(1, 200)
        val traces = if (a.bool("from_log")) {
            val serial = rt.serialFor(a.serial)
            val f = rt.logs.file(serial) ?: throw BadArgument("no log capture for $serial: lab_logs_start before reproducing, or pass match")
            LogCapture.crashTraces(f.readText()).take(5).ifEmpty { return@tool text("no app crash in ${f.name}: nothing to look up") }
        } else listOfNotNull(a.strOrNull("match"))
        val (pkg, tf, all) = rt.crashlytics.issues(a.strOrNull("package"), a.strOrNull("type"), a.strOrNull("app_version"), a.strOrNull("device"), a.strOrNull("api_level"),
            a.strOrNull("timeframe"), if (traces.isEmpty()) limit else 500)
        text(buildJsonObject {
            put("package", pkg); put("timeframe", tf)
            if (traces.isEmpty()) {
                put("count", all.size)
                putJsonArray("issues") { all.take(limit).forEach { add(trim(it.json)) } }
            } else putJsonArray("crashes") {
                for (t in traces) addJsonObject {
                    put("crash", t.lines().take(4).joinToString("\n"))
                    val terms = Crashlytics.terms(t)
                    val ranked = Crashlytics.rank(all, terms)
                    put("matches", ranked.size)
                    if (ranked.isEmpty()) put("note", "no issue in Crashlytics shares this crash's exception or the app's frames (${all.size} issue(s) in $tf): not seen in the field, or reported under another name")
                    putJsonArray("issues") { ranked.take(limit).forEach { i -> addJsonObject { put("score", i.score); i.id?.let { put("id", it) }; put("issue", trim(i.json)) } } }
                }
            }
        }.encode())
    }

    tool(
        "lab_crashlytics_issue",
        """
        One Crashlytics issue (its id from lab_crashlytics_issues, e.g. firebase/abc123): metadata,
        app version / OS / device spread, a sample's stack trace, custom keys and logs. include
        narrows it (stack, distributions, metadata, logs, keys).
        """,
        str("issue_id", "the issue's id"),
        str("include", "all, or some of stack,distributions,metadata,logs,keys", "all"),
        str("sample_id", "a sample event's id; empty = a typical one", ""),
        str("timeframe", "24h | 7d | 30d | 60d | 90d; empty = the profile's", "", listOf("", "24h", "7d", "30d", "60d", "90d")),
        str("package", "which app; empty = production", ""),
        readOnly = true,
    ) { a ->
        val e = rt.crashlytics.issue(a.str("issue_id").trim(), a.strOrNull("package"), a.strOrNull("include"), a.strOrNull("timeframe"), a.strOrNull("sample_id"))
        text(e.encode().let { if (it.length > 60_000) it.take(60_000) + "\n… cut at 60 000 characters: narrow it with include" else it })
    }
}

/** An issue as the CLI gave it, with very long values (stack traces, logs) shortened for the list. */
private fun trim(e: JsonElement): JsonElement = when (e) {
    is JsonPrimitive -> if (e.isString && e.content.length > 400) JsonPrimitive(e.content.take(400) + "…") else e
    is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.take(20).map(::trim))
    is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(e.mapValues { trim(it.value) })
}
