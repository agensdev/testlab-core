package no.agens.testlab.services.diag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import no.agens.testlab.services.claude.ClaudeCli
import no.agens.testlab.services.i18n.tr
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.service.BackgroundService
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Everything that decides whether ${AppIdentity.NAME} works, as a checklist: `testlab status` prints it, the
 * settings page shows the gaps with a link to where each is fixed. Read-only: it checks, it doesn't fix.
 */
class SetupStatus(private val rt: LabRuntime, private val diag: Diagnostics = Diagnostics.create()) {
    enum class State { OK, TODO, INFO }

    /** Where it is fixed (the settings page's sections, the profiles and devices pages). */
    enum class Place { PROJECT, GITHUB, JIRA, ACCOUNTS, AUTOPILOT, PHONES, PROXYMAN, CLAUDE, SERVICE, UPDATES }

    enum class Group(val title: String) { PROJECT("Project"), KEYS("Keys"), PHONES("Phones"), CLAUDE("Claude"), PROXYMAN("Proxyman"), APP("App") }

    data class Item(
        val group: Group,
        val label: String,
        val state: State,
        val text: String,
        /** What to run in a terminal. */
        val cli: String? = null,
        val place: Place? = null,
        /** A page that fixes it (a token page, an SSO authorization). */
        val url: String? = null,
        /** False for what only some work needs (Jira for devtests, Proxyman, Claude). */
        val essential: Boolean = true,
    )

    /** [online] = check the GitHub and Jira tokens against the services (10 s each at most). */
    suspend fun check(online: Boolean = true): List<Item> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Item>()
        val slug = AppIdentity.SLUG
        val p = rt.profile

        rt.updates.cached()?.let { u -> out += Item(Group.APP, tr("update"), State.TODO, tr("{0} is available", u.version), "$slug upgrade", Place.UPDATES, essential = false) }

        // The project.
        if (p.github.repo.isBlank()) out += Item(Group.PROJECT, tr("project"), State.TODO, tr("no repository: set up a project for your app"), "$slug repos; $slug profile init --repo OWNER/NAME", Place.PROJECT)
        else out += Item(Group.PROJECT, tr("project"), State.OK, "${p.name} · ${p.github.repo} · " +
            (if (p.builds.isEmpty()) tr("no build types") else tr("{0} build type(s)", p.builds.size)))

