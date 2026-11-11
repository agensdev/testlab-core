package no.agens.testlab.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import no.agens.testlab.core.jev.Backend
import no.agens.testlab.core.jev.DecideOptions
import no.agens.testlab.core.jev.PlanContext
import no.agens.testlab.core.jev.PlanValidator
import no.agens.testlab.core.jev.ShotStore
import no.agens.testlab.core.jev.StepOptions
import no.agens.testlab.core.jev.doGoal
import no.agens.testlab.core.jev.pick
import no.agens.testlab.core.jev.pyTruthy
import no.agens.testlab.core.jev.runPlan
import no.agens.testlab.core.jev.step
import no.agens.testlab.device.hs.HsDevice
import no.agens.testlab.mcp.Param.Companion.SERIAL
import no.agens.testlab.mcp.Param.Companion.bool
import no.agens.testlab.mcp.Param.Companion.int
import no.agens.testlab.mcp.Param.Companion.num
import no.agens.testlab.mcp.Param.Companion.str
import no.agens.testlab.mcp.ToolBox.Companion.text
import no.agens.testlab.services.report.DirShotStore
import no.agens.testlab.services.runtime.LabEvent
import no.agens.testlab.services.runtime.LabRuntime
import no.agens.testlab.services.settings.Settings
import java.io.File

private val BACKEND = str("backend", "typesafe (hosted Jev) | ollama (local nimble model) | openai (OpenAI Decisions API) | stub (offline test: always the first candidate); empty = Settings", "", listOf("", "typesafe", "ollama", "openai", "stub"))
private val ALLOW_RISKY = bool("allow_risky", "offer purchase/delete-worded nodes (kjøp, betal, slett…) too", false)
private val MIN_CONFIDENCE = num("min_confidence", "below this the step hands back instead of acting", 0.5)

private fun decideOptions(rt: LabRuntime, a: Args, serial: String, maxCandidates: Int? = null): DecideOptions {
    val b = a.strOrNull("backend")
    return DecideOptions(
        maxCandidates = maxCandidates ?: 60,
        allowRisky = runCatching { a.bool("allow_risky") }.getOrDefault(false),
        backend = b?.takeIf { it != "stub" }?.let(Backend::parse),
        stub = b == "stub",
        geometry = rt.geometry(serial),
    )
}

private fun minConfidence(rt: LabRuntime, a: Args): Double = if (a.has("min_confidence")) a.double("min_confidence") else rt.settings.current.jev.minConfidence

/** The package of the activity in front (`pkg/.Activity`). */
private suspend fun frontPackage(dev: HsDevice): String = dev.topActivity().substringBefore('/').trim()

/** Risky taps are refused on the production app unless Settings allows them (plan guardrail). */
private suspend fun guardRisky(rt: LabRuntime, dev: HsDevice, risky: Boolean, opens: List<String> = emptyList()) {
    if (!risky || rt.settings.current.device.allowRiskyOnProd) return
    val prod = rt.profile.app.productionPackage ?: return
    if (prod in opens || frontPackage(dev) == prod) {
        throw IllegalStateException("allow_risky is refused on the production app ($prod): purchases there spend real money. Use a test build, or allow it: `testlab config set device.allowRiskyOnProd true`")
    }
}

internal fun parsePlan(v: JsonElement?): JsonArray {
    val el = when (v) {
        null -> throw BadArgument("missing argument 'plan'")
        is JsonArray -> v
        is JsonPrimitive -> try { Json.parseToJsonElement(v.content) } catch (e: Exception) { throw BadArgument("plan must be a JSON list of steps: ${e.message}") }
        else -> v
    }
    return el as? JsonArray ?: throw BadArgument("plan must be a JSON list of steps")
}

/** Validation errors stop a run; warnings are returned next to the result. */
internal fun checkPlan(rt: LabRuntime, plan: JsonArray): List<String> {
    val issues = PlanValidator.validate(plan, rt.credentials.knownRefs())
    val errors = issues.filter { it.error }
    if (errors.isNotEmpty()) throw BadArgument("plan is invalid:\n" + errors.joinToString("\n") { "- $it" })
    return issues.filter { !it.error }.map { it.toString() }
}

