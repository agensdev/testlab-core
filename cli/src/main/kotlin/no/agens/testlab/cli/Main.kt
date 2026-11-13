package no.agens.testlab.cli

import no.agens.testlab.core.profile.problems
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.agens.testlab.AppIdentity
import no.agens.testlab.core.autopilot.Backend
import no.agens.testlab.core.autopilot.DecideOptions
import no.agens.testlab.core.autopilot.DecisionClient
import no.agens.testlab.core.autopilot.Geometry
import no.agens.testlab.core.autopilot.HsRunner
import no.agens.testlab.core.autopilot.AutopilotEngine
import no.agens.testlab.core.autopilot.PlanContext
import no.agens.testlab.core.autopilot.PlanValidator
import no.agens.testlab.core.autopilot.pick
import no.agens.testlab.core.autopilot.runPlan
import no.agens.testlab.core.versionLine
import no.agens.testlab.device.ToolLocator
import no.agens.testlab.device.hs.HsDevice
import no.agens.testlab.mcp.McpEndpoint
import no.agens.testlab.mcp.StdioBridge
import no.agens.testlab.mcp.TestlabMcp
import no.agens.testlab.mcp.mcpToken
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.SelfTest
import no.agens.testlab.services.http.KtorHttpPost
import no.agens.testlab.services.report.DirShotStore
import no.agens.testlab.services.runtime.LabBoot
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.runtime.TestAccounts
import no.agens.testlab.services.secrets.Imports
import no.agens.testlab.services.secrets.SecretStore
import no.agens.testlab.services.secrets.VaultLocked
import java.io.File
import kotlin.system.exitProcess

private val USAGE = """
    |${AppIdentity.SLUG} — headless ${AppIdentity.NAME}
    |
    |  status [--offline]        what is set up and what is missing: version, profile, keys, the server and
    |                            service, Claude, Proxyman, adb and phones (--offline skips the GitHub/Jira checks)
    |  config list | get KEY | set KEY VALUE | unset KEY
    |                            read and change settings (settings.json) by dotted key, e.g. proxyman.host;
    |                            running windows, services and Claude's server pick changes up within seconds
    |  --version                 print the version
    |  --selftest[=file]         check the runtime (TLS, crypto) and exit
    |  tools                     where adb, scrcpy and ffmpeg were found
    |  tools --download-adb --accept-android-sdk-terms
    |                            download Google's platform-tools into the app's folder
    |  diag [--out F.zip]        a diagnostics zip to send when something is wrong (secrets removed)
    |  dirs                      the data, cache and workspace folders
    |  devices                   phones and emulators adb can see, and which space holds each
    |  devices lease SERIAL | release [SERIAL]
    |                            hold a shared phone for this space until released / hand it over (clears this client's apps, proxy, TalkBack, font)
    |  emulator [list|profiles|create PROFILE|start NAME|stop NAME] [--headless] [--cold]
    |                            Android emulators; create (medium_phone, small_phone, …) downloads what it needs
    |  android-cli [status|install|login|logout]
    |                            Google's Android CLI: makes emulators; login = the Google account for Crashlytics
    |  simulator [list|models|create [MODEL]|start NAME|stop NAME] [--no-start]
    |                            iPhone simulators (Mac); create downloads the iOS runtime when there is none
    |  xcode [status|enable|approve|open PATH]
    |                            Xcode 27's MCP server, for the iOS crash and performance reports (enable/approve use sudo)
    |  connect [--serial S] [--take-over]
    |                            start the daemon on a device (--take-over stops other
    |                            automation tools that hold the accessibility connection)
    |  ui [--serial S] [--tree|--json]   the current screen as `hs ui` text
    |  tap X Y | tap TEXT [--serial S]   tap a point, or a label / #id / CSS-like selector
    |  type --text T [--serial S]        type into the focused field (any characters)
    |  talkback [status|off|on] [--serial S]
    |                            TalkBack, for testing with it (taps and typing work either way); off/on restore it exactly
    |  pick --goal G [--serial S] [--backend typesafe|ollama] [--stub]
    |       [--ollama url|model,...] [--allow-risky] [--max-candidates N] [--dump FILE]
    |                            ask the decision model which element advances G (no tap)
    |  plan --file F [--serial S] [--start N] [--backend …] [--stub] [--ollama …]
    |       [--min-confidence X] [--shots DIR]
    |                            validate and run a JSON plan; {"cred":"acct.field"} comes from the vault
    |                            (or TESTLAB_CRED_ACCT_FIELD in the environment)
    |
    |  install (--pr N | --branch B | --release TAG|latest | --apk FILE) [--type feature|main|maestro]
    |          [--serial S]      install a CI build, a release or an APK file (GitHub token from the vault)
    |  builds [--type T]         the newest CI build per branch (cached; --refresh fetches what is new)
    |
    |  repos [FILTER] [--all]    the repositories your GitHub token can read (newest push first; --all adds
    |                            archived ones and forks), to pick one for profile init
    |  test-repos [sync]         the profile's helper repos (testRepos): fetched read-only, their skills
    |                            and flows; sync checks GitHub for newer commits now
    |  profile list | check FILE | use ID | import PATH | export [ID] [--out F.zip]
    |  profile init --repo OWNER/NAME [--jira SITE] [--name N] [--id ID] [--projects K,K]
    |       [--production-package P] [--read-packages] [--dry-run]
    |                            propose a profile from the repo's Actions builds and Jira, save and use it
    |                            the project profiles (app, repo, builds, Jira, deeplinks; docs/PROFILES.md)
    |  jira me | issue KEY | search JQL [--max N]   your Jira (e-mail + API token from the vault)
    |  devtest queue | prs KEY   tickets waiting for a devtest; the PRs that may belong to KEY
    |
    |  record --out F.mp4 [--seconds N] [--serial S]   record the screen (Ctrl-C or N seconds ends it)
    |  record --recover DIR --out F.mp4                 turn an interrupted recording into an MP4
    |
    |  serve [--port N] [--listen ADDR] [--allow-host NAME,…]
    |                            run the MCP server, the control API and the web pages here; Ctrl-C stops it.
    |                            --listen 0.0.0.0 or a tailnet IP shares the phones (docs/SERVICE.md)
    |  service install [--system [--user U] [--passphrase-file F]] [--port N] [--listen ADDR] [--allow-host …]
    |  service uninstall | start | stop | status | logs [--system]
    |                            run `serve` in the background: systemd (Linux), launchd (macOS),
    |                            a logon task (Windows); --system = a machine-wide unit for a phone rig
    |  upgrade [--ci | --from PATH] [--rollback]
    |                            install a newer build over this one (CLI and, on macOS, the app): the newest
    |                            release, the newest package run (--ci), or a downloaded folder/zip/file;
    |                            --rollback puts the previous one back
    |  approvals [--watch]       writes waiting for a yes (Jira comments, uninstalls) on the running server
    |  approve ID|--all  /  deny ID|--all
    |                            answer them (the approvals page and host apps can too; no answer in time = no)
    |  mcp --stdio | --mcp-stdio stdio MCP for Claude Desktop: forwards to the running app, else serves here
    |  mcp-config [--port N]     print the connect snippets for Claude Code, Claude Desktop and Codex
    |  space list | create NAME [--from Z] | use | migrate [--rest] | delete
    |                            one space per client: its own vault, settings, reports, port (--space NAME on any command)
    |  space claude [NAME] [--dir D] [--sandbox]
    |                            a Claude Code profile for the space: its tools only, other clients' code denied
    |  space export [NAME] [--out F]
    |                            the space's rules and profiles for a colleague (space create X --from F); no secrets
    |  space rules | allow|disallow github OWNER|jira SITE|app FAMILY|code PATH
    |                            what the space's profiles, GitHub and Jira calls may reach (space.json)
    |  setup [--team ID] [--no-web]
    |                            set everything up, step by step (re-run any time; Enter skips a step); keys and
    |                            accounts on a web page when a browser is there (--no-web: here). Android
    |                            and iOS devices for the platforms the project covers (iOS on a Mac; --team is
    |                            the Apple team that signs WebDriverAgent for real iPhones)
    |  setup ios [--team ID]     only the iOS steps: Xcode, AXe, Node.js, Appium, WebDriverAgent, real iPhones
    |  open [PAGE] [ARG] [--print] [--ip]
    |                            a page in the browser: devices (default), setup, screen [serial], reports, report [id],
    |                            profiles, handback, approvals, settings, guide
    |  claude-plugin [--path]    install the Claude Code plugin (phone and reports as terminal panes); upgrade keeps it current
    |
    |  accounts [list]           the test accounts: what each is for, which platforms it can log in on, fields set
    |  accounts add NAME [--note TEXT]  /  note NAME TEXT  /  remove NAME
    |                            fill the fields with `secrets set account.NAME.FIELD` (remove deletes them too)
    |
    |  secrets list              which secrets are set (names only)
    |  secrets set KEY           read the value from stdin (no echo on a terminal); keys: github.token
    |                            (github.token@OWNER for one owner's repos, e.g. the Testlab repo for upgrades),
    |                            jira.email, jira.token, typesafe.key, device_pin, device_pin.<serial>,
    |                            account.<name>.<field>
    |  secrets delete KEY
    |  secrets import-env FILE   GITHUB_TOKEN, JIRA_EMAIL/JIRA_API_TOKEN, TYPESAFE_API_KEY … from a .env file
    |  secrets import-lab FILE   device_pin and accounts.<name>.<field> from a lab-credentials.json
    |
    |The vault key lives in the OS keyring; without one (headless Linux) set TESTLAB_VAULT_PASSPHRASE
    |or type the passphrase when asked. TYPESAFE_API_KEY in the environment overrides the stored key.
""".trimMargin()

