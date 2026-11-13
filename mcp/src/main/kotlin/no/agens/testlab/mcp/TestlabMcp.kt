package no.agens.testlab.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.launch
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.AppIdentity
import no.agens.testlab.services.runtime.LabRuntime

/**
 * Everything ${AppIdentity.NAME} exposes over MCP: the tools (names as in the lab, so its prompts and
 * skills carry over), the playbooks as prompts and resources, and the server instructions.
 */
class TestlabMcp(
    private val rt: LabRuntime,
    private val endpoint: McpEndpoint = McpEndpoint.forBind(rt.settings.current.mcp.port, rt.settings.current.mcp.listen),
) {
    /** The browser pages: one instance, so a ticket a tool makes is accepted by the server's routes. */
    private val ui by lazy { UiPages(rt) }

    /** A signed-in link to a browser page on this server (`testlab.localhost`, which browsers send here). */
    fun pageUrl(page: String, query: Pair<String, String>? = null): String {
        val base = endpoint.url.removeSuffix("/mcp").replace(Regex("//(127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0|\\[::1\\])"), "//${McpGateway.UI_HOST}")
        return "$base/ui/$page?t=${ui.ticket()}" + (query?.let { (k, v) -> "&$k=" + java.net.URLEncoder.encode(v, Charsets.UTF_8) } ?: "")
    }

    val tools = ToolBox(rt).apply {
        deviceTools(rt)
        viewTools(rt)
        autopilotTools(rt)
        buildTools(rt)
        reportTools(rt)
        jiraTools(rt)
        proxymanTools(rt)
        rigTools(rt)
        testRepoTools(rt)
        androidCliTools(rt)
        iosTools(rt)
        screenTools(rt)
        labTools(rt) { endpoint.url }
        pageTools(rt, { serving && rt.settings.current.mcp.controlApi }, ::pageUrl)
    }
    private val skills = Skills(rt)

    /** Whether this process serves the browser pages (an HTTP gateway was made); stdio-only servers don't. */
    @Volatile private var serving = false

    /** [listen] / [allowedHosts] override the `mcp` settings for this run (`serve --listen`). */
    fun gateway(listen: String? = null, allowedHosts: List<String>? = null): McpGateway {
        // Proxyman's tool list for the sessions to come (it starts Proxyman's mcp-server once).
        if (rt.settings.current.proxyman.enabled) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { rt.proxyman.refreshTools() } }
        serving = true
        val ui = if (rt.settings.current.mcp.controlApi) this.ui else null
        val api = if (rt.settings.current.mcp.controlApi) ControlApi(rt, { name, args -> tools.call(name, args) }, ui) else null
        return McpGateway(
            token = { rt.mcpToken() }, instructions = ::instructions, register = { register(this) }, api = api, ui = ui,
            discovery = api?.let { java.io.File(rt.dirs.data, "control.json") },
            listen = { listen ?: rt.settings.current.mcp.listen }, allowedHosts = { allowedHosts ?: rt.settings.current.mcp.allowedHosts },
        )
    }

    fun register(server: Server) {
        tools.registerAll(server)
        warmProxyman()
        tools.mirrorProxyman(server, rt)
        prompts(server)
        registerDevtestPrompt(server)
        resources(server)
        Views.register(server)
    }

    @Volatile private var lastWarm = 0L

    /**
     * A session lists its tools once (no list_changed), so Proxyman's must be there when it starts: when
     * the start-up listing came back empty (bridge not up yet, network late after login), try again,
     * for at most 5 s and at most once a minute.
     */
    private fun warmProxyman() {
        val px = rt.settings.current.proxyman
        if (!px.enabled || !px.mirrorTools || rt.proxyman.tools.isNotEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastWarm < 60_000) return
        lastWarm = now
        runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(5_000) { rt.proxyman.refreshTools() } } }
    }

    fun instructions(): String = instructions(rt.profile, rt.defaultPackage().ifBlank { "(none: set app.defaultPackage in the profile)" }) + iosInstructions()

    /** With an iOS profile in the space: how its simulators are driven, beside the Android phones. */
    private fun iosInstructions(): String {
        val ios = rt.profiles.forPlatform(no.agens.testlab.core.Platform.IOS).profile
        if (ios.id in no.agens.testlab.core.profile.Profile.GENERIC_IDS) return ""
        return "\n\nIOS SIMULATORS: «${ios.name}» (bundle ${ios.app.defaultPackage.ifBlank { "?" }}) is this space's iOS profile. A call on an iOS Simulator " +
            "(platform ios in handsets_list_adb_devices, serial = its UDID; boot one with handsets_simulator_start) uses it: its builds " +
            "(handsets_install_branch_build installs the simulator build), destinations, login and plans. The same tools work as on a phone; " +
            "BACK is the navigation bar's back button or the back swipe; TalkBack, handsets_key and unlock are Android only; handsets_proxy(on) " +
            "routes the whole Mac (it asks first). Devtest a ticket on its platform: the queue says which (" +
            ios.tracker.projects.joinToString("/").ifEmpty { "the iOS project" } + " on a simulator, the Android project's on a phone). " +
            "PLATFORMS AT ONCE: every call uses the platform of the device it names (pass serial), of the ticket key, or `platform` on " +
            "github_* and build tools, so an Android phone and a simulator can be driven side by side. With both free, devtest one " +
            "ticket per device at the same time: jira_devtest_next(serial) gives each device the next ticket for its platform (devtest skill). " +
            "REAL iPHONES AND THIS MAC: paired iPhones/iPads (iOS 17+) and, on Apple Silicon, this Mac (serial `mac`) are listed with state " +
            "install-only: handsets_install_branch_build puts the device build (.ipa) on them, handsets_open_app(package, args) and " +
            "handsets_login start the app (with its launch arguments), but their screens can't be read or tapped: drive a simulator for that."
    }

    private fun prompts(server: Server) {
        fun user(text: String, description: String) = GetPromptResult(listOf(PromptMessage(Role.User, TextContent(text))), description)
        fun skill(name: String) = skills.get(name)?.text ?: "(skill $name is missing)"

        server.addPrompt(
            "drive-device", "Drive the attached phone with ${AppIdentity.NAME}'s tools: the read → act → sync loop.",
            listOf(PromptArgument("task", "what to do on the device", required = false)),
        ) { req ->
            val task = req.params.arguments?.get("task")?.takeIf { it.isNotBlank() }
            user(skill("drive-device") + "\n\n---\n\n" + (task?.let { "Task: $it\n\nStart with handsets_use_device, then follow the loop above." } ?: "Start with handsets_use_device, then ask what to do on the device."), "drive the device")
        }
        server.addPrompt(
            "write-plan", "Write, validate and save an autopilot plan for a device flow, then run it.",
            listOf(PromptArgument("flow", "the flow to automate, from which starting state", required = true), PromptArgument("name", "name to save it under", required = false)),
        ) { req ->
            val flow = req.params.arguments?.get("flow").orEmpty()
            val name = req.params.arguments?.get("name")?.takeIf { it.isNotBlank() } ?: "my-plan"
            user(
                skill("write-plan") + "\n\n---\n\nWrite a plan for this flow: $flow\n\n" +
                    "Check the credential references with lab_list_credentials, look at the screens you are unsure of with handsets_ui_dump, " +
                    "save it with lab_save_plan(name=\"$name\") and fix every reported mistake, then run it with handsets_autopilot_plan_file(\"$name\") " +
                    "and resume from any hand-back.",
                "write a plan",
            )
        }
        server.addPrompt(
            "device-login", "Log the app in on the attached device with a stored test account (the profile's login plan).",
            listOf(PromptArgument("account", "stored test account (default: the profile's first)", required = false), PromptArgument("env", "environment (default: the profile's first)", required = false)),
        ) { req ->
            val p = rt.profile
            val account = req.params.arguments?.get("account")?.takeIf { it.isNotBlank() } ?: rt.accounts().firstOrNull() ?: "my_account"
            val env = req.params.arguments?.get("env")?.takeIf { it.isNotBlank() } ?: p.accounts.loginEnvironments.firstOrNull().orEmpty()
            user(skill("device-login") + "\n\n---\n\nLog in as account \"$account\"" + (if (env.isNotEmpty()) " on $env" else "") + ": device-prep first, then handsets_login(account=\"$account\"" + (if (env.isNotEmpty()) ", env=\"$env\"" else "") + "). Finish with a screenshot.", "log in")
        }
    }

    /** The devtest playbook plus the ticket (the `devtest` MCP prompt). */
    private fun devtestPrompt(key: String, focus: String? = null): String =
        (skills.get("devtest")?.text ?: "(skill devtest is missing)") + "\n\n---\n\nDevtest $key." + (focus?.let { " Focus: $it." } ?: "") +
            " Start with jira_get_issue(\"$key\") and jira_linked_prs(\"$key\"), then write the plan and show it to me before touching the phone."

    private fun registerDevtestPrompt(server: Server) {
        server.addPrompt(
            "devtest", "Devtest a Jira ticket on the attached phone: ticket, PR, plan, build, evidence, report.",
            listOf(PromptArgument("ticket", "Jira key, e.g. PROJ-123", required = true), PromptArgument("focus", "what to concentrate on (optional)", required = false)),
        ) { req ->
            val raw = req.params.arguments?.get("ticket").orEmpty()
            val key = no.agens.testlab.services.jira.Jira.parseKey(raw) ?: raw.trim()
            val focus = req.params.arguments?.get("focus")?.takeIf { it.isNotBlank() }
            GetPromptResult(listOf(PromptMessage(Role.User, TextContent(devtestPrompt(key, focus)))), "devtest $key")
        }
    }

    private fun resources(server: Server) {
        for (s in skills.all()) {
            server.addResource("testlab://skills/${s.name}", "skill: ${s.name}", s.description, "text/markdown") { req ->
                ReadResourceResult(listOf(TextResourceContents(skills.get(s.name)?.text ?: "", req.params.uri, "text/markdown")))
            }
        }
        for (p in PlanLibrary(rt).list().filter { it.error == null }) {
            server.addResource("testlab://plans/${p.name}", "plan: ${p.name}", p.note ?: "${p.steps} steps", "application/json") { req ->
                ReadResourceResult(listOf(TextResourceContents(PlanLibrary(rt).read(p.name).let { it.encode() }, req.params.uri, "application/json")))
            }
        }
        server.addResource("testlab://schema/plan", "plan schema", "JSON Schema of an autopilot plan (the step forms handsets_autopilot_plan runs)", "application/schema+json") { req ->
            ReadResourceResult(listOf(TextResourceContents(PLAN_SCHEMA, req.params.uri, "application/schema+json")))
        }
    }

    companion object {
        /** The server instructions for [p]: the core's rules plus the profile's destinations, builds, login and devtest command. */
        fun instructions(p: Profile, defaultPackage: String): String {
            val nav = p.navigation
            val acct = p.accounts.defaults.firstOrNull() ?: "my_account"
            val field = p.accounts.fields.firstOrNull()?.key ?: "password"
            val build = p.defaultBuild
            return listOfNotNull(
                "${AppIdentity.NAME}: drive an Android phone or emulator attached to this computer" + (p.description?.let { " ($it)" } ?: "") +
                    ". Active project profile: «${p.name}». Tools by prefix: handsets_* (the device and the autopilot), lab_* (plans, reports, playbooks, credentials, status), jira_* (the user's Jira), github_* (the repo's PRs, read-only).",
                "DEVICES: whatever adb can reach from this computer: USB, Wi-Fi (Android 11+ wireless debugging), an IP address (adb connect host:port) or an emulator. There is no fixed device list. handsets_connect_device(address, pairing_code?) connects a phone over the network; handsets_list_adb_devices also shows phones advertising wireless debugging nearby. handsets_list_adb_devices shows serials and which one is active. Call handsets_use_device once per session (pass serial when several are attached). Every handsets_* tool takes an optional serial; omitted = the active device.",
                "NARRATE: every tool that acts on the phone takes an optional `why`: one short sentence of what you are doing and why (\"open the cart to check the item count\"). A tester watching the phone sees it next to each action, so pass it on the actions of a test flow.",
                "VISUALS: when the user asks to see / show / look at the app or screen, call handsets_screenshot (a JPEG) and include it in your reply. handsets_ui_dump is text, for locating tappable elements. To let the user watch a whole flow (and tap themselves), call handsets_show_screen once: where the host supports MCP Apps (Claude Desktop) it shows the screen live in the conversation.",
                "INTERACTION (read the screen, then act): handsets_ui_dump returns the accessibility tree as text, one node per line with widget type, \"label\", #resource-id and x,y centre; lines starting with 'tap' are actionable, '-' are labels. Read it BEFORE every tap (never guess coordinates). Then act with handsets_tap (label, #id or CSS-like selector), handsets_fill(selector, value) for fields, handsets_type_text / handsets_submit, handsets_swipe(dir) to scroll, handsets_navigate(back|home|…) for hardware keys, and handsets_wait_for(text) to sync after a screen loads. Full loop and selector syntax: lab_read_skill('drive-device').",
                "ACCESSIBILITY: TalkBack does not block these tools: taps, swipes and typing are injected past its touch handling, so never ask the user to turn it off to let you drive. handsets_talkback(status|on|off) is for testing with TalkBack (off/on restore it exactly); handsets_info shows whether it runs. A PIN or code screen with its own number pad usually takes typed digits: try handsets_type_secret(ref) (or handsets_type_text) before tapping the pad.",
                if (nav.destinations.isNotEmpty()) "NAVIGATION: prefer handsets_go(destination) (deeplinks, robust) over tapping through menus: ${nav.destinations.keys.joinToString(", ")} (handsets_go('list') enumerates); handsets_open_url for any other " + (if (nav.host.isNotBlank()) "page under ${nav.host}." else "URL.")
                else "NAVIGATION: handsets_open_url(url) opens a deeplink in the app; the profile names no handsets_go destinations.",
                "AUTOPILOT: for a short navigation goal with no deeplink, handsets_autopilot_do(goal) reads the screen as numbered candidates, picks and taps the element that advances the goal, verifies the screen changed and repeats (about 1–3 s a step). It never types, pays or launches apps; purchase-worded nodes are filtered. Statuses: goal_done | blocked | needs_decision (nothing executed: pick from the candidates with handsets_tap_xy) | stuck | error. Preview one decision with handsets_autopilot_pick(goal), take one step with handsets_autopilot_step. FASTEST: handsets_autopilot_plan(plan) runs a whole flow in ONE call (goals, scripted taps, typed credentials by reference, waits, screenshots) and returns only on a hand-back; on paused, act on hand_back and resume with start=paused_at+1. Save plans with lab_save_plan and run them by name with handsets_autopilot_plan_file; lab_read_skill('write-plan') explains the step forms. lab_autopilot_stats summarises the decisions afterwards.",
                "CREDENTIALS: secrets live in ${AppIdentity.NAME}'s vault on this computer and are used by reference only: {\"type\": {\"cred\": \"$acct.$field\"}} in a plan, handsets_type_secret(ref), handsets_fill(selector, cred=ref). lab_list_credentials shows which references are set. Values are never returned and are masked in every result. Never ask the user to paste a password, national ID or token into the chat: they enter it in ${AppIdentity.NAME} → Settings.",
                (if (p.accounts.loginPlan != null) "LOGIN: handsets_login(account, env) runs the profile's login" + (p.accounts.loginDescription?.let { " ($it)" } ?: "") + " (playbook: device-login). " + (if (p.accounts.pools.isNotEmpty()) "Test-user pools (a fresh user per login, handsets_login(account=<pool>), no saved credentials): " + p.accounts.pools.joinToString { it.name + (it.description?.let { d -> " ($d)" } ?: "") } + ". " else "") else "LOGIN: the profile has no login plan; log in step by step or with a saved plan. ") + "If the phone is locked, handsets_unlock_device.",
                if (build != null) "BUILDS: handsets_install_branch_build(branch or PR number) installs the CI-built PR-head APK of «${build.label}» (login survives); other build types: ${p.builds.joinToString(", ") { it.id }} (type=…). handsets_list_branch_builds shows whether one exists and whether it is from the newest commit; releases are handsets_list_builds / handsets_install_build. handsets_installed_builds says which build is on the phone (for the build line of a report). A branch the workflow skips can be built with handsets_start_branch_build (asks the user first: ${p.text("builds.startCost", "it takes a CI runner for several minutes")})."
                else "BUILDS: the profile names no CI builds; handsets_install_build installs GitHub releases when a repo is set.",
                "SESSION RECORDING: when a session USES THE DEVICE, call lab_session_start at the start (device-prep does). It records the whole session and is attached AUTOMATICALLY by lab_report_write. Don't record analysis-only work. If the user declines a report (or there is nothing to save), call lab_session_discard. lab_session_note(report_id, text) adds a caption at that moment, so the video explains itself.",
                "REPORTS: when the user wants a saved report: lab_report_start(title) → lab_report_screenshot(report_id, caption) at each notable step (and pass report_id to handsets_autopilot_plan so its screenshot steps land there) → lab_report_write(report_id, markdown) covering what was done, what was found and the evidence, with the screenshot lines next to the text they belong to. Screenshots on consecutive lines render side by side. Every report states the app version and build under test (handsets_installed_builds). The report opens offline; report-standalone.html plus session.mp4 can be attached to Jira.",
                if (p.tracker.site.isNotBlank()) "DEVTEST: " + (p.devtest.teamCommand?.let { "with the team's own `${it.substringBefore(' ')}` command (in the app's checkout), use these tools to run the plan items on the device and collect evidence; follow that command's report and approval rules. Without it, " } ?: "") +
                    "lab_read_skill('devtest') is the whole flow: jira_get_issue → jira_linked_prs (several candidates: ask, never guess) → github_get_pr / github_pr_diff → plan → handsets_install_branch_build → lab_report_start(title, ticket=key) with evidence → jira_post_devtest_report (preview=true first; the build line is filled from the installed build; write the items in ${p.report.language}). jira_devtest_queue lists tickets waiting for a devtest."
                else null,
                if (p.tracker.site.isNotBlank()) "JIRA (${p.tracker.site}): reads are free; every write (jira_add_comment, jira_attach, jira_update_labels, jira_assign, jira_post_devtest_report) waits for the user's approval of the exact payload: on the approvals page (`${AppIdentity.SLUG} open approvals`, with a desktop notification), with `${AppIdentity.SLUG} approve`, from another app, or in this client's own dialog when it started ${AppIdentity.NAME} itself. No answer in time is a no. Attachments only come from the reports folder. Comments are Markdown and converted for Jira." else null,
                "PROXYMAN (when the user turned it on in the settings): handsets_proxy(on) routes the phone through the user's Proxyman; proxyman_* are Proxyman's own tools (flows, Map Local mocks, breakpoints…), with an explicit method on every rule (never 'ANY'). After mocking, lab_reset_proxyman and relaunch the app. If the app suddenly can't reach its backend while routed: lab_proxyman_health. lab_report_write attaches the session's traffic as flows.har. WebSockets (e.g. GraphQL subscriptions): a wss 101 flow with isIntercepted=true is decrypted even with responseSize 0 (the messages need proxyman_get_websocket_messages, which only macOS Proxyman has); bare CONNECT tunnels to the host mean SSL proxying is off for it. lab_read_skill('websocket-capture').",
                "SAFETY: uninstalling an app, starting CI builds and Jira writes wait for the user's approval (`${AppIdentity.SLUG} open approvals`, `${AppIdentity.SLUG} approve`, another app, or this client's dialog)." + (p.app.productionPackage?.let { " allow_risky (purchase-worded taps) is refused on the production app $it." } ?: "") + " Default app: $defaultPackage.",
                "PROFILES: lab_list_profiles shows the installed project profiles, lab_use_profile(id) switches (only when the user asks or agrees). These instructions describe «${p.name}» as it was when you connected; after a switch, trust lab_status.",
                "PLAYBOOKS: call lab_list_skills FIRST to see ready-made procedures (device prep, driving the device, writing plans, login), then lab_read_skill(name) and follow it.",
                p.texts["mcp.instructions"],
            ).joinToString("\n\n")
        }

        val PLAN_SCHEMA = """
            {
              "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "${AppIdentity.NAME} autopilot plan",
              "description": "Steps run in order by handsets_autopilot_plan. On a hand-back the run pauses; resume with start=paused_at+1.",
              "type": "array",
              "items": {
                "type": "object",
                "oneOf": [
                  {"required": ["goal"], "properties": {"if_present": {"type": "string", "description": "run this step only when this (#id or text) is on screen; skipped otherwise"}, "goal": {"type": "string", "description": "state goal; Jev loops until the screen shows it"}, "allow_risky": {"type": "boolean"}, "max_steps": {"type": "integer", "default": 5}, "optional": {"type": "boolean", "description": "a goal that is not reached does not stop the plan"}}},
                  {"required": ["step"], "properties": {"if_present": {"type": "string", "description": "run this step only when this (#id or text) is on screen; skipped otherwise"}, "step": {"type": "string", "description": "one action; done when the screen changed"}, "allow_risky": {"type": "boolean"}, "optional": {"type": "boolean", "description": "a step that changes nothing does not stop the plan"}}},
                  {"required": ["tap"], "properties": {"if_present": {"type": "string", "description": "run this step only when this (#id or text) is on screen; skipped otherwise"}, "tap": {"type": "string", "description": "#id, exact label or x,y; no model"}, "nth": {"type": "integer", "minimum": 0}}},
                  {"required": ["type"], "properties": {"if_present": {"type": "string", "description": "run this step only when this (#id or text) is on screen; skipped otherwise"}, "type": {"oneOf": [{"type": "string"}, {"type": "object", "required": ["cred"], "properties": {"cred": {"type": "string", "description": "account.field or device_pin"}}}]}}},
                  {"required": ["hide_keyboard"], "properties": {"if_present": {"type": "string", "description": "run this step only when this (#id or text) is on screen; skipped otherwise"}, "hide_keyboard": {"const": true}}},
                  {"required": ["wait_for"], "properties": {"if_present": {"type": "string", "description": "run this step only when this (#id or text) is on screen; skipped otherwise"}, "wait_for": {"type": "string"}, "timeout": {"type": "number", "default": 15}}},
                  {"required": ["open"], "properties": {"if_present": {"type": "string", "description": "run this step only when this (#id or text) is on screen; skipped otherwise"}, "open": {"type": "string", "description": "package, pkg/.Activity, or an iOS bundle id"}, "fresh": {"type": "boolean", "description": "clear the app's data first (a login from a clean state)"}, "args": {"type": "array", "description": "launch values: iOS launch arguments, or Android am start extras (--es key value, --ez key true)", "items": {"oneOf": [{"type": "string"}, {"type": "object", "required": ["cred"], "properties": {"cred": {"type": "string"}}}]}}}},
                  {"required": ["key"], "properties": {"if_present": {"type": "string", "description": "run this step only when this (#id or text) is on screen; skipped otherwise"}, "key": {"type": "string", "description": "back, home, enter…"}}},
                  {"required": ["swipe"], "properties": {"if_present": {"type": "string", "description": "run this step only when this (#id or text) is on screen; skipped otherwise"}, "swipe": {"type": "string", "enum": ["up", "down", "left", "right"], "description": "the finger's direction: up scrolls further down"}}},
                  {"required": ["sleep"], "properties": {"if_present": {"type": "string", "description": "run this step only when this (#id or text) is on screen; skipped otherwise"}, "sleep": {"type": "number", "minimum": 0}}},
                  {"required": ["screenshot"], "properties": {"if_present": {"type": "string", "description": "run this step only when this (#id or text) is on screen; skipped otherwise"}, "screenshot": {"type": "string", "description": "caption"}}},
                  {"required": ["note"], "properties": {"if_present": {"type": "string", "description": "run this step only when this (#id or text) is on screen; skipped otherwise"}, "note": {"type": "string"}}}
                ]
              }
            }
        """.trimIndent()
    }
}

/** The MCP bearer token: created on first use and kept in the vault. */
fun LabRuntime.mcpToken(): String = secrets.get(no.agens.testlab.services.secrets.SecretStore.MCP_TOKEN)?.takeIf { it.isNotEmpty() }
    ?: McpGateway.newToken().also { secrets.put(no.agens.testlab.services.secrets.SecretStore.MCP_TOKEN, it) }
