package no.agens.testlab.services.profile

import no.agens.testlab.core.Platform
import no.agens.testlab.core.profile.Profile
import no.agens.testlab.services.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileStoreTest {
    private val tmp = Files.createTempDirectory("tl-profiles").toFile()
    private val settings = SettingsStore(File(tmp, "settings.json"))
    private val store = ProfileStore(File(tmp, "profiles"), File(tmp, "cache"), settings)

    @AfterTest
    fun tearDown() { tmp.deleteRecursively() }

    @Test
    fun genericUntilAProfileIsSavedAndActivated() {
        assertEquals("generic", store.current.id)
        store.save(ExampleProfile.PROFILE)
        store.activate("example-shop")
        assertEquals("example-shop", settings.current.profile)
        assertEquals("example-org/shop-android", store.current.github.repo)
        // a second store on the same folder starts with it
        assertEquals("example-shop", ProfileStore(File(tmp, "profiles"), File(tmp, "cache"), settings).current.id)
    }

    @Test
    fun anAndroidAndAnIosProfileAreActiveAtOnceAndTheFocusPicksOne() {
        val ios = ExampleProfile.PROFILE.copy(id = "example-shop-ios", name = "Example Shop iOS", platform = Platform.IOS)
        store.save(ExampleProfile.PROFILE); store.save(ios)
        store.activate("example-shop")
        assertEquals("generic-ios", store.forPlatform(Platform.IOS).profile.id, "nothing chosen for iOS yet")
        store.activate("example-shop-ios")
        assertEquals("example-shop", store.forPlatform(Platform.ANDROID).profile.id, "activating the iOS one leaves Android's")
        assertEquals("example-shop-ios", store.current.id, "and moves the focus to iOS")
        assertEquals(mapOf("ios" to "example-shop-ios"), settings.current.platformProfiles)
        assertEquals("example-shop", settings.current.profile)
        store.focus(Platform.ANDROID)
        assertEquals("example-shop", store.current.id)
        val again = ProfileStore(File(tmp, "profiles"), File(tmp, "cache"), settings)
        assertEquals("example-shop-ios", again.forPlatform(Platform.IOS).profile.id)
        store.delete("example-shop-ios")
        assertEquals("generic-ios", store.forPlatform(Platform.IOS).profile.id)
        assertEquals(emptyMap(), settings.current.platformProfiles)
    }

    @Test
    fun exportImportRoundTripsWithSkillsAndPlans() {
        val e = store.save(ExampleProfile.PROFILE)
        File(e.dir, "skills/device-login").apply { mkdirs() }.resolve("SKILL.md").writeText("---\nname: device-login\n---\nours")
        File(e.dir, "plans").apply { mkdirs() }.resolve("login.json").writeText("[]")
        val zip = store.export("example-shop", File(tmp, "out/shop.zip"))
        ZipFile(zip).use { z ->
            assertEquals(setOf("example-shop/profile.json", "example-shop/skills/device-login/SKILL.md", "example-shop/plans/login.json"), z.entries().toList().map { it.name }.toSet())
        }
        store.delete("example-shop")
        assertEquals("generic", store.current.id)
        val imported = store.import(zip)
        assertEquals("example-shop", store.current.id)
        assertEquals("ours", File(imported.skillsDir, "device-login/SKILL.md").readText().substringAfterLast("\n"))
    }

    @Test
    fun badProfilesAreRefusedWithTheirProblems() {
        val bad = File(tmp, "profile.json").apply { writeText("""{"id":"x y","name":"","builds":[{"id":"A","label":"a","artifact":"("}]}""") }
        val e = assertFailsWith<BadProfile> { store.import(bad) }
        assertTrue("id 'x y'" in e.message!! && "not a valid regex" in e.message!!, e.message)
        assertFailsWith<BadProfile> { store.save(Profile(id = "ok", name = "", github = Profile.GitHub(repo = "nope"))) }
        assertFailsWith<BadProfile> { store.delete("generic") }
    }
}

