package no.agens.testlab.cli

import no.agens.testlab.AppIdentity

/**
 * The CLI's help, from the one usage text: a short overview by task (`testlab`, `testlab help`), one
 * command's options (`testlab help install`, `testlab install --help`), everything (`testlab help --all`),
 * and "did you mean" for a mistyped command instead of the whole text.
 */
internal object Help {
    /** One command's lines in the usage text. */
    data class Entry(val name: String, val lines: List<String>) {
        /** What it does: the text after the synopsis on its first line, else on the next one. */
    }

    /** The overview: by task, one plain line each (the usage text has the options). */
    private val GROUPS = listOf(
        "Get started" to listOf(
            "setup" to "set everything up, step by step (safe to run again)",
            "space" to "one space per client: its own keys, settings, reports and port",
            "open" to "pages in the browser: setup, devices, the phone live, reports, approvals, the guide …",
            "status" to "what is set up and what is missing, with what to do",
            "secrets" to "set the GitHub and Jira tokens, test accounts and the device PIN",
            "repos" to "the repositories your GitHub token can read",
            "profile" to "set up, switch, import and export project profiles",
            "config" to "read and change settings",
            "upgrade" to "install a newer build (or --rollback)",
        ),
        "The phone" to listOf(
            "devices" to "phones and emulators adb can see",
            "emulator" to "make, start and stop Android emulators",
            "simulator" to "make, start and stop iPhone simulators (Mac)",
            "connect" to "start ${AppIdentity.NAME}'s helper on a phone",
            "ui" to "what is on the screen, as text",
            "tap" to "tap a label, #id or x y",
            "type" to "type into the focused field",
            "talkback" to "TalkBack status, on, off",
            "record" to "record the screen to MP4",
        ),
        "Builds and tests" to listOf(
            "builds" to "the newest CI build per branch",
            "install" to "install a PR, branch, release or APK",
            "plan" to "run a saved plan",
            "pick" to "ask the autopilot which element to tap (no tap)",
            "devtest" to "tickets waiting for a devtest, and their PRs",
            "jira" to "read Jira tickets and searches",
        ),
        "Claude and the background service" to listOf(
            "mcp-config" to "how to connect Claude Code, Claude Desktop and Codex",
            "claude-plugin" to "the phone and reports as panes in Claude Code",
            "service" to "run the server in the background (install, start, stop, status)",
            "serve" to "run the server in this terminal",
            "approvals" to "writes waiting for your yes",
            "approve" to "approve (or deny) a waiting write",
        ),
        "Help and support" to listOf(
            "diag" to "a diagnostics zip to send when something is wrong",
            "dirs" to "where ${AppIdentity.NAME} keeps its files",
            "tools" to "where adb, scrcpy and ffmpeg were found",
            "android-cli" to "Google's Android CLI: install, sign in (for Crashlytics)",
            "xcode" to "Xcode's MCP server for iOS crash reports: status, enable, approve",
            "version" to "print the version",
        ),
    )

    fun entries(usage: String): List<Entry> {
        val out = mutableListOf<Entry>()
        for (line in usage.lines()) {
            val m = Regex("^  (--)?([a-z][a-z-]*)").find(line)
            if (m != null && !line.startsWith("   ")) {
                val name = m.groupValues[2]
                val prev = out.lastOrNull()
                // Several lines for one command (profile list…, profile init…) make one entry.
                if (prev?.name == name) out[out.lastIndex] = prev.copy(lines = prev.lines + line) else out += Entry(name, listOf(line))
            } else if (line.startsWith("   ") && out.isNotEmpty()) {
                out[out.lastIndex] = out.last().let { it.copy(lines = it.lines + line) }
            }
        }
        return out
    }

    fun overview(): String = buildString {
        appendLine("${AppIdentity.SLUG} — ${AppIdentity.NAME}: drive the phone, install builds, run the server for Claude.")
        for ((group, cmds) in GROUPS) {
            appendLine()
            appendLine(group)
            for ((n, what) in cmds) appendLine("  " + n.padEnd(15) + what)
        }
        appendLine()
        appendLine("${AppIdentity.SLUG} help COMMAND    the options of one command (or: ${AppIdentity.SLUG} COMMAND --help)")
        appendLine("${AppIdentity.SLUG} help --all      everything at once")
        append("Something not working? ${AppIdentity.SLUG} status")
    }

    fun command(usage: String, name: String): String? = entries(usage).firstOrNull { it.name == name }?.lines?.joinToString("\n") { it.removePrefix("  ") }

    /** Known commands close to [typed]: a typo (edit distance ≤ 2) or a prefix. */
    fun suggestions(usage: String, typed: String): List<String> {
        val names = entries(usage).map { it.name }.distinct()
        return names.filter { it.startsWith(typed) || typed.startsWith(it) && typed.length - it.length <= 2 || distance(it, typed) <= 2 }.take(3)
    }

    fun unknown(usage: String, typed: String): String {
        val s = suggestions(usage, typed)
        return "unknown command: $typed" + (if (s.isNotEmpty()) "\nDid you mean: ${s.joinToString(", ") { "${AppIdentity.SLUG} $it" }}?" else "") +
            "\nAll commands: ${AppIdentity.SLUG} help" +
            // An older install lacks commands the docs mention.
            "\n(${AppIdentity.NAME} ${AppIdentity.VERSION}, ${AppIdentity.BUILD}: a command from newer docs may need ${AppIdentity.SLUG} upgrade, or a manual update the first time.)"
    }

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }
}
