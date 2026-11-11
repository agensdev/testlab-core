package no.agens.testlab.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import no.agens.testlab.AppIdentity
import no.agens.testlab.core.jev.PlanValidator
import no.agens.testlab.mcp.Param.Companion.int
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.secrets.SecretStore
import java.io.File

private val prettyJson = Json { prettyPrint = true }
private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")

internal fun checkName(kind: String, name: String): String {
    val n = name.removeSuffix(".json")
    if (!NAME.matches(n) || ".." in n) throw BadArgument("$kind names are letters, digits, '.', '_' and '-' (got '$name')")
    return n
}

/** Saved plans: `<workspace>/plans/<name>.json`, shared with the app's Plans screen. */
class PlanLibrary(private val rt: LabRuntime) {
    private val dir: File get() = rt.plansDir()

    data class Entry(val name: String, val steps: Int, val note: String?, val credentials: List<String>, val error: String?)

    /** The user's plans, then the active profile's (read-only; a saved plan of the same name wins). */
    private fun files(): List<File> {
        val own = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().toList()
        val names = own.map { it.name }.toSet()
        val profile = rt.profiles.entry.planFiles().filter { it.name !in names }
        return (own + profile).sortedBy { it.name }
    }

    fun list(): List<Entry> = files().map { f ->
        val name = f.name.removeSuffix(".json")
        try {
            val plan = Json.parseToJsonElement(f.readText()) as JsonArray
            val note = plan.firstNotNullOfOrNull { ((it as? JsonObject)?.get("note") as? JsonPrimitive)?.content }
            Entry(name, plan.size, note, PlanValidator.credRefs(plan), null)
        } catch (e: Exception) {
            Entry(name, 0, null, emptyList(), "not a JSON list of steps")
        }
    }

    fun read(name: String): JsonArray {
        val n = checkName("plan", name) + ".json"
        val f = File(dir, n).takeIf { it.isFile } ?: rt.profiles.entry.planFile(n) ?: File(dir, n)
        if (!f.isFile) throw BadArgument("no plan named '$name' (lab_list_plans shows the saved ones)")
        return parsePlan(JsonPrimitive(f.readText()))
    }

    /** Validates (errors refuse the save) and writes atomically; returns the warnings. */
    fun save(name: String, plan: JsonArray, overwrite: Boolean): List<String> {
        val n = checkName("plan", name)
        val issues = PlanValidator.validate(plan, null)
        val errors = issues.filter { it.error }
        if (errors.isNotEmpty()) throw BadArgument("plan is invalid, not saved:\n" + errors.joinToString("\n") { "- $it" })
        dir.mkdirs()
        val f = File(dir, "$n.json")
        if (f.exists() && !overwrite) throw BadArgument("a plan named '$n' exists: pass overwrite=true to replace it")
        val tmp = File(dir, "$n.json.tmp")
        tmp.writeText(prettyJson.encodeToString(JsonElement.serializer(), plan) + "\n")
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        val missing = PlanValidator.credRefs(plan).filter { it !in rt.credentials.knownRefs() }
        return issues.filter { !it.error }.map { it.toString() } + missing.map { "credential '$it' is not set yet (the setup page (`testlab open setup`))" }
    }
}

/** Playbooks: the bundled skills, then the active profile's `skills/`, then `<workspace>/skills/<name>/SKILL.md`; later ones override by name. */
class Skills(private val rt: LabRuntime) {
    data class Skill(val name: String, val description: String, val text: String, val bundled: Boolean)

    private fun userDir(): File = (rt.settings.current.workspace?.let(::File) ?: rt.dirs.workspace).resolve("skills")

