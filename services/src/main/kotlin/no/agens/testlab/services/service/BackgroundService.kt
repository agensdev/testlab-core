package no.agens.testlab.services.service

import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `testlab serve` as a background service, so Claude Code, host apps and the web pages reach the
 * phones from login on:
 * - Linux: a systemd unit, per user (`~/.config/systemd/user`) or for the whole machine (`--system`,
 *   a shared phone rig, with `User=`)
 * - macOS: a launchd agent in `~/Library/LaunchAgents` (the login keychain stays reachable)
 * - Windows: a scheduled task at logon (a real Windows service would run as SYSTEM, without the
 *   user's Credential Manager or adb keys)
 * The unit files are built by pure functions ([systemdUnit], [launchdPlist], [windowsTaskXml]) so
 * every OS is tested everywhere; [run] executes the OS commands.
 */
class BackgroundService(
    private val os: HostOs = HostOs.current(),
    private val home: File = File(System.getProperty("user.home")),
    private val run: (List<String>) -> Result = ::exec,
) {
    /** What the service runs: the launcher and its arguments (ending in `serve`), and where its log goes. */
    data class Spec(
        val command: List<String>,
        val logFile: File,
        val workDir: File? = null,
        val env: Map<String, String> = emptyMap(),
        /** Linux `--system`: the account it runs as. */
        val user: String? = null,
        /** Linux: a file holding the vault passphrase, handed over with systemd's LoadCredential=. */
        val passphraseFile: File? = null,
    )

    data class Result(val code: Int, val out: String)

    enum class State { NOT_INSTALLED, STOPPED, RUNNING }

    data class Status(val state: State, val where: String, val detail: String = "")

    /** Where the unit / plist / task definition lives (the task's XML is kept for `status`). */
    fun unitFile(system: Boolean = false): File = when (os) {
        HostOs.LINUX -> if (system) File("/etc/systemd/system/$UNIT") else File(home, ".config/systemd/user/$UNIT")
        HostOs.MAC -> File(home, "Library/LaunchAgents/$LABEL.plist")
        HostOs.WINDOWS -> File(home, "AppData/Local/${AppIdentity.NAME}/service-task${no.agens.testlab.services.Spaces.suffix()}.xml")
    }

    fun install(spec: Spec, system: Boolean = false): String {
        require(spec.command.isNotEmpty()) { "no command to run" }
        require(system || spec.user == null) { "--user only applies with --system" }
        require(os == HostOs.LINUX || !system) { "--system is for Linux (systemd); on ${os.name.lowercase()} the service runs in your login session" }
        val f = unitFile(system)
        f.parentFile.mkdirs()
        spec.logFile.parentFile?.mkdirs()
        when (os) {
            HostOs.LINUX -> {
                f.writeText(systemdUnit(spec, system))
                check(systemctl(system, "daemon-reload"))
                check(systemctl(system, "enable", "--now", UNIT))
            }
            HostOs.MAC -> {
                runCatching { run(listOf("launchctl", "bootout", "${guiDomain()}/$LABEL")) }
                f.writeText(launchdPlist(spec))
                check(run(listOf("launchctl", "bootstrap", guiDomain(), f.path)))
            }
            HostOs.WINDOWS -> {
                // schtasks reads the XML as UTF-16 (with a BOM); UTF-8 files are refused on some versions.
                f.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + windowsTaskXml(spec, windowsUser()).toByteArray(Charsets.UTF_16LE))
                check(run(listOf("schtasks", "/Create", "/F", "/TN", TASK, "/XML", f.path)))
                check(run(listOf("schtasks", "/Run", "/TN", TASK)))
            }
        }
        return f.path
    }

    fun uninstall(system: Boolean = false): Boolean {
        val f = unitFile(system)
        when (os) {
            HostOs.LINUX -> { systemctl(system, "disable", "--now", UNIT); val had = f.delete(); systemctl(system, "daemon-reload"); return had }
            HostOs.MAC -> { run(listOf("launchctl", "bootout", "${guiDomain()}/$LABEL")); return f.delete() }
            HostOs.WINDOWS -> { run(listOf("schtasks", "/End", "/TN", TASK)); val r = run(listOf("schtasks", "/Delete", "/F", "/TN", TASK)); f.delete(); return r.code == 0 }
        }
    }

    fun start(system: Boolean = false): Result = when (os) {
        HostOs.LINUX -> run(systemctlCmd(system, "start", UNIT))
        HostOs.MAC -> run(listOf("launchctl", "kickstart", "${guiDomain()}/$LABEL"))
        HostOs.WINDOWS -> run(listOf("schtasks", "/Run", "/TN", TASK))
    }

    fun stop(system: Boolean = false): Result = when (os) {
        HostOs.LINUX -> run(systemctlCmd(system, "stop", UNIT))
        // kill rather than bootout: the agent stays installed and starts again at the next login.
        HostOs.MAC -> run(listOf("launchctl", "kill", "SIGTERM", "${guiDomain()}/$LABEL"))
        HostOs.WINDOWS -> run(listOf("schtasks", "/End", "/TN", TASK))
    }

    /**
     * When the installed service starts by itself after a restart of the computer: macOS and Windows at
     * your login; Linux `--system` at boot; a Linux user unit at boot only with lingering on, else at login.
     */
    enum class Start(val words: String) { BOOT("at boot"), LOGIN("at login") }

    fun startsAt(system: Boolean = false): Start = when {
        os != HostOs.LINUX -> Start.LOGIN
        system -> Start.BOOT
        lingering() -> Start.BOOT
        else -> Start.LOGIN
    }

    /** Whether systemd keeps this user's services running without a login (`loginctl enable-linger`). */
    fun lingering(): Boolean = os == HostOs.LINUX &&
        run(listOf("loginctl", "show-user", System.getProperty("user.name"), "-p", "Linger", "--value")).out.trim() == "yes"

    /** Turns lingering on for this user; polkit usually allows it for one's own account, else it needs sudo. */
    fun enableLinger(): Result = run(listOf("loginctl", "enable-linger", System.getProperty("user.name")))

    fun status(system: Boolean = false): Status {
        val f = unitFile(system)
        return when (os) {
            HostOs.LINUX -> {
                if (!f.isFile) return Status(State.NOT_INSTALLED, f.path)
                val r = run(systemctlCmd(system, "is-active", UNIT))
                Status(if (r.out.trim() == "active") State.RUNNING else State.STOPPED, f.path, r.out.trim())
            }
            HostOs.MAC -> {
                if (!f.isFile) return Status(State.NOT_INSTALLED, f.path)
                val r = run(listOf("launchctl", "print", "${guiDomain()}/$LABEL"))
                val st = Regex("""^\s*state = (\S+)""", RegexOption.MULTILINE).find(r.out)?.groupValues?.get(1)
                Status(if (st == "running") State.RUNNING else State.STOPPED, f.path, st ?: r.out.lineSequence().firstOrNull().orEmpty())
            }
            HostOs.WINDOWS -> {
                val r = run(listOf("schtasks", "/Query", "/TN", TASK, "/FO", "LIST"))
                if (r.code != 0) return Status(State.NOT_INSTALLED, TASK)
                val st = Regex("""(?im)^Status:\s*(.+)$""").find(r.out)?.groupValues?.get(1)?.trim().orEmpty()
                Status(if (st.equals("Running", ignoreCase = true)) State.RUNNING else State.STOPPED, TASK, st)
            }
        }
    }

    /** What to run to follow the log. */
    fun logsCommand(spec: Spec, system: Boolean = false): String = when (os) {
        HostOs.LINUX -> if (system) "journalctl -u $UNIT -f   (also logs/ in the service account's data folder)" else "journalctl --user -u $UNIT -f   (also ${spec.logFile.parentFile.path})"
        HostOs.MAC -> "tail -f \"${spec.logFile.path}\""
        HostOs.WINDOWS -> "Get-Content -Wait \"${spec.logFile.path}\""
    }

    private fun systemctlCmd(system: Boolean, vararg a: String) = listOf("systemctl") + (if (system) emptyList() else listOf("--user")) + a
    private fun systemctl(system: Boolean, vararg a: String) = run(systemctlCmd(system, *a))
    private fun check(r: Result): Result {
        if (r.code != 0) throw IllegalStateException(r.out.trim().ifEmpty { "exit ${r.code}" })
        return r
    }
    private fun guiDomain() = "gui/" + (run(listOf("id", "-u")).out.trim().ifEmpty { "501" })
    private fun windowsUser(): String = (System.getenv("USERDOMAIN")?.let { "$it\\" } ?: "") + System.getProperty("user.name")

    companion object {
        // Per space, so two clients' servers run side by side: testlab-acme.service, ….service.acme, "Testlab service (acme)".
        val UNIT get() = "${AppIdentity.SLUG}${no.agens.testlab.services.Spaces.suffix()}.service"
        val LABEL get() = "${AppIdentity.ID}.service" + no.agens.testlab.services.Spaces.current().let { if (it == no.agens.testlab.services.Spaces.DEFAULT) "" else ".$it" }
        val TASK get() = "${AppIdentity.NAME} service" + no.agens.testlab.services.Spaces.current().let { if (it == no.agens.testlab.services.Spaces.DEFAULT) "" else " ($it)" }
        const val DESCRIPTION_SUFFIX = "MCP server and control API for Android test phones (headless)"

        /**
         * How the OS starts this program again: the CLI distribution's launcher (`testlab.home` is set by
         * its `bin/` scripts; on Windows `javaw`, so no console window opens at logon), a native app
         * launcher (`Testlab serve`), or the JVM with this classpath and [mainClass] (development).
         */
        fun selfCommand(mainClass: String, os: HostOs = HostOs.current(), console: Boolean = false): List<String> {
            val windows = os == HostOs.WINDOWS
            // A service wants javaw (no console window at logon); a stdio server (Claude Desktop) needs java.exe.
            val javaExe = if (console) "java.exe" else "javaw.exe"
            System.getProperty("testlab.home")?.let { File(it).canonicalFile }?.let { home ->
                return if (windows) listOf(
                    File(home, "runtime\\bin\\$javaExe").path, "-Xmx1g", "-Dfile.encoding=UTF-8", "-Dtestlab.home=${home.path}",
                    "-cp", File(home, "lib").path + "\\*", mainClass,
                ) else listOf(File(home, "bin/${AppIdentity.SLUG}").path)
            }
            val cmd = ProcessHandle.current().info().command().orElse(null) ?: throw IllegalStateException("can't tell how this program was started")
            if (Regex("javaw?(\\.exe)?").matches(File(cmd).name)) {
                val java = if (windows) File(File(cmd).parentFile, javaExe).path else cmd
                return listOf(java, "-cp", System.getProperty("java.class.path"), mainClass)
            }
            return listOf(cmd)
        }

        fun exec(cmd: List<String>): Result = try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); Result(124, out + "\n(timed out)") } else Result(p.exitValue(), out)
        } catch (e: java.io.IOException) {
            Result(127, "${cmd.first()}: ${e.message}")
        }

        /** systemd's quoting for ExecStart: double quotes, `\` and `"` escaped, `%` and `$` doubled. */
        fun systemdQuote(arg: String): String {
            val e = arg.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%").replace("$", "$$")
            return if (e.isEmpty() || e.any { it.isWhitespace() || it in "\"'\\;" } || e != arg) "\"$e\"" else e
        }

        fun systemdUnit(spec: Spec, system: Boolean): String = buildString {
            appendLine("# Written by `${AppIdentity.SLUG} service install`; `${AppIdentity.SLUG} service uninstall` removes it.")
            appendLine("[Unit]")
            appendLine("Description=${AppIdentity.NAME}: $DESCRIPTION_SUFFIX")
            appendLine("After=network-online.target")
            appendLine("Wants=network-online.target")
            appendLine()
            appendLine("[Service]")
            appendLine("Type=simple")
            appendLine("ExecStart=" + spec.command.joinToString(" ") { systemdQuote(it) })
            // A machine-wide unit runs as another account: its home, not the installer's folders.
            if (system && spec.user != null) appendLine("WorkingDirectory=~")
            else spec.workDir?.let { appendLine("WorkingDirectory=${systemdQuote(it.path)}") }
            if (system) spec.user?.let { appendLine("User=$it") }
            appendLine("Environment=TESTLAB_SERVICE=1")
            spec.env.toSortedMap().forEach { (k, v) -> appendLine("Environment=" + systemdQuote("$k=$v")) }
            spec.passphraseFile?.let { appendLine("LoadCredential=vault-passphrase:${it.path}") }
            // SIGTERM exits 0 (HeadlessServer); 143 too, for a stop that comes before the handler is set.
            appendLine("SuccessExitStatus=143")
            appendLine("Restart=on-failure")
            appendLine("RestartSec=5")
            // The adb server it may start belongs to the machine, not to this service: leave it running on stop.
            appendLine("KillMode=process")
            appendLine("NoNewPrivileges=yes")
            appendLine()
            appendLine("[Install]")
            appendLine("WantedBy=" + if (system) "multi-user.target" else "default.target")
        }

        private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun launchdPlist(spec: Spec): String = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
            appendLine("""<plist version="1.0">""")
            appendLine("<dict>")
            appendLine("  <key>Label</key><string>${xml(LABEL)}</string>")
            appendLine("  <key>ProgramArguments</key>")
            appendLine("  <array>")
            spec.command.forEach { appendLine("    <string>${xml(it)}</string>") }
            appendLine("  </array>")
            appendLine("  <key>RunAtLoad</key><true/>")
            // Restart after a crash, not after a clean stop (`service stop`).
            appendLine("  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>")
            appendLine("  <key>ThrottleInterval</key><integer>10</integer>")
            spec.workDir?.let { appendLine("  <key>WorkingDirectory</key><string>${xml(it.path)}</string>") }
            appendLine("  <key>StandardOutPath</key><string>${xml(spec.logFile.path)}</string>")
            appendLine("  <key>StandardErrorPath</key><string>${xml(spec.logFile.path)}</string>")
            // Interactive, not Background: the login keychain (the vault key) must stay reachable.
            appendLine("  <key>ProcessType</key><string>Interactive</string>")
            appendLine("  <key>EnvironmentVariables</key>")
            appendLine("  <dict>")
            // launchd's PATH lacks Homebrew, where scrcpy and ffmpeg usually are.
            (mapOf("PATH" to "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin", "TESTLAB_SERVICE" to "1") + spec.env).toSortedMap().forEach { (k, v) ->
                appendLine("    <key>${xml(k)}</key><string>${xml(v)}</string>")
            }
            appendLine("  </dict>")
            appendLine("</dict>")
            appendLine("</plist>")
        }

        /** Windows' CommandLineToArgvW quoting, for the task's <Arguments>. */
        fun windowsQuote(arg: String): String {
            if (arg.isNotEmpty() && arg.none { it.isWhitespace() || it == '"' }) return arg
            val sb = StringBuilder("\"")
            var slashes = 0
            for (c in arg) {
                when (c) {
                    '\\' -> { slashes++; continue }
                    '"' -> { sb.append("\\".repeat(slashes * 2 + 1)).append('"') }
                    else -> { sb.append("\\".repeat(slashes)).append(c) }
                }
                slashes = 0
            }
            return sb.append("\\".repeat(slashes * 2)).append('"').toString()
        }

        fun windowsTaskXml(spec: Spec, user: String): String = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-16"?>""")
            appendLine("""<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">""")
            appendLine("  <RegistrationInfo><Description>${xml("${AppIdentity.NAME}: $DESCRIPTION_SUFFIX")}</Description></RegistrationInfo>")
            appendLine("  <Triggers><LogonTrigger><Enabled>true</Enabled><UserId>${xml(user)}</UserId></LogonTrigger></Triggers>")
            appendLine("  <Principals><Principal id=\"Author\"><UserId>${xml(user)}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal></Principals>")
            appendLine("  <Settings>")
            appendLine("    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>")
            appendLine("    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>")
            appendLine("    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>")
            appendLine("    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>")
            appendLine("    <StartWhenAvailable>true</StartWhenAvailable>")
            appendLine("    <RestartOnFailure><Interval>PT1M</Interval><Count>999</Count></RestartOnFailure>")
            appendLine("  </Settings>")
            appendLine("  <Actions Context=\"Author\">")
            appendLine("    <Exec>")
            appendLine("      <Command>${xml(spec.command.first())}</Command>")
            if (spec.command.size > 1) appendLine("      <Arguments>${xml(spec.command.drop(1).joinToString(" ") { windowsQuote(it) })}</Arguments>")
            spec.workDir?.let { appendLine("      <WorkingDirectory>${xml(it.path)}</WorkingDirectory>") }
            appendLine("    </Exec>")
            appendLine("  </Actions>")
            appendLine("</Task>")
        }
    }
}
