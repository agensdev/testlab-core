package no.agens.testlab.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import no.agens.testlab.services.diag.Check
import no.agens.testlab.services.diag.Diagnostics
import no.agens.testlab.services.diag.Examples
import no.agens.testlab.services.diag.SetupStatus
import no.agens.testlab.services.secrets.SecretStore
import java.io.File

/**
 * `setup`: the setup checklist (the same as `status`), fixed in place, one step at a time:
 * the GitHub token, the project (a repo picked from yours), Jira, the autopilot key, a test
 * account, the devices for the platforms the project covers (an Android phone by USB or Wi-Fi
 * pairing; on a Mac, iOS Simulators and iPhones, as `setup ios` does alone), Claude Code and
 * Claude Desktop, the background service. In a terminal on a desktop it first offers a web page
 * for the keys, Jira, test accounts and the Apple team (`--no-web` asks here instead). A step that
 * is done is skipped, so it is safe to run again; Enter skips a step. Answers come from the
 * terminal, or line by line from stdin (scripts, tests; no web page then).
 */
internal fun setup(opts: Opts): Int = runBlocking {
    if (opts.positional().firstOrNull() == "ios") return@runBlocking setupIos(opts.value("team"))
    val io = Prompter()
    val rt = runtime
    val slug = AppIdentity.SLUG
    println("${AppIdentity.NAME} setup: each step is skipped when it's done already; Enter skips one.\n")

    // The vault: opening the runtime unlocked it (the passphrase was asked for if needed).
    val keyIn = (rt.secrets as? no.agens.testlab.services.secrets.VaultSecretStore)?.providerLabel ?: "OS keystore"
    io.step("Secrets", "kept in a vault on this computer, unlocked by " + if (keyIn == "passphrase") "a passphrase (TESTLAB_VAULT_PASSPHRASE for services)" else "the $keyIn")
    if (HostOs.current() == HostOs.MAC) io.note("The first time the background service or Claude starts ${AppIdentity.NAME}, macOS may ask whether it may use its Keychain item: choose «Always Allow».")

    // Keys, Jira and test accounts on a web page instead of the terminal, when there is a browser to show it.
    var web: no.agens.testlab.mcp.McpGateway? = null
    if (System.console() != null && canBrowse() && !opts.flag("no-web") &&
        io.yes("Fill in the keys, Jira and test accounts on a web page instead of here?", true)) {
        runCatching { signedIn(opts, "setup") }.onSuccess { (url, g) ->
            web = g
            io.note(url)
            browse(url)
            io.ask("Fill in the page, then press Enter here to go on")
        }.onFailure { io.problem("the page didn't open (${it.message}): carrying on here") }
    }

    // GitHub.
    io.step("GitHub", "a token so ${AppIdentity.NAME} can read the repo, its PRs and CI builds")
    var ghOk = false
    for (attempt in 0 until 3) {
        val token = rt.githubToken()
        val check = if (token.isNullOrBlank()) null else Diagnostics.create().github(token, rt.profile.github.owner, rt.profile.github.repo, rt.githubApi())
        if (check is Check.Ok) { io.ok(check.message); ghOk = true; break }
        (check as? Check.Problem)?.let { io.problem(it.message) } ?: (check as? Check.Unreachable)?.let { io.problem(it.message) }
        if (check is Check.Unreachable) break // a new token won't help
        io.note("Make one at ${Diagnostics.TOKEN_PAGE} (classic, «repo» scope; authorize it for your organisation's SSO if it uses one).")
        val t = io.secret("GitHub token") ?: run { io.skipped(); null } ?: break
        rt.secrets.put(SecretStore.GITHUB_TOKEN, t)
    }

    // The project.
    io.step("Project", "which app: its repo, build types and Jira")
    if (rt.profile.github.repo.isNotBlank()) io.ok("${rt.profile.name} · ${rt.profile.github.repo} · ${rt.profile.builds.size} build type(s)")
    else if (io.ask("Your team's profile, a .zip or folder (Enter: make one from a repo instead)")?.let { path ->
            val f = File(path.removePrefix("~").let { if (path.startsWith("~")) System.getProperty("user.home") + it else path })
            runCatching { rt.profiles.import(f) }.onSuccess { e -> io.ok("imported ${e.profile.id}: ${e.profile.name} · ${e.profile.github.repo}") }
                .onFailure { e -> io.problem("can't import ${f.path}: ${e.message}") }.isSuccess
        } == true) Unit
    else if (!ghOk) io.note("Needs the GitHub token first.")
    else {
        io.note("${AppIdentity.NAME} reads the app's repo (pull requests, diffs, CI builds) to install and test builds; it never writes to it.")
        val listed = rt.projectSetup.repos()
        if (listed.ssoHidden.isNotEmpty()) io.note("${listed.ssoHidden.size} organisation(s) hide their repos until the token is authorized for their SSO (GitHub → Settings → Developer settings → Tokens → Configure SSO).")
        val repos = listed.repos.filter { !it.archived && !it.fork }
        val first = pickRepo(io, repos, "Where is the app's code?")
        if (first == null) io.skipped()
        else {
            val site = io.ask("Jira site, e.g. yourteam.atlassian.net (Enter: no Jira)")?.removePrefix("https://")?.trimEnd('/')
            val jira = site?.let { s -> if (!ensureJira(io, s)) null else runCatching { rt.projectSetup.scanJira(s) }.onFailure { e -> io.problem("Jira: ${e.message}") }.getOrNull() }
            makeProfile(io, first, site, jira)
            // One project per app repo: an app with Android and iOS in separate repos gets two, picked by the device in use.
            if (io.yes("Does the app have another repo, for example a separate iOS or Android app?", false)) {
                pickRepo(io, repos.filter { it.fullName != first.fullName }, "The other repo")?.let { makeProfile(io, it, site, jira) } ?: io.skipped()
            }
            io.note("Destinations, the devtest comment and the login are in each profile's profile.json (docs/PROFILES.md), or on the profiles page: $slug open profiles. A team shares one: $slug profile export.")
        }
    }

    // Jira, when the project has a site.
    val site = rt.profile.tracker.site
    if (site.isNotBlank()) {
        io.step("Jira", "$site: for the devtest queue and comments")
        ensureJira(io, site)
    }

    // The autopilot.
    if (rt.settings.current.autopilot.backend == "typesafe") {
        io.step("Autopilot", "picks what to tap toward a goal (TypeSafe's Jev; or a local Ollama model, or OpenAI's Decisions API)")
        if (rt.secrets.has(SecretStore.TYPESAFE_KEY) || System.getenv("TYPESAFE_API_KEY") != null) io.ok("TypeSafe key set")
        else {
            val k = io.secret("TypeSafe API key (Enter: skip; type «ollama» for a local model, «openai» for OpenAI)")
            when {
                k == null -> io.skipped()
                k.equals("ollama", ignoreCase = true) -> { rt.settings.update { it.copy(autopilot = it.autopilot.copy(backend = "ollama")) }; io.ok("using Ollama (${rt.settings.current.autopilot.ollamaUrls.firstOrNull() ?: "set jev.ollamaUrls"})") }
                k.equals("openai", ignoreCase = true) -> { rt.settings.update { it.copy(autopilot = it.autopilot.copy(backend = "openai")) }; ensureOpenaiKey(io) }
                else -> { rt.secrets.put(SecretStore.TYPESAFE_KEY, k); io.ok("saved") }
            }
        }
    } else if (rt.settings.current.autopilot.backend == "openai") {
        io.step("Autopilot", "picks what to tap toward a goal (OpenAI's Decisions API)")
        ensureOpenaiKey(io)
    }

    // A test account, when the project logs in.
    if (rt.profile.accounts.loginPlan != null) {
        io.step("Test account", "what handsets_login logs in with")
        val have = rt.secrets.keys().filter { it.startsWith("account.") }.map { it.split('.')[1] }.distinct()
        if (have.isNotEmpty()) io.ok(have.joinToString())
        else {
            val name = io.ask("Account name, e.g. ${rt.profile.accounts.defaults.firstOrNull() ?: "qa"} (Enter: skip)")
            if (name == null) io.skipped()
            else {
                val acct = name.lowercase().replace(Regex("[^a-z0-9_-]+"), "_")
                for (f in rt.profile.accounts.fields) io.secret(f.label)?.let { rt.secrets.put("account.$acct.${f.key}", it) }
                rt.settings.update { s -> s.copy(accounts = (rt.accounts() + acct).distinct()) }
                io.ok("account $acct saved")
            }
        }
    }

    // Devices: Android and iOS in the same run, for the platforms the project covers.
    val covered = rt.profiles.activeAll().filterValues { it.profile.id !in no.agens.testlab.core.profile.Profile.GENERIC_IDS }.keys
    val android = covered.isEmpty() || no.agens.testlab.core.Platform.ANDROID in covered
    val ios = no.agens.testlab.core.Platform.IOS in covered ||
        (covered.isEmpty() && HostOs.current() == HostOs.MAC && io.yes("Set up iOS too (Simulators and iPhones on this Mac)?", false))
    if (android) androidSteps(io, both = ios)
    if (ios) {
        if (HostOs.current() == HostOs.MAC) iosSteps(io, opts.value("team"))
        else { io.step("iOS", "Simulators and iPhones"); io.note("The project covers iOS too, which needs a Mac with Xcode: run «$slug setup ios» there.") }
    }

    // Claude.
    io.step("Claude", "Claude Code and Claude Desktop drive the phone through ${AppIdentity.NAME}'s tools")
    val claude = no.agens.testlab.services.claude.ClaudeCli.locate(rt.settings.current.claude.binary)
    if (claude == null) io.note("Claude Code isn't installed (https://claude.com/claude-code); run this again after installing it to add the plugin.")
    else if (io.yes("Install or update the ${AppIdentity.NAME} plugin for Claude Code (the phone and reports as panes)?", true)) {
        runCatching { claudePlugin(Opts(emptyList())) }.onFailure { io.problem(it.message ?: "the plugin install failed") }
    }
    desktopConfig()?.let { file ->
        val launcher = runCatching { no.agens.testlab.services.service.BackgroundService.selfCommand("no.agens.testlab.cli.MainKt", console = true) }.getOrNull()
        val entry = runCatching { (Json.parseToJsonElement(file.readText()).jsonObject["mcpServers"] as? JsonObject)?.get(no.agens.testlab.services.Spaces.mcpName()) }.getOrNull()
        when {
            launcher == null -> {}
            entry != null && entry.toString().contains(Regex(Regex.escape(launcher.first().replace("\\", "\\\\")))) -> io.ok("Claude Desktop has the $slug entry")
            io.yes("Add $slug to Claude Desktop (${file.path})?", true) -> {
                writeDesktopEntry(file, launcher)
                io.ok("added: restart Claude Desktop to load it")
            }
        }
    }

    // The background service.
    io.step("Background service", "keeps the server running for Claude, from login")
    val svc = runCatching { no.agens.testlab.services.service.BackgroundService().status() }.getOrNull()
    if (svc != null && svc.state != no.agens.testlab.services.service.BackgroundService.State.NOT_INSTALLED) io.ok("installed (${svc.state.name.lowercase().replace('_', ' ')}), starts ${no.agens.testlab.services.service.BackgroundService().startsAt().words}")
    else if (io.yes("Install it (starts at login)?", true)) {
        // A Linux user unit starts at login only; a rig nobody logs in to needs lingering to start at boot.
        val boot = HostOs.current() == HostOs.LINUX && io.yes("Start it at boot too, before anyone logs in (for a machine nobody logs in to)?", false)
        runCatching { service(Opts(listOfNotNull("install", if (boot) "--boot" else null))) }.onFailure { io.problem(it.message ?: "install failed") }
    }
    else io.skipped()

    // What is left.
    println()
    val left = SetupStatus(rt).check(online = true).filter { it.state == SetupStatus.State.TODO }
    web?.stop()
    if (left.isEmpty()) {
        println("All set.\n")
        println(Examples.forTerminal(rt.profiles.activeAll().values.map { it.profile }.filter { it.id !in no.agens.testlab.core.profile.Profile.GENERIC_IDS }))
        println("\nThe phone's screen: /testlab-screen in Claude Code, or $slug open screen. The guide: $slug open guide")
    } else {
        println("Still to do:")
        left.sortedBy { !it.essential }.forEach { println("  ${if (it.essential) "✗" else "!"} ${it.label}: ${it.text}" + (it.cli?.let { c -> "  ($c)" } ?: "")) }
        println("The guide walks through it all: $slug open guide")
        println()
        println(Examples.forTerminal(rt.profiles.activeAll().values.map { it.profile }.filter { it.id !in no.agens.testlab.core.profile.Profile.GENERIC_IDS }))
    }
    rt.close()
    if (left.any { it.essential }) 1 else 0
}