        // Keys.
        val keys = runCatching { rt.secrets.keys() }.getOrElse { e ->
            out += Item(Group.KEYS, tr("vault"), State.TODO, e.message ?: tr("can't be opened"), "set TESTLAB_VAULT_PASSPHRASE, or run in a terminal")
            emptySet()
        }
        val gh = rt.githubToken()
        out += when {
            gh.isNullOrBlank() -> Item(Group.KEYS, "GitHub", State.TODO, tr("no token"), "$slug secrets set github.token", Place.GITHUB, Diagnostics.TOKEN_PAGE)
            !online || p.github.repo.isBlank() -> Item(Group.KEYS, "GitHub", State.OK, tr("token set"))
            else -> when (val c = withTimeoutOrNull(10_000) { diag.github(gh, p.github.owner, p.github.repo, rt.githubApi()) }) {
                is Check.Ok -> Item(Group.KEYS, "GitHub", State.OK, c.message)
                is Check.Problem -> Item(Group.KEYS, "GitHub", State.TODO, c.message, "$slug secrets set github.token", Place.GITHUB, c.fixUrl)
                is Check.Unreachable -> Item(Group.KEYS, "GitHub", State.TODO, tr("token set, but GitHub can't be reached: {0}", c.message))
                null -> Item(Group.KEYS, "GitHub", State.TODO, tr("GitHub didn't answer in 10 s"))
            }
        }
        if (p.tracker.site.isNotBlank()) {
            val s0 = rt.settings.current
            val email = rt.jiraEmail(); val token = rt.jiraToken()
            out += when {
                email.isNullOrBlank() || token.isNullOrBlank() -> Item(Group.KEYS, "Jira", State.TODO, tr("e-mail or API token missing (needed for devtests)"), "$slug secrets set jira.email; $slug secrets set jira.token", Place.JIRA, essential = false)
                !online -> Item(Group.KEYS, "Jira", State.OK, tr("e-mail and token set"))
                else -> when (val c = withTimeoutOrNull(10_000) { diag.jira(p.tracker.site, email, token, s0.connections.jiraBase(p.tracker.site)) }) {
                    is Check.Ok -> Item(Group.KEYS, "Jira", State.OK, c.message)
                    is Check.Problem -> Item(Group.KEYS, "Jira", State.TODO, c.message, "$slug secrets set jira.token", Place.JIRA, essential = false)
                    is Check.Unreachable -> Item(Group.KEYS, "Jira", State.TODO, tr("token set, but Jira can't be reached: {0}", c.message), essential = false)
                    null -> Item(Group.KEYS, "Jira", State.TODO, tr("Jira didn't answer in 10 s"), essential = false)
                }
            }
        }
        val s = rt.settings.current
        if (s.jev.backend == "typesafe") out += if (keys.contains(no.agens.testlab.services.secrets.SecretStore.JEV_KEY) || System.getenv("TYPESAFE_API_KEY") != null)
            Item(Group.KEYS, tr("autopilot"), State.OK, tr("TypeSafe key set"))
        else Item(Group.KEYS, tr("autopilot"), State.TODO, tr("no TypeSafe key: the autopilot can't decide (or choose Ollama or OpenAI)"), "$slug secrets set jev.key", Place.AUTOPILOT, essential = false)
        if (s.jev.backend == "openai") out += if (keys.contains(no.agens.testlab.services.secrets.SecretStore.OPENAI_KEY) || System.getenv("OPENAI_API_KEY") != null)
            Item(Group.KEYS, tr("autopilot"), State.OK, tr("OpenAI key set (Decisions API, {0})", s.jev.openaiModel))
        else Item(Group.KEYS, tr("autopilot"), State.TODO, tr("no OpenAI key: the autopilot can't decide"), "$slug secrets set openai.key", Place.AUTOPILOT, no.agens.testlab.services.diag.Diagnostics.OPENAI_KEY_PAGE, essential = false)
        val accounts = keys.filter { it.startsWith("account.") }.map { it.split('.')[1] }.distinct()
        if (p.accounts.loginPlan != null) out += if (accounts.isEmpty())
            Item(Group.KEYS, tr("test accounts"), State.TODO, tr("none yet: logins need one"), "$slug secrets set account.NAME.FIELD", Place.ACCOUNTS, essential = false)
        else Item(Group.KEYS, tr("test accounts"), State.OK, accounts.joinToString())

        // Phones.
        val adb = rt.locateAdb()
        if (adb == null) out += Item(Group.PHONES, "adb", State.TODO, tr("not found"), "$slug tools --download-adb --accept-android-sdk-terms", Place.PHONES)
        else {
            val devices = runCatching { rt.listDevices() }.getOrDefault(emptyList())
            if (devices.none { it.ready }) out += Item(Group.PHONES, tr("phone"), State.TODO, tr("none connected: plug in a phone with USB debugging on, or make an emulator ({0} emulator create)", slug), "$slug devices", Place.PHONES)
            devices.forEach { d ->
                out += if (d.ready) Item(Group.PHONES, d.serial, State.OK, listOfNotNull(d.model, d.os?.let { "${d.platform.label} $it" }, if (d.transport == "simulator") tr("simulator") else if (d.isEmulator) tr("emulator") else null).joinToString(" · "))
                else Item(Group.PHONES, d.serial, State.TODO, if (d.state == "unauthorized") tr("accept the USB debugging prompt on the phone") else d.state, place = Place.PHONES)
            }
        }
        // Google's Android CLI: optional; makes emulators, and its Google sign-in reads Crashlytics.
        val acli = rt.androidCli
        val crashApps = p.crashlytics.apps.isNotEmpty()
        out += when {
            !acli.installed -> Item(Group.PHONES, "Android CLI", State.INFO, tr("not installed (optional): it makes emulators") + if (crashApps) tr(" and reads the app's crashes in Crashlytics") else "", "$slug android-cli install", Place.PHONES, no.agens.testlab.services.tools.AndroidCli.PAGE, essential = false)
            else -> {
                val account = acli.account()
                if (account == null && crashApps) Item(Group.PHONES, "Android CLI", State.TODO, tr("not signed in to Google: Crashlytics needs it"), "$slug android-cli login", Place.PHONES, essential = false)
                else Item(Group.PHONES, "Android CLI", State.OK, listOfNotNull(acli.version(), account?.let { tr("signed in as {0}", it) }).joinToString(" · "))
            }
        }