/** Screenshots of a run: into a report when one is open, else a scratch folder in the workspace. */
private fun shotsFor(rt: LabRuntime, reportId: String?, serial: String? = null): ShotStore {
    if (reportId == null) return DirShotStore(File(rt.reportsDir(), "_jev_shots"), serial = serial)
    if ('/' in reportId || '\\' in reportId || ".." in reportId || !File(rt.reportsDir(), "$reportId/manifest.json").isFile) {
        throw BadArgument("unknown report_id '$reportId' (call lab_report_start first)")
    }
    return DirShotStore(File(rt.reportsDir(), reportId), serial = serial)
}


/** Runs a validated plan on [dev] and reports a hand-back to the app. */
private val ISOLATE = bool("isolate", "disable the app's sibling builds while the plan runs (they claim the same return links), enabled again after", false)

/** Runs [block] with the sibling builds of the plan's app (its first `open`, else the default app) disabled. */
private suspend fun isolated(rt: LabRuntime, serial: String, plan: JsonArray, on: Boolean, block: suspend () -> JsonObject): JsonObject {
    if (!on) return block()
    if (rt.profile.app.family == null) throw BadArgument("isolate needs app.family in the profile «${rt.profile.name}» (the prefix the sibling builds share)")
    val keep = plan.firstNotNullOfOrNull { ((it as? JsonObject)?.get("open") as? JsonPrimitive)?.content?.substringBefore('/')?.takeIf { p -> "$" !in p } } ?: requirePackage(rt)
    val disabled = rt.isolation.isolate(serial, keep = keep)
    try {
        val r = block()
        return if (disabled.isEmpty()) r else JsonObject(r + ("isolated" to JsonArray(disabled.map(::JsonPrimitive))))
    } finally {
        if (disabled.isNotEmpty()) rt.isolation.restore(serial)
    }
}

internal suspend fun runPlanOn(rt: LabRuntime, dev: HsDevice, plan: JsonArray, a: Args, tool: String): JsonObject {
    val warnings = checkPlan(rt, plan)
    val risky = plan.any { (it as? JsonObject)?.get("allow_risky")?.let(::pyTruthy) == true }
    val opens = plan.mapNotNull { ((it as? JsonObject)?.get("open") as? JsonPrimitive)?.content?.substringBefore('/') }
    guardRisky(rt, dev, risky, opens)
    val ctx = PlanContext(
        cred = { ref -> rt.credential(ref, dev.serial) },
        hideKeyboard = { dev.hideKeyboard() },
        shots = shotsFor(rt, a.strOrNull("report_id"), dev.serial),
        clearData = { pkg -> rt.ops(dev.serial).clearData(dev.serial, pkg) },
    )
    val r = rt.engine().runPlan(
        plan, dev.asHsRunner(), ctx,
        start = a.int("start"),
        minConfidence = minConfidence(rt, a),
        decide = decideOptions(rt, a, dev.serial),
        trace = rt.traces.open(dev.serial, "plan"),
    )
    // Paused and failed steps alike stop at a step the user can resolve on the hand-back page.
    var out = r
    if ((r["paused_at"] as? JsonPrimitive)?.content?.toIntOrNull() != null) {
        val e = LabEvent.HandBack(tool, r, plan, dev.serial)
        rt.handBacks.add(e)
        rt.emit(e)
        // Where a person resolves it.
        out = JsonObject(out + ("resolve" to JsonPrimitive("the user can resolve it in the browser: `${no.agens.testlab.AppIdentity.SLUG} open handback`")))
    }
    return if (warnings.isEmpty()) out else JsonObject(out + ("warnings" to JsonArray(warnings.map(::JsonPrimitive))))
}

