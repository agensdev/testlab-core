package no.agens.testlab.mcp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForegroundGuardTest {
    private val app = listOf("com.example.shop.feature", "com.example.shop")

    @Test
    fun theAppAndWhatItHandsOverToPassOtherAppsDoNot() {
        assertTrue(ForegroundGuard.allowed("com.example.shop.feature/.MainActivity", app))
        assertTrue(ForegroundGuard.allowed("com.example.shop.debug/.MainActivity", app), "the family's other builds")
        assertTrue(ForegroundGuard.allowed("com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity", app), "a login in a custom tab")
        assertTrue(ForegroundGuard.allowed("com.google.android.permissioncontroller/.GrantPermissionsActivity", app))
        assertFalse(ForegroundGuard.allowed("com.android.settings/.SubSettings", app), "after a back left the app: Developer options")
        assertFalse(ForegroundGuard.allowed("com.google.android.apps.nexuslauncher/.NexusLauncherActivity", app), "the home screen")
        assertFalse(ForegroundGuard.allowed("com.example.shopping/.Main", app), "a prefix that is not the family")
        assertTrue(ForegroundGuard.allowed("", app), "nothing known: no judgement")
    }
}
