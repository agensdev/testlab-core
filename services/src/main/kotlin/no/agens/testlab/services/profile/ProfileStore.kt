package no.agens.testlab.services.profile

import no.agens.testlab.AppIdentity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.asContextElement
import no.agens.testlab.core.Platform
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.core.profile.problems
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BadProfile(message: String) : IllegalArgumentException(message)

/**
 * A profile and where its skills and plans live (null for one built into the app). For a profile
 * that covers several platforms, [forPlatform] gives the entry of one: its [profile] is that
 * platform's variant, and `plans/<platform>/` and `skills/<platform>/` win over the shared ones.
 */
data class ProfileEntry(val profile: Profile, val dir: File?, val bundled: Boolean, val platform: Platform? = null) {
    val skillsDir: File? get() = dir?.let { File(it, "skills") }?.takeIf { it.isDirectory }
    val plansDir: File? get() = dir?.let { File(it, "plans") }?.takeIf { it.isDirectory }

    fun forPlatform(p: Platform): ProfileEntry = if (profile.platforms.isEmpty()) this else copy(profile = profile.variant(p), platform = p)

    /** The plan [name] (`login.json`): this platform's own, else the shared one. */
    fun planFile(name: String): File? = listOfNotNull(platform?.let { p -> plansDir?.let { File(it, p.id) } }, plansDir)
        .map { File(it, name) }.firstOrNull { it.isFile }

    /** Every plan file, this platform's own winning by name. */
    fun planFiles(): List<File> = files(plansDir) { it.isFile && it.name.endsWith(".json") }

    /** Every skill folder, this platform's own winning by name. */
    fun skillDirs(): List<File> = files(skillsDir) { it.isDirectory && Platform.of(it.name) == null }

    private fun files(base: File?, keep: (File) -> Boolean): List<File> {
        base ?: return emptyList()
        val own = platform?.let { File(base, it.id).listFiles(keep) }.orEmpty().toList()
        return (own + base.listFiles(keep).orEmpty().filter { f -> own.none { it.name == f.name } }).sortedBy { it.name }
    }
}

/**
 * The profiles this computer knows: the ones built into the app (classpath `/profiles/index.txt`
 * lists their ids; each is `/profiles/<id>/profile.json` + skills/ + plans/, unpacked to the cache
 * so they read like the user's) and the user's own under `<data>/profiles/<id>/`, which win by id.
 * One profile is active per platform (`Settings.profile` for Android, `Settings.platformProfiles`
 * for the others); without one, the first bundled profile of that platform that isn't generic,
 * else the generic one. A profile that covers several platforms is active for each of them.
 *
 * [current] is the profile of the platform a call is about: the one [scope] puts in the call's
 * coroutine context (the device or ticket it names), else [focus], the active device's. [active]
 * follows [focus].
 */