private val pretty = Json { prettyPrint = true }

fun main(args: Array<String>) {
    no.agens.testlab.mcp.MenuBar.backgroundApp()
    StdioBridge.quietStdout()
    exitProcess(run(args))
}

fun run(argv: Array<String>): Int {
    // `--space NAME` anywhere: the client space everything below works in (its own folders, vault, port).
    val at = argv.indexOf("--space")
    val args = if (at >= 0) {
        val name = argv.getOrNull(at + 1) ?: run { System.err.println("--space needs a name"); return 2 }
        try { no.agens.testlab.services.Spaces.override = no.agens.testlab.services.Spaces.check(name) } catch (e: IllegalArgumentException) { System.err.println(e.message); return 2 }
        argv.filterIndexed { i, _ -> i != at && i != at + 1 }.toTypedArray()
    } else argv
    if (SelfTest.isRequested(args)) return SelfTest.runAndReport(SelfTest.outputFile(args))
    if ("--mcp-stdio" in args) return mcpStdio(Opts(args.toList()))
    val opts = Opts(args.drop(1))
    // `testlab install --help`: that command's options, not the command.
    if (args.size >= 2 && (args.contains("--help") || args.contains("-h"))) {
        Help.command(USAGE, args.first().removePrefix("--"))?.let { println(it); return 0 }
    }
    return try {
        when (args.firstOrNull()) {
            "--version", "version" -> { println(versionLine("Java ${System.getProperty("java.version")}")); 0 }
            "status" -> status(opts)
            "config" -> config(opts)
            "tools" -> if (opts.flag("download-adb")) downloadAdb(opts) else { printTools(); 0 }
            "diag" -> diag(opts)
            "dirs" -> { printDirs(); 0 }
            "devices" -> devices(opts)
            "emulator" -> plainErrors { emulator(opts) }
            "android-cli" -> plainErrors { androidCli(opts) }
            "simulator" -> plainErrors { simulator(opts) }
            "xcode" -> plainErrors { xcode(opts) }
            "connect" -> withDevice(opts) { d -> val (w, h) = d.screenSize(); println("connected ${d.serial}: ${w}x$h"); 0 }
            "ui" -> withDevice(opts) { d ->
                print(when { opts.flag("json") -> d.dumpJson().toString() + "\n"; opts.flag("tree") -> d.tree(); else -> d.table() })
                0
            }
            "tap" -> withDevice(opts) { d ->
                val pos = opts.positional()
                val x = pos.getOrNull(0)?.toIntOrNull()
                val y = pos.getOrNull(1)?.toIntOrNull()
                if (x != null && y != null) { d.tap(x, y); println("ok") }
                else println(d.tapText(pos.joinToString(" ").ifEmpty { throw CliError("tap needs X Y or a label") }))
                0
            }
            "type" -> withDevice(opts) { d ->
                d.typeText(opts.value("text") ?: throw CliError("type needs --text"))
                0
            }
            "pick" -> pick(opts)
            "plan" -> plan(opts)
            "bench" -> withDevice(opts) { dev ->
                // Latency of the hot path over one persistent connection: dump + render, and a screenshot.
                val n = opts.value("n")?.toInt() ?: 30
                dev.tree() // warm-up (connect, daemon start)
                fun pct(ms: List<Long>, p: Double) = ms.sorted()[((ms.size - 1) * p).toInt()]
                val dumps = (1..n).map { val t = System.nanoTime(); dev.tree(); (System.nanoTime() - t) / 1_000_000 }
                val shots = (1..n).map { val t = System.nanoTime(); dev.screenshot(768); (System.nanoTime() - t) / 1_000_000 }
                println("dump+render  p50 ${pct(dumps, 0.5)} ms  p90 ${pct(dumps, 0.9)} ms  (n=$n)")
                println("screenshot   p50 ${pct(shots, 0.5)} ms  p90 ${pct(shots, 0.9)} ms  (768 px JPEG)")
                0
            }
            "talkback" -> withDevice(opts) { d ->
                val st = when (opts.positional().getOrNull(0) ?: "status") {
                    "off" -> runtime.talkback.off(d.serial)
                    "on" -> runtime.talkback.on(d.serial)
                    "status" -> runtime.talkback.status(d.serial)
                    else -> throw CliError("usage: ${AppIdentity.SLUG} talkback [status|off|on] [--serial S]")
                }
                println("TalkBack on ${d.serial}: " + (if (st.touchExploration) "running" else if (st.enabled) "enabled, not running" else "off") +
                    (st.component?.let { " (installed: $it)" } ?: " (not installed)") + (if (st.turnedOffByUs.isNotEmpty()) "; turned off by ${AppIdentity.NAME}: `talkback on` restores it" else ""))
                0
            }
            "install" -> install(opts)
            "builds" -> buildsList(opts)
            "jira" -> jira(opts)
            "profile" -> profile(opts)
            "repos" -> runBlocking {
                val l = try { runtime.projectSetup.repos() } catch (e: no.agens.testlab.services.github.GitHubError) { throw CliError("GitHub: ${e.message}" + (e.fixUrl?.let { " ($it)" } ?: "")) }
                val q = opts.positional().getOrNull(0)?.lowercase()
                l.repos.filter { (opts.flag("all") || (!it.archived && !it.fork)) && (q == null || q in it.fullName.lowercase()) }.forEach { r ->
                    println(r.fullName.padEnd(48) + listOfNotNull(if (r.private) "private" else "public", r.language, r.pushedAt?.take(10)).joinToString("  "))
                }
                if (l.ssoHidden.isNotEmpty()) System.err.println("${l.ssoHidden.size} organisation(s) hide their repos until the token is authorized for their SSO: https://github.com/settings/tokens → Configure SSO")
                System.err.println("Set one up: ${AppIdentity.SLUG} profile init --repo OWNER/NAME [--jira SITE] [--read-packages]")
                0
            }
            "test-repos" -> runBlocking {
                // The active profile's helper repos, fetched read-only (sync: check GitHub now).
                val tr = runtime.testRepos
                if (tr.configured().isEmpty()) { println("The profile «${runtime.profile.name}» has no test repos (profile.json: testRepos, docs/PROFILES.md)."); return@runBlocking 0 }
                var bad = false
                for (s in tr.configured().map { tr.sync(it, force = opts.positional().firstOrNull() == "sync") }) {
                    val st = s.state
                    println("${s.repo.id.padEnd(18)} ${s.repo.repo.padEnd(44)} " + (st?.let { "${it.sha.take(7)}  ${it.files} files" + if (s.changed) "  (updated)" else "" } ?: "not fetched"))
                    s.problem?.let { bad = true; println("  ${it}") }
                    val sk = tr.skills(s.repo).map { it.name }
                    if (sk.isNotEmpty()) println("  skills: ${sk.joinToString()}")
                    if (s.repo.kind == "maestro") println("  flows: ${tr.flows(s.repo).size}")
                }
                if (bad) 1 else 0
            }
            "devtest" -> devtest(opts)
            "record" -> record(opts)
            "serve" -> serve(opts)
            "service" -> service(opts)
            "upgrade" -> upgrade(opts)
            "approvals" -> approvals(opts)
            "approve" -> answer(opts, true)
            "deny" -> answer(opts, false)
            "mcp" -> if (opts.flag("stdio")) mcpStdio(opts) else throw CliError("usage: ${AppIdentity.SLUG} mcp --stdio")
            "mcp-config" -> mcpConfig(opts)
            "claude-plugin" -> claudePlugin(opts)
            "setup" -> setup(opts)
            "open" -> open(opts)
            "space" -> space(opts)
            "secrets" -> secrets(opts)
            "accounts" -> accounts(opts)
            null, "-h", "--help", "help" -> {
                val topic = args.getOrNull(1)
                when {
                    topic == null -> println(Help.overview())
                    topic == "--all" -> println(USAGE)
                    else -> println(Help.command(USAGE, topic.removePrefix("--")) ?: Help.unknown(USAGE, topic))
                }
                0
            }
            else -> { System.err.println(Help.unknown(USAGE, args.first())); 2 }
        }
    } catch (e: CliError) {
        System.err.println(e.message)
        1
    } catch (e: VaultLocked) {
        System.err.println("${e.message}\nSet TESTLAB_VAULT_PASSPHRASE, or run in a terminal to be asked for the passphrase.")
        1
    } catch (e: no.agens.testlab.device.hs.AutomationBusy) {
        System.err.println("${e.message}\n(add --take-over)")
        1
    } catch (e: no.agens.testlab.device.hs.DaemonStartError) {
        System.err.println(e.message)
        1
    } catch (e: no.agens.testlab.device.hs.HsError) {
        System.err.println(listOf(e.tag, e.detail).filter { it.isNotEmpty() }.joinToString(": "))
        1
    } catch (e: no.agens.testlab.core.autopilot.AutopilotError) {
        System.err.println("decision error: ${e.message}")
        1
    } catch (e: no.agens.testlab.services.runtime.NoAdb) {
        System.err.println(e.message)
        1
    } catch (e: no.agens.testlab.services.runtime.NoDevice) {
        System.err.println(e.message)
        1
    } catch (e: no.agens.testlab.services.runtime.DeviceInUse) {
        System.err.println(e.message)
        1
    } catch (e: no.agens.testlab.services.SpaceRefused) {
        System.err.println(e.message)
        1
    }
}