    fun all(): List<Skill> {
        val out = LinkedHashMap<String, Skill>()
        for (n in BUNDLED) bundled(n)?.let { out[n] = it }
        // The active profile's skills replace the core's of the same name and add the team's own.
        rt.profiles.entry.skillDirs().forEach { d ->
            val f = File(d, "SKILL.md")
            if (f.isFile) { val t = f.readText(); out[d.name] = Skill(d.name, frontmatter(t)["description"].orEmpty(), t, false) }
        }
        // The profile's test repos: their skills as `<repo id>:<name>` (the repo's own top SKILL.md as `<repo id>`).
        for (t in rt.testRepos.configured()) runCatching { rt.testRepos.skills(t) }.getOrDefault(emptyList()).forEach { s -> out[s.name] = Skill(s.name, s.description, s.text, false) }
        userDir().listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val f = File(d, "SKILL.md")
            if (f.isFile) { val t = f.readText(); out[d.name] = Skill(d.name, frontmatter(t)["description"].orEmpty(), t, false) }
        }
        return out.values.toList()
    }

    fun get(name: String): Skill? = all().firstOrNull { it.name == name }

    private fun bundled(name: String): Skill? = Skills::class.java.getResourceAsStream("/skills/$name/SKILL.md")?.use { s ->
        val t = s.readBytes().decodeToString()
        Skill(name, frontmatter(t)["description"].orEmpty(), t, true)
    }

    companion object {
        val BUNDLED = listOf(
            "device-prep", "drive-device", "write-plan", "device-login", "install-build", "devtest", "websocket-capture", "accessibility",
            "test-plan", "ab-control", "proxy-forcing", "lifecycle-and-config", "report-honesty", "rig-cleanup", "jev-usage",
            "evidence-and-media", "rig-health",
        )

        /** `--- name: … description: … ---`, as the lab parses it. */
        fun frontmatter(text: String): Map<String, String> {
            if (!text.startsWith("---")) return emptyMap()
            val end = text.indexOf("\n---", 3).takeIf { it >= 0 } ?: return emptyMap()
            return text.substring(3, end).lines().mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
        }
    }
}

