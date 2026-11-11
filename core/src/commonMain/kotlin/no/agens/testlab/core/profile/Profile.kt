package no.agens.testlab.core.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import no.agens.testlab.core.Platform

/**
 * Everything project-specific: which app, which GitHub repo and builds, which Jira project and
 * labels, the devtest comment, deeplink destinations, the words the autopilot treats as risky,
 * test accounts and the login plan. One profile is active per platform; the core ships a generic
 * one, and a team shares its own as a folder or zip (profile.json + skills/ + plans/).
 * Unknown fields are ignored, so older apps read newer profiles.
 *
 * One profile can cover several platforms: [platforms] holds, per platform id, the fields that
 * differ (app, github, builds, tracker.projects, navigation…), merged over the shared ones. The
 * runtime only ever works with a [variant]: a plain one-platform profile.
 */
@Serializable
data class Profile(
    /** Short id: letters, digits, `-` and `_`; the folder name under `<data>/profiles/`. */
    val id: String,
    val name: String,
    val description: String? = null,
    /** The devices it is for; a space has one active profile per platform, chosen by the device in use. */
    val platform: no.agens.testlab.core.Platform = no.agens.testlab.core.Platform.ANDROID,
    val app: App = App(),
    val github: GitHub = GitHub(),
    val builds: List<BuildDef> = emptyList(),
    val releases: Releases = Releases(),
    val tracker: Tracker = Tracker(),
    val linking: Linking = Linking(),
    val report: Report = Report(),
    val devtest: Devtest = Devtest(),
    val navigation: Navigation = Navigation(),
    val jev: Jev = Jev(),
    val accounts: Accounts = Accounts(),
    /** Free texts shown to the user, by key (e.g. `proxyman.cert`); missing keys use the core's text. */
    val texts: Map<String, String> = emptyMap(),
    val imports: Imports = Imports(),
    /**
     * Helper repos the team's testing leans on (test management guides, an E2E test suite): Testlab
     * fetches them read-only with the user's GitHub token, offers their skills next to its own, and
     * lets Claude list, read and search them, so nobody has to work from inside those repos.
     */
    val testRepos: List<TestRepo> = emptyList(),
    /**
     * Where the app's screenshot tests keep their reference images (globs; empty = the usual places:
     * Paparazzi's and Roborazzi's folders, swift-snapshot-testing's `__Snapshots__`). A PR that
     * changes the UI updates them, which shows its screens before and after without a build.
     */
    val snapshotImages: List<String> = emptyList(),
    /** Where the app's crashes in the field are: Firebase Crashlytics, read with Google's Android CLI. */
    val crashlytics: Crashlytics = Crashlytics(),
    /**
     * Dialogs that are noise for testing (a test-build service asking to sign in, a rating prompt):
     * handsets_open_app and Jev's screen reads dismiss them with the button named, never another,
     * and Jev never sees them as a step.
     */
    val dismiss: List<Dismiss> = emptyList(),
    /**
     * Per platform (`android`, `ios`): the fields that differ from the shared ones above, as they
     * would be written at the top level. Objects merge field by field, lists and values replace.
     * Empty = a profile for [platform] only.
     */
    val platforms: Map<String, JsonObject> = emptyMap(),
    /** Set on a [variant]: the platform it was made for (never written to profile.json). */
    @Transient val variantOf: Platform? = null,
) {
    @Serializable
    data class App(
        /** The package plans, deeplinks and the login open by default. */
        val defaultPackage: String = "",
        /** The production package: risky (purchase-worded) taps are refused on it unless Settings allows them. */
        val productionPackage: String? = null,
        /** Package family whose other installed builds `isolate` disables during a login (`family` and `family.*`). */
        val family: String? = null,
        /** Words for the app in UI and tool texts ("the app"); defaults to "the app". */
        val label: String? = null,
    )

    @Serializable
    data class GitHub(
        /** REST API base: GitHub Enterprise, or a local fake in end-to-end tests. */
        val apiUrl: String = "https://api.github.com",
        /** Organisation, for the SSO hint; defaults to the repo owner. */
        val org: String? = null,
        /** `owner/name`; empty = no GitHub features. */
        val repo: String = "",
    ) {
        val owner: String get() = org ?: repo.substringBefore('/')
    }

    /** One kind of CI build: which workflow makes it, how its APK artifact is named, which package it installs as. */
    @Serializable
    data class BuildDef(
        val id: String,
        val label: String,
        /** The workflow file whose runs carry it (`build.yml`); null when it only comes with other runs. */
        val workflowFile: String? = null,
        /** Regex for the artifact (or release asset) name. */
        val artifact: String,
        val packageName: String = "",
        /** Branch prefixes the workflow builds by itself for PRs; empty = any branch. */
        val autoPrefixes: List<String> = emptyList(),
        /** workflow_dispatch inputs for «Start build»; the first input with choices is offered. */
        val dispatchInputs: Map<String, DispatchInput> = emptyMap(),
        /** The build a PR number or branch means by default. */
        val default: Boolean = false,
        /**
         * iOS: `device` for a device build (`.ipa`: real iPhones and iPads, this Mac), `simulator` for a
         * simulator build (`.app`). A real device takes the first `device` type when none is named.
         */
        val target: String? = null,
    )

    @Serializable
    data class DispatchInput(val default: String = "", val choices: List<String> = emptyList(), val description: String? = null)

    @Serializable
    data class Releases(
        /** Which build type a release asset counts as (its artifact regex matches the asset name). */
        val buildId: String? = null,
    )

    @Serializable
    data class Tracker(
        /** Jira Cloud site host (`example.atlassian.net`); empty = no Jira features. */
        val site: String = "",
        /** Project keys the queue and searches use. */
        val projects: List<String> = emptyList(),
        /** Labels that clear a ticket for a devtest. */
        val queueLabels: List<String> = listOf("devtest"),
        /**
         * When set, a ticket must also carry at least one of these to be picked (e.g. a review step
         * that must be passed or skipped first: `["ui-review-skipped", "ui-review-approved"]`).
         */
        val requiredLabels: List<String> = emptyList(),
        /** Set when a devtest passes; tickets with it leave the queue. */
        val approvedLabel: String = "devtest-approved",
        /** Replaces the generated queue JQL when set; `{projects}`, `{labels}`, `{required}`, `{approved}` are filled in. */
        val queueJql: String? = null,
        /** What «approved» does besides the comment. */
        val onApproved: Outcome = Outcome(addLabels = listOf("devtest-approved")),
        /** What «changes needed» does besides the comment. */
        val onChanges: Outcome = Outcome(handBack = true),
    )

    @Serializable
    data class Outcome(val addLabels: List<String> = emptyList(), val removeLabels: List<String> = emptyList(), val handBack: Boolean = false)

    @Serializable
    data class Linking(
        /** Branch prefixes searched for `{prefix}{KEY}`… branches; empty = the build types' auto prefixes. */
        val branchPrefixes: List<String> = emptyList(),
        /** Also branches named `{KEY}-…` without a prefix. */
        val bareKeyBranches: Boolean = true,
    )

    @Serializable
    data class Report(
        /**
         * The devtest comment in Markdown. Placeholders: {{date}} {{tester}} {{build}} {{device}} {{branch}}
         * {{env}} {{result}} {{report}} {{approver}}; lists {{#tested}}…{{.}}…{{/tested}},
         * {{#notTested}}…{{/notTested}}, with {{^tested}}…{{/tested}} when empty; sections
         * {{#report}}…{{/report}} only when a report is attached.
         */
        val template: String = DEFAULT_TEMPLATE,
        /** java.time pattern for {{date}}. */
        val dateFormat: String = "yyyy-MM-dd",
        /** Words the template uses for missing values. */
        val unknown: String = "unknown",
        /** Where the build's commit is named: «{version} ({code}), {package}, CI build @ {sha}». */
        val buildLine: String = "{version} ({code}), {package}, {source}",
        val ciBuildWord: String = "CI build",
        val releaseWord: String = "release",
        /** The comment's heading, also the default report title prefix. */
        val title: String = "Devtest",
        /** Hint for Claude: the language the comment items are written in. */
        val language: String = "English",
    ) {
        companion object {
            val DEFAULT_TEMPLATE = """
                ## Devtest {{date}}

                **Tested by:** {{tester}}⏎
                **Build:** {{build}}⏎
                {{#device}}
                **Device:** {{device}}⏎
                {{/device}}
                **Branch:** {{branch}}⏎
                **Environment:** {{env}}

                ### Tested
                {{#tested}}
                - {{.}}
                {{/tested}}
                {{^tested}}
                - (nothing)
                {{/tested}}

                ### Not tested
                {{#notTested}}
                - {{.}}
                {{/notTested}}
                {{^notTested}}
                - (nothing left out)
                {{/notTested}}

                ### Result
                {{result}}
                {{#report}}

                A report with screenshots and the recording is attached ({{report}}).
                {{/report}}

                🤖 Written by Claude, approved by {{approver}}
            """.trimIndent().replace("⏎", "  ") // two trailing spaces: a Markdown line break
        }
    }

    @Serializable
    data class Devtest(
        /** The team's own Claude Code command in a checkout that has it, e.g. `/devtest {key}`. */
        val teamCommand: String? = null,
        /** Paths (relative to the work folder) whose presence means the checkout has that command. */
        val teamCommandPaths: List<String> = emptyList(),
        /** The environments offered for the comment's «Environment» line; the first is the default. */
        val environments: List<String> = listOf("test"),
    )

    @Serializable
    data class Navigation(
        /** Base for deeplink paths (`https://www.example.com`); empty = full URLs only. */
        val host: String = "",
        /** Named destinations for `handsets_go`. */
        val destinations: Map<String, Destination> = emptyMap(),
        /** Subpages a destination with `subpages = true` accepts (`…/{name}/{subpage}`). */
        val subpages: List<String> = emptyList(),
        val aliases: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class Destination(
        /** A deeplink path under the host (or a full URL). */
        val path: String? = null,
        /** Or a tab reached by tapping this resource id (no deeplink). */
        val tab: String? = null,
        /** Heading it is listed under (`handsets_go('list')`). */
        val group: String = "destinations",
        val subpages: Boolean = false,
    )

    @Serializable
    data class Dismiss(
        /** What it is, for results and logs ("Firebase App Distribution sign-in"). */
        val name: String,
        /** Texts of which one must be on screen (contains, any case). */
        @kotlinx.serialization.SerialName("when") val whenShown: List<String>,
        /** The button to press, by its exact text (any case); the first one found wins. */
        val tap: List<String>,
        /** Only while one of these apps is in front; empty = any. */
        val packages: List<String> = emptyList(),
    )

    @Serializable
    data class Jev(
        /** Translations and app vocabulary the model gets with every question: `Label=meaning; …`. */
        val glossary: String = "",
        /** One example sentence about picking by meaning rather than by literal words. */
        val pickExample: String? = null,
        /** One example about tabs that look alike, for the «is the goal done» question. */
        val doneExample: String? = null,
        /** The language most labels are in, when it differs from the goals' («Norwegian»). */
        val labelLanguage: String? = null,
        /** Extra purchase/destructive words (regex alternatives) on top of the core's English ones. */
        val riskyWords: List<String> = emptyList(),
        /** Extra labels that leave the screen (back/close in the app's language). */
        val leaveWords: List<String> = emptyList(),
        /** Extra sentence for the pick question (e.g. not to give up on goals that mention betting). */
        val pickNote: String? = null,
    )

    @Serializable
    data class Accounts(
        /** Account names created by default in Settings → Test accounts. */
        val defaults: List<String> = emptyList(),
        /** The fields every test account has (vault entries `account.<name>.<field>`). */
        val fields: List<Field> = listOf(Field("username", "Username"), Field("password", "Password")),
        /** The plan (in the profile's plans/) `handsets_login` runs, with `${account}`, `${package}`, `${env}`, `${Env}` filled in. */
        val loginPlan: String? = null,
        /** The environments `handsets_login` accepts; the first is the default. */
        val loginEnvironments: List<String> = emptyList(),
        /** Shown in the tool description: what the login does. */
        val loginDescription: String? = null,
        /** Installed sibling builds are disabled during the login (they claim the same return link). */
        val isolateDuringLogin: Boolean = false,
        /** Test-user pools: accounts fetched fresh from a service for each login instead of saved by hand. */
        val pools: List<Pool> = emptyList(),
        /** The login plan for a pool account; null = [loginPlan]. */
        val poolLoginPlan: String? = null,
    )

    /**
     * A test-user pool: `handsets_login(account = name)` asks [url] for a user and the plan's
     * `{"cred": "<name>.<field>"}` references read that user's [fields]. The service's key is a
     * secret (`pool.<name>.key`, never in the profile), put into [url] or [headers] as `${key}`;
     * `${env}` is the login's environment.
     */
    @Serializable
    data class Pool(
        /** The account name plans and handsets_login use (`testpool`). */
        val name: String,
        /** https URL answering with JSON: one user, or a list (one is picked at random). */
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        /** Account field → where it is in the answer (`personId`, `user.id`). */
        val fields: Map<String, String> = emptyMap(),
        /** The field that names a user: shown in the result, and what `user` pins a login to. */
        val id: String? = null,
        /** What the pool's users are, shown to Claude. */
        val description: String? = null,
    )

    @Serializable
    data class Field(val key: String, val label: String, val secret: Boolean = true)

    @Serializable
    data class Crashlytics(
        /** Firebase app id per package name (`no.example.app` → `1:1234567890:android:abc123`), from the app's google-services.json. */
        val apps: Map<String, String> = emptyMap(),
        /** The time window when none is asked for: 24h, 7d, 30d, 60d or 90d. */
        val timeframe: String = "7d",
    )

    @Serializable
    data class TestRepo(
        /** Short name, used in tool calls and skill names (`testforvaltning`, `maestro`). */
        val id: String,
        /** `owner/name` on the profile's GitHub. */
        val repo: String,
        /** Branch or tag; null = the repo's default branch. */
        val ref: String? = null,
        /** `docs` (guides and skills) or `maestro` (also an index of its Maestro flows). */
        val kind: String = "docs",
        /** Where its skills are: folders holding `<name>/SKILL.md`, or SKILL.md files. */
        val skills: List<String> = listOf(".claude/skills", "SKILL.md"),
        /** What it is for, shown to Claude next to its id. */
        val description: String? = null,
    )

    @Serializable
    data class Imports(
        /** macOS Keychain services to offer importing tokens from: service → (keychain account → vault key). */
        val keychain: Map<String, Map<String, String>> = emptyMap(),
    )

    fun build(id: String): BuildDef? = builds.firstOrNull { it.id.equals(id, ignoreCase = true) }
    val defaultBuild: BuildDef? get() = builds.firstOrNull { it.default } ?: builds.firstOrNull { it.workflowFile != null }
    val appLabel: String get() = app.label ?: "the app"

    fun text(key: String, fallback: String): String = texts[key] ?: fallback

    /** The platforms this profile is for: [platforms]' keys, else [platform]. */
    val covers: List<Platform> get() = if (platforms.isEmpty()) listOf(platform) else platforms.keys.mapNotNull { Platform.of(it) }

    /** The one-platform profile for [p]: the shared fields with [p]'s merged over them. */
    fun variant(p: Platform): Profile {
        if (platforms.isEmpty()) return this
        val own = platforms.entries.firstOrNull { Platform.of(it.key) == p }?.value ?: JsonObject(emptyMap())
        val merged = merge(shared(), own) + mapOf("platform" to JsonPrimitive(p.id))
        return json.decodeFromJsonElement(serializer(), JsonObject(merged)).copy(variantOf = p)
    }

    /**
     * This (multi-platform) profile with an edited [variant] written back: a field the variant's
     * platform overrides changes there, any other field changes for every platform.
     */
    fun withEdit(edited: Profile): Profile {
        val p = edited.variantOf ?: return edited
        if (platforms.isEmpty()) return edited.copy(variantOf = null)
        val before = json.encodeToJsonElement(serializer(), variant(p)).jsonObject
        val after = json.encodeToJsonElement(serializer(), edited.copy(variantOf = null, platforms = emptyMap())).jsonObject
        val key = platforms.keys.first { Platform.of(it) == p }
        val own = platforms.getValue(key).toMutableMap()
        val shared = shared().toMutableMap()
        for (k in (before.keys + after.keys) - setOf("platform", "platforms")) {
            if (before[k] == after[k]) continue
            val target = if (k in own) own else shared
            after[k]?.let { target[k] = it } ?: target.remove(k)
        }
        return json.decodeFromJsonElement(serializer(), JsonObject(shared)).copy(platforms = platforms + (key to JsonObject(own)))
    }

    private fun shared(): JsonObject = json.encodeToJsonElement(serializer(), copy(platforms = emptyMap(), variantOf = null)).jsonObject

    companion object {
        val ID = Regex("^[A-Za-z0-9_-]{1,40}$")
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }

        fun parse(text: String): Profile = json.decodeFromString(serializer(), text)

        /** [over] merged into [base]: objects field by field, anything else replaced. */
        fun merge(base: JsonObject, over: JsonObject): Map<String, JsonElement> {
            val out = base.toMutableMap()
            for ((k, v) in over) {
                val b = out[k]
                out[k] = if (b is JsonObject && v is JsonObject) JsonObject(merge(b, v)) else v
            }
            return out
        }

        /** The core's profile: no project configured, every feature that needs one explains how to add it. */
        val GENERIC = Profile(id = "generic", name = "Generic Android app", description = "No project configured yet: set up a project in Settings → Profiles.")
        val GENERIC_IOS = Profile(id = "generic-ios", name = "Generic iOS app", platform = no.agens.testlab.core.Platform.IOS, description = "No iOS project configured yet: set up a project in Settings → Profiles.")

        /** The core's profile for [p]: what a platform without a profile of its own uses. */
        fun generic(p: no.agens.testlab.core.Platform): Profile = if (p == no.agens.testlab.core.Platform.IOS) GENERIC_IOS else GENERIC
        val GENERIC_IDS = setOf(GENERIC.id, GENERIC_IOS.id)
    }
}
