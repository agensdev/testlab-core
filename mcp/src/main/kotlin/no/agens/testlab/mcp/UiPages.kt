package no.agens.testlab.mcp

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.launch
import no.agens.testlab.AppIdentity
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.core.profile.problems
import no.agens.testlab.services.profile.ProjectSetup
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.SecretStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The pages `testlab open …` shows in the browser, served by the local server: the live screen,
 * a report, the profiles (make one from a repo, edit, import, export) and hand-backs.
 *
 * Getting in: the CLI (bearer token) asks for a one-time ticket, opens `/ui/PAGE?t=TICKET`, and
 * the page trades it for an HttpOnly, SameSite=Strict cookie and loads again without the ticket.
 * With that cookie the page calls `/api/v1` from its own origin (loopback only); a request from
 * any other origin never carries it. The token never reaches the browser.
 */
class UiPages(private val rt: LabRuntime) {
    private val random = SecureRandom()
    private val tickets = ConcurrentHashMap<String, Long>()
    private val sessions = ConcurrentHashMap<String, Long>()
    private val scans = ConcurrentHashMap<String, ProjectSetup.RepoScan>()
    private val json = Json { prettyPrint = true; encodeDefaults = false }

    private fun secret() = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /** A ticket for one page load, valid a minute. */
    fun ticket(): String = secret().also { tickets[it] = System.currentTimeMillis() + 60_000 }

    /** Whether [call] carries a live page session. */
    fun session(call: ApplicationCall): Boolean {
        val id = call.request.cookies[COOKIE] ?: return false
        val until = sessions[id] ?: return false
        if (until < System.currentTimeMillis()) { sessions.remove(id); return false }
        return true
    }