class ProfileStorePlatformsTest {
    private val tmp = Files.createTempDirectory("tl-profiles2").toFile()
    private val settings = SettingsStore(File(tmp, "settings.json"))
    private val store = ProfileStore(File(tmp, "profiles"), File(tmp, "cache"), settings)
    private val both = ExampleProfile.PROFILE.copy(
        id = "shop", name = "Shop",
        platforms = mapOf(
            "android" to kotlinx.serialization.json.Json.parseToJsonElement("""{"github": {"repo": "example-org/shop-android"}}""") as kotlinx.serialization.json.JsonObject,
            "ios" to kotlinx.serialization.json.Json.parseToJsonElement("""{"github": {"repo": "example-org/shop-ios"}, "app": {"defaultPackage": "com.example.Shop"}}""") as kotlinx.serialization.json.JsonObject,
        ),
    )

    @AfterTest
    fun tearDown() { tmp.deleteRecursively() }

    @Test
    fun oneProfileIsActiveForBothPlatforms() {
        store.save(both); store.activate("shop")
        assertEquals("example-org/shop-android", store.forPlatform(Platform.ANDROID).profile.github.repo)
        assertEquals("example-org/shop-ios", store.forPlatform(Platform.IOS).profile.github.repo)
        assertEquals(Platform.IOS, store.forPlatform(Platform.IOS).profile.platform)
        assertEquals("shop", settings.current.profile); assertEquals(mapOf("ios" to "shop"), settings.current.platformProfiles)
    }

    @Test
    fun aCallsScopeWinsOverTheFocus(): Unit = kotlinx.coroutines.runBlocking {
        store.save(both); store.activate("shop"); store.focus(Platform.ANDROID)
        // Two calls at once, one for each platform: each reads its own repo however they interleave.
        val seen = java.util.concurrent.ConcurrentHashMap<Platform, MutableSet<String>>()
        kotlinx.coroutines.coroutineScope {
            for (p in Platform.entries) repeat(20) {
                launch(kotlinx.coroutines.Dispatchers.Default + store.scope(p)) {
                    repeat(50) { kotlinx.coroutines.yield(); seen.getOrPut(p) { java.util.concurrent.ConcurrentHashMap.newKeySet() } += store.current.github.repo }
                }
            }
        }
        assertEquals<Set<String>?>(setOf("example-org/shop-android"), seen[Platform.ANDROID])
        assertEquals<Set<String>?>(setOf("example-org/shop-ios"), seen[Platform.IOS])
        assertEquals("example-org/shop-android", store.current.github.repo, "outside a scope: the focus")
    }

    @Test
    fun aPlatformsOwnPlansAndSkillsWin() {
        val e = store.save(both); store.activate("shop")
        File(e.dir, "plans/ios").mkdirs()
        File(e.dir, "plans/login.json").writeText("[\"shared\"]"); File(e.dir, "plans/ios/login.json").writeText("[\"ios\"]")
        File(e.dir, "plans/other.json").writeText("[]")
        File(e.dir, "skills/ios/device-login").mkdirs(); File(e.dir, "skills/device-login").mkdirs(); File(e.dir, "skills/devtest").mkdirs()
        val ios = store.forPlatform(Platform.IOS); val android = store.forPlatform(Platform.ANDROID)
        assertEquals("[\"ios\"]", ios.planFile("login.json")!!.readText())
        assertEquals("[\"shared\"]", android.planFile("login.json")!!.readText())
        assertEquals(listOf("login.json", "other.json"), ios.planFiles().map { it.name })
        assertTrue(ios.planFiles().first().path.endsWith("ios/login.json"))
        assertEquals(listOf("device-login", "devtest"), android.skillDirs().map { it.name }, "the platform folders are not skills")
        assertTrue(ios.skillDirs().first().path.endsWith("ios/device-login"))
    }

    @Test
    fun savingTheActiveVariantKeepsTheOtherPlatform() {
        store.save(both); store.activate("shop")
        val ios = store.forPlatform(Platform.IOS).profile
        store.save(ios.copy(app = ios.app.copy(defaultPackage = "com.example.Shop2"), report = ios.report.copy(title = "Review")))
        val saved = store.get("shop")!!.profile
        assertEquals(setOf("android", "ios"), saved.platforms.keys)
        assertEquals("com.example.Shop2", store.forPlatform(Platform.IOS).profile.app.defaultPackage)
        assertEquals(ExampleProfile.PROFILE.app.defaultPackage, store.forPlatform(Platform.ANDROID).profile.app.defaultPackage)
        assertEquals("Review", store.forPlatform(Platform.ANDROID).profile.report.title)
    }
}