        // iOS Simulators: only once the space has an iOS profile, so Android-only setups see nothing new.
        val iosProfile = rt.profiles.forPlatform(no.agens.testlab.core.Platform.IOS).profile
        if (iosProfile.id !in no.agens.testlab.core.profile.Profile.GENERIC_IDS) {
            val x = rt.xcrun
            out += when {
                x == null -> Item(Group.PHONES, "iOS", State.INFO, tr("«{0}» is for iOS Simulators, which need a Mac", iosProfile.name), essential = false)
                !x.available() -> Item(Group.PHONES, "Xcode", State.TODO, tr("not found: install Xcode from the App Store and open it once"), "sudo xcode-select -s /Applications/Xcode.app", Place.PHONES, essential = false)
                !rt.iosUsesAxe() -> Item(Group.PHONES, "AXe", State.TODO, tr("not installed: it reads and taps simulator screens quickly"), "$slug setup ios", Place.PHONES, essential = false)
                !rt.appium.installed() -> Item(Group.PHONES, "Appium", State.TODO, tr("not installed: needed to hide the keyboard and for the live view's video"), "$slug setup ios", Place.PHONES, essential = false)
                else -> Item(Group.PHONES, "AXe", State.OK, tr("ready for iOS Simulators"))
            }
            // Real iPhones: driven once an Apple team signs WebDriverAgent for them.
            val phones = runCatching { rt.listDevices() }.getOrDefault(emptyList()).filter { it.kind == "iphone" || it.kind == "ipad" }
            if (x != null && phones.isNotEmpty() && rt.phoneDriving() == null) {
                out += Item(Group.PHONES, tr("Real iPhones"), State.TODO, tr("{0} paired: set your Apple team so their screens can be read and tapped (installs and app starts work without)", phones.size), "$slug setup ios --team <TEAM ID>", Place.PHONES, essential = false)
            }
        }

