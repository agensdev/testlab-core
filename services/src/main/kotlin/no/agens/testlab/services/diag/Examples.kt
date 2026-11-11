package no.agens.testlab.services.diag

import no.agens.testlab.core.Platform
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.services.github.UserRepo

/**
 * What to ask Claude once Testlab is set up: a handful of prompts that work with the active
 * project, using its own destinations, Jira project and accounts so they can be pasted as they are.
 */
object Examples {
    data class Prompt(val what: String, val prompt: String)

    fun prompts(profiles: Collection<Profile>): List<Prompt> {
        val p = profiles.firstOrNull { it.platform == Platform.ANDROID } ?: profiles.firstOrNull()
        val dest = p?.navigation?.destinations?.keys?.firstOrNull { it.isNotBlank() }
        val project = p?.tracker?.projects?.firstOrNull()?.takeIf { p.tracker.site.isNotBlank() }
        val login = profiles.any { it.accounts.loginPlan != null }
        val ios = profiles.any { it.platform == Platform.IOS }
        return buildList {
            add(Prompt("See the phone", "Show me the phone"))
            add(Prompt("Check the rig", "Is the phone ready to test? Check its network, the proxy and leftover settings"))
            if (login) add(Prompt("Log in", "Open the app and log in with a test account"))
            add(Prompt("A pull request's build", "Install the build of PR 123 and check that the app starts"))
            add(Prompt("Go somewhere", if (dest != null) "Go to $dest and take a screenshot" else "Open the app's settings screen and take a screenshot"))
            add(Prompt("Explore and report", "Tap through ${dest ?: "the main screens"}, look for anything broken, and write a report with screenshots"))
            if (project != null) {
                add(Prompt("Devtest a ticket", "Devtest $project-123"))
                add(Prompt("The devtest queue", "Which tickets are waiting for a devtest?"))
            }
            add(Prompt("A reusable flow", "Write a plan that ${if (login) "logs in and " else ""}opens ${dest ?: "the app"}, run it, and save it as a plan"))
            add(Prompt("Network evidence", "Record the session with Proxyman and show me the requests the app made"))
            if (ios) add(Prompt("iOS", "Do the same on the iOS Simulator"))
        }
    }

    /** Text for a terminal: one prompt per line under a heading. */
    fun forTerminal(profiles: Collection<Profile>): String = buildString {
        appendLine("Try these in Claude Code (or Claude Desktop):")
        prompts(profiles).forEach { appendLine("  «${it.prompt}»") }
    }.trimEnd()

    /** A guess at a repo's platform from its main language, for the repo picker. */
    fun platformHint(r: UserRepo): String? = when (r.language?.lowercase()) {
        "kotlin", "java" -> "Android?"
        "swift", "objective-c" -> "iOS?"
        "dart" -> "Flutter?"
        "typescript", "javascript" -> if (Regex("(?i)(app|mobile|native|expo)").containsMatchIn(r.fullName)) "React Native?" else null
        else -> null
    }

    /** Repos that look like a mobile app come first, then by last push. */
    fun appFirst(repos: List<UserRepo>): List<UserRepo> =
        repos.sortedWith(compareBy<UserRepo> { if (platformHint(it) != null) 0 else 1 }.thenByDescending { it.pushedAt.orEmpty() })
}
