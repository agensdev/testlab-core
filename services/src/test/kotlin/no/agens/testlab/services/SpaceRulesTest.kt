package no.agens.testlab.services

import no.agens.testlab.core.profile.Profile
import no.agens.testlab.device.HostOs
import no.agens.testlab.services.profile.ExampleProfile
import no.agens.testlab.services.profile.ProfileStore
import no.agens.testlab.services.secrets.MemorySecretStore
import no.agens.testlab.services.secrets.Redactor
import no.agens.testlab.services.secrets.SecretHashes
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpaceRulesTest {
    private val home = Files.createTempDirectory("rules").toFile()
    @AfterTest fun tearDown() { home.deleteRecursively() }

    private fun dirs(space: String) = AppDirs.forHost(HostOs.LINUX, mapOf("XDG_DATA_HOME" to File(home, "d").path, "XDG_CACHE_HOME" to File(home, "c").path), home, space)
    private fun store(d: AppDirs) = ProfileStore(File(d.data, "profiles"), File(d.cache, "profiles"), SettingsStore(d.settings), SpaceRulesStore(d))

    private val shop = ExampleProfile.PROFILE
    private val other = Profile(
        id = "other", name = "Other client", app = Profile.App(defaultPackage = "org.other.app", family = "org.other.app"),
        github = Profile.GitHub(repo = "other-co/app"), tracker = Profile.Tracker(site = "other.atlassian.net"),
    )

    private fun zip(p: Profile): File {
        val dir = File(home, "export-${p.id}").apply { mkdirs() }
        File(dir, "profile.json").writeText(Profile.json.encodeToString(Profile.serializer(), p))
        return dir
    }

    @Test
    fun theFirstProfilePinsTheSpaceAndAnotherClientsIsRefused() {
        val d = dirs("alpha")
        val profiles = store(d)
        profiles.import(zip(shop))
        val r = SpaceRulesStore(d).current
        assertEquals(listOf("example-org"), r.githubOwners)
        assertEquals(listOf("example.atlassian.net"), r.jiraSites)
        assertEquals(listOf("com.example.shop"), r.appFamilies)
        val e = assertFailsWith<SpaceRefused> { profiles.import(zip(other)) }
        assertTrue("other-co" in e.message!! && "other.atlassian.net" in e.message!! && "space allow" in e.message!!, e.message)
        assertTrue(profiles.all().none { it.profile.id == "other" }, "nothing of it was kept")
        assertFailsWith<SpaceRefused> { profiles.save(other) }
    }

    @Test
    fun aProfileCopiedInByHandIsHiddenAndCantBeActivated() {
        val d = dirs("alpha")
        val profiles = store(d)
        profiles.import(zip(shop))
        File(d.data, "profiles/other").mkdirs()
        zip(other).resolve("profile.json").copyTo(File(d.data, "profiles/other/profile.json"))
        assertTrue(profiles.all().none { it.profile.id == "other" })
        assertFailsWith<SpaceRefused> { profiles.activate("other") }
    }

    @Test
    fun githubAndJiraCallsOutsideTheSpaceAreRefused() {
        val d = dirs("alpha")
        val rules = SpaceRulesStore(d)
        rules.admit(shop)
        rules.checkRepo("example-org/shop-android")
        rules.checkSite("https://Example.atlassian.net/")
        assertFailsWith<SpaceRefused> { rules.checkRepo("other-co/app") }
        assertFailsWith<SpaceRefused> { rules.checkSite("other.atlassian.net") }
    }

    @Test
    fun theDefaultSpaceHasNoRulesUnlessItHasAFile() {
        val d = dirs(Spaces.DEFAULT)
        val profiles = store(d)
        profiles.import(zip(shop))
        profiles.import(zip(other))
        assertFalse(d.rules.exists(), "nothing is pinned in the default space")
        SpaceRulesStore(d).save(SpaceRules(githubOwners = listOf("example-org"), jiraSites = listOf("example.atlassian.net"), appFamilies = listOf("com.example.shop")))
        assertFailsWith<SpaceRefused> { SpaceRulesStore(d).checkRepo("other-co/app") }
    }

    @Test
    fun aBrokenRulesFileAllowsNothing() {
        val d = dirs("alpha")
        d.rules.parentFile.mkdirs(); d.rules.writeText("{ not json")
        assertFailsWith<SpaceRefused> { SpaceRulesStore(d).checkRepo("example-org/shop-android") }
    }

    @Test
    fun anotherSpacesSecretIsMaskedByItsHash() {
        val acme = dirs("alpha"); val globex = dirs("beta")
        acme.data.mkdirs(); globex.data.mkdirs()
        val token = "ghp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8"
        val acmeRedactor = Redactor(MemorySecretStore(mapOf("github.token" to token, "device_pin" to "1234")), SecretHashes(File(acme.data, "secret-hashes"), acme::otherSpacesData))
        acmeRedactor.publish()
        val published = File(acme.data, "secret-hashes").readText()
        assertFalse(token in published, "only hashes are published")
        val globexRedactor = Redactor(MemorySecretStore(mapOf("github.token" to "ghp_globexglobexglobexglobex")), SecretHashes(File(globex.data, "secret-hashes"), globex::otherSpacesData))
        val text = """{"note":"curl -H 'Authorization: Bearer $token' https://x:$token@github.com","pr":"#1234"}"""
        val out = globexRedactor.apply(text)
        assertFalse(token in out, out)
        assertEquals(2, Regex(Redactor.MASK).findAll(out).count(), out)
        assertTrue("#1234" in out, "PINs aren't published, so numbers stay")
        assertTrue(globexRedactor.leaks(text))
        assertEquals("ghp_globexglobex is not it", globexRedactor.apply("ghp_globexglobex is not it"))
    }
}