/**
 * A repo from the token's list: first the account or organisation (most people see many), then its
 * repos with the ones that look like a mobile app first and a hint of their platform. Typing narrows
 * either list; `owner/name` picks a repo directly.
 */
private fun pickRepo(io: Prompter, repos: List<no.agens.testlab.services.github.UserRepo>, title: String): no.agens.testlab.services.github.UserRepo? {
    if (repos.isEmpty()) { io.note("The token reads no repos: check its «repo» scope and SSO authorization."); return null }
    val owners = repos.groupBy { it.fullName.substringBefore('/') }.entries.sortedByDescending { e -> e.value.maxOf { it.pushedAt.orEmpty() } }
    val inOwner = if (owners.size == 1) owners.single().value else {
        val i = io.pick("$title — the account or organisation it is under", owners.map { (o, rs) ->
            "$o  (${rs.size} repo(s)" + rs.count { Examples.platformHint(it) != null }.let { n -> if (n > 0) ", $n look like apps)" else ")" }
        }, filterable = true) ?: return null
        owners[i].value
    }
    val sorted = Examples.appFirst(inOwner)
    val i = io.pick("$title — the repo (apps first, newest first)", sorted.map { r ->
        r.fullName + "  " + listOfNotNull(if (r.private) "private" else "public", r.language, Examples.platformHint(r)).joinToString(" · ")
    }, filterable = true) ?: return null
    return sorted[i]
}