internal fun ToolBox.labTools(rt: LabRuntime, mcpUrl: () -> String) {
    val plans = PlanLibrary(rt)
    val skills = Skills(rt)

    tool("lab_list_plans", "List the saved plans (name, steps, first note, credentials they use). Run one with handsets_jev_plan_file.", readOnly = true) {
        val list = plans.list()
        if (list.isEmpty()) return@tool text("(no saved plans yet: write one with lab_save_plan; see lab_read_skill('write-plan'))")
        text(buildJsonObject {
            putJsonArray("plans") {
                for (p in list) addJsonObject {
                    put("name", p.name); put("steps", p.steps)
                    p.note?.let { put("note", it) }
                    if (p.credentials.isNotEmpty()) putJsonArray("credentials") { p.credentials.forEach { add(JsonPrimitive(it)) } }
                    p.error?.let { put("error", it) }
                }
            }
        }.encode())
    }

    tool("lab_read_plan", "Return a saved plan's JSON.", str("name", "plan name"), readOnly = true) { a ->
        text(prettyJson.encodeToString(JsonElement.serializer(), plans.read(a.str("name"))))
    }

    tool(
        "lab_save_plan",
        "Validate and save a plan under a name (every mistake is listed at once). Returns warnings, e.g. credentials not set yet.",
        str("name", "letters, digits, '.', '_' and '-'"),
        Param.plan("plan", "JSON list of steps (a JSON string or the array itself)"),
        Param.bool("overwrite", "replace an existing plan with this name", false),
    ) { a ->
        val warnings = plans.save(a.str("name"), parsePlan(a.json("plan")), a.bool("overwrite"))
        text("saved ${checkName("plan", a.str("name"))}" + if (warnings.isEmpty()) "" else "\nwarnings:\n" + warnings.joinToString("\n") { "- $it" })
    }

    tool(
        "lab_list_credentials",
        """
        Which credential references are set in the vault (names and set/unset only, never values).
        Use them as {"type": {"cred": "<ref>"}} in plans, handsets_type_secret(ref) or
        handsets_fill(selector, cred=ref). Each test account also has its `note` (what it is for:
        pick the account a test needs by it), `login_on` (the platforms handsets_login has every
        field for) and `in_use_on` (the device logged in with it). `pools` are the profile's
        test-user pools: no saved fields, a fresh user per handsets_login. The user adds accounts in
        ${AppIdentity.NAME} → Settings or with `testlab accounts add`.
        """,
        readOnly = true,
    ) {
        val keys = rt.secrets.keys()
        val ta = rt.testAccounts
        text(buildJsonObject {
            putJsonObject("accounts") {
                for (acct in ta.names()) putJsonObject(acct) {
                    ta.note(acct)?.let { put("note", it) }
                    putJsonArray("login_on") { ta.readyOn(acct).forEach { add(JsonPrimitive(it.id)) } }
                    ta.inUseOn(acct)?.let { put("in_use_on", it) }
                    val fields = (ta.fields().map { it.key } + keys.filter { it.startsWith("account.$acct.") }.map { it.removePrefix("account.$acct.") }).distinct()
                    for (f in fields) put("$acct.$f", if (rt.secrets.has(SecretStore.account(acct, f))) "set" else "not set")
                }
            }
            val pools = rt.pools.all()
            if (pools.isNotEmpty()) putJsonObject("pools") {
                for (pl in pools) putJsonObject(pl.name) {
                    pl.description?.let { put("note", it) }
                    put("key", if (rt.pools.hasKey(pl.name)) "set" else "not set: `testlab secrets set ${SecretStore.poolKey(pl.name)}`")
                    putJsonArray("refs") { pl.fields.keys.forEach { add(JsonPrimitive("${pl.name}.$it")) } }
                    put("use", "handsets_login(account=\"${pl.name}\") takes a fresh user per login; the refs then read that user on that device")
                }
            }
            put("device_pin", if (rt.secrets.has(SecretStore.DEVICE_PIN)) "set" else "not set")
            val perDevice = keys.filter { it.startsWith("device_pin.") }.map { it.removePrefix("device_pin.") }
            if (perDevice.isNotEmpty()) putJsonArray("device_pin_for") { perDevice.forEach { add(JsonPrimitive(it)) } }
        }.encode())
    }

    tool("lab_status", "${AppIdentity.NAME}'s state: version, MCP endpoint, adb, devices, decision backend and keys (set/unset), whether Jira takes the login, default app, folders.", readOnly = true) {
        val s = rt.settings.current
        text(buildJsonObject {
            put("app", "${AppIdentity.NAME} ${AppIdentity.VERSION}")
            put("mcp", mcpUrl())
            put("adb", rt.locateAdb()?.path ?: "not found: install Android platform-tools")
            put("proxyman", proxymanStatus(rt))
            put("jira_login", JiraLogin.status(rt))
            try {
                putJsonArray("devices") {
                    for (d in rt.listDevices()) addJsonObject { put("serial", d.serial); put("state", d.state); d.model?.let { put("model", it) } }
                }
            } catch (e: Exception) {
                put("devices", ToolErrors.describe(e))
            }
            put("active", rt.active.value ?: "")
            putJsonObject("decisions") {
                put("backend", s.jev.backend)
                put("typesafe_key", if (rt.secrets.has(SecretStore.JEV_KEY)) "set" else "not set")
                put("openai_key", if (rt.secrets.has(SecretStore.OPENAI_KEY) || System.getenv("OPENAI_API_KEY") != null) "set" else "not set")
                put("openai_model", s.jev.openaiModel)
                putJsonArray("ollama") { s.jev.ollamaUrls.forEach { add(JsonPrimitive(it)) } }
                put("min_confidence", s.jev.minConfidence)
            }
            putJsonObject("profile") {
                val p = rt.profile
                put("id", p.id); put("name", p.name)
                put("repo", p.github.repo); put("jira", p.tracker.site); putJsonArray("projects") { p.tracker.projects.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("build_types") { p.builds.forEach { b -> add(JsonPrimitive(b.id + if (b == p.defaultBuild) " (default)" else "")) } }
                putJsonArray("destinations") { p.navigation.destinations.keys.forEach { add(JsonPrimitive(it)) } }
            }
            put("default_package", rt.defaultPackage())
            put("allow_risky_on_prod", s.device.allowRiskyOnProd)
            put("confirm_writes", s.mcp.confirmWrites)
            put("plans", rt.plansDir().path)
            put("reports", rt.reportsDir().path)
            put("traces", rt.dirs.traces.path)
        }.encode())
    }

    tool("lab_list_profiles", "The project profiles installed (which app, repo, builds and Jira ${AppIdentity.NAME} is set up for); * marks the active one. Switch with lab_use_profile.", readOnly = true) {
        val active = rt.profile.id
        text(rt.profiles.all().joinToString("\n") { e ->
            val p = e.profile
            (if (p.id == active) "* " else "  ") + "${p.id}: ${p.name}" +
                listOfNotNull(p.github.repo.ifBlank { null }, p.tracker.site.ifBlank { null }, p.defaultBuild?.let { "default build ${it.id} (${it.workflowFile ?: it.label})" }).joinToString(", ", " — ").takeIf { it != " — " }.orEmpty() +
                if (e.bundled) " [built in]" else ""
        } + "\nProfiles are added with `${AppIdentity.SLUG} profile import` or `${AppIdentity.SLUG} open profiles`; they show up here without a restart.")
    }

    tool(
        "lab_use_profile",
        "Make another installed profile active (lab_list_profiles shows the ids). Builds, Jira, deeplinks and the login follow it at once. Only when the person asks for it, or agrees.",
        str("id", "profile id from lab_list_profiles"),
    ) { a ->
        rt.profiles.activate(a.str("id"))
        val p = rt.profile
        text("Active profile: ${p.id} (${p.name})" +
            (p.github.repo.ifBlank { null }?.let { ", repo $it" } ?: "") +
            ", build types: " + p.builds.joinToString { b -> b.id + if (b == p.defaultBuild) " (default)" else "" }.ifEmpty { "none" } +
            ".\nThe server instructions and tool descriptions this session got at connect still describe the previous profile; trust lab_status, or start a new conversation for fresh ones.")
    }

    tool(
        "lab_list_skills",
        "List the ready-made playbooks (name: description). Call this FIRST for a task; load one with lab_read_skill(name). Skills from the profile's test repos are named <repo id>:<name>.",
        readOnly = true,
    ) {
        // The test repos' skills are part of the list: bring them up to date first (at most hourly).
        val problems = rt.testRepos.syncAll().filter { it.problem != null }.map { "${it.repo.id}: ${it.problem}" }
        text(skills.all().joinToString("\n") { "- ${it.name}: ${it.description}" } + if (problems.isEmpty()) "" else "\n(test repos not updated: ${problems.joinToString("; ")})")
    }

    tool("lab_read_skill", "Return a playbook's full SKILL.md. lab_list_skills shows the names.", str("name", "skill name"), readOnly = true) { a ->
        val n = a.str("name")
        if ('/' in n || '\\' in n || ".." in n) throw BadArgument("invalid skill name")
        text(skills.get(n)?.text ?: "(no skill named '$n'; call lab_list_skills())")
    }

    tool(
        "lab_jev_stats",
        """
        Summarise the Jev decisions from the traces of the last N minutes: calls per tool,
        decisions, average/max latency, input tokens and cost, outcomes (acted / goal_done /
        needs_decision / blocked) and the hand-backs with their goals.
        """,
        int("since_minutes", "look back this many minutes", 60),
        readOnly = true,
    ) { a -> text(jevStats(rt.dirs.traces, a.int("since_minutes")).encode()) }
}

/** The lab's `lab_jev_stats` over ${AppIdentity.NAME}'s trace folder (same JSON shape). */
internal fun jevStats(dir: File, sinceMinutes: Int, nowMs: Long = System.currentTimeMillis()): JsonObject {
    val cutoff = nowMs - sinceMinutes * 60_000L
    var files = 0
    val calls = LinkedHashMap<String, Int>()
    val outcomes = LinkedHashMap<String, Int>()
    val latencies = mutableListOf<Long>()
    var inputTokens = 0L
    val handBacks = mutableListOf<JsonObject>()
    for (f in (dir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()).sortedBy { it.name }) {
        if (f.lastModified() < cutoff) continue
        files++
        val tool = f.name.removeSuffix(".jsonl").substringAfterLast('-')
        calls[tool] = (calls[tool] ?: 0) + 1
        for (line in f.readLines()) {
            val r = try { Json.parseToJsonElement(line) as? JsonObject } catch (e: Exception) { null } ?: continue
            val resp = r["response"] as? JsonObject ?: continue
            latencies += (r["latency_ms"] as? JsonPrimitive)?.longOrNull ?: 0
            inputTokens += ((resp["usage"] as? JsonObject)?.get("input_tokens") as? JsonPrimitive)?.longOrNull ?: 0
            val oc = (r["outcome"] as? JsonPrimitive)?.content ?: "?"
            outcomes[oc] = (outcomes[oc] ?: 0) + 1
            if (oc == "needs_decision") {
                val next = (resp["answers"] as? JsonObject)?.get("next") as? JsonObject
                handBacks += buildJsonObject {
                    put("goal", r["goal"] ?: JsonPrimitive(""))
                    put("choice", next?.get("choice") ?: JsonPrimitive(""))
                    put("confidence", Math.round(((next?.get("confidence") as? JsonPrimitive)?.doubleOrNull ?: 0.0) * 100) / 100.0)
                    put("file", f.name)
                }
            }
        }
    }
    return buildJsonObject {
        put("since_minutes", sinceMinutes)
        put("files", files)
        putJsonObject("calls") { calls.forEach { (k, v) -> put(k, v) } }
        put("decisions", latencies.size)
        put("input_tokens", inputTokens)
        putJsonObject("outcomes") { outcomes.forEach { (k, v) -> put(k, v) } }
        put("hand_backs", JsonArray(handBacks))
        put("latency_avg_ms", if (latencies.isEmpty()) JsonPrimitive(null as Long?) else JsonPrimitive(Math.round(latencies.average())))
        put("latency_max_ms", latencies.maxOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(null as Long?))
        put("cost_usd", Math.round(inputTokens * 0.042 / 1e6 * 1e5) / 1e5)
    }
}

/**
 * Whether Jira takes the stored login, for lab_status: a wrong token otherwise only shows as «Not found»
 * on the first read. Asked of /myself at most every 5 minutes per site, e-mail and token.
 */
internal object JiraLogin {
    private var cached: Pair<Pair<Int, Long>, String>? = null

    suspend fun status(rt: LabRuntime): String {
        val site = rt.profile.tracker.site
        if (site.isBlank()) return "no Jira site in the profile"
        val email = rt.jiraEmail()?.trim().orEmpty()
        val token = rt.jiraToken()?.trim().orEmpty()
        if (email.isEmpty() || token.isEmpty()) return "not set: ${AppIdentity.SLUG} secrets set jira.email / jira.token"
        val key = listOf(site, email, token, rt.settings.current.connections.jiraBase(site)).hashCode()
        val now = System.currentTimeMillis()
        cached?.let { (k, v) -> if (k.first == key && now - k.second < 300_000) return v }
        val v = try {
            val me = rt.jira.myself()
            "ok: ${me.displayName}" + if ("api.atlassian.com" in rt.settings.current.connections.jiraBase(site)) " (scoped token, through the API gateway)" else ""
        } catch (e: no.agens.testlab.services.jira.JiraError) {
            if (e.status == 401) "rejected: Jira refuses ${email}'s token (wrong, expired, another account's, or a scoped token before `${AppIdentity.SLUG} status` found the gateway)" else "error: ${e.message}"
        } catch (e: Exception) {
            return "unreachable: ${e.message?.take(120)}"
        }
        cached = (key to now) to v
        return v
    }
}
