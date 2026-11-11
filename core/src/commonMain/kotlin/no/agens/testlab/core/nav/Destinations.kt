package no.agens.testlab.core.nav

import no.agens.testlab.core.profile.Profile

/**
 * Named destinations from the active profile: deeplinks (a path under the profile's host, or a
 * full URL) routed by the app's own link handling, and tabs with no deeplink, reached by tapping
 * their resource id.
 */
class Destinations(private val nav: Profile.Navigation) {
    private val absoluteUrl = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    sealed interface Target {
        data class Deeplink(val url: String) : Target
        data class Tab(val rid: String) : Target
    }

    val names: List<String> get() = nav.destinations.keys.toList()

    fun resolve(destination: String, subpage: String? = null): Target? {
        val key = destination.trim().lowercase()
        val d = nav.aliases.entries.firstOrNull { it.key.lowercase() == key }?.value ?: nav.destinations.keys.firstOrNull { it.lowercase() == key } ?: return null
        val dest = nav.destinations.getValue(d)
        val sub = subpage?.trim()?.trim('/')?.takeIf { it.isNotEmpty() && dest.subpages }
        return when {
            dest.tab != null -> Target.Tab(dest.tab)
            dest.path != null -> Target.Deeplink(url(dest.path) + (sub?.let { "/$it" } ?: ""))
            else -> null
        }
    }

    /** A path under the host, or a full URL. */
    fun url(pathOrUrl: String): String = when {
        absoluteUrl.containsMatchIn(pathOrUrl) -> pathOrUrl
        nav.host.isBlank() -> throw IllegalArgumentException("'$pathOrUrl' is a path, but the profile has no navigation.host: pass a full URL")
        else -> nav.host.trimEnd('/') + (if (pathOrUrl.startsWith("/")) "" else "/") + pathOrUrl
    }

    fun listing(): String = buildString {
        if (nav.destinations.isEmpty()) {
            appendLine("(the active profile names no destinations: add them under navigation.destinations)")
        } else {
            val width = nav.destinations.values.maxOf { it.group.length }
            for ((group, ds) in nav.destinations.entries.groupBy { it.value.group }) {
                appendLine("${group.padEnd(width)} : ${ds.joinToString(" ") { it.key }}")
                if (ds.any { it.value.subpages } && nav.subpages.isNotEmpty()) appendLine("${"".padEnd(width)}   + subpage: ${nav.subpages.joinToString(" ")}")
            }
        }
        append("anything else : handsets_open_url(" + (if (nav.host.isBlank()) "url" else "path-or-url") + ")")
    }
}