/**
 * A profile for [r]: its CI's APK builds make it an Android project; a repo without them whose
 * language says iOS becomes an iOS project (its simulator build types are added on the profiles page).
 */
private suspend fun makeProfile(io: Prompter, r: no.agens.testlab.services.github.UserRepo, site: String?, jira: no.agens.testlab.services.profile.ProjectSetup.JiraScan?) {
    val rt = runtime
    val scan = runCatching { rt.projectSetup.scanRepo(r.fullName) }.getOrElse { io.problem("can't read ${r.fullName}: ${it.message}"); return }
    scan.problems.forEach { io.note(it) }
    val name = r.fullName.substringAfter('/')
    val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val ios = scan.candidates.isEmpty() && Examples.platformHint(r) == "iOS?"
    val p = rt.projectSetup.draft(id, name, scan, scan.candidates, jira, jira?.projects?.singleOrNull()?.let { listOf(it.first) }.orEmpty(), jira?.queueLabels.orEmpty(), jira?.approvedLabel, null, null)
        .let { if (site != null && jira == null) it.copy(tracker = it.tracker.copy(site = site)) else it }
        .let { if (ios) it.copy(platform = no.agens.testlab.core.Platform.IOS) else it }
    rt.profiles.save(p); rt.profiles.activate(p.id)
    when {
        ios -> io.ok("profile ${p.id} (iOS) for ${r.fullName}: PRs and diffs work now; add its simulator build type on the profiles page to install builds")
        p.builds.isEmpty() -> io.ok("profile ${p.id} for ${r.fullName}, but no CI build with an APK was found in its recent runs: PRs and diffs work; builds need a workflow that uploads an APK artifact (or a release with one)")
        else -> io.ok("profile ${p.id} for ${r.fullName}: ${p.builds.size} build type(s)" + (p.builds.firstOrNull { it.default }?.let { " (PR builds: ${it.workflowFile})" } ?: ""))
    }
}

