package no.agens.testlab.services.runtime

import no.agens.testlab.core.Platform
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.services.profile.ProfileEntry
import no.agens.testlab.services.secrets.SecretStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The test accounts: names in Settings (else the profile's defaults) plus any the vault has fields
 * for, a note each saying what the account is for, and which device logged in with which, so a
 * login without an account takes one that is complete for the device's platform and not in use on
 * another device.
 */
class TestAccounts(private val rt: LabRuntime) {
    private val inUse = ConcurrentHashMap<String, Pair<String, Instant>>() // account → (serial, since)

    /** Every account: the listed ones first, then those only the vault knows (set with the CLI or by hand). */
    fun names(): List<String> = (rt.accounts() + rt.secrets.keys().filter { it.startsWith("account.") }.map { it.split('.')[1] }).distinct()

    fun note(name: String): String? = rt.settings.current.accountNotes[name]?.takeIf { it.isNotBlank() }

    /** Adds [name] to the list (and sets its note when given). */
    fun add(name: String, note: String? = null) {
        require(NAME.matches(name)) { "account names are letters, digits, _ and -" }
        rt.settings.update { s ->
            s.copy(
                accounts = (s.accounts ?: rt.profile.accounts.defaults).plus(name).distinct(),
                accountNotes = if (note == null) s.accountNotes else if (note.isBlank()) s.accountNotes - name else s.accountNotes + (name to note.trim()),
            )
        }
    }

    /** Removes [name] from the list, its note and its vault fields. */
    fun remove(name: String) {
        rt.secrets.keys().filter { it.startsWith("account.$name.") }.forEach(rt.secrets::delete)
        rt.settings.update { s -> s.copy(accounts = (s.accounts ?: rt.profile.accounts.defaults) - name, accountNotes = s.accountNotes - name) }
        inUse.remove(name)
    }

    /** The project's active profile per platform, the generic ones left out (none: the current one). */
    fun projects(): Map<Platform, ProfileEntry> =
        rt.profiles.activeAll().filterValues { it.profile.id !in Profile.GENERIC_IDS }.ifEmpty { mapOf(rt.profiles.platform to rt.profiles.entry) }

    /** The fields an account has on any of the project's platforms. */
    fun fields(): List<Profile.Field> = projects().values.flatMap { it.profile.accounts.fields }.distinctBy { it.key }

    /** The fields [entry]'s login plan types (`${account}.<field>`); null when it has none. */
    fun loginFields(entry: ProfileEntry): List<String>? {
        val name = entry.profile.accounts.loginPlan ?: return null
        val f = entry.planFile(if (name.endsWith(".json")) name else "$name.json") ?: return null
        return LOGIN_FIELD.findAll(f.readText()).map { it.groupValues[1] }.distinct().toList()
    }

    /** Whether [name] has every field [entry]'s login needs; null when it has no login plan. */
    fun ready(name: String, entry: ProfileEntry): Boolean? = loginFields(entry)?.all { rt.secrets.has(SecretStore.account(name, it)) }

    /** The platforms [name] can log in on. */
    fun readyOn(name: String): List<Platform> = projects().filter { (_, e) -> ready(name, e) == true }.keys.toList()

    /** The device [name] was last logged in on, in the last 12 hours, other than [serial]. */
    fun inUseOn(name: String, serial: String? = null): String? =
        inUse[name]?.takeIf { (s, at) -> s != serial && at.isAfter(Instant.now().minus(HOLD)) }?.first

    /** [serial] logs in with [name]; any account it held before is free again. */
    fun loggedIn(serial: String, name: String) {
        inUse.entries.removeIf { it.value.first == serial }
        inUse[name] = serial to Instant.now()
    }

    /**
     * The account a login on [serial] takes when none is named: of those complete for its platform,
     * the one it used last, else one no other device uses, else the first; with none complete, the first listed.
     */
    fun pick(serial: String, entry: ProfileEntry = rt.profiles.entry): String? {
        val all = names()
        val ready = all.filter { ready(it, entry) != false }
        return ready.firstOrNull { inUse[it]?.first == serial } ?: ready.firstOrNull { inUseOn(it, serial) == null } ?: ready.firstOrNull() ?: all.firstOrNull()
    }

    companion object {
        val NAME = Regex("[A-Za-z0-9_-]+")
        private val LOGIN_FIELD = Regex("""\$\{account}\.([A-Za-z0-9_-]+)""")
        private val HOLD: Duration = Duration.ofHours(12)
    }
}
