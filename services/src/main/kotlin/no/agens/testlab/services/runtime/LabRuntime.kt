package no.agens.testlab.services.runtime

import no.agens.testlab.AppIdentity

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import no.agens.testlab.core.autopilot.DecisionClient
import no.agens.testlab.core.autopilot.Geometry
import no.agens.testlab.core.autopilot.AutopilotEngine
import no.agens.testlab.device.ToolLocator
import no.agens.testlab.device.adb.AdbCli
import no.agens.testlab.device.adb.AdbDevice
import no.agens.testlab.device.hs.HsDevice
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.http.KtorHttpPost
import no.agens.testlab.services.secrets.CredentialResolver
import no.agens.testlab.services.secrets.Redactor
import no.agens.testlab.services.secrets.SecretStore
import no.agens.testlab.services.settings.Settings
import no.agens.testlab.services.settings.SettingsStore
import no.agens.testlab.services.trace.FileTraces
import java.io.File

class NoAdb : RuntimeException("adb was not found. Install Android platform-tools, or let ${AppIdentity.NAME} download it: `${AppIdentity.SLUG} tools --download-adb --accept-android-sdk-terms`.")
class NoDevice(message: String) : RuntimeException(message)

/** What happened, for the UI's activity feed and status bar. Secrets are already redacted. */
sealed interface LabEvent {
    /**
     * [title] is what it did in words («Tap "Log in"»), null where there is none; [why] the agent's
     * own reason, when it gave one (the tools' `why` argument).
     */
    data class ToolCall(val tool: String, val summary: String, val ms: Long, val ok: Boolean, val title: String? = null, val why: String? = null) : LabEvent
    /** A plan Claude ran paused; [plan] and [serial] let the hand-back page resolve it and run the rest. */
    data class HandBack(val tool: String, val result: JsonObject, val plan: kotlinx.serialization.json.JsonArray? = null, val serial: String? = null) : LabEvent
    data class DeviceChanged(val active: String?) : LabEvent
}

/**
 * The shared core of the app, the headless CLI server and the MCP tools: settings, the vault,
 * adb and the daemon, one connection per phone, the decision engine and traces.
 */
