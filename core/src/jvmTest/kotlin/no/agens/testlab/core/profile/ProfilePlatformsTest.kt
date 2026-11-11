package no.agens.testlab.core.profile

import no.agens.testlab.core.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfilePlatformsTest {
    private val both = Profile.parse(
        """
        {
          "id": "shop", "name": "Shop",
          "tracker": {"site": "example.atlassian.net", "queueLabels": ["devtest"], "projects": ["SHOP"]},
          "report": {"title": "Check"},
          "navigation": {"host": "https://shop.example", "destinations": {"cart": {"path": "/cart"}}},
          "platforms": {
            "android": {
              "app": {"defaultPackage": "com.example.shop"},
              "github": {"repo": "example/shop-android"},
              "builds": [{"id": "pr", "label": "PR", "workflowFile": "pr.yml", "artifact": "app\\.apk", "default": true}],
              "tracker": {"projects": ["AND"]}
            },
            "ios": {
              "app": {"defaultPackage": "com.example.Shop"},
              "github": {"repo": "example/shop-ios"},
              "builds": [{"id": "sim", "label": "Simulator", "workflowFile": "sim.yml", "artifact": "Shop\\.app", "default": true}],
              "tracker": {"projects": ["IOS"]},
              "navigation": {"destinations": {"wallet": {"tab": "wallet"}}}
            }
          }
        }
        """.trimIndent(),
    )

    @Test
    fun eachPlatformGetsTheSharedFieldsWithItsOwnMergedOver() {
        assertEquals(listOf(Platform.ANDROID, Platform.IOS), both.covers)
        val a = both.variant(Platform.ANDROID)
        val i = both.variant(Platform.IOS)
        assertEquals(Platform.ANDROID, a.platform); assertEquals(Platform.IOS, i.platform)
        assertEquals("example/shop-android", a.github.repo); assertEquals("example/shop-ios", i.github.repo)
        assertEquals(listOf("pr"), a.builds.map { it.id }); assertEquals(listOf("sim"), i.builds.map { it.id })
        // objects merge field by field: the site and labels stay, the projects are the platform's
        assertEquals("example.atlassian.net", i.tracker.site); assertEquals(listOf("devtest"), i.tracker.queueLabels)
        assertEquals(listOf("IOS"), i.tracker.projects)
        assertEquals("Check", i.report.title)
        assertEquals(setOf("cart", "wallet"), i.navigation.destinations.keys, "maps merge too")
        assertEquals("https://shop.example", i.navigation.host)
        assertTrue(a.platforms.isEmpty() && a.variantOf == Platform.ANDROID)
        assertEquals(emptyList(), both.problems())
    }

    @Test
    fun aOnePlatformProfileIsItsOwnVariant() {
        val one = Profile(id = "x", name = "X")
        assertEquals(listOf(Platform.ANDROID), one.covers)
        assertEquals(one, one.variant(Platform.ANDROID))
    }

    @Test
    fun problemsNameThePlatform() {
        val bad = both.copy(platforms = both.platforms + ("windows" to kotlinx.serialization.json.JsonObject(emptyMap())))
        assertTrue(bad.problems().any { it.startsWith("platforms.windows") }, bad.problems().toString())
        val badRepo = Profile.parse(Profile.json.encodeToString(Profile.serializer(), both).replace("example/shop-ios", "not a repo"))
        assertEquals(listOf("platforms.ios: github.repo 'not a repo' is not owner/name"), badRepo.problems())
    }

    @Test
    fun anEditedVariantGoesBackWhereTheFieldLives() {
        val i = both.variant(Platform.IOS)
        val edited = i.copy(
            github = i.github.copy(repo = "example/shop-ios-2"), // the platform's own
            report = i.report.copy(title = "Review"), // shared
        )
        val back = both.withEdit(edited)
        assertEquals("example/shop-ios-2", back.variant(Platform.IOS).github.repo)
        assertEquals("example/shop-android", back.variant(Platform.ANDROID).github.repo, "Android's repo untouched")
        assertEquals("Review", back.variant(Platform.ANDROID).report.title, "a shared field changes for both")
        assertEquals(both.platforms.keys, back.platforms.keys)
        assertEquals(null, back.variantOf)
    }
}