class CliError(message: String) : RuntimeException(message)

/** Tiny `--key value` / `--flag` parser; good enough for a developer CLI. */
internal class Opts(val raw: List<String>) {
    private val valued = setOf("from", "serial", "text", "goal", "backend", "ollama", "max-candidates", "dump", "file", "start", "min-confidence", "shots", "n", "port", "pr", "branch", "release", "apk", "type", "out", "seconds", "recover", "dir", "listen", "allow-host", "user", "passphrase-file", "launcher", "control", "claude", "config-dir", "client", "note")
    fun value(name: String): String? = raw.indexOf("--$name").takeIf { it >= 0 }?.let { raw.getOrNull(it + 1) }
    fun flag(name: String): Boolean = "--$name" in raw
    /** Every value of a repeatable option, commas splitting too (`--allow-host a,b --allow-host c`). */
    fun values(name: String): List<String> = raw.indices.filter { raw[it] == "--$name" }.mapNotNull { raw.getOrNull(it + 1) }
        .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    /** Arguments that are not options or option values. */
    fun positional(): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < raw.size) {
            val a = raw[i]
            if (a.startsWith("--")) { i += if (a.removePrefix("--") in valued) 2 else 1; continue }
            out += a
            i++
        }
        return out
    }
}

/** The vault passphrase: the environment, else a prompt on a terminal. */
private fun passphrase(): CharArray? =
    no.agens.testlab.services.vaultPassphrase() ?: System.console()?.readPassword("${AppIdentity.NAME} vault passphrase: ")

