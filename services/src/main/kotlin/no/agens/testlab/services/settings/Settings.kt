package no.agens.testlab.services.settings

import no.agens.testlab.services.runtime.Approvals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.agens.testlab.AppIdentity
import no.agens.testlab.core.jev.Backend
import no.agens.testlab.core.jev.JevConfig
import java.io.File

/** Everything that is not a secret. Secrets live in the vault (see SecretStore). */
@Serializable
data class Settings(
    /** The active profile (see ProfileStore): the project's repo, builds, Jira, app and destinations. */
    val profile: String? = null,
    /** The active profile of each other platform, by platform id (`ios`); Android's is [profile]. */
    val platformProfiles: Map<String, String> = emptyMap(),
    /** Per-machine connection details that a profile can't know. */
    val connections: Connections = Connections(),
    val jev: Jev = Jev(),
    val mcp: Mcp = Mcp(),
    val device: Device = Device(),
    val proxyman: Proxyman = Proxyman(),
    val claude: Claude = Claude(),
    /** Test accounts by name; their fields are vault entries `account.<name>.<field>`. Null = the profile's defaults. */
    val accounts: List<String>? = null,
    /** What each test account is for («iOS, 500 kr on the account»), by name: not secret, shown to the agent. */
    val accountNotes: Map<String, String> = emptyMap(),
    /** Where plans and reports go; null = the per-OS default (AppDirs.workspace). */
    val workspace: String? = null,
    /** Look for a newer release once a day (needs the GitHub token: the repo is private). */
    val checkUpdates: Boolean = true,
    /** While the server runs: its icon in the macOS menu bar (on Windows the taskbar's notification area). */
    val menuBarIcon: Boolean = true,
) {
    @Serializable
    data class Connections(
        /** Jira cloud ids by site, found when a scoped API token only works through api.atlassian.com/ex/jira/{cloudId}. */
        val jiraCloudIds: Map<String, String> = emptyMap(),
        /** REST base overrides by Jira site: a local fake in end-to-end tests. */
        val jiraApiUrls: Map<String, String> = emptyMap(),
        /** GitHub API base override (GitHub Enterprise, or a fake in tests); null = the profile's. */
        val githubApiUrl: String? = null,
    ) {
        fun jiraBase(site: String): String = jiraApiUrls[site]?.trimEnd('/') ?: jiraCloudIds[site]?.let { "https://api.atlassian.com/ex/jira/$it" } ?: "https://$site"
    }

    @Serializable
    data class Jev(
        val backend: String = "typesafe",
        /** Ollama System One endpoints tried in order, `url|model`. */
        val ollamaUrls: List<String> = listOf("http://localhost:11434/v1/systemone|nimble:9b"),
        val minConfidence: Double = 0.5,
        /** The model for the `openai` backend (OpenAI's Decisions API). */
        val openaiModel: String = "gpt-6-luna",
    ) {
        /** The decision settings with the profile's vocabulary. */
        fun toConfig(p: no.agens.testlab.core.profile.Profile.Jev = no.agens.testlab.core.profile.Profile.Jev()): JevConfig = JevConfig(
            backend = Backend.parse(backend), ollamaUrls = ollamaUrls, openaiModel = openaiModel, glossary = p.glossary, pickExample = p.pickExample, doneExample = p.doneExample,
            labelLanguage = p.labelLanguage, pickNote = p.pickNote, riskyWords = p.riskyWords, leaveWords = p.leaveWords,
        )
    }

    @Serializable
    data class Mcp(
        val enabled: Boolean = true,
        val port: Int = AppIdentity.MCP_DEFAULT_PORT,
        /** Jira writes and app uninstalls started over MCP wait for an in-app confirmation. */
        val confirmWrites: Boolean = true,
        /** The control API for host apps (`/api/v1` on the same port, same token; docs/control-api.yaml). */
        val controlApi: Boolean = true,
        /**
         * The address the server binds: 127.0.0.1 (this computer only), or e.g. 0.0.0.0 / a tailnet IP for
         * a shared phone rig. Anything but loopback also needs [allowedHosts] (docs/SERVICE.md).
         */
        val listen: String = "127.0.0.1",
        /** Host names clients may use besides localhost (the rig's LAN or tailnet name and IP). */
        val allowedHosts: List<String> = emptyList(),
        /** How long a write waits for a yes before it counts as a no. */
        val approvalTimeoutSeconds: Int = Approvals.DEFAULT_TIMEOUT_S,
    )

    @Serializable
    data class Device(
        /** Overrides the profile's default package on this computer (e.g. testing another flavour). */
        val packageOverride: String? = null,
        /** Risky (purchase-worded) taps are refused on the profile's production app unless this is on. */
        val allowRiskyOnProd: Boolean = false,
        /** Stop other automation tools that hold the accessibility connection when connecting. */
        val takeOver: Boolean = false,
        /**
         * The Apple developer team that signs WebDriverAgent for real iPhones (10 characters). Set, the
         * paired iPhones can be driven like simulators; unset, they take builds and app starts only.
         */
        val iosTeamId: String? = null,
        /** WebDriverAgent's bundle id on real iPhones; null = one made from the team id. */
        val iosWdaBundleId: String? = null,
    )

    /** The user's Claude Code (their own CLI and login). */
    @Serializable
    data class Claude(
        /** Path of the `claude` binary; null = the usual install locations and PATH. */
        val binary: String? = null,
        /** The app's checkout, where the team's own devtest command may live (it is named in the devtest prompt). Null = none. */
        val workDir: String? = null,
    )

    /** The user's own Proxyman as the phones' HTTP proxy and its MCP tools (all opt-in). */
    @Serializable
    data class Proxyman(
        val enabled: Boolean = false,
        /** Proxyman's port (Proxyman → Settings → Port). */
        val port: Int = 9090,
        /** Proxy host the phone uses; null = 127.0.0.1 via adb reverse (USB, emulator) or this computer's LAN address (Wi-Fi/IP). */
        val host: String? = null,
        /** Path of Proxyman's bundled mcp-server, or the http(s) URL of a remote Proxyman's MCP bridge; null = the usual install locations. */
        val mcpServer: String? = null,
        /** Mirror Proxyman's MCP tools as proxyman_* on this app's MCP server. */
        val mirrorTools: Boolean = true,
        /** lab_report_write attaches the captured flows as flows.har with a request table. */
        val attachFlows: Boolean = true,
        /** lab_report_write deletes the session's own Proxyman rules and checks they are gone (others' rules stay). */
        val cleanupOnReport: Boolean = true,
    )

}