/** An Android phone: adb (downloaded on request), then a device by cable or Wi-Fi pairing. */
private suspend fun androidSteps(io: Prompter, both: Boolean) {
    val rt = runtime
    io.step(if (both) "Android phone" else "Phone", "a phone with USB debugging, or over Wi-Fi (Android 11+ wireless debugging)")
    if (rt.locateAdb() == null) {
        io.note("adb (Android platform-tools) isn't installed. ${AppIdentity.NAME} can download Google's copy; its terms: ${no.agens.testlab.services.tools.PlatformTools.TERMS}")
        if (io.yes("Download platform-tools and accept those terms?", false)) {
            println("  " + rt.platformTools.install(acceptedTerms = true) { _, _ -> })
        }
    }
    if (rt.locateAdb() != null) for (it in 0 until 4) {
        val devs = runCatching { rt.listDevices() }.getOrDefault(emptyList())
        val ready = devs.filter { it.ready }
        if (ready.isNotEmpty()) { io.ok(ready.joinToString { d -> (d.model?.replace('_', ' ') ?: d.serial) + if (d.isEmulator) " (emulator)" else "" }); break }
        devs.filter { !it.ready }.forEach { io.problem("${it.serial} is ${it.state}" + (it.hint?.let { h -> ": $h" } ?: "")) }
        if (devs.isEmpty() && it == 0) no.agens.testlab.services.diag.DeviceHelp.steps().forEachIndexed { i, s -> io.note("${i + 1}. $s") }
        val how = io.ask("Plug it in and press Enter, type «wifi» to pair over Wi-Fi, «emulator» for an emulator on this computer (or «skip»)") ?: ""
        when (how.lowercase()) {
            "skip" -> { io.skipped(); break }
            "emulator" -> setupEmulator(io)
            "wifi" -> {
                io.note("On the phone: Developer options → Wireless debugging → Pair device with pairing code.")
                val pairAt = io.ask("Pairing address (IP:port on that dialog)")
                val code = pairAt?.let { io.ask("Pairing code (6 digits)") }
                if (pairAt != null && code != null) println("  " + rt.adb().pair(pairAt, code).trim())
                val at = io.ask("Connect address (IP:port on the Wireless debugging screen)")
                if (at != null) println("  " + rt.adb().connect(at).trim())
            }
        }
    }
}

