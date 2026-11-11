package no.agens.testlab.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.agens.testlab.AppIdentity
import no.agens.testlab.device.HostOs
import no.agens.testlab.services.AppDirs
import no.agens.testlab.services.Spaces
import no.agens.testlab.services.secrets.KeyringKeyProvider
import no.agens.testlab.services.settings.SettingsStore
import java.io.File

/**
 * `space list | create NAME [--from team.zip] [--no-setup] | use NAME | rules | allow … | migrate NAME | delete NAME --yes`:
 * one space per client, each with its own folders, vault, Keychain item, port and names, and rules
 * (`space.json`) on which GitHub owners, Jira sites and apps its profiles may use.
 */
internal fun space(opts: Opts): Int {
    val pos = opts.positional()
    val root = AppDirs.root()
    val name = pos.getOrNull(1)
    when (pos.getOrNull(0)) {
        null, "list" -> {
            val current = Spaces.current()
            for (s in Spaces.list(root)) {
                val d = AppDirs.forHost(space = s)
                val port = portOf(d)
                println("${if (s == current) "*" else " "} ${s.padEnd(16)} port $port   ${d.data.path}")
            }
            System.err.println("A space per client: ${AppIdentity.SLUG} space create NAME [--from team.zip]; --space NAME on any command, or TESTLAB_SPACE.")
        }
        "current" -> println(Spaces.current())
        "create" -> {
            val n = name ?: throw CliError("space create NAME")
            if (!Spaces.valid(n)) throw CliError("a space name is lower-case letters, digits and dashes, not default/plans/reports/skills: '$n'")
            val d = AppDirs.forHost(space = n)
            if (d.data.exists()) throw CliError("the space $n exists already (${d.data.path})")
            listOf(d.data, d.cache, d.workspace).forEach { it.mkdirs() }
            // Its rules start empty: the first profile (from --from, setup or an import) pins its owners, sites and apps.
            no.agens.testlab.services.SpaceRulesStore(d).save(no.agens.testlab.services.SpaceRules(client = opts.value("client") ?: n))
            // Its own port, so two clients' servers run side by side.
            val port = (Spaces.list(root).filter { it != n }.mapNotNull { portOf(AppDirs.forHost(space = it)) } + (AppIdentity.MCP_DEFAULT_PORT - 1)).max() + 1
            SettingsStore(d.settings).update { it.copy(mcp = it.mcp.copy(port = port)) }
            Spaces.override = n
            Spaces.use(n, root)
            println("space $n: port $port, data ${d.data.path}, workspace ${d.workspace.path}")
            opts.value("from")?.let { from -> importInto(File(from), d) }
            println("Now the space for later commands (${AppIdentity.SLUG} space use default goes back).")
            println("Claude Code for this client only: ${AppIdentity.SLUG} space claude $n (and ${AppIdentity.SLUG} space allow code PATH for its checkouts)")
            if (!opts.flag("no-setup") && System.console() != null) return setup(opts)
            println("Next: ${AppIdentity.SLUG} --space $n setup")
        }
        "use" -> {
            val n = name ?: throw CliError("space use NAME")
            if (n !in Spaces.list(root)) throw CliError("no space $n (${Spaces.list(root).joinToString()})")
            Spaces.use(n, root)
            println("using $n")
        }
        "migrate" -> {
            // Today's data (the default space) becomes a client's space.
            val n = name ?: throw CliError("space migrate NAME: moves the default space's data into the new space NAME")
            if (!Spaces.valid(n)) throw CliError("not a space name: '$n'")
            val target = AppDirs.forHost(space = n)
            if (opts.flag("rest")) return migrateRest(root, target, n)
            if (target.data.exists()) {
                // A space that only has what any command writes (settings, rules, hashes) can take the
                // data; one with its own vault, profiles or reports is someone's, and stays as it is.
                val own = listOfNotNull(
                    "a vault".takeIf { target.vault.isFile },
                    "profiles".takeIf { File(target.data, "profiles").listFiles().orEmpty().isNotEmpty() },
                    "reports".takeIf { File(target.workspace, "reports").listFiles().orEmpty().isNotEmpty() },
                )
                if (own.isNotEmpty()) {
                    val done = !root.vault.isFile && File(root.data, "profiles").listFiles().orEmpty().isEmpty()
                    throw CliError(
                        if (done) "the space $n has ${own.joinToString(" and ")} and the default space is empty: it looks migrated already (${AppIdentity.SLUG} --space $n secrets list)"
                        else "the space $n has ${own.joinToString(" and ")} of its own: migrate into a new name, or delete it first (${AppIdentity.SLUG} space delete $n --yes)",
                    )
                }
                if (File(target.data, "control.json").isFile) throw CliError("the space $n is running (control.json): stop it first")
            }
            if (File(root.data, "control.json").isFile) throw CliError("${AppIdentity.NAME} is running (control.json): stop the service and any serve first")
            // On Linux the spaces' workspaces sit inside the default one (~/testlab/<space>): those stay.
            val keep = setOf("spaces", "space", "leases") + Spaces.list(root) + n
            fun move(from: File, to: File) {
                to.mkdirs()
                from.listFiles().orEmpty().filter { it.name !in keep }.forEach { f ->
                    val dest = File(to, f.name)
                    // Into a space that already had its first files: the default space's win, except its rules.
                    if (dest.exists()) { if (f.name == "space.json") return@forEach; dest.deleteRecursively() }
                    if (!f.renameTo(dest)) throw CliError("could not move ${f.path}")
                }
            }
            if (HostOs.current() != HostOs.WINDOWS && root.vault.isFile && no.agens.testlab.services.env("VAULT_PASSPHRASE") == null) {
                // The vault's key moves with it: the new space's Keychain item gets the same key.
                runCatching { KeyringKeyProvider(HostOs.current(), n).copyFrom(KeyringKeyProvider(HostOs.current())) }
                    .onFailure { System.err.println("note: the Keychain key wasn't copied (${it.message}); a passphrase vault keeps its passphrase") }
            }
            // The default space's background service would now start an empty space: it moves too.
            val svc = no.agens.testlab.services.service.BackgroundService()
            val hadService = svc.unitFile().isFile
            if (hadService) runCatching { svc.uninstall() }
            move(root.data, target.data)
            move(root.cache, target.cache)
            if (root.workspace.isDirectory) move(root.workspace, target.workspace)
            Spaces.use(n, root)
            println("moved the default space into $n: ${target.data.path}, workspace ${target.workspace.path}")
            if (hadService) {
                Spaces.override = n
                runCatching { service(Opts(listOf("install"))) }
                    .onSuccess { println("the background service is now the $n space's") }
                    .onFailure { System.err.println("the old service is removed; install the space's: ${AppIdentity.SLUG} --space $n service install (${it.message})") }
            }
            if (File(target.data, "claude-plugin").isDirectory) {
                // The plugin's folder moved with the data: register it at its new place, under the space's name.
                Spaces.override = n
                runCatching { claudePlugin(Opts(listOf("--if-installed"))) }.onFailure { System.err.println("Claude Code plugin: ${it.message} (run ${AppIdentity.SLUG} --space $n claude-plugin)") }
            }
            println("Its port stays ${portOf(target)}. Update the Claude entries, which name the space: ${AppIdentity.SLUG} --space $n setup, or ${AppIdentity.SLUG} space claude $n.")
        }
        "claude" -> {
            // The space's own Claude Code profile: only its Testlab server, other clients' code denied.
            val n = name ?: Spaces.current()
            if (n !in Spaces.list(root)) throw CliError("no space $n")
            val dir = (opts.value("dir")?.let(::File) ?: no.agens.testlab.services.claude.SpaceClaude.defaultDir(n)).absoluteFile
            val others = Spaces.list(root).filter { it != n }.associateWith { no.agens.testlab.services.SpaceRulesStore(AppDirs.forHost(space = it)).current }
            val file = File(dir, "settings.json")
            val existing = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            dir.mkdirs()
            file.writeText(Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
                no.agens.testlab.services.claude.SpaceClaude.settings(existing, n, others, sandbox = opts.flag("sandbox"))))
            val d = AppDirs.forHost(space = n)
            File(d.data, "claude-config-dir").apply { parentFile.mkdirs() }.writeText(dir.path + "\n")
            val roots = others.values.flatMap { it.codeRoots }.distinct()
            println("Claude profile for $n: ${file.path}")
            println("  TESTLAB_SPACE=$n; the other spaces' Testlab tools denied (${others.keys.joinToString { Spaces.mcpName(it) }})")
            println("  other clients' code denied: " + roots.ifEmpty { listOf("none listed yet (${AppIdentity.SLUG} --space OTHER space allow code PATH)") }.joinToString())
            if (!opts.flag("sandbox") && roots.isNotEmpty()) println("  --sandbox also turns Claude Code's sandbox on, so Bash can't read them either")
            // The plugin (panes + this space's MCP server), registered in that profile.
            Spaces.override = n
            runCatching { claudePlugin(opts, dir) }.onFailure { System.err.println("plugin: ${it.message}") }
            println("\nStart it with:  CLAUDE_CONFIG_DIR=${dir.path} claude   (log in once with /login)")
        }
        "export" -> {
            // What the next consultant needs: the rules and the profiles. Never secrets, never local paths.
            val n = name ?: Spaces.current()
            if (n !in Spaces.list(root)) throw CliError("no space $n")
            val d = AppDirs.forHost(space = n)
            val out = File(opts.value("out") ?: "$n.${AppIdentity.SLUG}-space.zip").absoluteFile
            val rules = no.agens.testlab.services.SpaceRulesStore(d).current.copy(codeRoots = emptyList())
            val profiles = File(d.data, "profiles").listFiles { f -> File(f, "profile.json").isFile }.orEmpty().sortedBy { it.name }
            out.parentFile?.mkdirs()
            java.util.zip.ZipOutputStream(out.outputStream()).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("space.json")); z.write(no.agens.testlab.services.SpaceRules.json.encodeToString(no.agens.testlab.services.SpaceRules.serializer(), rules).toByteArray()); z.closeEntry()
                for (p in profiles) p.walkTopDown().filter { it.isFile }.forEach { f ->
                    z.putNextEntry(java.util.zip.ZipEntry("profiles/${p.name}/" + f.relativeTo(p).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
            println(out.path)
            System.err.println("${profiles.size} profile(s) and the space's rules; no secrets. A colleague: ${AppIdentity.SLUG} space create $n --from ${out.name}")
        }
        "rules" -> {
            val d = AppDirs.forHost(space = name ?: Spaces.current())
            val r = no.agens.testlab.services.SpaceRulesStore(d)
            println(if (r.enforced) no.agens.testlab.services.SpaceRules.json.encodeToString(no.agens.testlab.services.SpaceRules.serializer(), r.current) else "The default space has no rules (no ${d.rules.path}): every profile, repo and site is allowed.")
            if (r.enforced && r.current.open) System.err.println("Nothing pinned yet: the first profile this space gets decides its GitHub owners, Jira sites and apps.")
        }
        "allow", "disallow" -> {
            // space allow github OWNER | jira SITE | app FAMILY | code PATH (in the current space, or --space)
            val kind = name ?: throw CliError("space ${pos[0]} github OWNER | jira SITE | app FAMILY | code PATH")
            val v = pos.getOrNull(2) ?: throw CliError("space ${pos[0]} $kind VALUE")
            val d = AppDirs.forHost()
            val store = no.agens.testlab.services.SpaceRulesStore(d)
            val r = store.current
            fun List<String>.edit(x: String) = if (pos[0] == "allow") (this + x).distinct() else this - x
            store.save(when (kind) {
                "github" -> r.copy(githubOwners = r.githubOwners.edit(v))
                "jira" -> r.copy(jiraSites = r.jiraSites.edit(v.removePrefix("https://").trimEnd('/').lowercase()))
                "app" -> r.copy(appFamilies = r.appFamilies.edit(v))
                "code" -> r.copy(codeRoots = r.codeRoots.edit(File(v).absoluteFile.normalize().path))
                else -> throw CliError("space ${pos[0]} github | jira | app | code, not '$kind'")
            })
            println("${d.space}: ${pos[0]}ed $kind $v (${d.rules.path})")
        }
        "delete" -> {
            val n = name ?: throw CliError("space delete NAME --yes")
            if (n == Spaces.DEFAULT) throw CliError("the default space can't be deleted")
            if (n !in Spaces.list(root)) throw CliError("no space $n")
            if (!opts.flag("yes")) throw CliError("this deletes the space $n: its vault (tokens, accounts), settings, profiles, reports and caches. Add --yes")
            val d = AppDirs.forHost(space = n)
            if (File(d.data, "control.json").isFile) throw CliError("the space $n is running: stop it first")
            listOf(d.data, d.cache, d.workspace).forEach { it.deleteRecursively() }
            KeyringKeyProvider(HostOs.current(), n).delete()
            if (Spaces.current() == n) Spaces.use(Spaces.DEFAULT, root)
            println("deleted the space $n")
        }
        else -> throw CliError("usage: ${AppIdentity.SLUG} space list | current | create NAME [--from team.zip] [--no-setup] | use NAME | claude [NAME] [--dir D] [--sandbox] | export [NAME] [--out F] | rules [NAME] | allow|disallow github OWNER|jira SITE|app FAMILY|code PATH | migrate NAME | delete NAME --yes")
    }
    return 0
}

/**
 * `create --from`: a space export (space.json + profiles/) brings its rules and every profile; a
 * profile folder or zip is imported as the space's first profile (which pins its rules).
 */
private fun importInto(from: File, d: AppDirs) {
    val tmp = kotlin.io.path.createTempDirectory("space-import").toFile()
    try {
        val base = when {
            from.isDirectory -> from
            from.name.endsWith(".zip") -> tmp.also { unzipTo(from, it) }
            else -> null
        }
        val rulesFile = base?.let { File(it, "space.json") }?.takeIf { it.isFile }
        if (rulesFile == null) {
            val e = runtime.profiles.import(from)
            println("imported the profile ${e.profile.id}: ${e.profile.name}")
            return
        }
        val rules = no.agens.testlab.services.SpaceRules.json.decodeFromString(no.agens.testlab.services.SpaceRules.serializer(), rulesFile.readText())
        val store = no.agens.testlab.services.SpaceRulesStore(d)
        // Code roots are this computer's; the client name stays the one given at create unless the export has one.
        store.save(rules.copy(client = rules.client ?: store.current.client, codeRoots = store.current.codeRoots))
        println("rules from ${from.name}: GitHub ${rules.githubOwners.joinToString().ifEmpty { "none" }}, Jira ${rules.jiraSites.joinToString().ifEmpty { "none" }}, apps ${rules.appFamilies.joinToString().ifEmpty { "none" }}")
        File(base, "profiles").listFiles { f -> File(f, "profile.json").isFile }.orEmpty().sortedBy { it.name }.forEachIndexed { i, p ->
            val e = runtime.profiles.import(p, activate = i == 0)
            println("imported the profile ${e.profile.id}: ${e.profile.name}")
        }
    } finally { tmp.deleteRecursively() }
}

private fun unzipTo(zip: File, dest: File) {
    java.util.zip.ZipFile(zip).use { z ->
        for (e in z.entries()) {
            val f = File(dest, e.name).canonicalFile
            if (!f.path.startsWith(dest.canonicalPath + File.separator)) throw CliError("${zip.name}: ${e.name} points outside the folder")
            if (e.isDirectory) f.mkdirs() else { f.parentFile.mkdirs(); z.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it) } } }
        }
    }
}