internal val runtime: LabRuntime by lazy { LabBoot.open(passphrase = ::passphrase) }

private fun <T> withDevice(opts: Opts, block: suspend (HsDevice) -> T): T = runBlocking {
    try {
        block(runtime.use(opts.value("serial"), opts.flag("take-over")))
    } finally {
        runtime.close()
    }
}

private fun devices(opts: Opts): Int {
    val pos = opts.positional()
    when (pos.getOrNull(0)) {
        "lease" -> {
            val serial = pos.getOrNull(1) ?: throw CliError("devices lease SERIAL")
            val steps = runtime.leases.ensure(serial, sticky = true)
            println("$serial is held for the space ${runtime.leases.space} until ${no.agens.testlab.AppIdentity.SLUG} devices release $serial")
            steps.forEach { println("  hand-over from the space before: $it") }
            return 0
        }
        "release" -> {
            val serials = pos.getOrNull(1)?.let(::listOf) ?: runtime.leases.all().filter { it.space == runtime.leases.space }.map { it.serial }
            if (serials.isEmpty()) println("this space holds no phone")
            for (s in serials) {
                val steps = runtime.leases.release(s)
                println("$s released" + if (steps.isEmpty()) "" else ":")
                steps.forEach { println("  $it") }
            }
            runtime.close()
            return 0
        }
        null, "list" -> {}
        else -> throw CliError("devices [list] | lease SERIAL | release [SERIAL]")
    }
    val list = runtime.listDevices()
    if (list.isEmpty()) println("no devices") else list.forEach { d ->
        val held = when (runtime.leases.state(d.serial)) { "yours" -> "  [this space]"; "other" -> "  [in use by another client]"; else -> "" }
        println("${d.serial.padEnd(22)} ${d.state.padEnd(14)} ${d.model ?: ""}${d.hint?.let { "  — $it" } ?: ""}$held")
    }
    if (list.isEmpty() || list.any { !it.ready }) {
        System.err.println("\nCan't see your phone?")
        no.agens.testlab.services.diag.DeviceHelp.steps().forEachIndexed { i, s -> System.err.println("  ${i + 1}. $s") }
    }
    return 0
}

private fun pick(opts: Opts): Int = runBlocking {
    val goal = opts.value("goal") ?: throw CliError("pick needs --goal")
    val engine = engineFor(opts)
    val dumpFile = opts.value("dump")
    val runner: HsRunner
    var geometry = Geometry.REFERENCE
    var serial = "dump"
    if (dumpFile != null) {
        val text = File(dumpFile).readText()
        runner = HsRunner { a -> if (a.firstOrNull() == "ui") text.trim() else "" }
    } else {
        val d = runtime.use(opts.value("serial"), opts.flag("take-over"))
        serial = d.serial
        geometry = runtime.geometry(d.serial)
        runner = d.asHsRunner()
    }
    try {
        val out: JsonObject = engine.pick(
            goal, runner, serial,
            DecideOptions(
                maxCandidates = opts.value("max-candidates")?.toInt() ?: 60,
                allowRisky = opts.flag("allow-risky"),
                stub = opts.flag("stub"),
                geometry = geometry,
            ),
            if (dumpFile != null) no.agens.testlab.services.trace.FileTraces(AppDirs.forHost().traces) else runtime.traces,
        )
        println(pretty.encodeToString(JsonObject.serializer(), out))
        0
    } finally {
        if (dumpFile == null) runtime.close()
    }
}

