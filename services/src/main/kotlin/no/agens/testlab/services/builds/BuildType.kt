package no.agens.testlab.services.builds

import no.agens.testlab.core.profile.Profile

/** One kind of CI build from the active profile (see [Profile.BuildDef]). */
data class BuildType(val def: Profile.BuildDef) {
    val id: String get() = def.id
    val label: String get() = def.label
    val workflowFile: String? get() = def.workflowFile
    val packageName: String get() = def.packageName
    private val artifact = Regex(def.artifact)

    fun matches(artifactName: String): Boolean = artifact.matches(artifactName)

    /** A device build (`.ipa`), for real iPhones and this Mac. */
    val forDevice: Boolean get() = def.target == "device"

    companion object {
        fun all(p: Profile): List<BuildType> = p.builds.map(::BuildType)

        /** The build a PR number or a branch means when no type is given. */
        fun default(p: Profile): BuildType = p.defaultBuild?.let(::BuildType) ?: throw NoBuild(
            if (p.builds.isEmpty()) "The profile «${p.name}» names no CI builds: add them under builds (`testlab open profiles`)"
            else "No build type in «${p.name}» has a workflow file",
        )

        fun of(p: Profile, artifactName: String): BuildType? = all(p).firstOrNull { it.matches(artifactName) }

        /** [parse] for a device: on a real iOS device ([realDevice]) the default is the first device build type. */
        fun parse(p: Profile, s: String?, realDevice: Boolean): BuildType {
            if (!realDevice || !s.isNullOrBlank()) return parse(p, s)
            return all(p).firstOrNull { it.forDevice } ?: throw NoBuild("The profile «${p.name}» has no build type for real devices (a .ipa with \"target\": \"device\"): add one under builds")
        }

        fun parse(p: Profile, s: String?): BuildType {
            val v = s?.trim()?.lowercase().orEmpty()
            if (v.isEmpty()) return default(p)
            return all(p).firstOrNull { it.id == v || it.label.lowercase() == v || it.packageName == v }
                ?: throw IllegalArgumentException("unknown build type '$s' (${all(p).joinToString(" | ") { it.id }.ifEmpty { "the profile has none" }})")
        }
    }
}