private fun portOf(d: AppDirs): Int? = runCatching {
    Json.parseToJsonElement(d.settings.readText()).jsonObject["mcp"]?.jsonObject?.get("port")?.jsonPrimitive?.int
}.getOrNull() ?: if (d.data.exists() || d.settings.parentFile?.exists() == true) AppIdentity.MCP_DEFAULT_PORT else null

/**
 * `space migrate NAME --rest`: what the default space gathered after the move (reports, builds, routings)
 * goes to NAME too, without replacing anything NAME has: its vault, settings and rules stay.
 */
private fun migrateRest(root: AppDirs, target: AppDirs, n: String): Int {
    if (n !in Spaces.list(root)) throw CliError("no space $n: migrate without --rest first")
    if (File(root.data, "control.json").isFile) throw CliError("${AppIdentity.NAME} is running on the default space (control.json): stop the service and any serve first")
    val keep = setOf("spaces", "space", "leases", "space.json", "settings.json", "secrets.vault", "secret-hashes", "vault.key", "control.json") + Spaces.list(root)
    val moved = mutableListOf<String>()
    val stayed = mutableListOf<String>()
    fun merge(from: File, to: File, top: Boolean) {
        to.mkdirs()
        for (f in from.listFiles().orEmpty()) {
            if (top && f.name in keep) continue
            val dest = File(to, f.name)
            when {
                !dest.exists() -> if (f.renameTo(dest)) moved += dest.path else stayed += f.path
                f.isDirectory && dest.isDirectory -> merge(f, dest, false)
                else -> stayed += f.path
            }
        }
    }
    merge(root.data, target.data, true)
    merge(root.cache, target.cache, true)
    if (root.workspace.isDirectory) merge(root.workspace, target.workspace, true)
    println("moved ${moved.size} item(s) from the default space into $n")
    if (stayed.isNotEmpty()) println("left in the default space (${n} has its own): " + stayed.take(8).joinToString() + if (stayed.size > 8) " …" else "")
    return 0
}

