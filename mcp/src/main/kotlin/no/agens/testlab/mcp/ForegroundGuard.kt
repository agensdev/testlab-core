package no.agens.testlab.mcp

import no.agens.testlab.device.hs.HsDevice
import no.agens.testlab.services.runtime.LabRuntime

/**
 * Before a blind coordinate tap: is the app under test (or an app it hands over to, such as a
 * browser for a login or a permission dialog) in front? After a system back has left the app, the
 * next coordinate taps land in Settings or the launcher and can change the phone itself.
 */
internal object ForegroundGuard {
    /** Apps a test flow legitimately passes through: browsers (custom tabs), permission and account dialogs. */
    private val HELPERS = listOf(
        "com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser", "com.microsoft.emmx",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.google.android.gms",
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.systemui",
        "com.apple.mobilesafari", "com.apple.SafariViewService",
    )

    /** The packages that count as the app under test; empty when the profile names none (no guard). */
    fun appPackages(rt: LabRuntime, serial: String): List<String> {
        val app = rt.profileFor(serial).app
        return listOfNotNull(rt.defaultPackage(serial).takeIf { it.isNotBlank() }, app.productionPackage, app.family).filter { it.isNotBlank() }
    }

    fun allowed(top: String, app: List<String>): Boolean {
        val pkg = top.substringBefore('/').trim()
        if (pkg.isEmpty()) return true // nothing to judge by
        return app.any { pkg == it || pkg.startsWith("$it.") } || pkg in HELPERS
    }

    suspend fun check(rt: LabRuntime, dev: HsDevice) {
        val app = appPackages(rt, dev.serial)
        if (app.isEmpty()) return
        val top = runCatching { dev.topActivity() }.getOrNull() ?: return
        if (!allowed(top, app)) throw BadArgument(
            "not tapped: ${top.substringBefore('/')} is in front, not the app under test (${app.first()}). " +
                "Coordinates from an earlier dump would land in another app: take a screenshot and re-read the screen, " +
                "bring the app back (handsets_open_app), or pass outside_app=true to tap there on purpose",
        )
    }
}
