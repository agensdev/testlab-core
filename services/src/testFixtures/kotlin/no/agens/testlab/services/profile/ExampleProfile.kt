package no.agens.testlab.services.profile

import no.agens.testlab.core.profile.Profile
import no.agens.testlab.services.runtime.LabRuntime

/**
 * A made-up shop app the tests and the e2e fakes agree on: repo example-org/shop-android with
 * feature/main/maestro/releasedebug builds, Jira example.atlassian.net project SHOP, deeplinks
 * under www.example.com, test accounts with four fields and a login plan.
 */
object ExampleProfile {
    val FEATURE_PREFIXES = listOf("feature/", "fix/", "bugfix/", "hotfix/")

    val PROFILE = Profile(
        id = "example-shop",
        name = "Example Shop",
        description = "the Example Shop Android app",
        app = Profile.App(defaultPackage = "com.example.shop.feature", productionPackage = "com.example.shop", family = "com.example.shop", label = "the shop app"),
        github = Profile.GitHub(repo = "example-org/shop-android"),
        builds = listOf(
            Profile.BuildDef("feature", "Feature", "build_feature.yml", "app-feature-firebase\\.apk", "com.example.shop.feature", FEATURE_PREFIXES,
                mapOf("environment" to Profile.DispatchInput("prod", listOf("prod", "stage", "int"), "Default environment")), default = true),
            Profile.BuildDef("main", "Main", "build_main.yml", "app-mainFlavor-firebase\\.apk", "com.example.shop.main"),
            Profile.BuildDef("releasedebug", "Release Debug", null, "app-releaseDebug-minifiedDebug(-[0-9a-f]{6,40})?\\.apk", "com.example.shop.releasedebug"),
            Profile.BuildDef("maestro", "Maestro", "build_maestro.yml", "app-maestro-minifiedDebug\\.apk", "com.example.shop.maestro"),
        ),
        releases = Profile.Releases(buildId = "releasedebug"),
        tracker = Profile.Tracker(
            site = "example.atlassian.net", projects = listOf("SHOP"), queueLabels = listOf("devtest", "devTest"), approvedLabel = "devtest-approved",
            onApproved = Profile.Outcome(addLabels = listOf("devtest-approved")), onChanges = Profile.Outcome(handBack = true),
        ),
        devtest = Profile.Devtest(teamCommand = "/devtest {key}", teamCommandPaths = listOf(".claude/commands/devtest.md"), environments = listOf("INT", "STAGE")),
        navigation = Profile.Navigation(
            host = "https://www.example.com",
            destinations = linkedMapOf(
                "books" to Profile.Destination(path = "/shop/books", group = "shop", subpages = true),
                "music" to Profile.Destination(path = "/shop/music", group = "shop", subpages = true),
                "bikes" to Profile.Destination(path = "/sport/bikes", group = "sport", subpages = true),
                "account" to Profile.Destination(path = "/my/account", group = "sections"),
                "home" to Profile.Destination(tab = "home_screen", group = "tabs"),
                "profile" to Profile.Destination(tab = "fragment_profile", group = "tabs"),
            ),
            subpages = listOf("results", "basket"),
            aliases = mapOf("profil" to "profile", "start" to "home"),
        ),
        accounts = Profile.Accounts(
            defaults = listOf("demo"),
            fields = listOf(Profile.Field("nnin", "National ID"), Profile.Field("login_one_time_code", "One-time code"), Profile.Field("login_password", "Password"), Profile.Field("app_personal_code", "App PIN")),
            loginPlan = "login", loginEnvironments = listOf("int", "stage"), loginDescription = "test-ID login with national ID, one-time code and password", isolateDuringLogin = true,
        ),
    )

    /** A login plan in the shape of the profile's (four credentials, the environment switch). */
    val LOGIN_PLAN = """
        [
          {"note": "test login: ${'$'}{Env}"},
          {"open": "${'$'}{package}"},
          {"wait_for": "#nnin", "timeout": 25},
          {"type": {"cred": "${'$'}{account}.nnin"}},
          {"type": {"cred": "${'$'}{account}.login_one_time_code"}},
          {"type": {"cred": "${'$'}{account}.login_password"}},
          {"type": {"cred": "${'$'}{account}.app_personal_code"}},
          {"screenshot": "logged in"}
        ]
    """.trimIndent()

    /** Saves the profile (with its login plan) and makes it the active one. */
    fun install(rt: LabRuntime, p: Profile = PROFILE): Profile {
        val e = rt.profiles.save(p)
        java.io.File(e.dir, "plans").apply { mkdirs() }.resolve("login.json").writeText(LOGIN_PLAN)
        rt.profiles.activate(p.id)
        return p
    }
}