internal fun ToolBox.jevTools(rt: LabRuntime) {
    tool(
        "handsets_jev_pick",
        """
        Ask Jev which on-screen element advances `goal`. Reads the screen, does NOT act.

        Returns JSON: choice, confidence, action {hs, desc} (the exact tap, or null for
        goal_done/blocked), top (5 × [key, probability, desc]), n_candidates, dropped_risky
        (purchase/delete-worded nodes filtered out unless allow_risky), truncated, usage, trace.
        Use it instead of reading a long ui_dump for "which button is it?", then act with
        handsets_tap_xy(action.hs[1], action.hs[2]), or let handsets_jev_step/jev_do act.
        Navigation goals only: Jev cannot type. Prefer handsets_go when a deeplink exists.
        """,
        str("goal", "what to achieve, in the screen's words"),
        SERIAL,
        int("max_candidates", "cap on numbered options", 60),
        ALLOW_RISKY,
        BACKEND,
        readOnly = true,
    ) { a ->
        val dev = rt.device(a.serial)
        guardRisky(rt, dev, a.bool("allow_risky"))
        text(rt.engine().pick(a.str("goal"), dev.asHsRunner(), dev.serial, decideOptions(rt, a, dev.serial, a.int("max_candidates")), rt.traces).encode())
    }

    tool(
        "handsets_jev_find",
        """
        Jev looks at a picture of the screen (TypeSafe Box on the screenshot) and says where `goal`
        is: for screens without an accessibility tree (games, canvas, Unity, maps, video), where
        handsets_jev_pick and the ui_dump are blind. Prefer the tree tools when the dump shows the
        target: they are exact and cheaper.

        Returns JSON: found (Jev's «is it there» ≥ 0.5: Box always draws a box, so trust `found`,
        not the box alone), present, confidence (of the box), box {left, top, right, bottom} as
        fractions of the screen, x, y = the box's centre in handsets_tap_xy pixels. Doesn't act:
        tap with handsets_tap_xy(x, y), or drag from it. 0.2–0.5 s per call. Needs the TypeSafe key
        (`testlab secrets set typesafe_api_key`). Images/Box are an early TypeSafe feature.
        """,
        str("goal", "what to find, e.g. \"the red back arrow in the top left\""),
        SERIAL,
        readOnly = true,
    ) { a ->
        val dev = rt.device(a.serial)
        val goal = a.str("goal")
        val shot = dev.screenshot(no.agens.testlab.services.runtime.JevFind.LONG_SIDE, quality = 85)
        val (w, h) = dev.screenSize()
        val r = rt.jevFind.find(goal, shot, w, h)
        val out = r.json().let { o -> buildJsonObject {
            o.forEach { (k, v) -> put(k, v) }
            put("latency_ms", r.latencyMs)
            if (!r.found) put("note", "Jev doesn't see it on this screen (present ${"%.2f".format(r.present)}): the box is only its best guess, don't tap it")
        } }
        runCatching {
            rt.traces.open(dev.serial, "jev_find")?.write(buildJsonObject {
                put("goal", goal); put("backend", "typesafe"); put("primitive", "box+noul"); put("image", "${r.image.first}x${r.image.second}")
                put("result", out); r.usage?.let { put("usage", it) }
            })
        }
        text(out.encode())
    }

    tool(
        "handsets_jev_step",
        """
        One Jev-driven step: read screen → Jev picks → tap/scroll/back → verify the screen changed.

        JSON adds to jev_pick: status (acted | goal_done | blocked | needs_decision), executed,
        changed, diff {added, removed, jaccard}, after_activity. needs_decision = confidence below
        min_confidence: nothing was executed; pick yourself from `top` (handsets_tap_xy). Never
        types, never pays.
        """,
        str("goal", "an action goal, e.g. \"Press Next\""),
        SERIAL,
        MIN_CONFIDENCE,
        ALLOW_RISKY,
        BACKEND,
    ) { a ->
        val dev = rt.device(a.serial)
        guardRisky(rt, dev, a.bool("allow_risky"))
        val out = rt.engine().step(a.str("goal"), dev.asHsRunner(), StepOptions(minConfidence(rt, a), decideOptions(rt, a, dev.serial)), trace = rt.traces.open(dev.serial, "step"))
        text(out.json.encode())
    }

    tool(
        "handsets_jev_do",
        """
        Autopilot: repeat jev_step until the goal is reached.

        For short in-app navigation with no deeplink ("Open the Library tab", "Go to Orders", "Open
        Mine spill"). Stops with status goal_done | blocked | needs_decision (+candidates; nothing
        else executed, continue by hand) | stuck (two no-change actions / same screen 3×) |
        max_steps | deadline (90 s) | error (FLAG_SECURE dump, daemon down, API/key problem).
        Returns the step list, final_activity, usage_total, trace. Only nodes on the current screen
        are ever offered; purchase/delete words are filtered unless allow_risky; no typing, no app
        launches. Phrase goals with words that appear on screen.
        """,
        str("goal", "a state goal, e.g. \"Open Search\""),
        SERIAL,
        int("max_steps", "give up after this many actions", 12),
        MIN_CONFIDENCE,
        ALLOW_RISKY,
        BACKEND,
    ) { a ->
        val dev = rt.device(a.serial)
        guardRisky(rt, dev, a.bool("allow_risky"))
        val out = rt.engine().doGoal(a.str("goal"), dev.asHsRunner(), a.int("max_steps").coerceIn(1, 50), StepOptions(minConfidence(rt, a), decideOptions(rt, a, dev.serial)), trace = rt.traces.open(dev.serial, "do"))
        text(out.encode())
    }

    tool(
        "handsets_jev_plan",
        """
        Run a whole device flow in one call; returns only when done or on a hand-back.

        `plan` is a JSON list of steps, executed in order:
          {"goal": "Open Search"}                      state goal (loops until reached; "allow_risky": true, "max_steps": 5)
          {"step": "Press Next"}                      single action (pick + tap + verify)
          {"tap": "#id" | "Exact label" | "x,y"}      scripted tap of a known element, no model ("nth": 1 = second match)
          {"type": "1234"} / {"type": {"cred": "demo.password"}}   keystrokes; credential references
                                                      (account.field or device_pin) are typed from the vault and never returned
          {"hide_keyboard": true}                     hide the soft keyboard (only when it is up)
          {"wait_for": "Spillet er", "timeout": 20}   wait for text / #id on screen
          {"open": "<pkg>"} / {"key": "back"} / {"sleep": 2} / {"screenshot": "caption"} / {"note": "..."}
        Any step can carry "if_present": "<text or #id>": it runs only when that is on screen, else it is
        skipped (a prompt that comes only sometimes, an age gate accepted earlier). A goal/step with
        "optional": true is skipped instead of handing back when it can't be done.
        isolate=true disables the app's sibling builds while the plan runs (they claim the same return
        links, e.g. an identity provider's), as handsets_login does.
        Result: status completed | paused (hand-back: read `hand_back`, act yourself, then call again
        with start=paused_at+1) | error; per-step results with timing, usage_total, trace path.
        With `report_id` every screenshot step lands in that report, numbered. Purchase/delete-worded
        taps still need allow_risky on the step and confidence ≥ 0.5; never on the production app.
        Save reusable plans with lab_save_plan and run them by name with handsets_jev_plan_file.
        """,
        Param.plan("plan", "JSON list of steps (a JSON string or the array itself)"),
        SERIAL,
        int("start", "step index to start at (resume after a hand-back with paused_at+1)", 0),
        MIN_CONFIDENCE,
        str("report_id", "report to put screenshot steps in (from lab_report_start)", ""),
        BACKEND,
        ISOLATE,
    ) { a ->
        val dev = rt.device(a.serial)
        val plan = parsePlan(a.json("plan"))
        text(isolated(rt, dev.serial, plan, a.bool("isolate")) { runPlanOn(rt, dev, plan, a, "handsets_jev_plan") }.encode())
    }

    tool(
        "handsets_jev_plan_file",
        "Run a saved plan by name (see lab_list_plans); same options and result as handsets_jev_plan.",
        str("name", "plan name from lab_list_plans"),
        SERIAL,
        int("start", "step index to start at", 0),
        MIN_CONFIDENCE,
        str("report_id", "report to put screenshot steps in", ""),
        BACKEND,
        ISOLATE,
    ) { a ->
        val dev = rt.device(a.serial)
        val plan = PlanLibrary(rt).read(a.str("name"))
        text(isolated(rt, dev.serial, plan, a.bool("isolate")) { runPlanOn(rt, dev, plan, a, "handsets_jev_plan_file") }.encode())
    }

    tool(
        "handsets_login",
        """
        Log the app in with the active profile's login plan in one call (the profiles page; the
        server instructions say what it does). Credentials come from the vault by reference
        (the setup page: <account>.<field>, lab_list_credentials). Written for a fresh
        install; on another state it hands back at the first step that doesn't fit: continue with
        handsets_jev_plan or by hand. Returns the plan result. With isolate (when the profile asks
        for it), the app's sibling builds are disabled during the login, because each of them
        claims the same return link, and enabled again after, also after a crash.
        A pool account (the profile's accounts.pools, e.g. a test-user pool service) takes a fresh
        user from the pool for the environment on every login; the result's pool_user names it, and
        user=<that id> logs the same one in again.
        """,
        str("account", "stored test account (lab_list_credentials shows them, with what each is for); empty = one complete for this device's platform that no other device is logged in with", ""),
        str("env", "environment the plan switches to; empty = the profile's first", ""),
        str("package", "app package; empty = the profile's default package", ""),
        bool("isolate", "disable the app's other installed builds during the login (default: as the profile says)", true),
        SERIAL,
        BACKEND,
        int("start", "step index to start at (resume after a hand-back with paused_at+1)", 0),
        str("user", "pool accounts only: the pool user to log in as (a pool_user from an earlier login); empty = a fresh one", ""),
    ) { a ->
        val p = rt.profile
        val poolPlan = a.strOrNull("account")?.let(rt.pools::pool)?.let { p.accounts.poolLoginPlan ?: p.accounts.loginPlan }
        val planName = poolPlan ?: p.accounts.loginPlan ?: throw BadArgument("the profile «${p.name}» has no login plan (accounts.loginPlan): log in step by step, or save a plan and run it with handsets_jev_plan_file")
        a.serial?.takeIf { rt.installOnly(it) }?.let { serial -> return@tool text(launchLogin(rt, serial, planName, a).encode()) }
        val dev = rt.device(a.serial)
        val pkg = a.strOrNull("package") ?: requirePackage(rt)
        val account = a.strOrNull("account") ?: rt.testAccounts.pick(dev.serial) ?: throw BadArgument("no test account: add one on the setup page (`testlab open setup`) or with `testlab accounts add`")
        val pool = rt.pools.pool(account)
        val elsewhere = if (pool == null) rt.testAccounts.inUseOn(account, dev.serial) else null
        val env = a.strOrNull("env") ?: p.accounts.loginEnvironments.firstOrNull().orEmpty()
        if (p.accounts.loginEnvironments.isNotEmpty() && env !in p.accounts.loginEnvironments) throw BadArgument("env '$env' is not one of ${p.accounts.loginEnvironments.joinToString()}")
        val plan = ProfilePlans.login(rt, planName, account, env, pkg)
        checkPlan(rt, plan) // refuse before touching the phone when a credential is missing
        val user = pool?.let { poolUser(rt, it, env, dev.serial, a.strOrNull("user")) }
        val args = Args(buildJsonObject { a.strOrNull("backend")?.let { put("backend", it) } }, mapOf("start" to int("start", "", a.int("start")), "backend" to BACKEND, "min_confidence" to MIN_CONFIDENCE, "report_id" to str("report_id", "", "")))
        // A pool login starts the app logged in (no return link to claim), so the siblings can stay.
        val isolate = a.bool("isolate") && p.accounts.isolateDuringLogin && p.app.family != null && pool == null
        val disabled = if (isolate) rt.isolation.isolate(dev.serial, keep = pkg) else emptyList()
        rt.testAccounts.loggedIn(dev.serial, account)
        try {
            val r = runPlanOn(rt, dev, plan, args, "handsets_login")
            val extra = buildMap<String, kotlinx.serialization.json.JsonElement> {
                put("account", JsonPrimitive(account))
                user?.let { putAll(poolJson(it)) }
                if (elsewhere != null) put("warning", JsonPrimitive("$account is also logged in on $elsewhere: tests that change the account (balance, limits) affect both devices; pick another account for parallel runs"))
                if (disabled.isNotEmpty()) put("isolated", kotlinx.serialization.json.JsonArray(disabled.map(::JsonPrimitive)))
            }
            text(kotlinx.serialization.json.JsonObject(r + extra).encode())
        } finally {
            if (disabled.isNotEmpty()) rt.isolation.restore(dev.serial)
        }
    }
}