    fun Route.mountUi() {
        get("/ui/{page}") {
            val page = call.parameters["page"].orEmpty()
            if (page !in PAGES) { call.respondText("no page $page", status = HttpStatusCode.NotFound); return@get }
            call.request.queryParameters["t"]?.let { t ->
                val until = tickets.remove(t)
                if (until == null || until < System.currentTimeMillis()) { call.respondText(page("Link expired", "<p>This link was used already or is older than a minute. Run <code>${AppIdentity.SLUG} open $page</code> again.</p>"), ContentType.Text.Html, HttpStatusCode.Unauthorized); return@get }
                val id = secret(); sessions[id] = System.currentTimeMillis() + 12 * 3600_000L
                call.response.cookies.append(Cookie(COOKIE, id, path = "/", httpOnly = true, maxAge = 12 * 3600, extensions = mapOf("SameSite" to "Strict")))
                // Again without the ticket, so it isn't left in the address bar or the history.
                val rest = call.request.queryParameters.entries().filter { it.key != "t" }.flatMap { (k, vs) -> vs.map { "$k=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) } }
                call.respondRedirect("/ui/$page" + if (rest.isEmpty()) "" else "?" + rest.joinToString("&"))
                return@get
            }
            if (!session(call)) { call.respondText(page("Open it from the terminal", "<p>Run <code>${AppIdentity.SLUG} open $page</code>: it opens this page signed in.</p>"), ContentType.Text.Html, HttpStatusCode.Unauthorized); return@get }
            call.response.header("Cache-Control", "no-store")
            call.response.header("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
            call.respondText(html(page), ContentType.Text.Html)
        }
    }

    /** `/api/v1` routes the pages use (mounted inside ControlApi's route). */
    fun Route.mountApi() {
        post("/ui/ticket") { ok { val t = ticket(); buildJsonObject { put("ticket", t) } } }
        // Profiles: the whole JSON to edit, save (checked), delete, export.
        get("/profiles/{id}/json") { ok { val e = rt.profiles.get(param("id")) ?: throw ApiError(HttpStatusCode.NotFound, "no profile ${param("id")}"); buildJsonObject { put("bundled", e.bundled); put("dir", e.dir?.path ?: ""); put("json", json.encodeToString(Profile.serializer(), e.profile)) } } }
        put("/profiles/{id}/json") { ok { saveProfile(param("id"), body()) } }
        delete("/profiles/{id}") { ok { rt.profiles.delete(param("id")); buildJsonObject { put("deleted", param("id")) } } }
        get("/profiles/{id}/export") {
            val id = param("id")
            val f = kotlin.io.path.createTempFile("profile", ".zip").toFile()
            try {
                rt.profiles.export(id, f)
                call.response.header("Content-Disposition", "attachment; filename=\"$id.${AppIdentity.SLUG}-profile.zip\"")
                call.respondBytes(f.readBytes(), ContentType.Application.Zip)
            } catch (e: Exception) { call.respondText(err(e.message ?: "export failed"), ContentType.Application.Json, HttpStatusCode.BadRequest) } finally { f.delete() }
        }
        post("/profiles/import") {
            val bytes = call.receive<ByteArray>()
            val f = kotlin.io.path.createTempFile("profile", ".zip").toFile()
            try {
                f.writeBytes(bytes)
                val e = rt.profiles.import(f)
                call.respondText(buildJsonObject { put("id", e.profile.id); put("name", e.profile.name) }.toString(), ContentType.Application.Json)
            } catch (e: Exception) { call.respondText(err("not a profile: ${e.message}"), ContentType.Application.Json, HttpStatusCode.BadRequest) } finally { f.delete() }
        }
        // Making one: the repos, a repo's build candidates, a Jira site's projects and labels, a draft.
        get("/setup/repos") { ok { repos() } }
        post("/setup/scan") { ok { scan(body()) } }
        post("/setup/jira") { ok { jira(body()) } }
        post("/setup/draft") { ok { draft(body()) } }
        // Reports, newest first.
        get("/reports") { ok { buildJsonObject { putJsonArray("reports") { for (r in rt.reports.list()) addJsonObject {
            put("id", r.id); put("title", r.title); put("created", r.created); put("shots", r.shots); put("video", r.video); put("written", r.written); r.ticket?.let { put("ticket", it) }
        } } } } }
        // Devices: phones advertising Wireless debugging nearby, connecting (and pairing) one, letting one go.
        get("/devices/nearby") { ok {
            val connected = runCatching { rt.listDevices() }.getOrDefault(emptyList())
            val nearby = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { rt.adb().mdnsServices() }.getOrDefault(emptyList()) }
            buildJsonObject { putJsonArray("nearby") { for ((name, kind, addr) in nearby) if (kind == "connect" && connected.none { name in it.serial || it.serial == addr }) addJsonObject { put("name", name); put("address", addr) } } }
        } }
        post("/devices/wireless") { ok {
            val b = body()
            val address = b.s("address") ?: throw ApiError(HttpStatusCode.BadRequest, "address is required (IP:port on the phone's Wireless debugging screen)")
            val out = mutableListOf<String>()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                b.s("pairing_code")?.let { code -> out += rt.adb().pair(b.s("pairing_address") ?: throw ApiError(HttpStatusCode.BadRequest, "pairing needs pairing_address too (on the pairing dialog)"), code).trim() }
                out += rt.adb().connect(address).trim()
            }
            buildJsonObject { put("said", out.joinToString("\n")); put("connected", out.last().contains("connected to")) }
        } }
        post("/devices/disconnect") { ok {
            val address = body().s("address") ?: throw ApiError(HttpStatusCode.BadRequest, "address is required")
            runCatching { rt.drop(address) }
            buildJsonObject { put("said", kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { rt.adb().disconnect(address) }) }
        } }
        // Settings: all of them as JSON, changed by merging a partial object (checked by the Settings type).
        get("/settings") { ok { settingsJson() } }
        post("/settings") { ok { patchSettings(body()) } }
        get("/setup") { ok {
            val items = no.agens.testlab.services.diag.SetupStatus(rt).check(online = false)
            buildJsonObject { putJsonArray("items") { for (i in items) addJsonObject {
                put("group", i.group.title); put("label", i.label); put("state", i.state.name.lowercase()); put("text", i.text); put("essential", i.essential)
                i.cli?.let { put("cli", it) }; i.url?.let { put("url", it) }; i.place?.let { put("place", it.name.lowercase()) }
            } } }
        } }
        post("/proxyman/test") { ok {
            val h = rt.proxyman.health()
            buildJsonObject {
                put("healthy", h.healthy); put("proxy_reachable", h.proxyReachable); h.viaProxyStatus?.let { put("via_proxy_status", it) }; h.viaProxySeconds?.let { put("via_proxy_seconds", it) }; put("mcp", h.mcp)
                putJsonArray("devices") { for (d in h.devices) addJsonObject { put("serial", d.serial); d.proxy?.let { put("proxy", it) }; put("ours", d.ours) } }
            }
        } }
        post("/devices/{serial}/proxy") { ok {
            val serial = param("serial")
            if (!rt.settings.current.proxyman.enabled) throw ApiError(HttpStatusCode.Conflict, "Proxyman is off: turn it on in Settings")
            val st = if (body().s("on") == "false") rt.proxyman.unroute(serial) else rt.proxyman.route(serial)
            buildJsonObject { put("serial", st.serial); st.proxy?.let { put("proxy", it) }; put("ours", st.ours); put("cert", no.agens.testlab.services.proxyman.Proxyman.certHint(rt.profile)) }
        } }
        post("/devices/{serial}/release") { ok {
            val steps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { rt.drop(param("serial")); rt.leases.release(param("serial")) }
            buildJsonObject { put("serial", param("serial")); putJsonArray("steps") { steps.forEach { add(JsonPrimitive(it)) } } }
        } }
        get("/devices/proxy") { ok {
            val ready = runCatching { rt.listDevices().filter { it.ready } }.getOrDefault(emptyList())
            buildJsonObject { put("enabled", rt.settings.current.proxyman.enabled); putJsonArray("devices") { for (d in ready) runCatching { rt.leases.peek { rt.proxyman.status(d.serial) } }.getOrNull()?.let { st -> addJsonObject { put("serial", st.serial); st.proxy?.let { put("proxy", it) }; put("ours", st.ours) } } } }
        } }
        // Hand-backs: plans waiting for a person.
        get("/handbacks") { ok { buildJsonObject { putJsonArray("handbacks") { for (p in rt.handBacks.list()) addJsonObject {
            put("id", p.id); put("serial", p.serial); put("step", p.step); put("reason", p.reason); put("paused_at", p.pausedAt); put("at", p.at); put("source", p.source); put("top", p.top)
        } } } } }
        get("/handbacks/{id}/marks") { ok { val marks = rt.handBacks.marks(param("id")); buildJsonObject { putJsonArray("marks") { for (m in marks) addJsonObject { put("key", m.key); put("label", m.label); put("x", m.x); put("y", m.y) } } } } }
        post("/handbacks/{id}") { ok {
            val b = body()
            val r = when (b.s("action")) {
                "tap" -> no.agens.testlab.services.plans.Resolution.Tapped(b.s("x")?.toIntOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "x is required"), b.s("y")?.toIntOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "y is required"))
                "skip" -> no.agens.testlab.services.plans.Resolution.Skip
                "retry" -> no.agens.testlab.services.plans.Resolution.Retry
                "stop" -> no.agens.testlab.services.plans.Resolution.Stop
                else -> throw ApiError(HttpStatusCode.BadRequest, "action is tap, skip, retry or stop")
            }
            val said = try { rt.handBacks.resolve(param("id"), r) } catch (e: IllegalArgumentException) { throw ApiError(HttpStatusCode.NotFound, e.message ?: "not waiting") }
            buildJsonObject { put("done", said) }
        } }
        // Tokens, written only (never read back): what the profile pages need to work.
        post("/secrets/{key}") { ok { putSecret(param("key"), body()) } }
        get("/secrets/status") { ok { secretStatus() } }
        get("/setup/state") { ok { setupState() } }
        post("/accounts") { ok { putAccount(body()) } }
    }

    private suspend fun repos(): JsonElement {
        val l = rt.projectSetup.repos()
        return buildJsonObject {
            // Repos that look like a mobile app first, each with a hint of its platform.
            putJsonArray("repos") { for (r in no.agens.testlab.services.diag.Examples.appFirst(l.repos.filter { !it.archived })) addJsonObject {
                put("name", r.fullName); put("private", r.private); r.language?.let { put("language", it) }; r.pushedAt?.let { put("pushed", it.take(10)) }; put("fork", r.fork)
                no.agens.testlab.services.diag.Examples.platformHint(r)?.let { put("hint", it) }
            } }
            put("sso_hidden", l.ssoHidden.size)
        }
    }

    private suspend fun scan(b: JsonObject): JsonElement {
        val repo = b.s("repo") ?: throw ApiError(HttpStatusCode.BadRequest, "repo is required (owner/name)")
        var s = rt.projectSetup.scanRepo(repo)
        if (b.s("read_packages") == "true") s = s.copy(candidates = s.candidates.map { c -> runCatching { rt.projectSetup.readManifest(s.repo, c) }.getOrDefault(c) })
        scans[s.repo] = s
        return buildJsonObject {
            put("repo", s.repo); put("default_branch", s.defaultBranch); s.releaseAsset?.let { put("release_asset", it) }
            putJsonArray("problems") { s.problems.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("candidates") {
                s.candidates.forEachIndexed { i, c -> addJsonObject {
                    put("index", i); put("workflow", c.workflow); put("workflow_file", c.workflowFile); put("event", c.event); put("artifact", c.artifact.name)
                    put("label", c.proposal.label); put("package", c.proposal.packageName); put("default", c.proposal.default)
                    c.manifest?.let { m -> putJsonArray("links") { m.links.forEach { add(JsonPrimitive(it)) } } }
                } }
            }
        }
    }

    private suspend fun jira(b: JsonObject): JsonElement {
        val site = b.s("site")?.removePrefix("https://")?.trimEnd('/') ?: throw ApiError(HttpStatusCode.BadRequest, "site is required")
        val j = rt.projectSetup.scanJira(site)
        return buildJsonObject {
            put("site", j.site)
            putJsonArray("projects") { j.projects.forEach { (k, n) -> addJsonObject { put("key", k); put("name", n) } } }
            putJsonArray("labels") { j.labels.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("queue_labels") { j.queueLabels.forEach { add(JsonPrimitive(it)) } }
            j.approvedLabel?.let { put("approved_label", it) }
        }
    }

    private fun draft(b: JsonObject): JsonElement {
        val repo = b.s("repo")
        val scan = repo?.let { scans[it] ?: throw ApiError(HttpStatusCode.BadRequest, "scan $it first") }
        val chosen = (b["builds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull()?.let { i -> scan?.candidates?.getOrNull(i) } } ?: scan?.candidates.orEmpty()
        val name = b.s("name") ?: repo?.substringAfter('/') ?: throw ApiError(HttpStatusCode.BadRequest, "name or repo is required")
        val id = b.s("id") ?: name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val site = b.s("site")
        val jiraScan = site?.let { ProjectSetup.JiraScan(it, emptyList(), emptyList(), emptyList(), null) }
        var p = rt.projectSetup.draft(id, name, scan, chosen, jiraScan, b.list("projects"), b.list("queue_labels"), b.s("approved_label"), b.s("default_package"), b.s("production_package"))
        if (b.list("required_labels").isNotEmpty()) p = p.copy(tracker = p.tracker.copy(requiredLabels = b.list("required_labels")))
        return buildJsonObject { put("json", json.encodeToString(Profile.serializer(), p)); putJsonArray("problems") { p.problems().forEach { add(JsonPrimitive(it)) } } }
    }

    private fun saveProfile(id: String, b: JsonObject): JsonElement {
        val text = b.s("json") ?: throw ApiError(HttpStatusCode.BadRequest, "json is required")
        val p = try { Profile.parse(text) } catch (e: Exception) { throw ApiError(HttpStatusCode.BadRequest, "not a profile: ${e.message?.lineSequence()?.firstOrNull()}") }
        if (p.id != id) throw ApiError(HttpStatusCode.BadRequest, "the JSON's id is ${p.id}, not $id")
        val problems = p.problems()
        if (problems.isNotEmpty()) return buildJsonObject { put("saved", false); putJsonArray("problems") { problems.forEach { add(JsonPrimitive(it)) } } }
        rt.profiles.save(p)
        if (b.s("activate") == "true") rt.profiles.activate(p.id)
        return buildJsonObject { put("saved", true); put("id", p.id); put("active", rt.profile.id) }
    }

    private fun putSecret(key: String, b: JsonObject): JsonElement {
        if (!WRITABLE.matches(key)) throw ApiError(HttpStatusCode.BadRequest, "only github.token, jira.email and jira.token (optionally @site or @owner), jev.key and openai.key are written here")
        val v = b.s("value") ?: throw ApiError(HttpStatusCode.BadRequest, "value is required")
        rt.secrets.put(key, v)
        return buildJsonObject { put("saved", key) }
    }

    /**
     * Each setup step the setup page shows, with what is done: GitHub and Jira checked against the
     * real service, the project, the autopilot, the test accounts (what each has, where it can log in)
     * and, when the project covers iOS, the Apple team for real iPhones.
     */
    private suspend fun setupState(): JsonElement {
        val d = no.agens.testlab.services.diag.Diagnostics.create()
        fun checkState(c: no.agens.testlab.services.diag.Check?): Pair<String, String> = when (c) {
            null -> "missing" to ""
            is no.agens.testlab.services.diag.Check.Ok -> "ok" to c.message
            else -> "problem" to c.message
        }
        val p = rt.profile
        val token = rt.githubToken()
        val gh = checkState(token?.takeIf { it.isNotBlank() }?.let { d.github(it, p.github.owner, p.github.repo, rt.githubApi()) })
        val site = p.tracker.site
        val email = rt.jiraEmail(); val jtoken = rt.jiraToken()
        val jira = if (site.isBlank()) null else checkState(if (email.isNullOrBlank() || jtoken.isNullOrBlank()) null else d.jira(site, email, jtoken, rt.settings.current.connections.jiraBase(site)))
        val covered = rt.profiles.activeAll().filterValues { it.profile.id !in Profile.GENERIC_IDS }
        val accounts = rt.testAccounts
        val oai = rt.secrets.get(SecretStore.OPENAI_KEY) ?: System.getenv("OPENAI_API_KEY")
        val openai = if (rt.settings.current.autopilot.backend == "openai" && !oai.isNullOrBlank()) d.openai(oai) else null
        return buildJsonObject {
            putJsonArray("examples") { for (e in no.agens.testlab.services.diag.Examples.prompts(covered.values.map { it.profile })) addJsonObject { put("what", e.what); put("prompt", e.prompt) } }
            putJsonObject("github") { put("state", gh.first); put("text", gh.second) }
            putJsonObject("project") {
                put("set", covered.isNotEmpty()); put("name", p.name); put("repo", p.github.repo)
                putJsonArray("platforms") { covered.keys.forEach { add(JsonPrimitive(it.id)) } }
            }
            jira?.let { (st, text) -> putJsonObject("jira") { put("site", site); put("state", st); put("text", text) } }
            putJsonObject("autopilot") {
                put("backend", rt.settings.current.autopilot.backend)
                put("key", rt.secrets.has(SecretStore.JEV_KEY) || System.getenv("TYPESAFE_API_KEY") != null)
                put("openai_key", rt.secrets.has(SecretStore.OPENAI_KEY) || System.getenv("OPENAI_API_KEY") != null)
                // The OpenAI key checked against OpenAI, like GitHub and Jira: a wrong key or an account without API credit shows here, not in the first plan.
                openai?.let { c ->
                    put("openai_state", checkState(c).first); put("openai_text", c.message)
                    (c as? no.agens.testlab.services.diag.Check.Problem)?.fixUrl?.let { put("openai_fix", it) }
                }
            }
            putJsonObject("accounts") {
                put("needed", covered.values.any { it.profile.accounts.loginPlan != null })
                putJsonArray("fields") { for (f in accounts.fields()) addJsonObject { put("key", f.key); put("label", f.label); put("secret", f.secret) } }
                putJsonArray("list") { for (n in accounts.names()) addJsonObject {
                    put("name", n); accounts.note(n)?.let { put("note", it) }
                    putJsonArray("set") { accounts.fields().filter { rt.secrets.has(SecretStore.account(n, it.key)) }.forEach { add(JsonPrimitive(it.key)) } }
                    putJsonArray("ready_on") { accounts.readyOn(n).forEach { add(JsonPrimitive(it.id)) } }
                } }
            }
            putJsonObject("ios") {
                put("covered", no.agens.testlab.core.Platform.IOS in covered)
                put("mac", no.agens.testlab.device.HostOs.current() == no.agens.testlab.device.HostOs.MAC)
                rt.settings.current.device.iosTeamId?.let { put("team", it) }
            }
        }
    }

    /** A test account from the setup page: its name, note and fields (into the vault). */
    private fun putAccount(b: JsonObject): JsonElement {
        val name = b.s("name")?.trim().orEmpty()
        if (!no.agens.testlab.services.runtime.TestAccounts.NAME.matches(name)) throw ApiError(HttpStatusCode.BadRequest, "an account name is letters, digits, _ and -")
        val known = rt.testAccounts.fields().map { it.key }.toSet()
        val fields = (b["fields"] as? JsonObject).orEmpty()
        fields.keys.firstOrNull { it !in known }?.let { throw ApiError(HttpStatusCode.BadRequest, "the project's accounts have no field «$it»") }
        rt.testAccounts.add(name, b.s("note"))
        for ((k, v) in fields) (v as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let { rt.secrets.put(SecretStore.account(name, k), it) }
        return buildJsonObject { put("saved", name) }
    }

    private fun secretStatus(): JsonElement = buildJsonObject {
        put("github_token", !rt.githubToken().isNullOrBlank())
        put("jira", !rt.jiraEmail().isNullOrBlank() && !rt.jiraToken().isNullOrBlank())
        put("typesafe", rt.secrets.has(SecretStore.JEV_KEY))
        put("openai", rt.secrets.has(SecretStore.OPENAI_KEY))
        put("jira_site", rt.profile.tracker.site)
    }

    private val settingsCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private fun settingsJson(): JsonElement = buildJsonObject {
        put("settings", settingsCodec.encodeToJsonElement(no.agens.testlab.services.settings.Settings.serializer(), rt.settings.current))
        put("keys", secretStatus())
        putJsonObject("proxyman") {
            rt.proxyman.mcpServer()?.let { put("mcp_server_found", it.path) }
            putJsonArray("mcp_server_candidates") { rt.proxyman.mcpServerCandidates().forEach { add(JsonPrimitive(it.path)) } }
            put("cert", no.agens.testlab.services.proxyman.Proxyman.certHint(rt.profile))
        }
        put("workspace_default", rt.dirs.workspace.path)
    }

    /** Merges [b] (a partial settings object) into the current settings; the Settings type checks it. */
    private fun patchSettings(b: JsonObject): JsonElement {
        fun merge(old: JsonElement?, new: JsonElement): JsonElement =
            if (old is JsonObject && new is JsonObject) JsonObject(old + new.mapValues { (k, v) -> merge(old[k], v) }) else new
        val current = settingsCodec.encodeToJsonElement(no.agens.testlab.services.settings.Settings.serializer(), rt.settings.current)
        val next = try { settingsCodec.decodeFromJsonElement(no.agens.testlab.services.settings.Settings.serializer(), merge(current, b)) }
            catch (e: Exception) { throw ApiError(HttpStatusCode.BadRequest, "not a valid setting: ${e.message?.lineSequence()?.firstOrNull()}") }
        rt.settings.update { next }
        if ("proxyman" in b) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { rt.proxyman.refreshTools() } }
        return settingsJson()
    }

    private fun html(page: String): String = when (page) {
        "screen", "report" -> {
            val opener = if (page == "screen") """{"tool":"handsets_show_screen","args":{}}""" else """{"tool":"lab_show_report","args":{}}"""
            Views.html(page).replaceFirst("<script>", "<script>\n" + Views.html("browser-host").replace("/*{{OPENER}}*/null", opener) + "\n</script>\n<script>")
        }
        "devices" -> Views.html(page).replace("/*{{DEVICE_HELP}}*/\"\"", Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(),
            JsonPrimitive(no.agens.testlab.services.diag.DeviceHelp.steps().joinToString("") { "<li>" + it.replace("&", "&amp;").replace("<", "&lt;") + "</li>" })))
        "guide" -> guide()
        else -> Views.html(page)
    }.let { h -> Regex("<body[^>]*>").find(h)?.let { m -> h.substring(0, m.range.last + 1) + nav(page) + h.substring(m.range.last + 1) } ?: h }

    /** The pages' shared menu (the one in a view tab too). */
    private fun nav(page: String): String {
        val items = listOf("setup" to "Setup", "devices" to "Devices", "reports" to "Reports", "profiles" to "Profiles", "handback" to "Hand-back", "approvals" to "Approvals", "settings" to "Settings", "guide" to "Guide")
        val links = items.joinToString("") { (p, label) -> """<a href="/ui/$p"${if (p == page || (page == "report" && p == "reports") || (page == "screen" && p == "devices")) " class=\"on\"" else ""}>$label</a>""" }
        return """<nav class="tl-nav"><b>${AppIdentity.NAME}</b>$links</nav><style>.tl-nav{display:flex;gap:4px;align-items:center;padding:8px 16px;border-bottom:1px solid rgba(127,127,127,.25);font:14px system-ui,sans-serif;flex-wrap:wrap}.tl-nav b{margin-right:12px}.tl-nav a{color:inherit;text-decoration:none;padding:4px 10px;border-radius:6px;opacity:.75}.tl-nav a:hover{opacity:1;background:rgba(127,127,127,.12)}.tl-nav a.on{opacity:1;background:rgba(127,127,127,.18);font-weight:600}</style>"""
    }

    private fun page(title: String, body: String) = """<!doctype html><meta charset="utf-8"><title>${AppIdentity.NAME}: $title</title>
        <body style="font:15px system-ui;max-width:640px;margin:60px auto;padding:0 16px"><h2>$title</h2>$body</body>"""

    /**
     * docs/GUIDE.md as a page: headings get anchors (the table of contents links to them), and links
     * to the other docs go to the repo, as there is no docs folder next to the server.
     */
    private fun guide(): String {
        val md = UiPages::class.java.getResourceAsStream("/guide/GUIDE.md")?.readBytes()?.decodeToString()
            ?: "# Guide\n\nThis build has no guide: see docs/GUIDE.md in the repo."
        val title = md.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: "Guide"
        val body = md.lineSequence().dropWhile { !it.startsWith("# ") }.drop(1).joinToString("\n")
        var html = no.agens.testlab.services.report.ReportHtml.markdown(body)
            .replace(Regex("""href="([A-Z]+\.md)""""), "href=\"https://github.com/${AppIdentity.REPO}/blob/main/docs/$1\" target=\"_blank\" rel=\"noopener\"")
        val toc = mutableListOf<Pair<String, String>>()
        html = Regex("<h2>(.*?)</h2>").replace(html) { m ->
            val text = m.groupValues[1]
            val id = text.lowercase().replace(Regex("<[^>]+>|&[a-z]+;"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-')
            toc += id to text
            "<h2 id=\"$id\">$text</h2>"
        }
        val nav = toc.joinToString(" · ", "<p class=\"meta\">", "</p>") { (id, text) -> "<a href=\"#$id\">$text</a>" }
        return no.agens.testlab.services.report.ReportHtml.page(title, "${AppIdentity.NAME} ${AppIdentity.VERSION}", nav + html)
    }

    // The small helpers ControlApi uses, for these routes.
    private suspend fun RoutingContext.ok(work: suspend () -> JsonElement) {
        val (status, body) = try { HttpStatusCode.OK to work() } catch (e: CancellationException) { throw e } catch (e: ApiError) { e.status to errJson(e.message) } catch (e: Exception) { HttpStatusCode.BadGateway to errJson(ToolErrors.describe(e)) }
        call.respondText(body.toString(), ContentType.Application.Json, status)
    }
    private fun RoutingContext.param(name: String) = call.parameters[name] ?: throw ApiError(HttpStatusCode.BadRequest, "missing $name")
    private suspend fun RoutingContext.body(): JsonObject = call.receiveText().ifBlank { "{}" }.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrElse { throw ApiError(HttpStatusCode.BadRequest, "the body is not a JSON object") } }
    private fun errJson(m: String?) = buildJsonObject { put("error", buildJsonObject { put("message", m ?: "error") }) }
    private fun err(m: String) = errJson(m).toString()
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject.list(k: String): List<String> = when (val v = this[k]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
        is JsonPrimitive -> v.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    companion object {
        /** Per space: two clients' pages in one browser never share a session. */
        val COOKIE get() = "testlab_ui" + no.agens.testlab.services.Spaces.suffix().replace('-', '_')
        val PAGES = setOf("devices", "screen", "reports", "report", "profiles", "handback", "settings", "setup", "approvals", "guide")
        private val WRITABLE = Regex("(github\\.token|jira\\.email|jira\\.token)(@[A-Za-z0-9_.-]+)?|jev\\.key|openai\\.key")
    }
}
