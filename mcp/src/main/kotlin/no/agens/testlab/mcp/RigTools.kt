package no.agens.testlab.mcp

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.agens.testlab.mcp.Param.Companion.SERIAL
import no.agens.testlab.mcp.Param.Companion.bool
import no.agens.testlab.mcp.Param.Companion.int
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.Param.Companion.strings
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.runtime.RigHealth

/** The rig's health from the phone, the phone's settings across a session, and its logs. */
internal fun ToolBox.rigTools(rt: LabRuntime) {
    tool(
        "lab_health",
        """
        Is the rig working, measured from the PHONE: adb, SIM PIN and lock screen, the active network,
        the proxy the phone uses (vs what Testlab set), whether the phone can reach that proxy and get
        an answer through it, Proxyman forwarding, its rules (mocked traffic is not real evidence), the
        SSL exclude list (Google hosts must stay excluded), whether the phone trusts Proxyman's CA, the
        app's builds on the phone (version, commit, disabled siblings), leftover settings (font size,
        TalkBack, Don't keep activities, animations, analytics debug), and runaway recordings. Each
        check: pass, warn or fail, with the fix. Run it at the start of a session and whenever the app
        shows a generic error («something went wrong»): a broken proxy or Wi-Fi looks like a backend
        failure. quick=true skips the app and settings checks.
        """,
        bool("quick", "only the connection checks", false),
        SERIAL,
        readOnly = true,
    ) { a ->
        val serial = rt.serialFor(a.serial)
        val checks = rt.rigHealth.run(serial, full = !a.bool("quick"))
        text(healthJson(serial, checks).encode())
    }

    tool(
        "lab_device_state",
        """
        The phone's test-relevant settings across a session (Android): snapshot saves them (font scale,
        dark mode, TalkBack and accessibility services, Don't keep activities, navigation mode,
        rotation, stay-awake, animation scales, show taps, demo mode, the global proxy, Firebase
        analytics debug properties, disabled packages); diff lists what changed since; restore puts the
        changed ones back. lab_session_start takes the snapshot, and lab_report_write lists what differs
        in the report. Run restore at the end of a round (rig-cleanup), after the report's screenshots.
        """,
        str("action", "snapshot | diff | restore", "diff", listOf("snapshot", "diff", "restore")),
        SERIAL,
    ) { a ->
        val serial = rt.serialFor(a.serial)
        if (rt.platformOf(serial) != no.agens.testlab.core.Platform.ANDROID) throw BadArgument("lab_device_state is for Android phones and emulators")
        when (a.str("action")) {
            "snapshot" -> {
                val s = rt.deviceState.snapshot(serial)
                text("snapshot of $serial at ${s.takenAt}: " + s.values.entries.joinToString(", ") { "${it.key}=${it.value ?: "unset"}" } +
                    (if (s.disabledPackages.isNotEmpty()) "; disabled: ${s.disabledPackages.joinToString()}" else ""))
            }
            "restore" -> {
                rt.confirmWrite("Restore phone settings", "Put $serial's settings back as they were at the session's start?")
                text(rt.deviceState.restore(serial).joinToString("\n"))
            }
            else -> {
                val (base, changes) = rt.deviceState.diff(serial)
                text(when {
                    base == null -> "no snapshot of $serial yet: lab_device_state(action=snapshot), or lab_session_start, takes one"
                    changes.isEmpty() -> "as at ${base.takenAt}: nothing changed"
                    else -> "changed since ${base.takenAt}:\n" + changes.joinToString("\n") { "- ${it.label}: ${it.was ?: "unset"} → ${it.now ?: "unset"}" } + "\nlab_device_state(action=restore) puts them back."
                })
            }
        }
    }

    tool(
        "lab_logs_start",
        """
        Start capturing the device's log to a file (Android logcat; on an iOS Simulator its log stream).
        Start BEFORE reproducing: a crash's stack trace or an analytics event that happened earlier is
        not captured. preset: all, app (only the app's own process: package), crash (AndroidRuntime,
        native crashes), analytics (Firebase Analytics' FA/FA-SVC lines). tags adds TAG:LEVEL filters
        (the app's own analytics or logging tags, which the project's skills name); with preset all, tags
        alone means only those. firebase_debug turns on Firebase Analytics debug logging for the
        package until the capture stops (each event is logged as it is sent). One capture per device.
        """,
        str("preset", "all | app | crash | analytics", "app", listOf("all", "app", "crash", "analytics")),
        strings("tags", "extra logcat filters, e.g. [\"MyTracker:V\"]"),
        bool("firebase_debug", "Firebase Analytics debug logging for the package while capturing", false),
        str("package", "the app's package; empty = the profile's", ""),
        str("process", "iOS Simulator: the app's process name for the log predicate", ""),
        SERIAL,
    ) { a ->
        val serial = rt.serialFor(a.serial)
        val pkg = a.strOrNull("package") ?: rt.defaultPackage(serial).ifBlank { null }
        val c = rt.logs.start(serial, a.str("preset"), pkg, a.strings("tags"), a.bool("firebase_debug"), a.strOrNull("process"))
        text("capturing ${c.preset} log of $serial to ${c.file.name}" + (if (c.firebase) " (Firebase debug on for ${c.pkg})" else "") +
            ". Reproduce now; lab_logs_read shows lines while it runs, lab_logs_stop ends it (and attaches it to a report).")
    }

    tool(
        "lab_logs_stop",
        """
        Stop the device's log capture: lines captured, crashes found (a recorder's own crash, scrcpy's,
        is marked as the rig's, not the app's), analytics events. With report_id the log is copied into
        the report folder and the result gives the Markdown link to put in lab_report_write.
        """,
        str("report_id", "a report to attach the log to", ""),
        SERIAL,
    ) { a ->
        val serial = rt.serialFor(a.serial)
        val s = rt.logs.stop(serial) ?: return@tool text("no log capture running on $serial")
        val link = a.strOrNull("report_id")?.let { id ->
            val dest = java.io.File(rt.reports.folder(id), "logs/${s.file.name}")
            dest.parentFile.mkdirs(); s.file.copyTo(dest, overwrite = true)
            "\nAttached; put this in the report: [log: ${s.file.name}](logs/${s.file.name})"
        }.orEmpty()
        text(buildString {
            append("stopped after %.0f s: %d line(s), %d analytics event(s)".format(s.seconds, s.lines, s.events))
            if (s.crashes.isEmpty()) append(", no crashes") else {
                append(", ${s.crashes.size} crash(es):")
                s.crashes.forEach { c -> append("\n- ${c.time} ${c.process}: ${c.first}" + if (c.recorder) " (the screen recorder's, not the app's)" else "") }
                if (s.crashes.any { !it.recorder } && rt.profile.crashlytics.apps.isNotEmpty())
                    append("\nWhether real users get it too: lab_crashlytics_issues(from_log=true) looks it up in Crashlytics.")
            }
            append("\nfile: ${s.file.path}")
            append(link)
        })
    }

    tool(
        "lab_logs_read",
        "Lines of the device's log capture (running, or the last one): the last `limit` lines, optionally only those matching `grep` (a regex, case-insensitive). Tokens are masked.",
        str("grep", "regex to keep matching lines", ""),
        int("limit", "at most this many lines (the newest)", 200),
        SERIAL,
        readOnly = true,
    ) { a ->
        val serial = rt.serialFor(a.serial)
        val lines = rt.logs.read(serial, a.strOrNull("grep"), a.int("limit").coerceIn(1, 2000))
        text(if (lines.isEmpty()) "(no lines: lab_logs_start begins a capture)" else lines.joinToString("\n"))
    }

    tool(
        "lab_analytics_events",
        """
        The analytics events in the device's log capture, in order: time, event name, parameters. By
        default Firebase Analytics' own debug lines (capture with preset analytics and firebase_debug,
        so each event is logged as it is sent). pattern reads the app's own analytics log line instead: a
        regex with the named groups name and params. Report the exact event names and parameters, and
        how many were sent.
        """,
        str("pattern", "regex with (?<name>…) and (?<params>…) for the app's own analytics lines", ""),
        str("name", "keep only events whose name contains this", ""),
        SERIAL,
        readOnly = true,
    ) { a ->
        val serial = rt.serialFor(a.serial)
        val f = rt.logs.file(serial) ?: return@tool text("no log capture for $serial yet: lab_logs_start(preset=analytics, firebase_debug=true) before reproducing")
        val pattern = a.strOrNull("pattern")?.let { runCatching { Regex(it) }.getOrElse { e -> throw BadArgument("pattern: ${e.message}") } }
        val events = no.agens.testlab.services.logs.LogCapture.events(f.readText(), pattern).filter { e -> a.strOrNull("name")?.let { it.lowercase() in e.name.lowercase() } ?: true }
        text(buildJsonObject {
            put("file", f.name); put("count", events.size)
            putJsonArray("events") { events.take(300).forEach { e -> addJsonObject { put("time", e.time); put("name", e.name); put("params", rt.redactor.apply(e.params).take(600)) } } }
        }.encode())
    }
}

internal fun healthJson(serial: String, checks: List<RigHealth.Check>) = buildJsonObject {
    put("serial", serial)
    put("verdict", when {
        checks.any { it.level == RigHealth.Level.FAIL } -> "fail"
        checks.any { it.level == RigHealth.Level.WARN } -> "warn"
        else -> "pass"
    })
    putJsonArray("checks") {
        for (c in checks) addJsonObject {
            put("check", c.name); put("result", c.level.name.lowercase()); put("value", c.value)
            c.fix?.let { put("fix", it) }
        }
    }
}

/** One line per failed or warned check, for lab_session_start. */
internal fun healthSummary(checks: List<RigHealth.Check>): String {
    val bad = checks.filter { it.level == RigHealth.Level.FAIL || it.level == RigHealth.Level.WARN }
    if (bad.isEmpty()) return "rig health: all ${checks.count { it.level == RigHealth.Level.PASS }} checks pass"
    return "rig health: " + bad.joinToString("; ") { "${it.level.name.lowercase()} ${it.name}: ${it.value}" + (it.fix?.let { f -> " → $f" } ?: "") }
}