/** The configured engine, or one built from the command-line overrides; TYPESAFE_API_KEY / OPENAI_API_KEY beat the vault. */
private fun engineFor(opts: Opts): AutopilotEngine {
    val backendFlag = opts.value("backend")?.let(Backend::parse)
    val ollama = opts.value("ollama")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    val envKey = System.getenv("TYPESAFE_API_KEY")
    if (backendFlag == null && ollama == null && envKey == null && opts.value("dump") == null) return runtime.engine()
    val base = if (opts.value("dump") == null) runtime.settings.current.autopilot.toConfig() else no.agens.testlab.core.autopilot.AutopilotConfig()
    val config = base.let { c -> backendFlag?.let { c.copy(backend = it) } ?: c }.let { c -> ollama?.let { c.copy(ollamaUrls = it) } ?: c }
    val key: () -> String? = { envKey ?: if (opts.value("dump") == null) runtime.secrets.get(SecretStore.TYPESAFE_KEY) else null }
    val openaiKey: () -> String? = { System.getenv("OPENAI_API_KEY") ?: if (opts.value("dump") == null) runtime.secrets.get(SecretStore.OPENAI_KEY) else null }
    return AutopilotEngine(config, DecisionClient(config, KtorHttpPost(), apiKey = key, nowMs = System::currentTimeMillis, openaiKey = openaiKey))
}

/** `acct.field` → env `TESTLAB_CRED_ACCT_FIELD`, for CI and the dev container's e2e runs; the vault is the normal source. */
private fun envCred(ref: String): String? = no.agens.testlab.services.env("CRED_" + ref.uppercase().replace('.', '_').replace('-', '_'))

private fun plan(opts: Opts): Int = runBlocking {
    val file = File(opts.value("file") ?: throw CliError("plan needs --file"))
    val plan = Json.parseToJsonElement(file.readText())
    val fromEnv = (plan as? JsonArray)?.let { PlanValidator.credRefs(it) }.orEmpty().filter { envCred(it) != null }
    val issues = PlanValidator.validate(plan, runtime.credentials.knownRefs() + fromEnv)
    issues.filter { !it.error }.forEach { System.err.println("warning: $it") }
    val errors = issues.filter { it.error }
    if (errors.isNotEmpty()) throw CliError("the plan has ${errors.size} problem(s):\n" + errors.joinToString("\n") { "  $it" })
    val engine = engineFor(opts)
    val dev = runtime.use(opts.value("serial"), opts.flag("take-over"))
    try {
        val shots = File(opts.value("shots") ?: File(runtime.dirs.reports, "_autopilot_shots").path)
        val out = engine.runPlan(
            plan as JsonArray, dev.asHsRunner(),
            PlanContext(
                cred = { ref -> envCred(ref) ?: runtime.credentials.resolve(ref) },
                hideKeyboard = { dev.hideKeyboard() },
                shots = DirShotStore(shots),
            ),
            start = opts.value("start")?.toInt() ?: 0,
            minConfidence = opts.value("min-confidence")?.toDouble() ?: runtime.settings.current.autopilot.minConfidence,
            decide = DecideOptions(stub = opts.flag("stub"), geometry = runtime.geometry(dev.serial)),
            trace = runtime.traces.open(dev.serial, "plan"),
        )
        println(runtime.redactor.apply(pretty.encodeToString(JsonObject.serializer(), out)))
        if ((out["status"] as JsonPrimitive).content == "completed") 0 else 1
    } finally {
        runtime.close()
    }
}

private fun install(opts: Opts): Int = runBlocking {
    val serial = runtime.use(opts.value("serial"), opts.flag("take-over")).serial
    val type = runtime.builds.type(opts.value("type"))
    try {
        val out = when {
            opts.value("pr") != null -> runtime.builds.installBranchBuild(opts.value("pr")!!, type, serial)
            opts.value("branch") != null -> runtime.builds.installBranchBuild(opts.value("branch")!!, type, serial)
            opts.value("release") != null -> runtime.builds.installRelease(opts.value("release")!!, serial)
            opts.value("apk") != null -> runtime.builds.installFile(File(opts.value("apk")!!), serial)
            else -> throw CliError("install needs --pr N, --branch B, --release TAG or --apk FILE")
        }
        val what = out.build?.let { "${it.type.label} ${it.branch} ${it.run.headSha.take(7)}" + if (!it.isLatest) " (not the PR's newest commit)" else "" }
            ?: out.release?.let { "release ${it.tag}" } ?: out.apk.name
        if (out.result.ok) {
            println("installed $what on $serial: ${out.info.packageName} ${out.info.versionName ?: ""} (${out.info.versionCode ?: "?"})")
            0
        } else {
            System.err.println("install of $what on $serial failed: ${out.result.advice}")
            1
        }
    } catch (e: no.agens.testlab.services.github.GitHubError) {
        throw CliError(no.agens.testlab.services.builds.buildError(e))
    } catch (e: no.agens.testlab.services.builds.NoBuild) {
        throw CliError(e.message ?: "no build")
    } finally {
        runtime.close()
    }
}

private fun buildsList(opts: Opts): Int = runBlocking {
    val type = opts.value("type")?.let { runtime.builds.type(it).id }
    val list = try {
        runtime.builds.catalog(refresh = opts.flag("refresh") || !File(runtime.dirs.builds, "catalog.json").exists())
    } catch (e: no.agens.testlab.services.github.GitHubError) {
        throw CliError(no.agens.testlab.services.builds.buildError(e))
    }
    list.filter { type == null || it.type == type }.forEach { e ->
        println("${e.type.padEnd(13)} ${e.branch.take(48).padEnd(48)} ${e.headSha.take(7)}  ${e.createdAt.take(16).replace('T', ' ')}  ${e.bytes / 1_000_000} MB")
    }
    0
}

