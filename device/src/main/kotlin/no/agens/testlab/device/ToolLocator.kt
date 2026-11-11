package no.agens.testlab.device

import no.agens.testlab.AppIdentity

import java.io.File

/** The host operating systems ${AppIdentity.NAME} ships for. */
enum class HostOs {
    MAC, WINDOWS, LINUX;

    companion object {
        fun current(osName: String = System.getProperty("os.name").orEmpty()): HostOs {
            val n = osName.lowercase()
            return when {
                n.contains("mac") || n.contains("darwin") -> MAC
                n.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

/** A located external tool and where it was found, for `testlab tools`. */
data class FoundTool(val path: File, val source: String)

/**
 * Finds external tools: SDK environment variables first, then fixed
 * install locations, and only then PATH. GUI apps on macOS start with a minimal PATH
 * that lacks Homebrew, so PATH alone is not reliable there.
 */
class ToolLocator(
    private val os: HostOs = HostOs.current(),
    private val env: Map<String, String> = System.getenv(),
    private val home: File = File(System.getProperty("user.home")),
    private val isExecutable: (File) -> Boolean = { it.isFile && (it.canExecute() || os == HostOs.WINDOWS) },
    /** Folder the app downloads platform-tools into when the user asks for it. */
    private val appToolsDir: File? = null,
) {
    private fun exe(name: String) = if (os == HostOs.WINDOWS) "$name.exe" else name

    /** Candidate locations for adb in priority order, each with a human-readable source. */
    fun adbCandidates(): List<Pair<File, String>> {
        val adb = exe("adb")
        val out = mutableListOf<Pair<File, String>>()
        for (v in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            env[v]?.takeIf { it.isNotBlank() }?.let { out += File(it, "platform-tools/$adb") to "\$$v" }
        }
        when (os) {
            HostOs.MAC -> {
                out += File(home, "Library/Android/sdk/platform-tools/$adb") to "Android Studio SDK"
                out += File("/opt/homebrew/bin/$adb") to "Homebrew (Apple silicon)"
                out += File("/usr/local/bin/$adb") to "Homebrew (Intel)"
            }
            HostOs.WINDOWS -> {
                env["LOCALAPPDATA"]?.let { out += File(it, "Android/Sdk/platform-tools/$adb") to "Android Studio SDK" }
                env["LOCALAPPDATA"]?.let { local ->
                    File(local, "Microsoft/WinGet/Packages").listFiles()
                        ?.filter { it.name.startsWith("Google.PlatformTools") }
                        ?.sortedByDescending { it.name }
                        ?.forEach { out += File(it, "platform-tools/$adb") to "winget" }
                }
            }
            HostOs.LINUX -> {
                out += File(home, "Android/Sdk/platform-tools/$adb") to "Android Studio SDK"
                out += File("/usr/bin/$adb") to "system package"
                out += File("/usr/local/bin/$adb") to "/usr/local/bin"
            }
        }
        appToolsDir?.let { out += File(it, "platform-tools/$adb") to "downloaded by ${no.agens.testlab.AppIdentity.NAME}" }
        pathDirs().forEach { out += File(it, adb) to "PATH" }
        return out
    }

    fun findAdb(): FoundTool? = firstExisting(adbCandidates())

    /** scrcpy and ffmpeg are optional: they only enable the external recorder and video post-processing. */
    fun findOptional(name: String): FoundTool? {
        val file = exe(name)
        val fixed = when (os) {
            HostOs.MAC -> listOf(File("/opt/homebrew/bin/$file") to "Homebrew", File("/usr/local/bin/$file") to "Homebrew (Intel)")
            HostOs.LINUX -> listOf(File("/usr/bin/$file") to "system package", File("/usr/local/bin/$file") to "/usr/local/bin")
            HostOs.WINDOWS -> emptyList()
        }
        return firstExisting(fixed + pathDirs().map { File(it, file) to "PATH" })
    }

    /** Google's Android CLI (`android`): where its installer, Homebrew and apt put it, then PATH. */
    fun findAndroidCli(): FoundTool? {
        val file = exe("android")
        val fixed = buildList {
            if (os != HostOs.WINDOWS) add(File(home, ".local/bin/$file") to "Android CLI installer")
            when (os) {
                HostOs.MAC -> { add(File("/opt/homebrew/bin/$file") to "Homebrew"); add(File("/usr/local/bin/$file") to "/usr/local/bin") }
                HostOs.LINUX -> { add(File("/usr/bin/$file") to "system package"); add(File("/usr/local/bin/$file") to "/usr/local/bin") }
                HostOs.WINDOWS -> env["LOCALAPPDATA"]?.let { add(File(it, "Android/cli/$file") to "Android CLI installer") }
            }
        }
        return firstExisting(fixed + pathDirs().map { File(it, file) to "PATH" })
    }

    private fun pathDirs(): List<String> =
        env["PATH"].orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }

    private fun firstExisting(candidates: List<Pair<File, String>>): FoundTool? =
        candidates.firstOrNull { isExecutable(it.first) }?.let { FoundTool(it.first, it.second) }
}