/**
 * settings.json in the data folder; unknown fields are kept tolerant across versions. Several processes
 * share it (the background service, `serve`, Claude Desktop's stdio server, the CLI): a change made
 * by another one is picked up within a couple of seconds ([current] checks the file, a watcher pushes it
 * to [settings]), and every [update] starts from what is on disk, so no process writes back a stale copy.
 */
class SettingsStore(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var stamp: List<Any?>? = stampOf()
    private val state = MutableStateFlow(load())
    val settings: StateFlow<Settings> = state.asStateFlow()
    val current: Settings get() { refresh(); return state.value }

    private fun load(): Settings = try {
        if (file.isFile) json.decodeFromString(Settings.serializer(), file.readText()) else Settings()
    } catch (e: Exception) {
        System.err.println("[settings] ${file.path} unreadable (${e.message}); using defaults")
        Settings()
    }

    private fun stampOf(): List<Any?>? = if (!file.isFile) null else runCatching {
        val a = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        listOf(a.fileKey(), a.lastModifiedTime().toMillis(), a.size())
    }.getOrNull()

    /** Re-reads the file when another process replaced it since we last read or wrote it. */
    @Synchronized
    fun refresh() {
        val now = stampOf()
        if (now == stamp) return
        stamp = now
        val loaded = load()
        if (loaded != state.value) state.value = loaded
    }

    @Synchronized
    fun update(change: (Settings) -> Settings) {
        refresh()
        val next = change(state.value)
        if (next == state.value) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Settings.serializer(), next))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        stamp = stampOf()
        state.value = next
    }

    init {
        // Screens and the MCP server follow [settings]; a change from another process reaches them too.
        Thread({
            while (true) {
                try { Thread.sleep(2_000); refresh() } catch (e: InterruptedException) { return@Thread } catch (_: Exception) {}
            }
        }, "settings-watch").apply { isDaemon = true }.start()
    }
}