class ProfileStore(
    private val root: File,
    private val bundleCache: File,
    private val settings: SettingsStore,
    private val rules: no.agens.testlab.services.SpaceRulesStore? = null,
) {
    @Volatile private var focused: Platform = Platform.ANDROID
    @Volatile private var byPlatform: Map<Platform, ProfileEntry> = resolveAll()
    private val activeFlow = MutableStateFlow(byPlatform.getValue(focused))
    val active: StateFlow<ProfileEntry> = activeFlow.asStateFlow()
    val current: Profile get() = entry.profile

    /** The entry [current] comes from (its plans and skills). */
    val entry: ProfileEntry get() = forPlatform(platform)

    /** The platform of the call in progress ([scope]), else the active device's. */
    val platform: Platform get() = scoped.get() ?: focused

    /** The platform whose profile [active] is: the runtime sets it to the active device's. */
    val focus: Platform get() = focused

    private val scoped = ThreadLocal<Platform?>()

    /**
     * A coroutine context element: inside it [current] is [p]'s profile, whatever the active device
     * is. Each tool call runs in the one of the device or ticket it names, so calls for an Android
     * phone and an iOS Simulator can run side by side.
     */
    fun scope(p: Platform): kotlin.coroutines.CoroutineContext = scoped.asContextElement(p)

    fun focus(p: Platform) {
        if (focused == p) return
        focused = p
        activeFlow.value = byPlatform.getValue(p)
    }

    /** The active profile for devices of [p]. */
    fun forPlatform(p: Platform): ProfileEntry = byPlatform.getValue(p)

    /** Every platform's active profile, generic ones included. */
    fun activeAll(): Map<Platform, ProfileEntry> = byPlatform

    private fun saved(p: Platform): String? = if (p == Platform.ANDROID) settings.current.profile else settings.current.platformProfiles[p.id]

    private fun resolveAll(): Map<Platform, ProfileEntry> = Platform.entries.associateWith { resolve(it, saved(it)) }

    private fun publish() {
        byPlatform = resolveAll()
        activeFlow.value = byPlatform.getValue(focused)
    }

    fun all(): List<ProfileEntry> {
        val out = LinkedHashMap<String, ProfileEntry>()
        out[Profile.GENERIC.id] = ProfileEntry(Profile.GENERIC, null, bundled = true)
        out[Profile.GENERIC_IOS.id] = ProfileEntry(Profile.GENERIC_IOS, null, bundled = true)
        for (id in bundledIds()) bundled(id)?.let { out[id] = it }
        root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.forEach { d -> load(d)?.let { out[it.profile.id] = it } }
        // Another client's profiles (a bundled one, one copied in by hand) don't show in this space.
        return out.values.filter { it.profile.id in Profile.GENERIC_IDS || rules?.allowed(it.profile) != false }
    }

    fun get(id: String): ProfileEntry? = all().firstOrNull { it.profile.id == id }

    private fun resolve(p: Platform, id: String?): ProfileEntry {
        val list = all().filter { p in it.profile.covers }
        val e = list.firstOrNull { it.profile.id == id } ?: list.firstOrNull { it.bundled && it.profile.id !in Profile.GENERIC_IDS }
            ?: list.firstOrNull { it.profile.id in Profile.GENERIC_IDS } ?: ProfileEntry(Profile.generic(p), null, bundled = true)
        return e.forPlatform(p)
    }

    /** Makes [id] the active profile of each platform it covers; the focus moves when it covers none of the focused one. */
    fun activate(id: String) {
        val e = get(id) ?: hidden(id)
        rules?.admit(e.profile)
        val covers = e.profile.covers
        settings.update { s ->
            covers.fold(s) { acc, p -> if (p == Platform.ANDROID) acc.copy(profile = id) else acc.copy(platformProfiles = acc.platformProfiles + (p.id to id)) }
        }
        if (focused !in covers) focused = covers.first()
        publish()
    }

    /** An id that exists but belongs to another client says so, instead of "no profile". */
    private fun hidden(id: String): Nothing {
        val p = (listOfNotNull(load(File(root, id))) + bundledIds().filter { it == id }.mapNotNull { bundled(it) }).firstOrNull()?.profile
        if (p != null) rules?.admit(p)
        throw BadProfile("no profile '$id'")
    }

    /** Re-reads the active profile (after an edit or import). */
    fun reload() = publish()

    /**
     * Saves [edited] as a user profile (creating or replacing `<data>/profiles/<id>/profile.json`).
     * One platform's variant (the active profile of a multi-platform one) is written back into it.
     */
    fun save(edited: Profile): ProfileEntry {
        val p = if (edited.variantOf != null) (get(edited.id)?.profile ?: edited).withEdit(edited) else edited
        val problems = p.problems()
        if (problems.isNotEmpty()) throw BadProfile("the profile has problems:\n- " + problems.joinToString("\n- "))
        rules?.admit(p)
        val d = File(root, p.id).apply { mkdirs() }
        // A bundled profile edited for the first time brings its skills and plans along.
        get(p.id)?.takeIf { it.bundled && it.dir != null && !File(d, "profile.json").exists() }?.dir?.let { src ->
            for (sub in listOf("skills", "plans")) File(src, sub).takeIf { it.isDirectory }?.copyRecursively(File(d, sub), overwrite = false)
        }
        val tmp = File(d, "profile.json.tmp")
        tmp.writeText(Profile.json.encodeToString(Profile.serializer(), p))
        if (!tmp.renameTo(File(d, "profile.json"))) { File(d, "profile.json").writeText(tmp.readText()); tmp.delete() }
        if (byPlatform.values.any { it.profile.id == p.id }) reload()
        return load(d)!!
    }

    fun delete(id: String) {
        val d = File(root, id)
        if (!File(d, "profile.json").isFile) throw BadProfile("'$id' is not a profile you added (built-in profiles can't be deleted)")
        d.deleteRecursively()
        settings.update { s -> s.copy(profile = s.profile.takeIf { it != id }, platformProfiles = s.platformProfiles.filterValues { it != id }) }
        reload()
    }

    /** Imports a profile folder or zip (profile.json at its root or one folder down); returns it. */
    fun import(source: File, activate: Boolean = true): ProfileEntry {
        val staging = File(root, ".import-${System.currentTimeMillis()}")
        try {
            when {
                source.isDirectory -> source.copyRecursively(staging)
                source.name.endsWith(".zip") -> unzip(source, staging)
                source.name == "profile.json" -> { staging.mkdirs(); source.copyTo(File(staging, "profile.json")) }
                else -> throw BadProfile("${source.name}: pick a profile folder, a .zip or a profile.json")
            }
            val base = if (File(staging, "profile.json").isFile) staging else staging.listFiles { f -> f.isDirectory && File(f, "profile.json").isFile }?.singleOrNull()
                ?: throw BadProfile("no profile.json in ${source.name}")
            val p = parse(File(base, "profile.json"))
            rules?.admit(p)
            val dest = File(root, p.id)
            dest.deleteRecursively()
            if (!base.renameTo(dest)) { base.copyRecursively(dest, overwrite = true) }
            if (activate) activate(p.id) else reload()
            return load(dest)!!
        } finally {
            staging.deleteRecursively()
        }
    }

    /** The profile as a zip to share: profile.json, skills/ and plans/. No secrets are in a profile. */
    fun export(id: String, dest: File): File {
        val e = get(id) ?: throw BadProfile("no profile '$id'")
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("${e.profile.id}/profile.json")); z.write(Profile.json.encodeToString(Profile.serializer(), e.profile).toByteArray()); z.closeEntry()
            for (sub in listOfNotNull(e.skillsDir, e.plansDir)) {
                sub.walkTopDown().filter { it.isFile }.forEach { f ->
                    z.putNextEntry(ZipEntry("${e.profile.id}/${sub.name}/" + f.relativeTo(sub).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
        }
        return dest
    }

    private fun parse(f: File): Profile {
        val p = try { Profile.parse(f.readText()) } catch (e: Exception) { throw BadProfile("${f.path} is not a valid profile: ${e.message?.lineSequence()?.firstOrNull()}") }
        val problems = p.problems()
        if (problems.isNotEmpty()) throw BadProfile("${f.path}:\n- " + problems.joinToString("\n- "))
        return p
    }

    private fun load(d: File): ProfileEntry? {
        val f = File(d, "profile.json").takeIf { it.isFile } ?: return null
        return try { ProfileEntry(parse(f), d, bundled = false) } catch (e: BadProfile) {
            System.err.println("[profiles] ${e.message}")
            null
        }
    }

    private fun bundledIds(): List<String> =
        javaClass.getResourceAsStream("/profiles/index.txt")?.use { s -> s.readBytes().decodeToString().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") } }.orEmpty()

    /** A bundled profile, unpacked once per app version into the cache so its skills and plans are plain files. */
    private fun bundled(id: String): ProfileEntry? {
        val text = javaClass.getResourceAsStream("/profiles/$id/profile.json")?.use { it.readBytes().decodeToString() } ?: return null
        val p = runCatching { Profile.parse(text) }.getOrElse { System.err.println("[profiles] bundled $id: ${it.message}"); return null }
        val d = File(bundleCache, "${no.agens.testlab.AppIdentity.VERSION}/$id")
        if (!File(d, ".complete").isFile) {
            d.deleteRecursively(); d.mkdirs()
            File(d, "profile.json").writeText(text)
            val files = javaClass.getResourceAsStream("/profiles/$id/files.txt")?.use { it.readBytes().decodeToString().lines().filter { l -> l.isNotBlank() } }.orEmpty()
            for (rel in files) javaClass.getResourceAsStream("/profiles/$id/$rel")?.use { s -> File(d, rel).apply { parentFile.mkdirs() }.outputStream().use { s.copyTo(it) } }
            File(d, ".complete").writeText("")
        }
        return ProfileEntry(p, d, bundled = true)
    }

    private fun unzip(zip: File, into: File) {
        val root = into.canonicalFile.apply { mkdirs() }
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val out = File(root, e.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw BadProfile("unsafe path in ${zip.name}: ${e.name}")
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile.mkdirs()
                z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
            }
        }
    }
}