private fun profile(opts: Opts): Int {
    val pos = opts.positional()
    val store = runtime.profiles
    return when (pos.firstOrNull()) {
        null, "list" -> {
            // One active profile per platform: each is marked, with its platform.
            val active = store.activeAll().values.map { it.profile.id }.toSet()
            store.all().forEach { e -> println("${if (e.profile.id in active) "*" else " "} ${e.profile.id.padEnd(20)} ${e.profile.name.padEnd(28)} ${e.profile.covers.joinToString(" + ") { it.label }}" + (if (e.bundled) "  (built in)" else "")) }
            0
        }
        "check" -> {
            val f = File(pos.getOrNull(1) ?: throw CliError("profile check FILE"))
            val p = try { no.agens.testlab.core.profile.Profile.parse(f.readText()) } catch (e: Exception) { throw CliError("${f.path}: not a profile: ${e.message?.lineSequence()?.firstOrNull()}") }
            val problems = p.problems()
            if (problems.isEmpty()) {
                val each = p.covers.map { pl -> p.variant(pl) }
                println("${f.path}: ok (${p.id}" + each.joinToString("") { v ->
                    (if (each.size > 1) "; ${v.platform.label}: ${v.github.repo.ifBlank { "no repo" }} ${v.tracker.projects.joinToString("/")}," else ",") +
                        " ${v.builds.size} build type(s), ${v.navigation.destinations.size} destination(s)"
                } + ")")
                0
            }
            else { problems.forEach { System.err.println("- $it") }; 1 }
        }
        "use" -> { store.activate(pos.getOrNull(1) ?: throw CliError("profile use ID")); println("active: ${store.current.id}"); 0 }
        "init" -> runBlocking {
            // Set up a project from a repo: propose, save, activate.
            val setup = runtime.projectSetup
            val repoName = opts.value("repo")
            val scan = repoName?.let { setup.scanRepo(it) }
            var cands = scan?.candidates.orEmpty()
            if (opts.flag("read-packages")) cands = cands.map { c -> setup.readManifest(scan!!.repo, c) }
            scan?.problems?.forEach { System.err.println("note: $it") }
            val jira = opts.value("jira")?.let { setup.scanJira(it) }
            val name = opts.value("name") ?: scan?.repo?.substringAfter('/') ?: throw CliError("profile init needs --repo OWNER/NAME and/or --name")
            val id = opts.value("id") ?: name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val projects = opts.value("projects")?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() } ?: jira?.projects?.singleOrNull()?.let { listOf(it.first) }.orEmpty()
            val p = setup.draft(id, name, scan, cands, jira, projects, jira?.queueLabels.orEmpty(), jira?.approvedLabel, null, opts.value("production-package"))
            if (opts.flag("dry-run")) { println(no.agens.testlab.core.profile.Profile.json.encodeToString(no.agens.testlab.core.profile.Profile.serializer(), p)); return@runBlocking 0 }
            store.save(p); store.activate(p.id)
            println("created and active: ${p.id} — ${p.builds.size} build type(s)" + (p.builds.firstOrNull { it.default }?.let { " (default ${it.id}: ${it.workflowFile})" } ?: "") +
                (if (p.tracker.site.isNotEmpty()) ", Jira ${p.tracker.site} ${p.tracker.projects.joinToString()}" else "") + "\nEdit ${store.active.value.dir}/profile.json for destinations, the comment template and the login (docs/PROFILES.md).")
            0
        }
        "import" -> { val e = store.import(File(pos.getOrNull(1) ?: throw CliError("profile import FOLDER|ZIP|profile.json"))); println("imported and active: ${e.profile.id}"); 0 }
        "export" -> { val id = pos.getOrNull(1) ?: store.current.id; println(store.export(id, File(opts.value("out") ?: "$id.testlab-profile.zip")).path); 0 }
        else -> throw CliError("usage: ${AppIdentity.SLUG} profile list | check FILE | use ID | init --repo O/N [--jira SITE] [--name N] [--read-packages] [--dry-run] | import PATH | export [ID] [--out F.zip]")
    }
}

private fun jiraCall(work: suspend () -> Unit): Int = runBlocking {
    try { work(); 0 } catch (e: no.agens.testlab.services.jira.JiraError) { throw CliError(no.agens.testlab.services.builds.buildError(e)) }
}

private fun jira(opts: Opts): Int {
    val pos = opts.positional()
    return when (pos.firstOrNull()) {
        "me" -> jiraCall { runtime.jira.myself().let { println("${it.displayName} (${it.accountId})") } }
        "issue" -> jiraCall {
            val i = runtime.jira.issue(pos.getOrNull(1) ?: throw CliError("jira issue KEY"))
            println("${i.key} · ${i.summary}\n${i.type} · ${i.status} · ${i.assignee?.displayName ?: "unassigned"} · ${i.labels.joinToString()}\n${i.url}\n\n${i.description}")
        }
        "search" -> jiraCall {
            val page = runtime.jira.search(pos.drop(1).joinToString(" ").ifBlank { throw CliError("jira search JQL") }, opts.value("max")?.toInt() ?: 20)
            page.issues.forEach { println("${it.key.padEnd(11)} ${it.status.take(14).padEnd(14)} ${(it.assignee?.displayName ?: "-").take(20).padEnd(20)} ${it.summary}") }
        }
        else -> throw CliError("usage: ${AppIdentity.SLUG} jira me | issue KEY | search JQL")
    }
}