        // Claude.
        val control = runCatching { Json.parseToJsonElement(File(rt.dirs.data, "control.json").readText()).jsonObject }.getOrNull()
        val pid = control?.get("pid")?.jsonPrimitive?.content?.toLongOrNull()
        val alive = pid?.let { ProcessHandle.of(it).map { h -> h.isAlive }.orElse(false) } ?: false
        val serverVersion = control?.get("version")?.jsonPrimitive?.content
        out += if (control != null && alive && serverVersion != null && serverVersion != AppIdentity.VERSION)
            Item(Group.CLAUDE, tr("server"), State.TODO, tr("the running {0} is {1}, this is {2}: Claude's tools come from it, so upgrade or restart it", control["mode"]?.jsonPrimitive?.content ?: "?", serverVersion, AppIdentity.VERSION), "$slug upgrade", Place.UPDATES, essential = false)
        else if (control != null && alive) Item(Group.CLAUDE, tr("server"), State.OK, tr("running ({0}) at {1}", control["mode"]?.jsonPrimitive?.content ?: "?", control["mcp"]?.jsonPrimitive?.content ?: "") + (serverVersion?.let { " · $it" } ?: ""))
        else Item(Group.CLAUDE, tr("server"), State.INFO, tr("not running: the background service, `serve` or Claude starts it"))
        val bg = BackgroundService()
        val svc = runCatching { bg.status() }.getOrNull()
        val starts = if (svc != null && svc.state != BackgroundService.State.NOT_INSTALLED) runCatching { bg.startsAt() }.getOrNull() else null
        out += Item(Group.CLAUDE, tr("background service"), State.INFO, when (svc?.state) {
            BackgroundService.State.RUNNING -> tr("installed, running")
            BackgroundService.State.STOPPED -> tr("installed, stopped")
            else -> tr("not installed (optional)")
        } + (starts?.let { tr(", starts by itself {0}", it.words) } ?: ""),
            if (starts == BackgroundService.Start.LOGIN && no.agens.testlab.device.HostOs.current() == no.agens.testlab.device.HostOs.LINUX) "$slug service install --boot" else null, place = Place.SERVICE)
        val claude = ClaudeCli.locate(s.claude.binary)
        out += if (claude == null) Item(Group.CLAUDE, "Claude Code", State.TODO, tr("not found: install it to use {app} from Claude"), "$slug config set claude.binary PATH", Place.CLAUDE, "https://claude.com/claude-code", essential = false)
        else Item(Group.CLAUDE, "Claude Code", State.OK, "${claude.path} ${ClaudeCli.version(claude).orEmpty()}".trim())
        desktopEntry()?.let { (_, cmd) ->
            out += when {
                cmd == null -> Item(Group.CLAUDE, "Claude Desktop", State.INFO, tr("not connected (optional): {0} mcp-config prints the entry", slug))
                File(cmd).name.matches(Regex("javaw?(\\.exe)?")) -> Item(Group.CLAUDE, "Claude Desktop", State.TODO, tr("its entry runs the JVM ({0}), not {1}", cmd, slug), "$slug mcp-config", essential = false)
                !File(cmd).exists() -> Item(Group.CLAUDE, "Claude Desktop", State.TODO, tr("its entry runs {0}, which isn't there any more", cmd), "$slug mcp-config", essential = false)
                else -> Item(Group.CLAUDE, "Claude Desktop", State.OK, cmd)
            }
        }

        // Proxyman (only when turned on).
        val pm = s.proxyman
        if (pm.enabled) {
            val host = pm.host?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
            out += if (reachable(host, pm.port)) Item(Group.PROXYMAN, tr("proxy"), State.OK, "$host:${pm.port}")
            else Item(Group.PROXYMAN, tr("proxy"), State.TODO, tr("{0}:{1} doesn't answer: is Proxyman running?", host, pm.port), "$slug config set proxyman.host HOST", Place.PROXYMAN, essential = false)
            val server = rt.proxyman.mcpServer()
            out += if (rt.proxyman.remoteUrl() != null) Item(Group.PROXYMAN, tr("Proxyman tools"), State.OK, rt.proxyman.remoteUrl()!!)
            else if (server == null) Item(Group.PROXYMAN, tr("Proxyman tools"), State.TODO, tr("Proxyman's mcp-server not found"), "$slug config set proxyman.mcpServer PATH", Place.PROXYMAN, essential = false)
            else Item(Group.PROXYMAN, tr("Proxyman tools"), State.OK, server.path)
        }
        out
    }

    private fun reachable(host: String, port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 2_000) }; true }.getOrDefault(false)

    /** Claude Desktop's config file and its entry's command for us (null command: no entry). Null when Claude Desktop isn't installed. */
    private fun desktopEntry(): Pair<File, String?>? {
        val home = System.getProperty("user.home")
        val f = when (HostOs.current()) {
            HostOs.MAC -> File(home, "Library/Application Support/Claude/claude_desktop_config.json")
            HostOs.WINDOWS -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Claude/claude_desktop_config.json")
            HostOs.LINUX -> File(home, ".config/Claude/claude_desktop_config.json")
        }
        if (!f.isFile) return null
        val entry = runCatching { (Json.parseToJsonElement(f.readText()).jsonObject["mcpServers"] as? JsonObject)?.get(no.agens.testlab.services.Spaces.mcpName()) as? JsonObject }.getOrNull()
        return f to entry?.get("command")?.jsonPrimitive?.content
    }
}