/** An Android emulator: Google's Android CLI (downloaded on request) makes one, then it is started. */
private fun setupEmulator(io: Prompter) {
    val rt = runtime
    val emus = rt.emulators
    if (!emus.canCreate && emus.avds().isEmpty()) {
        io.note("Google's Android CLI makes emulators (it downloads the emulator and a system image, a few GB): ${no.agens.testlab.services.tools.AndroidCli.PAGE}")
        if (!io.yes("Download the Android CLI into ~/.local/bin?", true)) return
        runCatching { println("  " + runBlocking { rt.androidCli.install() }) }.onFailure { io.problem(it.message ?: "the download failed"); return }
    }
    val name = emus.avds().let { list ->
        when {
            list.isEmpty() -> runCatching { io.note("making a medium_phone emulator (some minutes the first time) …"); emus.create("medium_phone") }.getOrElse { io.problem(it.message ?: "couldn't make it"); return }
            list.size == 1 -> list.single().name
            else -> io.pick("Which emulator?", list.map { it.name })?.let { list[it].name } ?: return
        }
    }
    io.note("starting $name …")
    runCatching { emus.start(name, headless = !no.agens.testlab.services.runtime.Browser.available()) }
        .onSuccess { io.ok("$name is running ($it). An emulator suits flows and layouts; camera, bank-ID logins, NFC and notifications need a real phone.") }
        .onFailure { io.problem(it.message ?: "it didn't start") }
}

/** The OpenAI key for the `openai` autopilot backend: asked for once when neither the vault nor OPENAI_API_KEY has it. */
private fun ensureOpenaiKey(io: Prompter) {
    val rt = runtime
    if (rt.secrets.has(SecretStore.OPENAI_KEY) || System.getenv("OPENAI_API_KEY") != null) { io.ok("OpenAI key set (${rt.settings.current.autopilot.openaiModel})"); return }
    println("    Make one at ${Diagnostics.OPENAI_KEY_PAGE}: sign in, pick the project, «Create new secret key», and copy it")
    println("    (OpenAI shows it once). The API is paid per use, billed apart from ChatGPT: the account needs API credit.")
    val k = io.secret("OpenAI API key (Enter: skip)")
    if (k == null) { io.skipped(); return }
    rt.secrets.put(SecretStore.OPENAI_KEY, k)
    when (val c = runBlocking { Diagnostics.create().openai(k) }) {
        is Check.Ok -> io.ok("saved and checked; using OpenAI's Decisions API (${rt.settings.current.autopilot.openaiModel})")
        is Check.Problem -> println("    saved, but: ${c.message}" + (c.fixUrl?.let { " ($it)" } ?: ""))
        else -> println("    saved; not checked: ${c.message}")
    }
}

/** Jira e-mail and API token for [site], asked for until they work (three tries). False = skipped. */
private suspend fun ensureJira(io: Prompter, site: String): Boolean {
    val rt = runtime
    repeat(3) { attempt ->
        val email = no.agens.testlab.services.secrets.SecretStore.scoped(rt.secrets, SecretStore.JIRA_EMAIL, site)
        val token = no.agens.testlab.services.secrets.SecretStore.scoped(rt.secrets, SecretStore.JIRA_TOKEN, site)
        val check = if (email.isNullOrBlank() || token.isNullOrBlank()) null else Diagnostics.create().jira(site, email, token, rt.settings.current.connections.jiraBase(site))
        if (check is Check.Ok) { io.ok(check.message); return true }
        (check as? Check.Problem)?.let { io.problem(it.message) } ?: (check as? Check.Unreachable)?.let { io.problem(it.message) }
        if (attempt == 0) io.note("$site needs your e-mail and an API token: ${Diagnostics.JIRA_TOKEN_PAGE}")
        val e = io.ask("Jira e-mail" + (email?.let { " [$it]" } ?: "")) ?: email ?: run { io.skipped(); return false }
        val t = io.secret("Jira API token") ?: run { io.skipped(); return false }
        rt.secrets.put("${SecretStore.JIRA_EMAIL}@$site", e)
        rt.secrets.put("${SecretStore.JIRA_TOKEN}@$site", t)
    }
    return false
}

