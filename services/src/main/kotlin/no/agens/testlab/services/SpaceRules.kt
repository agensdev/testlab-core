package no.agens.testlab.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.agens.testlab.AppIdentity
import no.agens.testlab.core.profile.Profile
import java.io.File

class SpaceRefused(message: String) : IllegalArgumentException(message)

/**
 * A space's `space.json`: which client it is for and what that client's work may reach. A profile
 * whose GitHub owner, Jira site or app isn't listed is refused (on import, save and activate), and
 * so are GitHub and Jira calls for anything else, so one client's token never goes to another's repo.
 *
 * An empty space (no lists yet) takes the first profile it gets as its rules ([admit] pins them);
 * after that an empty list means "none". The default space has no rules unless the file exists,
 * so installs from before spaces work as they did.
 */
@Serializable
data class SpaceRules(
    val client: String? = null,
    /** GitHub owners (users or organisations) whose repos this space may use. */
    val githubOwners: List<String> = emptyList(),
    /** Jira Cloud sites (`example.atlassian.net`). */
    val jiraSites: List<String> = emptyList(),
    /** App package families (`com.example.shop` and `com.example.shop.*`). */
    val appFamilies: List<String> = emptyList(),
    /** The client's code checkouts: Claude in this space may read them, and other spaces may not. */
    val codeRoots: List<String> = emptyList(),
    /** What handing a shared phone to another client does. */
    val handover: Handover = Handover(),
) {
    @Serializable
    data class Handover(
        /** Clear the data of the client's apps (logins, test accounts, caches). */
        val clearApps: Boolean = true,
        /** Uninstall them instead (test builds the next client must not see). */
        val uninstall: Boolean = false,
        /** Stop routing the phone through the proxy. */
        val proxy: Boolean = true,
        /** Put TalkBack and font size back. */
        val accessibility: Boolean = true,
    )

    /** No owner, site or app pinned yet: the first profile decides. */
    val open: Boolean get() = githubOwners.isEmpty() && jiraSites.isEmpty() && appFamilies.isEmpty()

    /** Why [p] doesn't belong in this space; empty when it does (or nothing is pinned yet). */
    fun problems(p: Profile, space: String): List<String> {
        if (open) return emptyList()
        val out = mutableListOf<String>()
        for (o in owners(p)) if (!ownerOk(o)) out += "its GitHub owner $o isn't one of this space's (${githubOwners.ifEmpty { listOf("none") }.joinToString()}): ${AppIdentity.SLUG} --space $space space allow github $o"
        p.tracker.site.takeIf { it.isNotBlank() }?.let { if (!siteOk(it)) out += "its Jira site $it isn't this space's (${jiraSites.ifEmpty { listOf("none") }.joinToString()}): ${AppIdentity.SLUG} --space $space space allow jira $it" }
        for (pkg in packages(p)) if (!appOk(pkg)) out += "its app $pkg isn't one of this space's (${appFamilies.ifEmpty { listOf("none") }.joinToString()}): ${AppIdentity.SLUG} --space $space space allow app ${p.app.family ?: pkg}"
        return out
    }

    /** These rules plus what [p] brings, for a space that has none yet. */
    fun pinnedBy(p: Profile): SpaceRules = if (!open) this else copy(
        githubOwners = owners(p).toList(),
        jiraSites = listOfNotNull(p.tracker.site.takeIf { it.isNotBlank() }?.lowercase()),
        appFamilies = listOfNotNull((p.app.family ?: p.app.defaultPackage).takeIf { it.isNotBlank() }),
    )

    fun ownerOk(owner: String) = open || githubOwners.any { it.equals(owner, ignoreCase = true) }
    fun siteOk(site: String) = open || jiraSites.any { it.equals(site.trim().removePrefix("https://").trimEnd('/'), ignoreCase = true) }
    fun appOk(pkg: String) = open || appFamilies.any { pkg == it || pkg.startsWith("$it.") }

    private fun owners(p: Profile): Set<String> =
        if (p.github.repo.isBlank()) emptySet() else setOf(p.github.repo.substringBefore('/'), p.github.owner).filter { it.isNotBlank() }.toSet()

    private fun packages(p: Profile): Set<String> = (listOfNotNull(p.app.defaultPackage, p.app.productionPackage, p.app.family) + p.builds.map { it.packageName })
        .filter { it.isNotBlank() }.toSet()

    companion object {
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** The rules of the space in [dirs], read fresh each time (a `space allow` from the CLI applies at once). */
class SpaceRulesStore(private val dirs: AppDirs) {
    val space: String get() = dirs.space
    private val file get() = dirs.rules

    /** The default space without a `space.json` has no rules at all. */
    val enforced: Boolean get() = space != Spaces.DEFAULT || file.isFile

    val current: SpaceRules get() =
        if (!file.isFile) SpaceRules(client = space.takeIf { it != Spaces.DEFAULT })
        else runCatching { SpaceRules.json.decodeFromString(SpaceRules.serializer(), file.readText()) }
            // A broken file must not open the space up: nothing is allowed until it is fixed.
            .getOrElse { SpaceRules(client = "‹unreadable ${file.path}›", githubOwners = listOf("‹none›")) }

    fun save(r: SpaceRules) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "space.json.tmp")
        tmp.writeText(SpaceRules.json.encodeToString(SpaceRules.serializer(), r))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    fun allowed(p: Profile): Boolean = !enforced || current.problems(p, space).isEmpty()

    /** Refuses a profile of another client; a space with no rules yet takes this one's. */
    fun admit(p: Profile) {
        if (!enforced) return
        val r = current
        val problems = r.problems(p, space)
        if (problems.isNotEmpty()) throw SpaceRefused("The profile «${p.name}» belongs to another client than the space $space:\n- " + problems.joinToString("\n- "))
        if (r.open && !r.pinnedBy(p).open) save(r.pinnedBy(p))
    }

    fun checkRepo(repo: String) {
        if (!enforced || repo.isBlank()) return
        val owner = repo.substringBefore('/')
        if (!current.ownerOk(owner)) throw SpaceRefused("$repo isn't this space's ($space allows GitHub owners ${current.githubOwners.joinToString().ifEmpty { "none" }})")
    }

    fun checkSite(site: String) {
        if (!enforced || site.isBlank()) return
        if (!current.siteOk(site)) throw SpaceRefused("$site isn't this space's ($space allows Jira sites ${current.jiraSites.joinToString().ifEmpty { "none" }})")
    }
}