class LabRuntime(
    val dirs: AppDirs,
    val settings: SettingsStore,
    val secrets: SecretStore,
    private val adbOverride: AdbCli? = null,
    backendOverride: DeviceBackend? = null,
) {
    init {
        // Company roots dropped in <data>/trust/*.pem are trusted next to the JDK's and the OS's.
        no.agens.testlab.services.http.TrustRoots.extraDir = File(dirs.data, "trust")
    }

    /** The space's rules: which GitHub owners, Jira sites and apps its profiles and calls may reach. */
    val rules = no.agens.testlab.services.SpaceRulesStore(dirs)

    /**
     * The project profiles; [profile] configures builds, Jira, the app and destinations for the call
     * in progress: the profile of the device or ticket it is about ([ProfileStore.scope]).
     */
    val profiles = no.agens.testlab.services.profile.ProfileStore(File(dirs.data, "profiles"), File(dirs.cache, "profiles"), settings, rules)
    val profile: no.agens.testlab.core.profile.Profile get() = profiles.current

    /** The profile for [serial]'s platform (a space can have an Android and an iOS profile active at once). */
    fun profileFor(serial: String): no.agens.testlab.core.profile.Profile = profiles.forPlatform(platformOf(serial)).profile

    /** Runs [block] with [profile] being [p]'s: the profile of the device or ticket a call is about. */
    suspend fun <T> onPlatform(p: no.agens.testlab.core.Platform?, block: suspend () -> T): T =
        if (p == null) block() else kotlinx.coroutines.withContext(profiles.scope(p)) { block() }

    /** The package plans and deeplinks open: this computer's override, else the profile's. */
    fun defaultPackage(serial: String? = null): String {
        val p = serial?.takeIf { it.isNotBlank() }?.let(::profileFor) ?: profile
        // The override is this computer's for the Android app; an iOS profile names its own bundle id.
        return settings.current.device.packageOverride?.takeIf { it.isNotBlank() && p.platform == no.agens.testlab.core.Platform.ANDROID } ?: p.app.defaultPackage
    }

    /** `handsets_go` destinations from the active profile. */
    fun destinations(): no.agens.testlab.core.nav.Destinations = no.agens.testlab.core.nav.Destinations(profile.navigation)

    /** Test account names: the user's list, else the profile's defaults. */
    fun accounts(): List<String> = settings.current.accounts ?: profile.accounts.defaults

    /** The GitHub API base: this computer's override (Enterprise, a fake), else the profile's. */
    fun githubApi(): String = (settings.current.connections.githubApiUrl ?: profile.github.apiUrl).trimEnd('/')

    /** A token for the profile's GitHub owner (`github.token@<owner>`), else the general one. */
    fun githubToken(): String? = SecretStore.scoped(secrets, SecretStore.GITHUB_TOKEN, profile.github.owner)
    fun jiraEmail(): String? = SecretStore.scoped(secrets, SecretStore.JIRA_EMAIL, profile.tracker.site)
    fun jiraToken(): String? = SecretStore.scoped(secrets, SecretStore.JIRA_TOKEN, profile.tracker.site)

    /**
     * Masks this space's secrets, and any other space's whose salted hash it recognises: another client's token
     * pasted into a Knit session never reaches a trace or report in clear.
     */
    val redactor = Redactor(secrets, no.agens.testlab.services.secrets.SecretHashes(File(dirs.data, "secret-hashes"), dirs::otherSpacesData))
        .also { r -> runCatching { r.publish() } }
    val traces = FileTraces(dirs.traces) { redactor.apply(it) }

    /** Jev on a picture of the screen (Box): the TypeSafe key is read on every request, like the engine's. */
    var autopilotFind: AutopilotFind = AutopilotFind(no.agens.testlab.services.http.KtorHttpPost(), apiKey = { secrets.get(SecretStore.TYPESAFE_KEY) ?: System.getenv("TYPESAFE_API_KEY") })

    private val activeSerial = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = activeSerial.asStateFlow()
    private val eventFlow = MutableSharedFlow<LabEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LabEvent> = eventFlow.asSharedFlow()
    fun emit(e: LabEvent) {
        if (e is LabEvent.ToolCall) synchronized(recent) { recent.addLast(System.currentTimeMillis() to e); while (recent.size > 40) recent.removeFirst() }
        eventFlow.tryEmit(e)
    }

    private val recent = ArrayDeque<Pair<Long, LabEvent.ToolCall>>()

    /** The last tool calls (newest last) with when they finished: what the live view lists under the screen. */
    fun recentCalls(): List<Pair<Long, LabEvent.ToolCall>> = synchronized(recent) { recent.toList() }

    private val devices = HashMap<String, HsDevice>()
    private val geometries = HashMap<String, Geometry>()

    val credentials = CredentialResolver(secrets) { activeSerial.value }

    /** Asks before writes that came in over MCP; the app and `serve` set it to [approvals]' queue. */
    @Volatile var approver: Approver = Approver.HEADLESS

    /** Writes waiting for an answer, from the approvals page, the control API or `testlab approve`. */
    val approvals = Approvals { settings.current.mcp.approvalTimeoutSeconds }

    /** Plans and reports live in the workspace (Settings can move it). */
    fun reportsDir(): File = settings.current.workspace?.let { File(it, "reports") } ?: dirs.reports
    fun plansDir(): File = settings.current.workspace?.let { File(it, "plans") } ?: dirs.plans

    val recordings by lazy { no.agens.testlab.services.rec.Recordings(this) }
    val reports by lazy { no.agens.testlab.services.report.Reports(this) }
    val planRuns by lazy { no.agens.testlab.services.plans.PlanRuns(this) }
    val handBacks by lazy { no.agens.testlab.services.plans.HandBacks(this) }
    val comparison by lazy { no.agens.testlab.services.plans.Comparison(this) }

    /** Jira and the devtest flow; tests pass a client with a mock engine. */
    @Volatile var jiraHttp: io.ktor.client.HttpClient? = null
    val jira by lazy {
        no.agens.testlab.services.jira.Jira(
            jiraHttp ?: no.agens.testlab.services.jira.Jira.httpClient(),
            base = { requireJira(); settings.current.connections.jiraBase(profile.tracker.site) }, site = { profile.tracker.site },
            email = { jiraEmail() }, token = { jiraToken() },
        )
    }
    private fun requireJira() {
        if (profile.tracker.site.isBlank()) throw no.agens.testlab.services.jira.JiraError(0, "The profile «${profile.name}» has no Jira site: set tracker.site (`testlab open profiles)")
        rules.checkSite(profile.tracker.site)
    }

    val devtest by lazy { no.agens.testlab.services.jira.Devtest(this) }

    val updates by lazy {
        no.agens.testlab.services.update.UpdateCheck(
            githubHttp ?: no.agens.testlab.services.github.GitHub.httpClient(), File(dirs.data, "update.json"),
            token = { runCatching { no.agens.testlab.services.update.Upgrader.updateToken(this) }.getOrNull() }, api = { no.agens.testlab.services.update.Upgrader.UPDATE_API },
        )
    }
    /** Google's Android CLI (optional): emulators it sets up, Crashlytics. Uses the SDK Testlab's adb comes from, when that is a full SDK. */
    val androidCli by lazy {
        no.agens.testlab.services.tools.AndroidCli(githubHttp ?: no.agens.testlab.services.github.GitHub.httpClient(), sdk = {
            locateAdb()?.parentFile?.parentFile?.takeIf { sdk -> listOf("emulator", "platforms", "system-images").any { java.io.File(sdk, it).isDirectory } }
        })
    }
    /** iOS Simulators made and started for the user (handsets_simulator_create). */
    val simulators by lazy { Simulators { xcrun } }
    /** Xcode's own MCP server (Xcode 27): App Store / TestFlight crashes and field performance (lab_ios_*). */
    private val xcodeLazy = lazy { XcodeMcp(java.io.File(dirs.data, "logs/xcode-mcp.log")) }
    val xcode by xcodeLazy
    /** The app's crashes in the field, from Firebase Crashlytics (lab_crashlytics_*). */
    val crashlytics by lazy { no.agens.testlab.services.crash.Crashlytics(this) }
    /** A PR's screens before and after, from its screenshot tests' reference images (lab_pr_screens). */
    val prScreens by lazy { no.agens.testlab.services.screens.PrScreens(this) }
    val platformTools by lazy { no.agens.testlab.services.tools.PlatformTools(githubHttp ?: no.agens.testlab.services.github.GitHub.httpClient(), dirs.cache) }
    private val proxymanLazy = lazy { no.agens.testlab.services.proxyman.Proxyman(this) }
    val proxyman by proxymanLazy
    val projectSetup by lazy { no.agens.testlab.services.profile.ProjectSetup(this) }
    val supportBundle by lazy { no.agens.testlab.services.diag.SupportBundle(this) }

    /**
     * Asks before a write to an outside system or the phone (Jira comments, uninstalls) when
     * the `mcp.confirmWrites` setting asks for confirmation; throws [NotApproved] when the user says no.
     */
    suspend fun confirmWrite(action: String, detail: String) {
        if (settings.current.mcp.confirmWrites && !approver.approve(action, detail)) throw NotApproved("Not approved: $action was declined in ${no.agens.testlab.AppIdentity.NAME}")
    }

    val adbOps by lazy { AdbOps(this) }
    val gestures by lazy { AndroidGestures(this) }
    /** TalkBack on the phones: tools warn while it runs, and turn it off and on again on request. */
    val talkback by lazy { TalkBack(this) }
    val talkbackGestures by lazy { TalkBackGestures(this) }
    val fontScale by lazy { FontScale(this) }
    val emulators by lazy { Emulators(this) }
    val isolation by lazy { Isolation(this) }
    val testAccounts by lazy { TestAccounts(this) }

    /** Test-user pools of the project's profiles; plans read the pool user of their device by reference. */
    val pools: TestPools = TestPools({ runCatching { testAccounts.projects().values.flatMap { it.profile.accounts.pools }.distinctBy { it.name } }.getOrDefault(emptyList()) }, secrets)
        .also { credentials.source = it }
    /** Phone settings snapshot at session start, the diff and the restore (lab_device_state). */
    val deviceState by lazy { DeviceState(this) }
    /** The rig's health measured from the phone (lab_health). */
    val rigHealth by lazy { RigHealth(this) }
    /** logcat / simulator log capture and the analytics events in it (lab_logs_*). */
    val logs by lazy { no.agens.testlab.services.logs.LogCapture(this) }
    /** The profile's helper repos (profile.testRepos), fetched read-only (lab_test_repos). */
    val testRepos by lazy { no.agens.testlab.services.testrepos.TestRepos(this) }

    /** GitHub builds; tests pass a client with a mock engine. */
    @Volatile var githubHttp: io.ktor.client.HttpClient? = null
    val builds by lazy { no.agens.testlab.services.builds.Builds(this, githubHttp ?: no.agens.testlab.services.github.GitHub.httpClient()) }

    // --- adb and the daemon ---

    /** Which space holds each shared phone (the folder all spaces share); see [DeviceLeases]. */
    val leases = DeviceLeases(
        File(dirs.rootData, "leases"), dirs.space,
        handover = { serial, from ->
            handover.run(serial, from).also { steps ->
                // Another client's steps name its apps: here only that it happened.
                if (from != dirs.space) System.err.println("$serial: handed over from another client's space (${steps.size} step(s): its apps, proxy and accessibility settings undone)")
            }
        },
        serverPid = { space ->
            val data = if (space == no.agens.testlab.services.Spaces.DEFAULT) dirs.rootData else File(dirs.rootData, "spaces/$space")
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(File(data, "control.json").readText()).let { (it as JsonObject)["pid"].toString().toLong() } }.getOrNull()
        },
    )
    val handover by lazy { Handover(this) }

    @Volatile private var adbCli: AdbCli? = adbOverride?.also { it.guard = { s -> leases.ensure(s) } }
    fun adb(): AdbCli = adbCli ?: run {
        val found = ToolLocator(appToolsDir = dirs.cache).findAdb() ?: throw NoAdb()
        // Every command for a phone holds it for this space first: another client's phone is refused.
        AdbCli(found.path, File(dirs.data, "adb-server.log")).also { it.guard = { s -> leases.ensure(s) }; it.startServer(); adbCli = it }
    }

    /** Where adb is, without starting its server (for status displays). */
    fun locateAdb(): File? = adbCli?.adb ?: ToolLocator(appToolsDir = dirs.cache).findAdb()?.path

    /** `xcrun simctl` on macOS (null elsewhere): iOS Simulators, leased like phones. */
    val xcrun: no.agens.testlab.device.ios.XcrunCli? =
        if (backendOverride == null && no.agens.testlab.device.HostOs.current() == no.agens.testlab.device.HostOs.MAC) no.agens.testlab.device.ios.XcrunCli().also { it.guard = { s -> leases.ensure(s) } } else null

    /** Appium for the simulators: one per space, on a port next to the space's MCP port. */
    val appium by lazy { no.agens.testlab.device.ios.Appium(File(dirs.cache, "appium"), 4723 + (settings.current.mcp.port - 8378).coerceIn(0, 200), File(dirs.data, "logs/appium.log")) }

    private val iosBackend: IosSimBackend? = xcrun?.let { IosSimBackend(it, appium, File(dirs.cache, "ios-bridge")) }
    /** Opens the simulator's WebDriverAgent session now (setup: it is built once, which takes minutes). */
    fun iosStartWda(serial: String) { iosBackend?.startWda(serial) }
    /** Whether iOS screens are read through AXe (installed), else WebDriverAgent. */
    fun iosUsesAxe(): Boolean = iosBackend?.usesAxe() == true

    /** Real iPhones and iPads (`devicectl`) and, on Apple Silicon, this Mac: installs and app starts. */
    private val devicectl = xcrun?.let { no.agens.testlab.device.ios.DevicectlCli(it) }

    /** What driving a real iPhone's screen takes, once an Apple team is set (null: installs and app starts only). */
    fun phoneDriving(): PhoneDriving? {
        val team = settings.current.device.iosTeamId?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        val bundle = settings.current.device.iosWdaBundleId?.trim()?.takeIf { it.isNotEmpty() }
            ?: no.agens.testlab.device.ios.Appium.defaultWdaBundleId(no.agens.testlab.AppIdentity.ID, team)
        return PhoneDriving(appium, team, bundle, File(dirs.cache, "ios-bridge"))
    }

    private val phoneBackend = IosDeviceBackend(devicectl, driving = ::phoneDriving)

    private val backend: DeviceBackend = backendOverride ?: CompositeBackend(buildMap {
        put(no.agens.testlab.core.Platform.ANDROID, AdbDeviceBackend(::adb, dirs.cache))
        iosBackend?.let { put(no.agens.testlab.core.Platform.IOS, IosBackends(it, phoneBackend)) }
    })

    private val platforms = java.util.concurrent.ConcurrentHashMap<String, no.agens.testlab.core.Platform>()

    fun listDevices(): List<AdbDevice> = backend.devices().onEach { platforms[it.serial] = it.platform; if (it.kind != null) realIos.add(it.serial) }

    private val realIos = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** A real iOS device (an iPhone, this Mac), not a simulator: it takes device builds (`.ipa`), and devicectl does its apps. */
    fun realIos(serial: String): Boolean = serial in realIos || runCatching { listDevices() }.getOrNull()?.any { it.serial == serial && it.kind != null } == true

    /** A real iOS device whose screen can't be driven (this Mac, or an iPhone with no Apple team set): app starts go straight to it. */
    fun installOnly(serial: String): Boolean = realIos(serial) && (serial == no.agens.testlab.device.ios.MacIosApps.SERIAL || phoneDriving() == null)

    /** Android or iOS, from the device list (a serial no backend lists counts as Android: adb says what's wrong). */
    fun platformOf(serial: String): no.agens.testlab.core.Platform =
        platforms[serial] ?: runCatching { listDevices() }.getOrNull()?.firstOrNull { it.serial == serial }?.platform ?: no.agens.testlab.core.Platform.ANDROID

    private val androidOps by lazy { AndroidOps(this) }
    /** Tests set their own; on macOS the simulators' simctl. */
    @Volatile var iosOps: PlatformOps? = xcrun?.let { IosSimOps(this, it) }
    /** Tests set their own; on macOS devicectl and this Mac. */
    @Volatile var iosDeviceOps: PlatformOps? = xcrun?.let { IosDeviceOps(this, devicectl) }

    /** Deeplinks, installs, app data, font size and unlocking for [serial]'s platform. */
    fun ops(serial: String): PlatformOps = when (platformOf(serial)) {
        no.agens.testlab.core.Platform.ANDROID -> androidOps
        no.agens.testlab.core.Platform.IOS -> (if (serial in realIos) iosDeviceOps else iosOps) ?: throw NotOnPlatform("iOS devices need macOS with Xcode")
    }

    /** Starts the daemon on [serial] (or the only ready device) and, when [activate], makes it the active device. */
    suspend fun use(serial: String? = null, takeOver: Boolean = settings.current.device.takeOver, activate: Boolean = true): HsDevice {
        val s = serial?.takeIf { it.isNotBlank() } ?: pickDefaultSerial()
        leases.ensure(s)
        val dev = synchronized(devices) {
            // A take-over request replaces a connection that was set up without it.
            if (takeOver) devices.remove(s)?.close()
            devices.getOrPut(s) { HsDevice(s) { backend.connect(s, takeOver) } }
        }
        val (w, h) = try {
            dev.screenSize() // connects (and proves the connection) now rather than on the first tool call
        } catch (e: Exception) {
            synchronized(devices) { if (devices[s] === dev) devices.remove(s) }
            dev.close()
            throw e
        }
        synchronized(devices) { geometries[s] = Geometry(w, h) }
        if (activate) profiles.focus(platformOf(s))
        if (activate && activeSerial.value != s) { activeSerial.value = s; emit(LabEvent.DeviceChanged(s)) }
        return dev
    }

    /**
     * The device for a tool call: the given serial, else the active one, else the only one. A call
     * naming a serial connects it without making it the active device (unless none is): the calls
     * that name none must not move to another phone in the middle of a session.
     */
    suspend fun device(serial: String? = null): HsDevice {
        val explicit = serial?.takeIf { it.isNotBlank() }
        val s = explicit ?: activeSerial.value
        // The current profile follows the active device; a call naming another device runs in that
        // device's profile scope (ToolBox), without moving the focus under calls running beside it.
        if (s != null && s == activeSerial.value) profiles.focus(platformOf(s))
        val noise = runCatching { profile.dismiss }.getOrDefault(emptyList())
        fun HsDevice.wire(): HsDevice {
            this.noise = noise
            // Android: an app start with launch values (extras) goes through adb; the daemon's start takes none.
            if (launchWith == null && platformOf(this.serial) == no.agens.testlab.core.Platform.ANDROID) launchWith = { c, args -> gestures.launch(this.serial, c, args) }
            return this
        }
        synchronized(devices) { s?.let { devices[it] } }?.let { return it.wire() }
        return use(s, activate = explicit == null || activeSerial.value == null).wire()
    }

    /**
     * The device a call means, without connecting to it: installs go through adb or simctl, and an
     * iOS Simulator's first session (Appium, WebDriverAgent) can take longer than a call may wait.
     */
    fun serialFor(serial: String? = null): String {
        val s = serial?.takeIf { it.isNotBlank() } ?: activeSerial.value ?: pickDefaultSerial()
        leases.ensure(s)
        return s
    }

    fun geometry(serial: String): Geometry = synchronized(devices) { geometries[serial] } ?: Geometry.REFERENCE

    fun drop(serial: String? = null) {
        val s = serial ?: activeSerial.value ?: return
        synchronized(devices) { devices.remove(s)?.close(); geometries.remove(s) }
        // A phone left pointing at a proxy (127.0.0.1 via a reverse port that dies with the session) has no network.
        if (File(dirs.data, "proxied.json").isFile && s in proxyman.routed.value) runCatching { kotlinx.coroutines.runBlocking { proxyman.unroute(s) } }
        if (File(dirs.data, "mac-proxy.json").isFile && proxyman.mac.routed(s)) runCatching { kotlinx.coroutines.runBlocking { proxyman.unroute(s) } }
        runCatching { backend.release(s) }
        if (activeSerial.value == s) { activeSerial.value = null; emit(LabEvent.DeviceChanged(null)) }
    }

    /**
     * The device a call without a serial means: the only ready one, else the only ready *phone* (a real
     * device is preferred over emulators for running builds), else the caller must say which.
     */
    fun pickDefaultSerial(): String {
        val all = listDevices()
        val free = all.filter { !leases.takenByOther(it.serial) }
        if (free.none { it.ready } && all.any { it.ready }) throw NoDevice("Every connected phone is in use by another client (another space): ${all.filter { it.ready }.joinToString { it.serial }}. Free one there with devices release, or connect another.")
        return DevicePick.default(free)
    }

    // --- the decision engine ---

    private val engines = HashMap<no.agens.testlab.core.Platform, Pair<Pair<Settings.Autopilot, no.agens.testlab.core.profile.Profile.Autopilot>, AutopilotEngine>>()

    /** Rebuilt when the decision settings change; the API key is read on every request. One per platform: they may run at once. */
    @Synchronized
    fun engine(): AutopilotEngine {
        val ap = settings.current.autopilot to profile.autopilot
        val platform = profiles.platform
        val ios = platform == no.agens.testlab.core.Platform.IOS
        engines[platform]?.takeIf { it.first == ap }?.let { return it.second }
        val config = ap.first.toConfig(ap.second).let { if (ios) it.copy(device = "an iPhone (iOS)", backAction = no.agens.testlab.core.autopilot.META_BACK_IOS) else it }
        val client = DecisionClient(
            config, KtorHttpPost(), apiKey = { secrets.get(SecretStore.TYPESAFE_KEY) }, nowMs = System::currentTimeMillis,
            openaiKey = { secrets.get(SecretStore.OPENAI_KEY) ?: System.getenv("OPENAI_API_KEY") },
        )
        return AutopilotEngine(config, client).also { engines[platform] = ap to it }
    }

    fun close() {
        if (proxymanLazy.isInitialized() || File(dirs.data, "proxied.json").isFile || File(dirs.data, "mac-proxy.json").isFile) runCatching { proxyman.close() }
        synchronized(devices) { devices.values.forEach { it.close() }; devices.clear() }
        runCatching { phoneBackend.close() }
        runCatching { iosBackend?.close() }
        if (xcodeLazy.isInitialized()) xcode.close()
    }
}