/** Claude Desktop's config file, when Claude Desktop is installed (its folder exists). */
private fun desktopConfig(): File? {
    val home = System.getProperty("user.home")
    val dir = when (HostOs.current()) {
        HostOs.MAC -> File(home, "Library/Application Support/Claude")
        HostOs.WINDOWS -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Claude")
        HostOs.LINUX -> File(home, ".config/Claude")
    }
    return if (dir.isDirectory) File(dir, "claude_desktop_config.json") else null
}

/** Merges our stdio entry into Claude Desktop's config, keeping everything else (a .bak first). */
internal fun writeDesktopEntry(file: File, launcher: List<String>) {
    val old = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
    if (file.isFile) file.copyTo(File(file.path + ".bak"), overwrite = true)
    val entry = buildJsonObject {
        put("command", launcher.first())
        put("args", buildJsonArray { (launcher.drop(1) + no.agens.testlab.services.Spaces.args() + "--mcp-stdio").forEach { add(JsonPrimitive(it)) } })
    }
    val servers = JsonObject(((old["mcpServers"] as? JsonObject) ?: JsonObject(emptyMap())) + (no.agens.testlab.services.Spaces.mcpName() to entry))
    file.parentFile?.mkdirs()
    file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(old + ("mcpServers" to servers))) + "\n")
}

/** Questions on the terminal, or line by line from stdin when there is none. Blank = skip. */
private class Prompter {
    private val console = System.console()
    private val stdin by lazy { System.`in`.bufferedReader() }
    private var n = 0

    fun step(title: String, what: String) { n++; println("\n$n. $title — $what") }
    fun ok(s: String) = println("   ✓ $s")
    fun problem(s: String) = println("   ✗ $s")
    fun note(s: String) = println("   · $s")
    fun skipped() = println("   skipped")

    private fun line(prompt: String): String? {
        print("   $prompt: "); System.out.flush()
        return (if (console != null) console.readLine() else stdin.readLine().also { println() })?.trim()
    }

    fun ask(prompt: String): String? = line(prompt)?.takeIf { it.isNotEmpty() }