private fun devtest(opts: Opts): Int {
    val pos = opts.positional()
    return when (pos.firstOrNull()) {
        "queue" -> jiraCall {
            runtime.devtest.queue().forEach { q -> println("${q.issue.key.padEnd(11)} ${(if (q.mine) "yours" else q.issue.assignee?.displayName ?: "-").take(20).padEnd(20)} ${q.issue.summary}" + q.warnings.joinToString("") { "  ⚠ $it" }) }
        }
        "prs" -> jiraCall {
            val c = runtime.devtest.candidates(pos.getOrNull(1) ?: throw CliError("devtest prs KEY"))
            c.candidates.forEach { p -> println("${p.number?.let { "#$it" } ?: "-"}  ${p.state ?: "?"}${if (p.draft) " (draft)" else ""}  ${p.branch ?: ""}  ${p.title ?: ""}  [${p.sources.joinToString()}]") }
            c.problems.forEach { System.err.println(it) }
            if (c.candidates.isEmpty()) println("(no PR found)")
        }
        else -> throw CliError("usage: ${AppIdentity.SLUG} devtest queue | prs KEY")
    }
}

private fun record(opts: Opts): Int {
    val out = File(opts.value("out") ?: throw CliError("record needs --out FILE.mp4"))
    opts.value("recover")?.let { d ->
        val r = no.agens.testlab.device.rec.Recording.toMp4(File(d), out)
        println("recovered ${r.frames} frames, ${r.durationMs / 1000.0} s, ${r.width}x${r.height} -> ${out.path}")
        return 0
    }
    val dir = File(opts.value("dir") ?: File(runtime.dirs.data, "recordings/cli-${System.currentTimeMillis()}").path)
    val seconds = opts.value("seconds")?.toLongOrNull()
    return runBlocking {
        val dev = runtime.use(opts.value("serial"), opts.flag("take-over"))
        val w = no.agens.testlab.device.rec.RecordingWriter(dir)
        System.err.println("recording ${dev.serial} into ${dir.path}" + (seconds?.let { " for $it s" } ?: " (Ctrl-C stops)"))
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { dev.h264 { w.frame(it) } }
        val stop = Thread { w.stop(); }
        if (seconds == null) Runtime.getRuntime().addShutdownHook(Thread { job.cancel(); w.stop(); runCatching { no.agens.testlab.device.rec.Recording.toMp4(dir, out) } })
        kotlinx.coroutines.delay(500)
        runCatching { dev.keyframe() }
        kotlinx.coroutines.delay((seconds ?: Long.MAX_VALUE / 2000) * 1000)
        job.cancel()
        stop.run()
        val r = no.agens.testlab.device.rec.Recording.toMp4(dir, out)
        println("recorded ${r.frames} frames, ${r.durationMs / 1000.0} s, ${r.width}x${r.height} -> ${out.path}")
        runtime.close()
        0
    }
}

internal fun endpoint(opts: Opts) = McpEndpoint.forBind(opts.value("port")?.toIntOrNull() ?: runtime.settings.current.mcp.port, opts.value("listen") ?: runtime.settings.current.mcp.listen)

private fun serve(opts: Opts): Int {
    no.agens.testlab.services.diag.LogFile.teeStderr(File(runtime.dirs.data, "logs"))
    val o = no.agens.testlab.mcp.HeadlessServer.Options(opts.value("port")?.toIntOrNull(), opts.value("listen"), opts.values("allow-host"))
    try {
        no.agens.testlab.mcp.HeadlessServer.run(runtime, o)
    } catch (e: no.agens.testlab.mcp.HeadlessServer.Failed) {
        throw CliError(e.message ?: "could not start")
    }
}

private fun mcpConfig(opts: Opts): Int {
    val ep = endpoint(opts)
    val token = runtime.mcpToken()
    println("# Claude Code\n${ep.claudeCodeCommand(token)}\n")
    // The launcher, not the JVM that runs this: in the CLI download that is bin/testlab, and `java --mcp-stdio`
    // would only make the JVM complain about an unknown option.
    val launcher = runCatching { no.agens.testlab.services.service.BackgroundService.selfCommand("no.agens.testlab.cli.MainKt", console = true) }
        .getOrElse { listOf(AppIdentity.SLUG) }
    println("# Claude Desktop (claude_desktop_config.json)\n${ep.claudeDesktopJson(launcher + no.agens.testlab.services.Spaces.args())}\n")
    println("# Codex (~/.codex/config.toml)\n${ep.codexToml(token)}")
    return 0
}

/** Claude Desktop's stdio server: bridge to the running app, or serve the tools here. */
private fun mcpStdio(opts: Opts): Int {
    val ep = endpoint(opts)
    if (StdioBridge.appIsListening(ep)) {
        System.err.println("[${AppIdentity.SLUG}] forwarding stdio to ${ep.url}")
        StdioBridge.forward(ep, runtime.mcpToken())
    } else {
        System.err.println("[${AppIdentity.SLUG}] ${AppIdentity.NAME} is not running; serving the tools in this process")
        // No window or service to ask: a write that needs a yes is asked in the client itself.
        runtime.approver = no.agens.testlab.mcp.ClientApproval.approver(runtime.approver) { runtime.settings.current.mcp.approvalTimeoutSeconds }
        StdioBridge.serveHere(TestlabMcp(runtime, ep))
    }
    runtime.close()
    return 0
}

private val SECRET_KEY = Regex("github\\.token(@[A-Za-z0-9_.-]+)?|jira\\.email(@[A-Za-z0-9_.-]+)?|jira\\.token(@[A-Za-z0-9_.-]+)?|typesafe\\.key|mcp\\.token|device_pin(\\.[A-Za-z0-9._:-]+)?|account\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|pool\\.[A-Za-z0-9_-]+\\.key")

private fun accounts(opts: Opts): Int {
    val pos = opts.positional()
    val ta = runtime.testAccounts
    fun name(i: Int) = pos.getOrNull(i)?.takeIf { TestAccounts.NAME.matches(it) } ?: throw CliError("an account name (letters, digits, _ and -)")
    when (pos.firstOrNull()) {
        null, "list" -> {
            val names = ta.names()
            if (names.isEmpty()) println("no test accounts: testlab accounts add NAME --note \"what it is for\"")
            val fields = ta.fields().map { it.key }
            for (n in names) {
                val on = ta.readyOn(n)
                println(n + (ta.note(n)?.let { "  · $it" } ?: ""))
                println("  logs in on: " + (on.joinToString { it.label }.ifEmpty { "-" }) + (ta.inUseOn(n)?.let { "   (in use on $it)" } ?: ""))
                val set = (fields + runtime.secrets.keys().filter { it.startsWith("account.$n.") }.map { it.removePrefix("account.$n.") }).distinct()
                println("  " + set.joinToString("  ") { f -> f + (if (runtime.secrets.has(SecretStore.account(n, f))) " ✓" else " -") })
            }
        }
        "add" -> { val n = name(1); ta.add(n, opts.value("note")); System.err.println("added $n: fill it with `testlab secrets set account.$n.<field>` (" + ta.fields().joinToString { it.key } + ")") }
        "note" -> { val n = name(1); ta.add(n, pos.drop(2).joinToString(" ")); System.err.println("noted $n") }
        "remove" -> { val n = name(1); ta.remove(n); System.err.println("removed $n and its fields") }
        else -> throw CliError("accounts [list] | add NAME [--note TEXT] | note NAME TEXT | remove NAME")
    }
    return 0
}

private fun secrets(opts: Opts): Int {
    val pos = opts.positional()
    val store = runtime.secrets
    when (pos.firstOrNull()) {
        "list" -> {
            val keys = (listOf(SecretStore.GITHUB_TOKEN, SecretStore.JIRA_EMAIL, SecretStore.JIRA_TOKEN, SecretStore.TYPESAFE_KEY, SecretStore.DEVICE_PIN) + store.keys().sorted()).distinct()
            keys.filter { it != SecretStore.MCP_TOKEN }.forEach { println("${it.padEnd(40)} ${if (store.has(it)) "set" else "-"}") }
        }
        "set" -> {
            // typesafe_api_key (the name TypeSafe's docs use) and the old jev.key both mean typesafe.key.
            val key = (pos.getOrNull(1) ?: throw CliError("secrets set KEY (the value is read from stdin)")).let(SecretStore::canonical)
            if (!SECRET_KEY.matches(key)) throw CliError("unknown key '$key': use github.token, jira.email, jira.token, typesafe.key (or typesafe_api_key), device_pin, device_pin.<serial>, account.<name>.<field> or pool.<name>.key")
            val console = System.console()
            val value = if (console != null && System.`in`.available() == 0) console.readPassword("$key: ")?.let(::String) else System.`in`.bufferedReader().readText().trimEnd('\n', '\r')
            if (value.isNullOrEmpty()) throw CliError("no value given")
            // `testlab setup` saves Jira and GitHub logins per site/owner (key@scope), and those win over the
            // plain key: setting the plain one alone would change nothing for the active profile.
            val scope = when (key) {
                SecretStore.JIRA_EMAIL, SecretStore.JIRA_TOKEN -> runCatching { runtime.profile.tracker.site }.getOrNull()
                SecretStore.GITHUB_TOKEN -> runCatching { runtime.profile.github.owner }.getOrNull()
                else -> null
            }
            val also = SecretStore.putFor(store, key, scope, value)
            // An account set here shows in Settings and lab_list_credentials like one added there.
            if (key.startsWith("account.")) key.split('.')[1].let { n -> if (n !in runtime.accounts()) runtime.testAccounts.add(n) }
            System.err.println("saved $key" + (also?.let { " and $it (the one used for ${it.substringAfter('@')})" } ?: ""))
        }
        "delete" -> {
            val key = pos.getOrNull(1) ?: throw CliError("secrets delete KEY")
            store.delete(key)
            System.err.println("deleted $key")
        }
        "import-env" -> println(Imports.dotEnv(File(pos.getOrNull(1) ?: throw CliError("secrets import-env FILE")), store))
        "import-lab" -> {
            val (result, accounts) = Imports.labCredentials(File(pos.getOrNull(1) ?: throw CliError("secrets import-lab FILE")), store)
            runtime.settings.update { s -> s.copy(accounts = (runtime.accounts() + accounts).distinct()) }
            println(result)
        }
        else -> throw CliError("secrets list | set KEY | delete KEY | import-env FILE | import-lab FILE")
    }
    return 0
}

private fun downloadAdb(opts: Opts): Int = runBlocking {
    val pt = runtime.platformTools
    if (!opts.flag("accept-android-sdk-terms")) throw CliError("read ${no.agens.testlab.services.tools.PlatformTools.TERMS} and add --accept-android-sdk-terms to download ${pt.url}")
    var shown = -1L
    println(pt.install(acceptedTerms = true) { done, total ->
        if (done / 1_000_000 != shown) { shown = done / 1_000_000; System.err.print("\r$shown MB" + (total?.let { " of ${it / 1_000_000} MB" } ?: "")) }
    })
    System.err.println()
    println("adb: ${pt.adb.path}")
    0
}

private fun diag(opts: Opts): Int {
    val b = runtime.supportBundle
    val r = b.write(File(opts.value("out") ?: b.defaultName()))
    println("${r.zip.path}: ${r.files.size} files (secrets and e-mail addresses removed)")
    return 0
}

private fun printTools() {
    val l = ToolLocator(appToolsDir = AppDirs.forHost().cache)
    val adb = l.findAdb()
    println("adb     ${adb?.let { "${it.path}  (${it.source})" } ?: "not found — install platform-tools or let the app download it"}")
    for (name in listOf("scrcpy", "ffmpeg")) {
        val t = l.findOptional(name)
        println("${name.padEnd(8)}${t?.let { "${it.path}  (${it.source})" } ?: "not found (optional)"}")
    }
}

private fun printDirs() {
    val d = AppDirs.forHost()
    println("data       ${d.data}")
    println("cache      ${d.cache}")
    println("workspace  ${d.workspace}")
}