/**
 * `handsets_login` on a real iPhone or this Mac, whose screen can't be driven: the login plan's app
 * start, with its launch arguments (credentials from the vault), is all it can do. That is the whole
 * login for an app that logs in from launch arguments; the result says which steps were left out.
 */
private suspend fun launchLogin(rt: LabRuntime, serial: String, planName: String, a: no.agens.testlab.mcp.Args): JsonObject {
    val p = rt.profile
    val pkg = a.strOrNull("package") ?: requirePackage(rt)
    val account = a.strOrNull("account") ?: rt.testAccounts.pick(serial) ?: throw BadArgument("no test account: add one on the setup page (`testlab open setup`) or with `testlab accounts add`")
    val env = a.strOrNull("env") ?: p.accounts.loginEnvironments.firstOrNull().orEmpty()
    if (p.accounts.loginEnvironments.isNotEmpty() && env !in p.accounts.loginEnvironments) throw BadArgument("env '$env' is not one of ${p.accounts.loginEnvironments.joinToString()}")
    val plan = ProfilePlans.login(rt, planName, account, env, pkg)
    checkPlan(rt, plan)
    val user = rt.pools.pool(account)?.let { poolUser(rt, it, env, serial, a.strOrNull("user")) }
    val steps = plan.mapNotNull { it as? JsonObject }
    val open = steps.firstOrNull { "open" in it } ?: throw BadArgument("the login plan '$planName' starts no app (no \"open\" step): a real iPhone can't be logged in by tapping yet. Use a simulator")
    val args = (open["args"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { v ->
        if (v is JsonObject && "cred" in v) rt.credentials.resolve((v["cred"] as JsonPrimitive).content, serial) else (v as? JsonPrimitive)?.content ?: v.toString()
    }
    val app = (open["open"] as JsonPrimitive).content.substringBefore('/')
    rt.testAccounts.loggedIn(serial, account)
    val started = rt.ops(serial).launch(serial, app, args)
    val skipped = steps.filter { "open" !in it && "note" !in it && "sleep" !in it }.map { it.keys.firstOrNull().orEmpty() }
    return kotlinx.serialization.json.buildJsonObject {
        put("status", "launched")
        put("account", account)
        user?.let { poolJson(it).forEach { (k, v) -> put(k, v) } }
        put("result", started)
        if (skipped.isNotEmpty()) put("skipped", "this device's screen can't be read or tapped, so these steps were left out: " + skipped.joinToString() + ". Look at the device to check the login")
    }
}

/** A user from [pool] for this login on [serial]; the pool's problems (no key, no VPN) come back as plain errors. */
private suspend fun poolUser(rt: LabRuntime, pool: no.agens.testlab.core.profile.Profile.Pool, env: String, serial: String, pinned: String?) =
    try { rt.pools.take(pool, env, serial, pinned) } catch (e: IllegalStateException) { throw BadArgument(e.message ?: "the pool failed") } catch (e: IllegalArgumentException) { throw BadArgument(e.message ?: "bad pool user") }

private fun poolJson(u: no.agens.testlab.services.runtime.TestPools.User): Map<String, kotlinx.serialization.json.JsonElement> = buildMap {
    put("pool", JsonPrimitive(u.pool))
    u.id?.let { put("pool_user", JsonPrimitive(it)) }
    put("pool_note", JsonPrimitive("the ${u.pool} user for ${u.env}" + (u.id?.let { "; log the same one in again with user=\"$it\"" } ?: "")))
}

/** Plans from the profile's plans/ folder (its platform's own first), filled in per call. */
internal object ProfilePlans {
    fun login(rt: LabRuntime, name: String, account: String, env: String, pkg: String): JsonArray {
        require(Regex("[A-Za-z0-9_\\-]+").matches(account)) { "account names are letters, digits, _ and -" }
        require(Regex("[A-Za-z0-9_.]+").matches(pkg)) { "not a package name: $pkg" }
        val e = rt.profiles.entry
        val dir = e.plansDir ?: throw BadArgument("the profile «${rt.profile.name}» has no plans/ folder for its login plan '$name'")
        val file = if (name.endsWith(".json")) name else "$name.json"
        val f = e.planFile(file) ?: throw BadArgument("the profile's login plan $file is missing from ${dir.path}")
        val envLabel = env.lowercase()
        val text = f.readText().replace("\${account}", account).replace("\${package}", pkg)
            .replace("\${env}", envLabel).replace("\${Env}", envLabel.replaceFirstChar { it.uppercase() })
        return Json.parseToJsonElement(text) as JsonArray
    }
}
