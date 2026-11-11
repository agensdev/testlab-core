package no.agens.testlab.services.claude

import no.agens.testlab.device.HostOs
import java.io.File
import java.util.concurrent.TimeUnit

/** The user's own Claude Code CLI: where it is installed and which version (setup and status). */
object ClaudeCli {
    /** Where Claude Code usually lives; GUI apps can't rely on PATH. */
    fun candidates(os: HostOs = HostOs.current(), env: Map<String, String> = System.getenv(), home: File = File(System.getProperty("user.home"))): List<File> = buildList {
        val names = if (os == HostOs.WINDOWS) listOf("claude.exe", "claude.cmd") else listOf("claude")
        when (os) {
            HostOs.WINDOWS -> {
                add(File(home, ".local/bin/claude.exe"))
                env["APPDATA"]?.let { add(File(it, "npm/claude.cmd")) }
                env["LOCALAPPDATA"]?.let { add(File(it, "Programs/claude/claude.exe")) }
            }
            else -> {
                add(File(home, ".local/bin/claude"))
                add(File(home, ".claude/local/claude"))
                add(File("/opt/homebrew/bin/claude"))
                add(File("/usr/local/bin/claude"))
                add(File(home, ".npm-global/bin/claude"))
                add(File("/usr/bin/claude"))
            }
        }
        env["PATH"].orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.forEach { d -> names.forEach { add(File(d, it)) } }
    }

    fun locate(override: String?, os: HostOs = HostOs.current()): File? =
        override?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile } ?: candidates(os).firstOrNull { it.isFile && (os == HostOs.WINDOWS || it.canExecute()) }

    /** A PATH that finds node for an npm-installed claude, as a GUI-less service has a bare one. */
    private fun environment(binary: File, base: Map<String, String> = System.getenv()): Map<String, String> {
        val home = System.getProperty("user.home")
        val extra = listOf(binary.parent, "/opt/homebrew/bin", "/usr/local/bin", "$home/.local/bin", "$home/.volta/bin", "$home/.bun/bin")
        return base + ("PATH" to (base["PATH"].orEmpty().split(File.pathSeparatorChar) + extra).filter { it.isNotBlank() }.distinct().joinToString(File.pathSeparator))
    }

    fun version(binary: File): String? = runCatching {
        val out = File.createTempFile("tl-claude", ".out")
        try {
            val pb = ProcessBuilder(binary.path, "--version").redirectErrorStream(true).redirectOutput(out)
            pb.environment().putAll(environment(binary))
            val p = pb.start()
            if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else out.readText().trim().lineSequence().firstOrNull()
        } finally { out.delete() }
    }.getOrNull()
}