    fun secret(prompt: String): String? {
        val v = if (console != null) console.readPassword("   $prompt (hidden): ")?.let(::String) else { print("   $prompt: "); stdin.readLine().also { println() } }
        return v?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun yes(prompt: String, default: Boolean): Boolean {
        val a = line("$prompt ${if (default) "[Y/n]" else "[y/N]"}")?.lowercase() ?: return default
        return if (a.isEmpty()) default else a.startsWith("y") || a.startsWith("j")
    }

    /** A numbered list; with [filterable], text narrows it first. Returns the index, null = skipped. */
    fun pick(title: String, items: List<String>, filterable: Boolean = false): Int? {
        if (items.isEmpty()) { note("none found"); return null }
        var shown = items.indices.toList()
        while (true) {
            println("   $title:")
            shown.take(20).forEachIndexed { i, idx -> println("     ${i + 1}. ${items[idx]}") }
            if (shown.size > 20) println("     … ${shown.size - 20} more: type part of the name")
            val a = ask(if (filterable) "Number, or text to narrow the list (Enter: skip)" else "Number (Enter: skip)") ?: return null
            a.toIntOrNull()?.let { k -> if (k in 1..minOf(20, shown.size)) return shown[k - 1] }
            if (filterable) shown = items.indices.filter { a.lowercase() in items[it].lowercase() }.ifEmpty { note("nothing matches «$a»"); items.indices.toList() }
        }
    }
}

/** `setup ios`: only the iOS steps of `setup` ([iosSteps]). */
private suspend fun setupIos(team: String? = null): Int {
    println("${AppIdentity.NAME} setup for iOS\n")
    return iosSteps(Prompter(), team)
}

/**
 * What iOS needs, in order: Xcode, AXe (screens, taps and keys; Homebrew), Node.js, Appium with its
 * XCUITest driver (installed into ${AppIdentity.NAME}'s cache), and WebDriverAgent built once on a booted
 * simulator (with AXe it only hides the keyboard and streams the live view). Then real iPhones: the
 * Apple team that signs WebDriverAgent for them ([team], `--team`, else asked when a paired iPhone is
 * there), and WebDriverAgent built on the first one. 1 when something essential is missing.
 */
private suspend fun iosSteps(io: Prompter, team: String?): Int {
    val rt = runtime
    io.step("Xcode", "iOS Simulators, real iPhones and their command-line tools")
    val x = rt.xcrun
    if (x == null || !x.available()) {
        io.problem(if (HostOs.current() != HostOs.MAC) "iOS needs a Mac with Xcode: do this part on one" else "xcrun simctl does not answer: install Xcode from the App Store, open it once, then run sudo xcode-select -s /Applications/Xcode.app")
        return 1
    }
    var sims = x.simulators()
    io.ok("${sims.size} simulator(s); booted: ${sims.filter { it.booted }.joinToString { it.name }.ifEmpty { "none" }}")
    if (sims.none { it.isPhone } && io.yes("No iPhone simulator yet: make one now? (it downloads the iOS runtime first if there is none: several GB)", true)) {
        runCatching { rt.simulators.create { io.note(it) } }
            .onSuccess { s -> io.ok("made ${s.name} (${s.runtime})"); sims = x.simulators() }
            .onFailure { io.problem(it.message ?: "couldn't make one: add it in Xcode → Window → Devices and Simulators") }
    }
    io.step("Xcode's MCP server", "the App Store's crash and performance reports for Claude (Xcode 27, optional)")
    val xs = rt.xcode.status()
    val major = xs.xcode?.let { Regex("Xcode (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
    when {
        major < 27 -> io.note("${xs.xcode ?: "Xcode"} has none; Xcode 27 or newer brings it. Skipped.")
        xs.headless != null -> io.ok("headless mode is on" + if (xs.pending.isNotEmpty()) "; ${xs.pending.size} request(s) wait for approval: ${AppIdentity.SLUG} xcode approve" else "")
        io.yes("Turn on its headless mode, so it works without an Xcode window? (sudo: macOS asks for your password)", true) ->
            if (ProcessBuilder("sudo", "/usr/bin/xcrun", "mcp-server", "enable").inheritIO().start().waitFor() == 0) io.ok("on; Xcode asks once for an approval when Claude first uses it: ${AppIdentity.SLUG} xcode approve")
            else io.problem("didn't turn on: run ${AppIdentity.SLUG} xcode enable later")
        else -> io.skipped()
    }
    io.step("AXe", "reads and taps the simulator's screen, like uiautomator on Android")
    if (no.agens.testlab.device.ios.AxeCli.locate() != null) io.ok(no.agens.testlab.device.ios.AxeCli.locate()!!)
    else {
        val brew = listOf("/opt/homebrew/bin/brew", "/usr/local/bin/brew").firstOrNull { File(it).canExecute() }
        if (brew == null) io.problem("not found: install Homebrew (brew.sh), then ${no.agens.testlab.device.ios.AxeCli.INSTALL}; without it every screen goes through WebDriverAgent (slower)")
        else {
            io.note("${no.agens.testlab.device.ios.AxeCli.INSTALL}")
            val p = ProcessBuilder(brew, "install", "cameroncooke/axe/axe").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().forEachLine { if (it.isNotBlank()) println("     $it") }
            if (p.waitFor() == 0 && no.agens.testlab.device.ios.AxeCli.locate() != null) io.ok("installed") else io.problem("brew install failed: run ${no.agens.testlab.device.ios.AxeCli.INSTALL} yourself")
        }
    }
    io.step("Node.js", "Appium runs on it")
    val node = rt.appium.nodeDir() ?: run { io.problem("not found: brew install node (or the installer from nodejs.org), then run this again"); return 1 }
    io.ok(node.path)
    io.step("Appium", "drives the simulator's screen (Appium ${no.agens.testlab.device.ios.Appium.APPIUM_VERSION}, XCUITest driver ${no.agens.testlab.device.ios.Appium.XCUITEST_VERSION})")
    if (rt.appium.installed()) io.ok("installed") else {
        io.note("installing with npm into ${File(rt.dirs.cache, "appium").path} (a minute or two)")
        runCatching { rt.appium.install { line -> if (line.isNotBlank()) println("     $line") } }
            .onSuccess { io.ok("installed") }.onFailure { io.problem(it.message ?: "install failed"); return 1 }
    }
    io.step("WebDriverAgent", "built by Xcode once, on a booted simulator")
    val running = sims.firstOrNull { it.booted && it.isPhone } ?: sims.firstOrNull { it.booted }
    val booted = running ?: sims.filter { it.isPhone }.maxByOrNull { it.runtime }?.let { s ->
        if (!io.yes("Boot ${s.name} (${s.runtime}) for it?", true)) null
        else runCatching { rt.simulators.start(s.udid) }.onFailure { io.problem(it.message ?: "it didn't boot") }.getOrNull()
    }
    val simResult = if (booted == null) {
        io.note("No simulator is booted: start one (Simulator.app, or ask Claude for handsets_simulator_start(\"iPhone 17\")), then run this again.")
        0
    } else {
        io.note("connecting to ${booted.name}: building WebDriverAgent the first time takes a few minutes")
        runCatching { rt.use(booted.udid).also { rt.iosStartWda(booted.udid) } }.fold(
            { dev -> io.ok("${booted.name} is ready: ${dev.screenSize().let { "${it.first}×${it.second}" }}"); rt.drop(booted.udid); 0 },
            { io.problem("${it.message}: see ${File(rt.dirs.data, "logs/appium.log").path}"); 1 },
        )
    }
    return maxOf(simResult, setupIphones(io, team))
}

/**
 * Real iPhones: their screens are read and tapped through WebDriverAgent built for the phone, which
 * Xcode signs with the user's Apple developer team. Sets the team ([team], else asked) and connects
 * the first paired iPhone, which builds and installs WebDriverAgent on it.
 */
private suspend fun setupIphones(io: Prompter, team: String?): Int {
    val rt = runtime
    io.step("Real iPhones", "driven through WebDriverAgent, signed with your Apple developer team")
    val phones = runCatching { rt.listDevices() }.getOrDefault(emptyList()).filter { it.kind == "iphone" || it.kind == "ipad" }
    val given = team?.trim()?.uppercase()
    if (given != null && !no.agens.testlab.device.ios.Appium.TEAM_ID.matches(given)) {
        io.problem("«$given» is not a team id: it is 10 letters and digits (developer.apple.com → Account → Membership, or Xcode → Settings → Accounts)")
        return 1
    }
    var current = rt.settings.current.device.iosTeamId
    if (given != null) {
        rt.settings.update { it.copy(device = it.device.copy(iosTeamId = given)) }
        current = given
    }
    if (current == null) {
        if (phones.isEmpty()) { io.note("No iPhone is paired: connect one by cable, unlock it and trust this Mac, then run this again (or set the team now with --team)."); return 0 }
        io.note("Paired: ${phones.joinToString { it.model ?: it.serial }}. Xcode signs WebDriverAgent for them with your team (an Apple ID signed in under Xcode → Settings → Accounts; a free personal team works too).")
        val answer = io.ask("Apple team id (10 characters, Enter skips): ")?.trim()?.uppercase() ?: run { io.note("skipped: the iPhones take builds and app starts only"); return 0 }
        if (!no.agens.testlab.device.ios.Appium.TEAM_ID.matches(answer)) { io.problem("«$answer» is not a team id (10 letters and digits)"); return 1 }
        rt.settings.update { it.copy(device = it.device.copy(iosTeamId = answer)) }
        current = answer
    }
    io.ok("team $current; WebDriverAgent's bundle id ${rt.phoneDriving()?.wdaBundleId}")
    val phone = phones.firstOrNull { it.ready } ?: run {
        phones.firstOrNull()?.let { io.problem("${it.model ?: it.serial}: ${it.detail}") } ?: io.note("No iPhone is paired yet: connect one by cable, unlock it and trust this Mac.")
        return 0
    }
    io.note("On ${phone.model}: Settings → Privacy & Security → Developer Mode on, Settings → Developer → Enable UI Automation on, and keep it unlocked.")
    io.note("connecting to ${phone.model}: Xcode builds WebDriverAgent and installs it on the phone (a few minutes the first time)")
    return runCatching { rt.use(phone.serial) }.fold(
        { dev -> io.ok("${phone.model} is ready: ${dev.screenSize().let { "${it.first}×${it.second}" }}"); rt.drop(phone.serial); 0 },
        { io.problem("${it.message}: see ${File(rt.dirs.data, "logs/appium.log").path}"); 1 },
    )
}
